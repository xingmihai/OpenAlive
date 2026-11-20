package org.aliveclean;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * LineageOS / AOSP SystemUI adapter.
 *
 * ColorOS drives the whole "AOD -> lock -> desktop" sequence through
 * com.oplus.systemui.aod.display.SmoothTransitionController. AOSP has no such
 * state machine, so the same three scene modes are rebuilt from the platform
 * keyguard lifecycle instead:
 *
 *   mode 0  onStartedGoingToSleep / ACTION_SCREEN_OFF
 *   mode 1  onStartedWakingUp     / ACTION_SCREEN_ON
 *   mode 2  startWakeAndUnlock / setKeyguardGoingAway /
 *           notifyStartSurfaceBehindRemoteAnimation / ACTION_USER_PRESENT
 *
 * Every lookup is optional. A missing class on a given LineageOS build only
 * removes that one precise signal; AospSceneLink's broadcast fallback still
 * delivers the scene change. Nothing here throws out of handleLoadPackage.
 */
final class AospBridge {

    private static final String TAG = "AliveClean";
    private static final Set<String> failures =
            Collections.synchronizedSet(new HashSet<String>());
    private static volatile boolean installed;
    /** system_server: set by WallpaperManagerService.setInAmbientMode(true) during doze. */
    private static volatile boolean ambient;

    private AospBridge() {}

    static synchronized void install(ClassLoader cl) {
        if (installed || cl == null) return;
        installed = true;
        installApplication(cl);
        installPowerEvents(cl);
        installUnlockEvents(cl);
        try {
            AospDozeHooks.install(cl);
        } catch (Throwable error) {
            failure("doze SystemUI hooks", error);
        }
        XposedBridge.log("AliveClean: AOSP bridge loaded");
        Log.i(TAG, "AOSP bridge loaded");
    }

    /** Grab a real SystemUI Context; the broadcast fallback lives in AospSceneLink. */
    private static void installApplication(ClassLoader cl) {
        Class<?> application = null;
        for (String name : new String[]{
                "com.android.systemui.application.impl.SystemUIApplicationImpl",
                "com.android.systemui.application.SystemUIApplication",
                "com.android.systemui.SystemUIApplication"}) {
            application = XposedHelpers.findClassIfExists(name, cl);
            if (application != null) break;
        }
        if (application == null) {
            failure("SystemUIApplication", new ClassNotFoundException("no AOSP application class"));
            return;
        }
        Log.i(TAG, "AOSP application=" + application.getName());
        try {
            XposedHelpers.findAndHookMethod(application, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    AospSceneLink.start((Context) p.thisObject);
                }
            });
        } catch (Throwable error) {
            failure("application.onCreate", error);
        }
    }

    /**
     * Wakefulness owners verified against the device's SystemUI APK (3 dex,
     * 140803 unique strings). On this LineageOS build KeyguardViewMediator has
     * NO onStartedGoingToSleep / onStartedWakingUp / keyguardGone methods:
     * they live in KeyguardService$2 (the IKeyguardService binder stub) and
     * KeyguardViewMediator$5. hookAllMethods is used because the binder stub's
     * signatures are (I) and (I, Z), not the legacy (int) ones.
     */
    private static void installPowerEvents(ClassLoader cl) {
        String[] owners = {
                "com.android.systemui.keyguard.KeyguardService$2",
                "com.android.systemui.keyguard.KeyguardViewMediator",
                "com.android.systemui.keyguard.KeyguardViewMediatorImpl",
        };
        int sleepHooks = 0, wakeHooks = 0;
        for (String name : owners) {
            Class<?> owner = XposedHelpers.findClassIfExists(name, cl);
            if (owner == null) continue;
            try {
                if (!XposedBridge.hookAllMethods(owner, "onStartedGoingToSleep",
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                if (!p.hasThrowable()) AospSceneLink.dispatch(0, true, 1, "AOD");
                            }
                        }).isEmpty()) sleepHooks++;
            } catch (Throwable error) {
                failure("onStartedGoingToSleep@" + name, error);
            }
            try {
                if (!XposedBridge.hookAllMethods(owner, "onStartedWakingUp",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                AospSceneLink.dispatch(1, true, 2, "KEYGUARD");
                            }
                        }).isEmpty()) wakeHooks++;
            } catch (Throwable error) {
                failure("onStartedWakingUp@" + name, error);
            }
        }
        Log.i(TAG, "AOSP wakefulness: binderStub="
                + (XposedHelpers.findClassIfExists(owners[0], cl) != null)
                + " sleepHooks=" + sleepHooks + " wakeHooks=" + wakeHooks);
        if (sleepHooks == 0 && wakeHooks == 0) {
            failure("wakefulness", new ClassNotFoundException(
                    "no sleep/wake owner; ACTION_SCREEN_OFF/ON fallback remains"));
        }
        // keyguardGone is a KeyguardViewMediator$5 callback on this build only.
        // Unlock is already covered by setKeyguardGoingAway, startWakeAndUnlock
        // and notifyStartSurfaceBehindRemoteAnimation, so keep this tolerant.
        Class<?> gone = firstExisting(cl,
                "com.android.systemui.keyguard.KeyguardViewMediator$5",
                "com.android.systemui.keyguard.KeyguardViewMediator");
        if (gone != null) {
            try {
                XposedBridge.hookAllMethods(gone, "keyguardGone", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!p.hasThrowable()) AospSceneLink.dispatch(2, true, 3, "UNLOCK");
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void installUnlockEvents(ClassLoader cl) {
        Class<?> monitor = XposedHelpers.findClassIfExists(
                "com.android.keyguard.KeyguardUpdateMonitor", cl);
        if (monitor != null) {
            try {
                XposedHelpers.findAndHookMethod(monitor, "setKeyguardGoingAway", boolean.class,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                if (!p.hasThrowable() && (boolean) p.args[0]) {
                                    AospSceneLink.dispatch(2, true, 3, "UNLOCK");
                                }
                            }
                        });
            } catch (Throwable error) {
                failure("setKeyguardGoingAway", error);
            }
        }

        Class<?> biometric = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.BiometricUnlockController", cl);
        if (biometric != null) {
            try {
                XposedBridge.hookAllMethods(biometric, "startWakeAndUnlock", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args.length == 0 || !(p.args[0] instanceof Integer)) return;
                        int mode = (Integer) p.args[0];
                        // AOSP authenticated wake-and-unlock modes; bouncer paths are excluded.
                        if (mode == 1 || mode == 2 || mode == 5 || mode == 6 || mode == 7) {
                            AospSceneLink.dispatch(2, true, 3, "UNLOCK");
                        }
                    }
                });
            } catch (Throwable error) {
                failure("startWakeAndUnlock", error);
            }
        }

        Class<?> animation = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.KeyguardUnlockAnimationController", cl);
        Log.i(TAG, "AOSP unlock owners: monitor=" + (monitor != null)
                + " biometric=" + (biometric != null) + " animation=" + (animation != null));
        if (animation != null) {
            try {
                XposedBridge.hookAllMethods(animation,
                        "notifyStartSurfaceBehindRemoteAnimation", new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                AospSceneLink.dispatch(2, true, 3, "UNLOCK");
                            }
                        });
            } catch (Throwable error) {
                failure("notifyStartSurfaceBehindRemoteAnimation", error);
            }
        }
    }

    /**
     * system_server side: grant AMBIENT_WALLPAPER for live-wallpaper setup.
     *
     * WallpaperManagerService.setWallpaperComponentChecked() refuses a wallpaper
     * with supportsAmbientMode=true unless the caller holds this
     * signature|privileged permission. On AOSP the check goes through
     * ContextImpl.checkCallingOrSelfPermission (not a by-name PackageManager
     * lookup), so the earlier PMS-only hook could miss it and produce
     * "Selected service does not have android.permission.AMBIENT_WALLPAPER".
     *
     * Hook every plausible check/enforce entry point on ContextImpl plus the
     * PackageManagerService by-name/uid paths, and only ever short-circuit the
     * single AMBIENT_WALLPAPER permission. Nothing else is granted.
     */
    static void installFramework(ClassLoader cl) {
        if (cl == null) return;

        // Keep the wallpaper window visible during doze and record the
        // ambient flag. Must run before any early return below.
        try {
            installDozeFramework(cl);
        } catch (Throwable error) {
            failure("doze framework hooks", error);
        }

        final String PERM = "android.permission.AMBIENT_WALLPAPER";
        XC_MethodHook grantInt = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && PERM.equals(p.args[0])) {
                    p.setResult(PackageManager.PERMISSION_GRANTED);
                }
            }
        };
        XC_MethodHook grantVoid = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && PERM.equals(p.args[0])) {
                    // enforce*() returns void and throws when denied; returning
                    // early suppresses the SecurityException.
                    p.setResult(null);
                }
            }
        };

        Class<?> contextImpl = XposedHelpers.findClassIfExists("android.app.ContextImpl", cl);
        if (contextImpl != null) {
            for (String method : new String[]{
                    "checkCallingOrSelfPermission", "checkCallingPermission",
                    "checkSelfPermission", "checkPermission"}) {
                try {
                    if (!XposedBridge.hookAllMethods(contextImpl, method, grantInt).isEmpty()) {
                        Log.i(TAG, "AOSP permission hook ContextImpl." + method);
                        XposedBridge.log("AliveClean: ambient grant on ContextImpl." + method);
                    }
                } catch (Throwable error) {
                    failure("ContextImpl " + method, error);
                }
            }
            for (String method : new String[]{
                    "enforceCallingOrSelfPermission", "enforceCallingPermission",
                    "enforcePermission"}) {
                try {
                    if (!XposedBridge.hookAllMethods(contextImpl, method, grantVoid).isEmpty()) {
                        Log.i(TAG, "AOSP permission hook ContextImpl." + method);
                        XposedBridge.log("AliveClean: ambient grant on ContextImpl." + method);
                    }
                } catch (Throwable error) {
                    failure("ContextImpl " + method, error);
                }
            }
        } else {
            XposedBridge.log("AliveClean: ContextImpl missing; falling back to PMS only");
        }

        Class<?> target = firstExisting(cl,
                "com.android.server.pm.PackageManagerService",
                "com.android.server.pm.PackageManagerService$IPackageManagerImpl",
                "com.android.server.pm.IPackageManagerImpl",
                "com.android.server.pm.PackageManagerServiceUtils");
        Log.i(TAG, "AOSP framework=" + (target == null ? "missing" : target.getName()));
        if (target == null) {
            XposedBridge.log("AliveClean: PMS permission adapter unavailable");
            return;
        }
        for (String method : new String[]{"checkPermission", "checkUidPermission"}) {
            try {
                if (!XposedBridge.hookAllMethods(target, method, grantInt).isEmpty()) {
                    XposedBridge.log("AliveClean: ambient grant on " + target.getName() + "." + method);
                    Log.i(TAG, "AOSP permission hook " + target.getName() + "." + method);
                }
            } catch (Throwable error) {
                failure("framework " + method, error);
            }
        }
    }

    /**
     * system_server half of the AOD picture takeover — adaptive version.
     *
     * The 17:09 log proves every signal already works:
     *   framework ambient=true / SystemUI dozing=true / Ambient=true duration=500
     *   AOSP scene mode=0/1/2 / moduleActive=true / render=true in STATE_DOZE.
     * Only the window compositing still hides the wallpaper. The previous
     * build hardcoded "WallpaperController.isWallpaperVisible" and
     * "WallpaperWindowToken.updateVisibility" + "setShownVisibility", which are
     * private framework names that differ across Android 17 builds.
     *
     * Instead of guessing, enumerate the real declared methods (including
     * superclasses) at install time, log the candidate list, and hook every
     * visibility/hidden/shown/ambient related one with a heuristic callback.
     * All diagnostics are written to BOTH XposedBridge.log and Log.i(TAG), so a
     * plain `logcat -s AliveClean` after boot shows everything without needing
     * the LSPosed log page or a diagnostic zip.
     */
    private static final String[] DOZE_FW_CLASSES = new String[]{
            "com.android.server.wm.WallpaperController",
            "com.android.server.wm.WallpaperWindowToken",
            "com.android.server.wm.WindowToken"};

    private static void installDozeFramework(ClassLoader cl) {
        Class<?> wms = firstExisting(cl,
                "com.android.server.wallpaper.WallpaperManagerService");
        if (wms != null) {
            try {
                int n = XposedBridge.hookAllMethods(wms, "setInAmbientMode",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                    if (p.args.length > 0 && p.args[0] instanceof Boolean) {
                                        boolean value = (Boolean) p.args[0];
                                        if (ambient != value) {
                                            ambient = value;
                                            dozeLog("framework ambient=" + value);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }).size();
                dozeLog("setInAmbientMode hook x" + n + (n == 0 ? " MISS" : ""));
            } catch (Throwable error) {
                failure("setInAmbientMode", error);
            }
        } else {
            dozeLog("WallpaperManagerService MISSING");
        }

        for (String name : DOZE_FW_CLASSES) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c == null) {
                dozeLog("doze fw MISSING " + name);
                continue;
            }
            java.util.Set<String> seen = new HashSet<String>();
            StringBuilder listing = new StringBuilder();
            int hooked = 0;
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                Method[] methods;
                try {
                    methods = k.getDeclaredMethods();
                } catch (Throwable error) {
                    continue;
                }
                for (Method m : methods) {
                    String low = m.getName().toLowerCase(Locale.ROOT);
                    if (!(low.contains("visib") || low.contains("shown")
                            || low.contains("hidden") || low.contains("ambient")
                            || low.contains("doz") || low.contains("wallpaper"))) continue;
                    String key = m.getName() + "#" + m.getParameterTypes().length;
                    if (!seen.add(key)) continue;
                    if (listing.length() < 1600) listing.append(key).append(' ');
                    if (hookVisibilityMethod(m)) hooked++;
                }
            }
            dozeLog("doze fw " + name + " hooked=" + hooked
                    + " candidates: " + listing);
        }

        dozeLog("doze framework hooks installed");
    }

    /** Heuristic hook: force "visible" only while ambient, only for wallpaper objects. */
    private static boolean hookVisibilityMethod(final Method m) {
        final String n = m.getName().toLowerCase(Locale.ROOT);
        final Class<?> ret = m.getReturnType();
        final Class<?>[] ps = m.getParameterTypes();
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!ambient || !isWallpaperObject(p.thisObject)) return;
                    for (int i = 0; i < ps.length && i < p.args.length; i++) {
                        try {
                            if (ps[i] == boolean.class || ps[i] == Boolean.class) {
                                p.args[i] = !n.contains("hidden");
                            } else if ((ps[i] == int.class || ps[i] == Integer.class)
                                    && (n.contains("visib") || n.contains("shown")
                                        || n.contains("hidden"))) {
                                p.args[i] = 0; // View.VISIBLE / not hidden
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!ambient || !isWallpaperObject(p.thisObject)) return;
                    if (ret == boolean.class || ret == Boolean.class) {
                        if (n.startsWith("is") || n.startsWith("has")
                                || n.contains("visible")) {
                            p.setResult(true);
                        }
                    } else {
                        forceShown(p.thisObject);
                    }
                }
            });
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean isWallpaperObject(Object o) {
        if (o == null) return false;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.contains("WallpaperController") || n.contains("WallpaperWindowToken")) return true;
        }
        return false;
    }

    /** Best-effort: flip any shown/visible setter on a wallpaper token after a state update. */
    private static void forceShown(Object o) {
        if (o == null) return;
        try { XposedHelpers.callMethod(o, "setShownVisibility", true); } catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(o, "setClientVisible", true); } catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(o, "setVisibility", 0); } catch (Throwable ignored) {}
        try {
            Object tokens = XposedHelpers.getObjectField(o, "mWallpaperTokens");
            if (tokens instanceof Iterable<?>) {
                for (Object t : (Iterable<?>) tokens) {
                    if (t != null && t != o) forceShown(t);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void dozeLog(String message) {
        XposedBridge.log("AliveClean: " + message);
        Log.i(TAG, message);
    }

    private static Class<?> firstExisting(ClassLoader cl, String... names) {
        for (String name : names) {
            Class<?> found = XposedHelpers.findClassIfExists(name, cl);
            if (found != null) return found;
        }
        return null;
    }

    private static void failure(String stage, Throwable error) {
        if (failures.add(stage)) {
            XposedBridge.log("AliveClean: AOSP " + stage + " unavailable: " + error);
            android.util.Log.w(TAG, "AOSP " + stage + " unavailable", error);
        }
    }
}

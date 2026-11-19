package org.aliveclean;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collections;
import java.util.HashSet;
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

    private AospBridge() {}

    static synchronized void install(ClassLoader cl) {
        if (installed || cl == null) return;
        installed = true;
        installApplication(cl);
        installPowerEvents(cl);
        installUnlockEvents(cl);
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
     * system_server side: AOSP has no com.android.server.pm.IPackageManagerBase.
     * AMBIENT_WALLPAPER is a signature|privileged permission, so a normally
     * installed module cannot hold it. Grant it on the by-name check path.
     *
     * If your build checks by uid only, install the APK as a priv-app instead;
     * ClassNameProbe below records which path is available in logcat.
     */
    static void installFramework(ClassLoader cl) {
        if (cl == null) return;
        Class<?> target = firstExisting(cl,
                "com.android.server.pm.PackageManagerService",
                "com.android.server.pm.PackageManagerService$IPackageManagerImpl",
                "com.android.server.pm.IPackageManagerImpl",
                "com.android.server.pm.PackageManagerServiceUtils");
        Log.i(TAG, "AOSP framework=" + (target == null ? "missing, use priv-app" : target.getName()));
        if (target == null) {
            XposedBridge.log("AliveClean: AOSP permission adapter unavailable; use priv-app install");
            return;
        }
        XC_MethodHook grant = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                boolean ambient = false;
                boolean ours = false;
                for (Object arg : p.args) {
                    if ("android.permission.AMBIENT_WALLPAPER".equals(arg)) ambient = true;
                    if ("org.aliveclean".equals(arg)) ours = true;
                }
                if (ambient && ours) {
                    p.setResult(PackageManager.PERMISSION_GRANTED);
                }
            }
        };
        for (String method : new String[]{"checkPermission", "checkUidPermission"}) {
            try {
                if (!XposedBridge.hookAllMethods(target, method, grant).isEmpty()) {
                    XposedBridge.log("AliveClean: AOSP ambient permission adapter on "
                            + target.getName() + "." + method);
                    Log.i(TAG, "AOSP permission hook " + target.getName() + "." + method);
                }
            } catch (Throwable error) {
                failure("framework " + method, error);
            }
        }
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

package org.aliveclean;

import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * SystemUI half of the AOD picture takeover.
 *
 * The 17:09 log already shows `SystemUI dozing=true`, so the dozing tracker
 * works. What remains is the compositing layer: the wallpaper window sits at
 * mBaseLayer 11000 while NotificationShade sits at 171000, and its
 * WindowManager.LayoutParams does NOT carry FLAG_SHOW_WALLPAPER while dozing.
 * Three things are done here, all guarded and only while dozing:
 *
 *   (1) every scrim alpha is forced to 0 (ScrimController methods + a
 *       reflective sweep of ScrimView's own alpha/tint fields),
 *   (2) NotificationShadeWindowControllerImpl's LayoutParams gains
 *       FLAG_SHOW_WALLPAPER on each apply(), so WMS keeps a wallpaper target,
 *   (3) the real method list is written to logcat so one `logcat -s AliveClean`
 *       after boot confirms every hook without a diagnostic zip.
 *
 * dozing=false restores stock lockscreen dimming immediately.
 */
final class AospDozeHooks {
    private static final String TAG = "AliveClean";
    private static final int FLAG_SHOW_WALLPAPER = 0x00100000;

    static volatile boolean dozing;
    private static volatile boolean installed;
    private static volatile int zeroed;

    private AospDozeHooks() {}

    static synchronized void install(ClassLoader cl) {
        if (installed || cl == null) return;
        installed = true;

        // ---- (a) dozing state -------------------------------------------------
        for (String owner : new String[]{
                "com.android.systemui.statusbar.phone.DozeScrimController",
                "com.android.systemui.shade.NotificationPanelViewController"}) {
            Class<?> c = XposedHelpers.findClassIfExists(owner, cl);
            if (c == null) {
                log("doze owner MISSING: " + owner);
                continue;
            }
            for (String method : new String[]{"setDozing", "onDozingChanged"}) {
                try {
                    int n = XposedBridge.hookAllMethods(c, method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (p.args.length > 0 && p.args[0] instanceof Boolean) {
                                    boolean value = (Boolean) p.args[0];
                                    if (dozing != value) {
                                        dozing = value;
                                        zeroed = 0;
                                        log("SystemUI dozing=" + value);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }).size();
                    if (n > 0) log("doze hook " + owner + "." + method + " x" + n);
                    else log("doze hook MISS " + owner + "." + method);
                } catch (Throwable error) {
                    log("doze hook " + owner + "." + method + " failed: " + error);
                }
            }
        }

        // ---- (b) transparent scrim -------------------------------------------
        Class<?> scrim = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.ScrimController", cl);
        if (scrim == null) {
            log("ScrimController MISSING");
        } else {
            StringBuilder listing = new StringBuilder();
            int hooked = 0;
            for (Class<?> k = scrim; k != null && k != Object.class; k = k.getSuperclass()) {
                Method[] methods;
                try { methods = k.getDeclaredMethods(); } catch (Throwable e) { continue; }
                for (Method m : methods) {
                    final String n = m.getName().toLowerCase(Locale.ROOT);
                    if (!(n.contains("alpha") || n.contains("scrim")
                            || n.contains("tint") || n.contains("color"))) continue;
                    if (listing.length() < 1400) {
                        listing.append(m.getName()).append('#')
                                .append(m.getParameterTypes().length).append(' ');
                    }
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!dozing) return;
                                for (int i = 0; i < p.args.length; i++) {
                                    try {
                                        if (p.args[i] instanceof Float) p.args[i] = 0f;
                                        else if ((n.contains("tint") || n.contains("color"))
                                                && p.args[i] instanceof Integer) p.args[i] = 0;
                                    } catch (Throwable ignored) {}
                                }
                            }
                        });
                        hooked++;
                    } catch (Throwable ignored) {}
                }
            }
            log("ScrimController alpha/color hooks x" + hooked + " methods: " + listing);

            XC_MethodHook clearViews = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!dozing) return;
                    try {
                        Object host = p.thisObject;
                        Class<?> type = host.getClass();
                        while (type != null && type != Object.class) {
                            for (Field f : type.getDeclaredFields()) {
                                if (Modifier.isStatic(f.getModifiers())) continue;
                                if (!f.getType().getName().endsWith("ScrimView")) continue;
                                f.setAccessible(true);
                                Object value = f.get(host);
                                if (value instanceof View) {
                                    View v = (View) value;
                                    if (v.getAlpha() != 0f) { v.setAlpha(0f); zeroed++; }
                                    zeroScrimFields(value);
                                }
                            }
                            type = type.getSuperclass();
                        }
                    } catch (Throwable ignored) {}
                }
            };
            for (String method : new String[]{
                    "updateScrims", "applyAndDispatchState", "onPreDraw",
                    "applyState$1", "scheduleUpdate$1"}) {
                try {
                    int n = XposedBridge.hookAllMethods(scrim, method, clearViews).size();
                    if (n > 0) log("ScrimController." + method + " x" + n + " (view sweep)");
                } catch (Throwable error) {
                    log("ScrimController." + method + " hook failed: " + error);
                }
            }
        }

        // ---- (c) give the shade window a wallpaper target while dozing --------
        Class<?> windowCtl = firstExisting(cl,
                "com.android.systemui.shade.NotificationShadeWindowControllerImpl",
                "com.android.systemui.statusbar.NotificationShadeWindowController");
        if (windowCtl == null) {
            log("NotificationShadeWindowController MISSING");
        } else {
            XC_MethodHook addFlag = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!dozing) return;
                    try {
                        Object host = p.thisObject;
                        Class<?> type = host.getClass();
                        while (type != null && type != Object.class) {
                            for (Field f : type.getDeclaredFields()) {
                                if (Modifier.isStatic(f.getModifiers())) continue;
                                if (!WindowManager.LayoutParams.class.isAssignableFrom(f.getType())) continue;
                                f.setAccessible(true);
                                WindowManager.LayoutParams lp =
                                        (WindowManager.LayoutParams) f.get(host);
                                if (lp != null && (lp.flags & FLAG_SHOW_WALLPAPER) == 0) {
                                    lp.flags |= FLAG_SHOW_WALLPAPER;
                                    zeroed++;
                                }
                            }
                            type = type.getSuperclass();
                        }
                    } catch (Throwable ignored) {}
                }
            };
            int total = 0;
            for (String method : new String[]{
                    "apply", "applyWindowLayoutParams", "batchApplyWindowLayoutParams",
                    "setPanelVisible", "onIsOnOrGoingToDreamChanged"}) {
                try {
                    total += XposedBridge.hookAllMethods(windowCtl, method, addFlag).size();
                } catch (Throwable ignored) {}
            }
            log("NotificationShade SHOW_WALLPAPER hooks x" + total);
        }

        log("AOSP doze hooks installed");
    }

    /** ScrimView draws with its own mAlpha/mTint, not just View.setAlpha(). */
    private static void zeroScrimFields(Object view) {
        try {
            for (Class<?> k = view.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field g : k.getDeclaredFields()) {
                    if (Modifier.isStatic(g.getModifiers())) continue;
                    String n = g.getName().toLowerCase(Locale.ROOT);
                    g.setAccessible(true);
                    if (n.contains("alpha") && g.getType() == float.class) g.setFloat(view, 0f);
                    else if ((n.contains("tint") || n.contains("color"))
                            && g.getType() == int.class) g.setInt(view, 0);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Class<?> firstExisting(ClassLoader cl, String... names) {
        for (String name : names) {
            Class<?> found = XposedHelpers.findClassIfExists(name, cl);
            if (found != null) return found;
        }
        return null;
    }

    private static void log(String message) {
        XposedBridge.log("AliveClean: " + message);
        Log.i(TAG, message);
    }
}

package org.aliveclean;

import android.util.Log;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * SystemUI half of the AOD picture takeover.
 *
 * Now that Zygisk Next injects com.android.systemui (LSPosed v2.2.1),
 * two things keep the stock AOD on screen even though CleanWallpaper is
 * already rendering mode 0:
 *
 *   1. ScrimController paints the behind/front scrim opaque black while
 *      dozing, covering the wallpaper window (mBaseLayer 11000) with the
 *      NotificationShade window (mBaseLayer 171000).
 *   2. The framework hides the wallpaper window; that is fixed separately
 *      by AospBridge.installDozeFramework() in system_server.
 *
 * Here we (a) track dozing from DozeScrimController / NotificationPanel
 * and (b) force every scrim alpha to 0 during doze so the OpenAlive frame
 * underneath becomes visible. The stock AOD clock still draws on top.
 *
 * Everything is optional and guarded: a missing class or method only logs,
 * never throws out of handleLoadPackage, and dozing=false restores normal
 * lockscreen dimming immediately.
 */
final class AospDozeHooks {
    private static final String TAG = "AliveClean";

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
                log("doze owner missing: " + owner);
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
                } catch (Throwable error) {
                    log("doze hook " + owner + "." + method + " failed: " + error);
                }
            }
        }

        // ---- (b) transparent scrim -------------------------------------------
        Class<?> scrim = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.ScrimController", cl);
        if (scrim == null) {
            log("ScrimController missing (no transparent AOD)");
            return;
        }

        try {
            int n = XposedBridge.hookAllMethods(scrim, "setScrimAlpha", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (dozing && p.args.length >= 2 && p.args[1] instanceof Float) {
                            p.args[1] = 0f;
                        }
                    } catch (Throwable ignored) {}
                }
            }).size();
            log("ScrimController.setScrimAlpha x" + n + (n > 0 ? " (force 0 in doze)" : " MISS"));
        } catch (Throwable error) {
            log("setScrimAlpha hook failed: " + error);
        }

        // Some builds animate the scrim View directly instead of going through
        // setScrimAlpha. Zero every ScrimView field on each state dispatch.
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
                                if (v.getAlpha() != 0f) {
                                    v.setAlpha(0f);
                                    zeroed++;
                                }
                            }
                        }
                        type = type.getSuperclass();
                    }
                } catch (Throwable ignored) {}
            }
        };
        for (String method : new String[]{"updateScrims", "applyAndDispatchState", "onPreDraw"}) {
            try {
                int n = XposedBridge.hookAllMethods(scrim, method, clearViews).size();
                if (n > 0) log("ScrimController." + method + " x" + n + " (view sweep)");
            } catch (Throwable error) {
                log("ScrimController." + method + " hook failed: " + error);
            }
        }
        log("AOSP doze hooks installed");
    }

    private static void log(String message) {
        XposedBridge.log("AliveClean: " + message);
        Log.i(TAG, message);
    }
}

package org.aliveclean;

import android.app.WallpaperInfo;
import android.util.Log;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LineageOS-only Xposed entry point.
 *
 * One runtime path, no platform sniffing:
 *   com.android.systemui -> AospBridge + ModuleActivationHook
 *   android              -> AospBridge.installFramework
 *
 * Every step is isolated. A failure in one hook must never abort the rest of
 * handleLoadPackage: earlier revisions lost the whole SystemUI bridge because
 * a single unguarded call threw. XposedBridge.log is used in addition to Log
 * so LSPosed's own "modules" log records the entry point even when logcat's
 * main buffer is flooded.
 */
public final class CleanHooks implements IXposedHookLoadPackage {
    private static final String TAG = "AliveClean";
    private static boolean ambientHooked;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg = p.packageName;
        if (!"android".equals(pkg) && !"com.android.systemui".equals(pkg)) return;

        XposedBridge.log("AliveClean: handleLoadPackage " + pkg);
        Log.i(TAG, "LineageOS build: hook entry " + pkg);

        if ("com.android.systemui".equals(pkg)) {
            // Scene bridge FIRST. It must survive even if the activation
            // receipt hook fails on a given SystemUI build.
            try {
                AospBridge.install(p.classLoader);
            } catch (Throwable error) {
                XposedBridge.log("AliveClean: AospBridge.install failed: " + error);
                Log.e(TAG, "AospBridge.install failed", error);
            }
            try {
                ModuleActivationHook.install(p.classLoader);
            } catch (Throwable error) {
                XposedBridge.log("AliveClean: activation hook failed: " + error);
                Log.e(TAG, "ModuleActivationHook.install failed", error);
            }
        } else {
            try {
                AospBridge.installFramework(p.classLoader);
            } catch (Throwable error) {
                XposedBridge.log("AliveClean: installFramework failed: " + error);
                Log.e(TAG, "AospBridge.installFramework failed", error);
            }
        }

        if (!ambientHooked) {
            try {
                XposedHelpers.findAndHookMethod(WallpaperInfo.class, "supportsAmbientMode",
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam call) {
                                WallpaperInfo info = (WallpaperInfo) call.thisObject;
                                if ("org.aliveclean".equals(info.getPackageName())) {
                                    call.setResult(true);
                                }
                            }
                        });
                ambientHooked = true;
            } catch (Throwable error) {
                XposedBridge.log("AliveClean: ambient mode hook failed: " + error);
            }
        }

        XposedBridge.log("AliveClean: hooks done in " + pkg);
    }
}

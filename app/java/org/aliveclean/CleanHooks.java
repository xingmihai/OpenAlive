package org.aliveclean;

import android.app.WallpaperInfo;
import android.util.Log;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LineageOS-only Xposed entry point.
 *
 * Two independent halves:
 *
 *   android (system_server) -> AospBridge.installFramework()
 *       REQUIRED for the AOD picture. We declare supportsAmbientMode=true so
 *       AOSP keeps the live-wallpaper window visible during doze and calls
 *       Engine.onAmbientModeChanged(true, ...). That declaration makes
 *       WallpaperManagerService.setWallpaperComponentChecked demand the
 *       signature|privileged AMBIENT_WALLPAPER permission, which a normally
 *       installed APK cannot hold. The framework hook grants exactly that one
 *       permission for wallpaper setup. Four diagnostic dumps confirm LSPosed
 *       reliably injects android on this NyaZygisk/KernelSU build.
 *
 *   com.android.systemui -> AospBridge.install() + ModuleActivationHook
 *       OPTIONAL. Adds finer keyguard/wakefulness signals. LSPosed never
 *       injects SystemUI on this device, so "LSP 未激活" is expected and does
 *       NOT mean the wallpaper is broken.
 *
 * The wallpaper's own mode 0/1/2 state machine lives in CleanWallpaper and
 * needs neither half: lockscreen and desktop always work.
 */
public final class CleanHooks implements IXposedHookLoadPackage {
    private static final String TAG = "AliveClean";
    private static boolean ambientHooked;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg = p.packageName;

        XposedBridge.log("AliveClean: handleLoadPackage " + pkg);

        if ("android".equals(pkg)) {
            try {
                AospBridge.installFramework(p.classLoader);
                XposedBridge.log("AliveClean: framework hooks done");
            } catch (Throwable error) {
                XposedBridge.log("AliveClean: installFramework failed: " + error);
                Log.e(TAG, "installFramework failed", error);
            }
        } else if ("com.android.systemui".equals(pkg)) {
            Log.i(TAG, "LineageOS build: hook entry " + pkg);
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
            return;
        }

        // Some builds resolve WallpaperInfo from a cached PackageManager
        // result rather than the manifest. Keep the declaration honest there.
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
                XposedBridge.log("AliveClean: WallpaperInfo ambient hook in " + pkg);
            } catch (Throwable error) {
                XposedBridge.log("AliveClean: WallpaperInfo ambient hook failed: " + error);
            }
        }
    }
}

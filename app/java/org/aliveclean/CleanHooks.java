package org.aliveclean;

import android.util.Log;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LineageOS-only Xposed entry point. This module is now OPTIONAL.
 *
 * The wallpaper (CleanWallpaper) drives mode 0/1/2 itself from platform
 * ACTION_SCREEN_OFF / SCREEN_ON / USER_PRESENT broadcasts and the Display
 * doze state. It declares supportsAmbientMode=false, so applying it never
 * needs AMBIENT_WALLPAPER or any framework hook. Lockscreen, desktop and
 * AOD scene switching therefore work even if this module is not loaded.
 *
 * When LSPosed does manage to inject com.android.systemui, AospBridge adds
 * finer keyguard lifecycle signals (wakefulness, biometric unlock) and the
 * activation receipt. Nothing here is a prerequisite for the wallpaper.
 *
 * Note: on this device (NyaZygisk v2.5-383 / KernelSU) LSPosed reliably
 * injects android/system_server but never com.android.systemui. Do not treat
 * "LSP 未激活" as a broken wallpaper; check the scene modes instead.
 */
public final class CleanHooks implements IXposedHookLoadPackage {
    private static final String TAG = "AliveClean";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg = p.packageName;

        if ("android".equals(pkg)) {
            // Kept in xposed_scope so existing configurations stay valid, but
            // intentionally a no-op: no permission spoofing is needed anymore.
            XposedBridge.log("AliveClean: framework scope unused on LineageOS");
            return;
        }
        if (!"com.android.systemui".equals(pkg)) return;

        XposedBridge.log("AliveClean: handleLoadPackage " + pkg);
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

        XposedBridge.log("AliveClean: hooks done in " + pkg);
    }
}

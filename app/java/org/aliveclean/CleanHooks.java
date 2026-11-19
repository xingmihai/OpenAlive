package org.aliveclean;

import android.app.WallpaperInfo;
import android.util.Log;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LineageOS-only Xposed entry point.
 *
 * Upstream OpenAlive targets ColorOS 17 and drives its scene machine through
 * com.oplus.* reflection. This fork deliberately has a single runtime path:
 *
 *   com.android.systemui -> ModuleActivationHook + AospBridge
 *   android (system_server) -> AospBridge.installFramework
 *
 * The ColorOS bridge, the OPlus wallpapers scope and the OEM permission shim
 * are gone. If this APK somehow lands on a ColorOS build it refuses to load
 * instead of half-working; use upstream Maga-King/OpenAlive there.
 *
 * Rendering, assets, Rust and the wallpaper editor are platform-neutral and
 * are shared verbatim with upstream.
 */
public final class CleanHooks implements IXposedHookLoadPackage {
    private static final String TAG = "AliveClean";
    private static boolean ambientHooked;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg = p.packageName;
        if (!"android".equals(pkg) && !"com.android.systemui".equals(pkg)) return;

        // Hard platform guard. A LineageOS SystemUI has zero com.oplus.* classes
        // (verified against the device's 3-dex SystemUI APK); a ColorOS one has
        // SmoothTransitionController. Do not silently fall back on the OEM ROM.
        if (XposedHelpers.findClassIfExists(
                "com.oplus.systemui.aod.display.SmoothTransitionController",
                p.classLoader) != null) {
            Log.w(TAG, "LineageOS-only fork: refusing to load on ColorOS (" + pkg + ")");
            return;
        }

        Log.i(TAG, "LineageOS build: installing AOSP bridge in " + pkg);

        if ("com.android.systemui".equals(pkg)) {
            ModuleActivationHook.install();
            AospBridge.install(p.classLoader);
        } else {
            AospBridge.installFramework(p.classLoader);
        }

        if (!ambientHooked) {
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
        }

        if (Diagnostics.TRACE) {
            XposedBridge.log("AliveClean: LineageOS hooks loaded in " + pkg);
        }
    }
}

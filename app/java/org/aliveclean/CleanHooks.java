package org.aliveclean;

import android.app.WallpaperInfo;
import android.util.Log;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Package-scoped wallpaper capability bridge.
 *
 * ColorOS keeps its original path untouched. When the hooked SystemUI does not
 * contain the OPlus AOD controller, the same scene protocol is served by the
 * AOSP bridge instead. The com.oplus.wallpapers branch simply never fires on
 * LineageOS, so no OPlus-only class is ever loaded there.
 */
public final class CleanHooks implements IXposedHookLoadPackage {
    private static boolean ambientHooked;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg = p.packageName;
        boolean colorOsUi = "com.android.systemui".equals(pkg)
                && Platform.colorOsSystemUi(p.classLoader);
        if ("android".equals(pkg) || "com.android.systemui".equals(pkg)) {
            Log.i("AliveClean", "platform=" + (colorOsUi ? "ColorOS" : "AOSP")
                    + " pkg=" + pkg);
        }

        if ("com.android.systemui".equals(pkg)) {
            ModuleActivationHook.install();
            if (colorOsUi) {
                ColorOsNativeClockFonts.install(p.classLoader);
                ColorOsBridge.install(p.classLoader);
            } else {
                AospBridge.install(p.classLoader);
            }
        }

        if ("com.oplus.wallpapers".equals(pkg)) {
            NativeClockApplyPolicy.install(p.classLoader);
            ColorOsNativeClockFonts.install(p.classLoader);
        }

        if (!"android".equals(pkg) && !"com.android.systemui".equals(pkg)) return;

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

        if ("android".equals(pkg)) {
            if (Platform.colorOsFramework(p.classLoader)) {
                // Verified against this phone's WallpaperManagerService and IPackageManagerBase Smali.
                // Binding checks this exact permission independently of supportsAmbientMode().
                Class<?> base = XposedHelpers.findClassIfExists(
                        "com.android.server.pm.IPackageManagerBase", p.classLoader);
                if (base != null) {
                    XposedHelpers.findAndHookMethod(base, "checkPermission",
                            String.class, String.class, int.class, new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam call) {
                                    if ("android.permission.AMBIENT_WALLPAPER".equals(call.args[0])
                                            && "org.aliveclean".equals(call.args[1])) {
                                        call.setResult(android.content.pm.PackageManager.PERMISSION_GRANTED);
                                    }
                                }
                            });
                } else {
                    XposedBridge.log("AliveClean: ambient permission adapter unavailable on this framework");
                }
            } else {
                AospBridge.installFramework(p.classLoader);
            }
        }

        if (Diagnostics.TRACE) {
            XposedBridge.log("AliveClean: platform hooks loaded in " + pkg);
        }
    }
}

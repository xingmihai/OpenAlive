package org.aliveclean;

import android.content.Context;
import de.robv.android.xposed.XposedHelpers;

/**
 * @deprecated Legacy ColorOS capability probe, no longer used by CleanHooks.
 * This fork is LineageOS-only and takes a single runtime path. The class is
 * kept so the out-of-tree PlatformApply helper and the independent provider
 * checks still compile; it is never loaded at runtime on LineageOS.
 */
final class Platform {

    private static final String OPLUS_AOD_CONTROLLER =
            "com.oplus.systemui.aod.display.SmoothTransitionController";
    private static final String OPLUS_PM_BASE =
            "com.android.server.pm.IPackageManagerBase";
    private static final String OPLUS_WALLPAPERS = "com.oplus.wallpapers";

    private Platform() {}

    /** True when the hooked SystemUI really is the OPlus implementation. */
    static boolean colorOsSystemUi(ClassLoader cl) {
        return cl != null
                && XposedHelpers.findClassIfExists(OPLUS_AOD_CONTROLLER, cl) != null;
    }

    /** True when system_server carries the OPlus permission-check shim. */
    static boolean colorOsFramework(ClassLoader cl) {
        return cl != null
                && XposedHelpers.findClassIfExists(OPLUS_PM_BASE, cl) != null;
    }

    /** Utility for UI code that wants to hide OPlus-only entries on AOSP. */
    static boolean hasOplusWallpapers(Context c) {
        if (c == null) return false;
        try {
            c.getPackageManager().getPackageInfo(OPLUS_WALLPAPERS, 0);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }
}

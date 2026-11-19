package org.aliveclean;

import android.content.Context;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runtime capability probe for the ColorOS / AOSP split.
 *
 * The fork has to decide which bridge to install from inside the process that is
 * being hooked, so a ro.build fingerprint or a shared static flag is useless:
 * system_server and SystemUI do not share a heap. Probes here only look at
 * classes and packages that are actually visible to the current loader.
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

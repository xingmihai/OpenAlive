package org.aliveclean;

import android.content.Context;

/**
 * LineageOS-only AOD setup.
 *
 * Upstream shells out to an OPlus AOD provider through `su 1000` to switch
 * ColorOS into panoramic AOD. LineageOS has no such provider and no such
 * setting: scene transitions are delivered by AospBridge inside SystemUI.
 * There is nothing to configure through root here, so no shell is executed.
 */
final class RootBridge {

    /** No-op on AOSP; kept as the single call site used by the home screen. */
    static void allowClockStartup(Context context) {
        android.util.Log.i("AliveClean", "LineageOS: no OEM clock startup setting to apply");
    }

    static String prepareAod(Context context) {
        if (android.os.Process.myUid() / 100000 != 0) {
            return "壁纸已应用；多用户下场景桥接仅作用于主用户";
        }
        // The receipt is process-lifetime only: replacing the APK kills this app
        // process and clears NativeClockStatusProvider.module, while a still
        // running SystemUI does not re-announce on its own. Ask it to announce
        // and poll briefly; the home indicator remains the authoritative check.
        ModuleActivation.request(context);
        boolean active = false;
        for (int i = 0; i < 8 && !active; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            active = ModuleActivation.active(context);
        }
        android.util.Log.i(TAG_FALLBACK, "LineageOS AOD: no OEM provider; moduleActive=" + active
                + " (after request; home indicator is authoritative)");
        return "壁纸已应用，AOSP 场景切换由系统界面桥接接管";
    }

    private static final String TAG_FALLBACK = "AliveClean";
    private RootBridge() {}
}

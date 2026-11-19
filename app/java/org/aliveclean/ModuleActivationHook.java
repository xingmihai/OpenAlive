package org.aliveclean;

import android.app.Application;
import android.content.*;
import android.os.Build;
import de.robv.android.xposed.*;

/**
 * SystemUI-side activation receipt.
 *
 * Upstream hooked Application.attach(Context). On this LineageOS build that
 * left no receiver registered: the app's QUERY broadcast went unanswered and
 * the home indicator stayed "LSP 未激活" even though LSPosed had injected the
 * module. We now hook the verified SystemUIApplicationImpl.onCreate() instead,
 * keep Application.attach as a fallback, register a receiver for several boot
 * events, and re-announce on each one.
 */
final class ModuleActivationHook {
    private static final String TAG = "AliveClean";
    private static boolean registered;

    static void install(ClassLoader cl) {
        String[] owners = {
                "com.android.systemui.application.impl.SystemUIApplicationImpl",
                "com.android.systemui.application.SystemUIApplication",
                "com.android.systemui.SystemUIApplication",
        };
        for (String name : owners) {
            Class<?> app = XposedHelpers.findClassIfExists(name, cl);
            if (app == null) continue;
            try {
                XposedHelpers.findAndHookMethod(app, "onCreate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        onSystemUiReady((Context) p.thisObject);
                    }
                });
                XposedBridge.log("AliveClean: activation hook on " + name + ".onCreate");
                return;
            } catch (Throwable error) {
                XposedBridge.log("AliveClean: activation hook " + name + " failed: " + error);
            }
        }
        // Legacy fallback for branches where onCreate is not overridable.
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            onSystemUiReady((Context) p.thisObject);
                        }
                    });
            XposedBridge.log("AliveClean: activation hook on Application.attach (fallback)");
        } catch (Throwable error) {
            XposedBridge.log("AliveClean: activation hook fallback failed: " + error);
        }
    }

    private static synchronized void onSystemUiReady(Context context) {
        if (context == null || !"com.android.systemui".equals(context.getPackageName())) return;
        if (!registered) {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    ModuleActivation.announce(context);
                }
            };
            IntentFilter filter = new IntentFilter(ModuleActivation.QUERY);
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            filter.addAction(Intent.ACTION_USER_UNLOCKED);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
                registered = true;
                XposedBridge.log("AliveClean: activation receiver registered");
            } catch (Throwable error) {
                XposedBridge.log("AliveClean: receiver registration failed: " + error);
            }
        }
        ModuleActivation.announce(context);
    }

    private ModuleActivationHook() {}
}

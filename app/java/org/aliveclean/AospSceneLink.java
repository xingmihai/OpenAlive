package org.aliveclean;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * Platform-neutral link between a hooked SystemUI and the OpenAlive wallpaper engine.
 *
 * This deliberately does NOT go through ColorOsBridge: that class keeps a lot of
 * OPlus state (aodClock, clocks, immersiveBackdrop, continuousAod) and its private
 * connect() dereferences them. On LineageOS none of those objects exist, so we
 * reproduce only the two things the renderer actually consumes:
 *
 *   1. the authenticated Binder channel from SceneProvider.call("connect")
 *   2. Message.what == 1 with {mode, animate, time, phase, clock_wake}
 *
 * Bundle keys, action strings and the SceneState ordering are byte-for-byte the
 * same as ColorOsBridge, so no renderer / Rust / asset change is needed.
 */
final class AospSceneLink {

    static final String ACTION = "org.aliveclean.SCENE";
    static final String ACTION_LAYOUT = "org.aliveclean.AOD_LAYOUT";
    private static final String SCENE_URI = "content://org.aliveclean.scene";
    private static final String TAG = "AliveClean";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final IBinder owner = new Binder();
    private static final SceneState stateOrder = new SceneState();

    private static volatile boolean selected;
    private static volatile Messenger channel;
    private static volatile boolean connecting;
    private static volatile Context context;
    private static volatile int lastMode = -1;
    private static Handler worker;

    private static final Messenger clockFeedback = new Messenger(new Handler(
            Looper.getMainLooper(), message -> true));

    private AospSceneLink() {}

    /** Called from SystemUIApplication.onCreate (or any SystemUI Context). */
    static synchronized void start(Context c) {
        if (c == null) return;
        if (context != null) return;
        Context app = c.getApplicationContext();
        context = app == null ? c : app;

        HandlerThread thread = new HandlerThread("AliveAospObserver");
        thread.start();
        worker = new Handler(thread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_WALLPAPER_CHANGED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    dispatch(0, true, 1, "AOD");
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    dispatch(1, true, 2, "KEYGUARD");
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    dispatch(2, true, 3, "UNLOCK");
                } else {
                    refresh();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        Log.i(TAG, "AOSP link start");
        refresh();
    }

    static boolean ownsWallpaper() {
        return selected;
    }

    /** mode: 0 = AOD, 1 = keyguard / lock, 2 = unlocked desktop. */
    static void dispatch(int mode, boolean animate, int phase, String state) {
        long time = SystemClock.uptimeMillis();
        main.post(() -> {
            Log.i(TAG, "AOSP scene mode=" + mode + " phase=" + phase
                    + " state=" + state + " selected=" + selected);
            if (!selected) return;
            stateOrder.accept(mode, time, phase);
            if (stateOrder.mode() != mode) return;
            lastMode = mode;
            Bundle b = new Bundle();
            b.putInt("mode", mode);
            b.putBoolean("animate", animate);
            b.putLong("time", time);
            b.putInt("phase", phase);
            b.putLong("clock_wake", 0L);
            send(1, b);
        });
    }

    private static void refresh() {
        Handler w = worker;
        Context c = context;
        if (w == null || c == null) return;
        w.post(() -> {
            try {
                WallpaperManager manager = WallpaperManager.getInstance(c);
                WallpaperInfo info;
                if (Build.VERSION.SDK_INT >= 34) {
                    info = manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
                } else {
                    info = manager.getWallpaperInfo();
                }
                if (info == null && manager.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0) {
                    info = manager.getWallpaperInfo();
                }
                selected = info != null && "org.aliveclean".equals(info.getPackageName());
                Log.i(TAG, "AOSP wallpaper selected=" + selected
                        + (info == null ? " (no WallpaperInfo)" : " pkg=" + info.getPackageName()));
                if (selected) {
                    connect();
                    if (lastMode >= 0) {
                        Bundle b = new Bundle();
                        b.putInt("mode", lastMode);
                        b.putBoolean("animate", false);
                        b.putLong("time", SystemClock.uptimeMillis());
                        b.putInt("phase", 0);
                        b.putLong("clock_wake", 0L);
                        send(1, b);
                    }
                } else {
                    synchronized (AospSceneLink.class) {
                        channel = null;
                        stateOrder.disconnect();
                        lastMode = -1;
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "AOSP wallpaper selection unavailable", error);
            }
        });
    }

    private static synchronized void connect() {
        if (connecting || channel != null || worker == null || !selected) return;
        connecting = true;
        final Context c = context;
        worker.post(() -> {
            try {
                Bundle request = new Bundle();
                request.putBinder("owner", owner);
                request.putBinder("clock_feedback", clockFeedback.getBinder());
                request.putInt("clock_api", 0);
                request.putString("clock_error", "");
                Bundle reply = c.getContentResolver().call(
                        Uri.parse(SCENE_URI), "connect", null, request);
                if (reply == null || reply.getBinder("channel") == null) {
                    throw new IllegalStateException("Missing scene channel");
                }
                IBinder binder = reply.getBinder("channel");
                binder.linkToDeath(() -> {
                    channel = null;
                    if (selected) refresh();
                }, 0);
                channel = new Messenger(binder);
                Log.i(TAG, "AOSP scene channel connected");
            } catch (Throwable error) {
                Log.w(TAG, "AOSP scene channel unavailable", error);
            } finally {
                synchronized (AospSceneLink.class) {
                    connecting = false;
                }
            }
        });
    }

    private static void send(int what, Bundle data) {
        Messenger target = channel;
        if (target != null) {
            try {
                Message message = Message.obtain();
                message.what = what;
                message.setData(data);
                target.send(message);
                return;
            } catch (RemoteException dead) {
                channel = null;
            }
        }
        Context c = context;
        if (c != null) {
            Intent event = new Intent(what == 1 ? ACTION : ACTION_LAYOUT)
                    .setPackage("org.aliveclean")
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtras(data);
            Log.i(TAG, "AOSP send fallback broadcast what=" + what);
            c.sendBroadcast(event);
        }
        connect();
    }
}

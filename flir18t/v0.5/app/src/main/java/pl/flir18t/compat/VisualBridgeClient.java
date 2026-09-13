package pl.flir18t.compat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;

final class VisualBridgeClient {
    private static final String TAG = "FLIR18T";
    private static final ComponentName SERVICE = new ComponentName(
            "pl.flir18t.compat", "pl.flir18t.compat.VisualBridgeService");

    private static volatile IVisualBridge bridge;
    private static volatile Context appContext;
    private static volatile CountDownLatch bindLatch = new CountDownLatch(1);
    private static volatile boolean binding;

    private VisualBridgeClient() {}

    static synchronized void bind(Context context) {
        if (context == null || bridge != null || binding) return;
        appContext = context.getApplicationContext();
        binding = true;
        bindLatch = new CountDownLatch(1);
        Intent intent = new Intent().setComponent(SERVICE);
        try {
            int flags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
            boolean ok = appContext.bindService(intent, connection, flags);
            XposedBridge.log(TAG + ": bind VisualBridge requested=" + ok);
            if (!ok) {
                binding = false;
                bindLatch.countDown();
            }
        } catch (Throwable t) {
            binding = false;
            bindLatch.countDown();
            XposedBridge.log(TAG + ": bind VisualBridge failed: " + t);
        }
    }

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            bridge = IVisualBridge.Stub.asInterface(service);
            binding = false;
            bindLatch.countDown();
            XposedBridge.log(TAG + ": VisualBridge connected");
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            binding = false;
            XposedBridge.log(TAG + ": VisualBridge disconnected");
        }
    };

    static boolean awaitReady(Context context, long timeoutMs) {
        if (bridge != null) return true;
        bind(context);
        CountDownLatch latch = bindLatch;
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return bridge != null;
    }

    static boolean start(Context context, Surface surface, String cameraId,
                         int width, int height, int minFps, int maxFps, boolean torch) {
        if (surface == null || !surface.isValid()) return false;
        if (!awaitReady(context, 2000)) return false;
        try {
            boolean ok = bridge.start(surface, cameraId, width, height, minFps, maxFps, torch);
            XposedBridge.log(TAG + ": VisualBridge.start=" + ok + " status=" + bridge.getStatus());
            return ok;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": VisualBridge.start failed: " + t);
            bridge = null;
            return false;
        }
    }

    static void stop() {
        IVisualBridge b = bridge;
        if (b == null) return;
        try { b.stop(); } catch (Throwable t) { XposedBridge.log(TAG + ": bridge.stop: " + t); }
    }

    static void setTorch(boolean enabled) {
        IVisualBridge b = bridge;
        if (b == null) return;
        try { b.setTorch(enabled); } catch (Throwable t) { XposedBridge.log(TAG + ": bridge.torch: " + t); }
    }
}

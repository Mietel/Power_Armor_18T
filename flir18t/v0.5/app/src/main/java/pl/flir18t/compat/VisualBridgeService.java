package pl.flir18t.compat;

import android.app.Service;
import android.content.Intent;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class VisualBridgeService extends Service {
    private static final String TAG = "FLIR18T";
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "com.flir.flirone", "com.mtat.pipetracker", "com.mtat.forgetherm",
            "org.intofuture.infraredexplorer", "com.flir.tools", "pl.flir18t.compat"));

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private Surface target;
    private boolean torch;
    private boolean flashAvailable;
    private int ownerUid = -1;
    private String status = "idle";

    @Override public void onCreate() {
        super.onCreate();
        cameraThread = new HandlerThread("FLIR18T-VisualCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        Log.i(TAG, "VisualBridgeService created uid=" + android.os.Process.myUid());
    }

    @Override public IBinder onBind(Intent intent) {
        Log.i(TAG, "VisualBridgeService bound");
        return binder;
    }

    @Override public boolean onUnbind(Intent intent) {
        stopInternal(); ownerUid = -1;
        return super.onUnbind(intent);
    }

    private final IVisualBridge.Stub binder = new IVisualBridge.Stub() {
        @Override public boolean start(Surface surface, String cameraId, int width, int height,
                                       int minFps, int maxFps, boolean torchEnabled) {
            int caller = enforceCaller();
            if (surface == null || !surface.isValid()) { status = "invalid-surface"; return false; }
            stopInternal(); ownerUid = caller; target = surface; torch = torchEnabled;
            try {
                CameraManager cm = getSystemService(CameraManager.class);
                String id = chooseCameraId(cm, cameraId);
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                flashAvailable = Boolean.TRUE.equals(cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
                final Range<Integer> fps = chooseFps(cc, minFps, maxFps);
                status = "opening:" + id + " " + width + "x" + height + " fps=" + fps;
                Log.i(TAG, status + " callerUid=" + caller);
                cm.openCamera(id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice c) { camera = c; Log.i(TAG, "camera opened id=" + c.getId()); createSession(fps); }
                    @Override public void onDisconnected(CameraDevice c) { status = "disconnected"; Log.w(TAG, status); c.close(); if (camera == c) camera = null; }
                    @Override public void onError(CameraDevice c, int error) { status = "camera-error:" + error; Log.e(TAG, status); c.close(); if (camera == c) camera = null; }
                }, cameraHandler);
                return true;
            } catch (Throwable t) {
                status = "open-failed:" + t.getClass().getSimpleName() + ":" + t.getMessage();
                Log.e(TAG, status, t); stopInternal(); return false;
            }
        }

        @Override public void stop() { int caller = enforceCaller(); if (ownerUid == -1 || caller == ownerUid || caller == android.os.Process.myUid()) { stopInternal(); ownerUid = -1; } }
        @Override public void setTorch(boolean enabled) { int caller = enforceCaller(); if (ownerUid == -1 || caller == ownerUid || caller == android.os.Process.myUid()) { torch = enabled; rebuildRepeating(); } }
        @Override public String getStatus() { enforceCaller(); return status; }
    };

    private String chooseCameraId(CameraManager cm, String requested) throws Exception {
        String[] ids = cm.getCameraIdList();
        if (requested != null && !requested.isEmpty()) for (String id : ids) if (requested.equals(id)) return id;
        for (String id : ids) if ("2".equals(id)) return id;
        if (ids.length == 0) throw new IllegalStateException("no cameras");
        return ids[0];
    }

    private Range<Integer> chooseFps(CameraCharacteristics cc, int minFps, int maxFps) {
        Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> best = ranges[0];
        int desiredMin = minFps > 0 ? minFps : best.getLower();
        int desiredMax = maxFps >= desiredMin ? maxFps : best.getUpper();
        long bestScore = Long.MAX_VALUE;
        for (Range<Integer> r : ranges) {
            long score = Math.abs((long) r.getLower() - desiredMin) + Math.abs((long) r.getUpper() - desiredMax);
            if (score < bestScore) { bestScore = score; best = r; }
        }
        return best;
    }

    private void createSession(final Range<Integer> fps) {
        CameraDevice c = camera; Surface s = target;
        if (c == null || s == null || !s.isValid()) { status = "session-missing-camera-or-surface"; return; }
        try {
            c.createCaptureSession(Arrays.asList(s), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cs) {
                    if (camera == null || target == null || !target.isValid()) { cs.close(); return; }
                    session = cs;
                    try {
                        request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        request.addTarget(target);
                        if (fps != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                        if (flashAvailable) request.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                        cs.setRepeatingRequest(request.build(), null, cameraHandler);
                        status = "streaming:" + camera.getId(); Log.i(TAG, status);
                    } catch (Throwable t) { status = "request-failed:" + t.getClass().getSimpleName() + ":" + t.getMessage(); Log.e(TAG, status, t); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs) { status = "configure-failed"; Log.e(TAG, status); }
            }, cameraHandler);
        } catch (Throwable t) { status = "session-failed:" + t.getClass().getSimpleName() + ":" + t.getMessage(); Log.e(TAG, status, t); }
    }

    private void rebuildRepeating() {
        if (session == null || request == null || !flashAvailable) return;
        try { request.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF); session.setRepeatingRequest(request.build(), null, cameraHandler); }
        catch (Throwable t) { status = "torch-failed:" + t.getClass().getSimpleName(); Log.e(TAG, status, t); }
    }

    private void stopInternal() {
        try { if (session != null) session.close(); } catch (Throwable ignored) {} session = null; request = null;
        try { if (camera != null) camera.close(); } catch (Throwable ignored) {} camera = null;
        try { if (target != null) target.release(); } catch (Throwable ignored) {} target = null;
        status = "idle"; Log.i(TAG, "bridge idle");
    }

    private int enforceCaller() {
        int uid = Binder.getCallingUid(); if (uid == android.os.Process.myUid()) return uid;
        String[] pkgs = getPackageManager().getPackagesForUid(uid);
        if (pkgs != null) for (String p : pkgs) if (ALLOWED.contains(p)) return uid;
        throw new SecurityException("Caller uid=" + uid + " not allowed");
    }

    @Override public void onDestroy() { stopInternal(); if (cameraThread != null) cameraThread.quitSafely(); Log.i(TAG, "VisualBridgeService destroyed"); super.onDestroy(); }
}

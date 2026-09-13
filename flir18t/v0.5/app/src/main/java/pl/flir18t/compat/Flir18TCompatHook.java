package pl.flir18t.compat;

import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.media.ImageReader;
import android.os.Build;
import android.os.SystemClock;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Flir18TCompatHook implements IXposedHookLoadPackage {
    private static final String TAG = "FLIR18T";
    private static final String DISCOVERY = "com.flir.thermalsdk.live.discovery.DiscoveryFactory";
    private static final String COMMS = "com.flir.thermalsdk.live.CommunicationInterface";
    private static final String VIS = "com.flir.thermalsdk.androidsdk.live.connectivity.integrated.VisualCameraModule";
    private static final String VIS_RESULT = VIS + "$InitResult";

    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "com.flir.flirone",
            "com.mtat.pipetracker",
            "com.mtat.forgetherm",
            "org.intofuture.infraredexplorer",
            "com.flir.tools"
    ));

    private static final Set<Object> bridgeModules =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<Object, Boolean>()));

    private static volatile Context context;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!isPowerArmor18T() || !TARGETS.contains(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": target loaded " + lpparam.packageName);
        hookApplicationAttach();
        installDiscoveryHook(lpparam.classLoader, lpparam.packageName);
        installVisualHook(lpparam.classLoader, lpparam.packageName);
    }

    private static void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context c = (Context) param.args[0];
                            context = c.getApplicationContext();
                            VisualBridgeClient.bind(context);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Application.attach hook: " + t);
        }
    }

    private static void installDiscoveryHook(ClassLoader cl, final String pkg) {
        try {
            final Class<?> discovery = XposedHelpers.findClass(DISCOVERY, cl);
            final Class<?> comm = XposedHelpers.findClass(COMMS, cl);
            @SuppressWarnings({"rawtypes", "unchecked"})
            final Object integrated = Enum.valueOf((Class<? extends Enum>) comm.asSubclass(Enum.class), "INTEGRATED");

            XposedBridge.hookAllMethods(discovery, "scan", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0) return;
                    int index = param.args.length - 1;
                    Object oldArray = param.args[index];
                    if (oldArray == null || !oldArray.getClass().isArray()) return;
                    if (oldArray.getClass().getComponentType() != comm) return;
                    int n = Array.getLength(oldArray);
                    for (int i = 0; i < n; i++) {
                        Object v = Array.get(oldArray, i);
                        if (v == integrated || "INTEGRATED".equals(String.valueOf(v))) return;
                    }
                    Object expanded = Array.newInstance(comm, n + 1);
                    for (int i = 0; i < n; i++) Array.set(expanded, i, Array.get(oldArray, i));
                    Array.set(expanded, n, integrated);
                    param.args[index] = expanded;
                    XposedBridge.log(TAG + ": " + pkg + " discovery +INTEGRATED " + n + "->" + (n + 1));
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": discovery hook unavailable in " + pkg + ": " + t);
        }
    }

    private static void installVisualHook(ClassLoader cl, final String pkg) {
        try {
            final Class<?> vis = XposedHelpers.findClass(VIS, cl);
            final Class<?> resultClass = XposedHelpers.findClass(VIS_RESULT, cl);
            final Object success = XposedHelpers.getStaticObjectField(resultClass, "SUCCESS");

            XposedHelpers.findAndHookMethod(vis, "initCamWithId", CameraManager.class, String.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object module = param.thisObject;
                            try {
                                ImageReader reader = (ImageReader) XposedHelpers.getObjectField(module, "mCameraImageReader");
                                if (reader == null) {
                                    XposedBridge.log(TAG + ": " + pkg + " bridge: ImageReader null; keeping SDK path");
                                    return;
                                }
                                int w = XposedHelpers.getIntField(module, "mVisWidth");
                                int h = XposedHelpers.getIntField(module, "mVisHeight");
                                int minFps = XposedHelpers.getIntField(module, "mMinFPS");
                                int maxFps = XposedHelpers.getIntField(module, "mMaxFPS");
                                boolean torch = XposedHelpers.getBooleanField(module, "mTorchEnabled");
                                String cameraId = String.valueOf(param.args[1]);
                                Context c = context;
                                if (c == null) {
                                    try { c = (Context) XposedHelpers.getObjectField(module, "mContext"); }
                                    catch (Throwable ignored) {}
                                }
                                if (c == null) {
                                    XposedBridge.log(TAG + ": " + pkg + " bridge: no Context; keeping SDK path");
                                    return;
                                }

                                long offset = System.currentTimeMillis() -
                                        (SystemClock.elapsedRealtimeNanos() / 1_000_000L);
                                XposedHelpers.setLongField(module, "mSystemTimeOffsetMillis", offset);

                                boolean ok = VisualBridgeClient.start(c, reader.getSurface(), cameraId,
                                        w, h, minFps, maxFps, torch);
                                if (!ok) {
                                    XposedBridge.log(TAG + ": " + pkg + " bridge unavailable; keeping SDK initCamWithId");
                                    return;
                                }
                                bridgeModules.add(module);
                                param.setResult(success);
                                XposedBridge.log(TAG + ": " + pkg + " VisualBridge ACTIVE camera=" + cameraId
                                        + " size=" + w + "x" + h + " fps=" + minFps + "-" + maxFps);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": " + pkg + " initCam bridge error: " + t);
                            }
                        }
                    });

            for (final String name : new String[]{"setupCapture", "createCaptureRequest"}) {
                XposedBridge.hookAllMethods(vis, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (bridgeModules.contains(param.thisObject)) {
                            param.setResult(null);
                            XposedBridge.log(TAG + ": " + pkg + " bypass " + name + " (bridge mode)");
                        }
                    }
                });
            }

            XposedBridge.hookAllMethods(vis, "stopStreaming", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!bridgeModules.remove(param.thisObject)) return;
                    VisualBridgeClient.stop();
                    try { XposedHelpers.setBooleanField(param.thisObject, "mStreaming", false); } catch (Throwable ignored) {}
                    param.setResult(null);
                    XposedBridge.log(TAG + ": " + pkg + " VisualBridge stopStreaming");
                }
            });

            XposedBridge.hookAllMethods(vis, "close", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!bridgeModules.remove(param.thisObject)) return;
                    VisualBridgeClient.stop();
                    try { XposedHelpers.setBooleanField(param.thisObject, "mStreaming", false); } catch (Throwable ignored) {}
                    param.setResult(null);
                    XposedBridge.log(TAG + ": " + pkg + " VisualBridge close");
                }
            });

            XposedHelpers.findAndHookMethod(vis, "setTorchMode", boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!bridgeModules.contains(param.thisObject)) return;
                    boolean enabled = (Boolean) param.args[0];
                    try { XposedHelpers.setBooleanField(param.thisObject, "mTorchEnabled", enabled); } catch (Throwable ignored) {}
                    VisualBridgeClient.setTorch(enabled);
                    param.setResult(null);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": visual hook unavailable in " + pkg + ": " + t);
        }
    }

    private static boolean isPowerArmor18T() {
        String maker = lower(Build.MANUFACTURER);
        String model = lower(Build.MODEL);
        String device = lower(Build.DEVICE);
        return maker.contains("ulefone") &&
                (model.contains("18t") || device.contains("18t") || model.contains("power armor 18"));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}

package pl.flir18t.compat;
import android.view.Surface;
interface IVisualBridge {
    boolean start(in Surface surface, String cameraId, int width, int height, int minFps, int maxFps, boolean torchEnabled);
    void stop();
    void setTorch(boolean enabled);
    String getStatus();
}

#!/system/bin/sh
OUT=/sdcard/Download/flir18t-v05-status.txt
PKG=pl.flir18t.compat
{
  echo "=== FLIR18T v0.5 ACTIVE STATUS ==="
  date
  echo
  echo "[DEVICE]"
  echo "manufacturer=$(getprop ro.product.manufacturer)"
  echo "model=$(getprop ro.product.model)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  echo "selinux=$(getenforce 2>/dev/null)"
  echo
  echo "[OEM BACKEND]"
  service check flir.lepton_camera_service 2>&1
  service list 2>/dev/null | grep -Ei 'lepton|flir' || true
  echo "rgb_camera_id=$(getprop ro.yft_lepton_rgb_camera_id)"
  echo
  echo "[COMPANION]"
  pm path "$PKG" 2>&1
  dumpsys package "$PKG" 2>/dev/null | grep -E 'versionName=|versionCode=|privileged=|android.permission.CAMERA|START_ACTIVITIES_FROM_BACKGROUND' | head -40 || true
  echo "appops CAMERA:"
  cmd appops get "$PKG" CAMERA 2>&1 || true
  echo
  echo "[LSPOSED/XPOSED]"
  pm list packages 2>/dev/null | grep -Ei 'lsposed|xposed' || true
  ls -ld /data/adb/lspd /data/adb/modules/zygisk_lsposed* /data/adb/modules/riru_lsposed* 2>/dev/null || true
  echo
  echo "[TARGETS]"
  for p in com.flir.flirone com.mtat.pipetracker com.mtat.forgetherm org.intofuture.infraredexplorer com.flir.tools; do
    pm path "$p" 2>/dev/null | head -1 || echo "$p: not installed"
  done
  echo
  echo "[RECENT FLIR18T LOGCAT]"
  logcat -d -v threadtime 2>/dev/null | grep -E 'FLIR18T|VisualBridge|IntegratedScanner|VisualCameraModule' | tail -250 || true
  echo "=== END ==="
} > "$OUT"
chmod 0644 "$OUT" 2>/dev/null || true

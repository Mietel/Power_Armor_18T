#!/system/bin/sh
MODDIR=${0%/*}
PKG=pl.flir18t.compat
LOG=/data/adb/flir18t/v05-service.log
mkdir -p /data/adb/flir18t
exec >>"$LOG" 2>&1

echo "=== v0.5 service $(date) ==="
for i in $(seq 1 90); do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 2
done
for i in $(seq 1 30); do
  pm path "$PKG" >/dev/null 2>&1 && break
  sleep 2
done

echo "pm path: $(pm path "$PKG" 2>&1)"
pm grant "$PKG" android.permission.CAMERA 2>&1 || true
cmd appops set "$PKG" CAMERA allow 2>&1 || appops set "$PKG" CAMERA allow 2>&1 || true
am force-stop "$PKG" 2>/dev/null || true

echo "CAMERA permission:"
dumpsys package "$PKG" 2>/dev/null | grep -A4 -B2 'android.permission.CAMERA' | head -30 || true
echo "CAMERA appops:"
cmd appops get "$PKG" CAMERA 2>&1 || true

echo "LSPosed packages:"
pm list packages 2>/dev/null | grep -Ei 'lsposed|xposed' || true

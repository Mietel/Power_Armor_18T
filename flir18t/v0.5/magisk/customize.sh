#!/system/bin/sh
ui_print "- 18T FLIR Mobile SDK Compat v0.5"
ui_print "- Installs privileged VisualBridge/Xposed companion"
ui_print "- OEM Lepton backend and calibration are NOT replaced"
set_perm_recursive "$MODPATH/system/priv-app/FLIR18TCompat" 0 0 0755 0644
set_perm "$MODPATH/system/etc/permissions/privapp-permissions-flir18t-compat.xml" 0 0 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
ui_print "- Reboot required so PackageManager sees the priv-app"
ui_print "- Then enable '18T FLIR SDK Compat' in LSPosed and scope target FLIR apps"

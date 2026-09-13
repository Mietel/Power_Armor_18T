# 18T FLIR Mobile SDK Compat v0.5-dev

Development branch for Ulefone Power Armor 18T FLIR Lepton 3.5 integration.

Verified on-device before this build:
- OEM Binder backend: `flir.lepton_camera_service` / `com.flir.sdk.internal.ILeptonCameraService`
- RGB camera id: `2`
- Five target apps expose the same Java-side FLIR SDK hook contract (`DiscoveryFactory`, `INTEGRATED`, `VisualCameraModule`, `initCamWithId`, ImageReader/timestamp fields).
- Native Atlas libraries differ across some apps, so v0.5 intentionally hooks the common Java API instead of native symbols.

v0.5 combines an LSPosed/Xposed module and a privileged bound Camera2 VisualBridge in one APK. Thermal data remains on the OEM Binder backend. The bridge owns RGB Camera2 and writes frames into the FLIR SDK-created `ImageReader.Surface`.

## Targets
- `com.flir.flirone`
- `com.mtat.pipetracker`
- `com.mtat.forgetherm`
- `org.intofuture.infraredexplorer`
- `com.flir.tools`

`com.flir.scout` is intentionally excluded because it is the Bluetooth path.

## Test-key note
The development APK is signed with a deliberately public test key committed to this branch. It is for repeatable development installs only, not a production trust key.

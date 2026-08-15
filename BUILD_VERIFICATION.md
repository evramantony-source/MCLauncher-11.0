# Build verification — MCLauncher 11.0 Alpha 05

## Fast validation

- Validate every repository-owned source/config/document file and parse every project JSON and XML document.
- Require a full pinned engine commit and all four runtime sources.
- Reject the obsolete launcher-APK downloader.
- Run Python vendoring tests plus app and core Kotlin/JVM tests.
- Check that runtime signature verification, Surface/JVM launch, raw keyboard, normalized virtual cursor and Android LWJGL mapping paths remain connected.
- Check theme, installed-content/icon and verified component-installer source paths.
- Check canonical built-in renderer identity and per-instance launch-setting paths.

## Standalone-APK validation

The workflow must:

- build the pinned Android engine source;
- extract valid `libpojavexec.so`, `libpojavexec_awt.so` and `libglfw.so` files;
- build pinned OpenLTW and verify its Minecraft 26.2 OpenGL query exports;
- package MobileGlues, patched OpenLTW and a GL4ES fallback;
- package version-matched Android JNA 6 and 7 native dispatch libraries;
- package universal and ABI layers for Java 8, 17, 21 and 25;
- verify Java 17/21/25 archives against the pinned RSA certificate;
- package patched LWJGL JARs and ABI classifier JARs;
- preserve Cacio/Caciocavallo support JARs and required notices;
- compile the Android application;
- reopen and validate the final APK payload;
- generate the APK SHA-256 digest.

## Device proof

Alpha 01 reached Minecraft 1.21.11/Fabric's main menu and a playable world on a physical Android device. Alpha 02 reached the game with mods disabled, while the device log identified Sodium's renderer-marker block. Alpha 03 confirmed that fix. Alpha 04 reached OpenLTW on 26.2 and then failed at the missing `glGetFloatv` export; its Compose menu-touch path also emitted no native pointer markers in the supplied trace. Alpha 05 requires focused checks for patched OpenLTW startup, Activity-level menu navigation, swipe look, external input, the 26.2 graphics-API selector and per-instance settings.

## 2026-08-15 build trigger

Requested by the repository owner to run the standalone arm64-v8a APK build from the current main branch.

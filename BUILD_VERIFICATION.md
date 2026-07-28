# Build verification — MCLauncher 11.0 Alpha 02

## Fast validation

- Validate every repository-owned source/config/document file and parse every project JSON and XML document.
- Require a full pinned engine commit and all four runtime sources.
- Reject the obsolete launcher-APK downloader.
- Run Python vendoring tests plus app and core Kotlin/JVM tests.
- Check that runtime signature verification, Surface/JVM launch, raw keyboard, virtual cursor and Android LWJGL mapping paths remain connected.
- Check theme, installed-content/icon and verified component-installer source paths.

## Standalone-APK validation

The workflow must:

- build the pinned Android engine source;
- extract valid `libpojavexec.so`, `libpojavexec_awt.so` and `libglfw.so` files;
- package MobileGlues with the modern OpenGL sampler exports plus a GL4ES/OpenLTW fallback;
- package version-matched Android JNA 6 and 7 native dispatch libraries;
- package universal and ABI layers for Java 8, 17, 21 and 25;
- verify Java 17/21/25 archives against the pinned RSA certificate;
- package patched LWJGL JARs and ABI classifier JARs;
- preserve Cacio/Caciocavallo support JARs and required notices;
- compile the Android application;
- reopen and validate the final APK payload;
- generate the APK SHA-256 digest.

## Device proof

Alpha 01 reached Minecraft 1.21.11/Fabric's main menu and a playable world on a physical Android device. Alpha 02 still needs physical checks for touch menu navigation, swipe look, keyboard/mouse stability, controllers, content state/icons and one-tap renderer installation.

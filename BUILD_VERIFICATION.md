# Build verification — MCLauncher 11.0 Alpha 01

## Fast validation

- Validate every repository-owned source/config/document file and parse every project JSON and XML document.
- Require a full pinned engine commit and all four runtime sources.
- Reject the obsolete launcher-APK downloader.
- Run Python vendoring tests plus app and core Kotlin/JVM tests.
- Check that runtime signature verification, Surface/JVM launch, input and Android LWJGL mapping paths remain connected.

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

## Final release proof

The Android SDK/NDK is not available in this workspace, so GitHub Actions performs the complete compile. A physical Android launch reaching Minecraft's main menu is still mandatory before calling the launcher device-proven.

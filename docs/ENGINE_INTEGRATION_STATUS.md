# Engine integration status — 11.0 Alpha 03

## Build-time path

1. Read the exact engine source commit from `vendor/engine-lock.json`.
2. Build the Android native engine and GLFW from source.
3. Extract the selected ABI's launch, AWT, audio, renderer and dependency libraries.
4. Download the pinned MobileGlues/JNA payloads, Java 8/17/21/25 and patched LWJGL artifacts.
5. Verify Java 17/21/25 archive signatures with the certificate from the pinned engine source.
6. Preserve Cacio/Caciocavallo support-JAR structure and third-party notices.
7. Fail if an engine ELF, default renderer, runtime archive, LWJGL classifier, signature record or required notice is missing.
8. Compile one ABI-specific APK and verify its embedded payload before uploading it.

## Device path

1. Copy the APK's payload to private application storage.
2. Extract universal and ABI layers for Java 8, 17, 21 and 25.
3. Resolve the Minecraft version's exact LWJGL coordinates through the Android substitution mapping.
4. Extract the matching ABI classifier natives.
5. Prefer MobileGlues, or configure the selected compatible graphics pack, before GLFW initialization.
6. Overlay MojoLauncher's `libawt_xawt.so` stub into the selected runtime while preserving OpenJDK's real `libawt_headless.so`.
7. Disable Android native heap pointer tagging for the compatibility process because the embedded OpenJDK/desktop-native stack contains code that truncates top-byte tags before deallocation.
8. Attach the Android Surface and launch in-process through the pinned MojoLauncher `JNI_CreateJavaVM` engine and its Android linker/native-library compatibility bridge.
9. Forward swipe/touch controls, raw Android keyboard events, absolute/relative
   mouse input, context-aware controllers and gyroscope input.

There is no installed-app dependency on another launcher. Alpha 01 reached
Minecraft's main menu and a world on a physical device, and Alpha 02 reached the
game with mods disabled. CI plus Alpha 03 direct-touch, Sodium/Iris and external-
input regression tests remain release gates.

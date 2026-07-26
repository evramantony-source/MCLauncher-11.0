# MCLauncher 11.0 Alpha 01

MCLauncher is an independent Android launcher for Minecraft: Java Edition. It has a responsive Material 3 interface and its own instance, account, download, content, settings and game-screen code. It reuses third-party Android launch-engine components only where required to start the Java game.

## Current alpha scope

- Mojang release and snapshot discovery with verified client, library and asset downloads.
- Separate instances, screenshots, logs and crash diagnosis.
- Offline accounts and Microsoft device-code authentication.
- Vanilla, Fabric, Quilt, Forge and NeoForge installation paths.
- Modrinth and CurseForge content and modpack installation.
- Java 8, 17, 21 and 25 Android runtime selection.
- ABI-specific Android LWJGL substitutions and native classifiers.
- Android Surface/JVM bridge, touch controls, keyboard, mouse, controller and gyroscope input.
- Manifest-driven GL4ES/OpenLTW and optional renderer-pack selection.

These features are present in source. They are not considered device-proven until the standalone APK workflow succeeds and a real Android test reaches the Minecraft main menu.

## Standalone engine build

The installed application does not download or install another launcher APK. GitHub Actions:

1. Checks out the exact LGPL engine revision in `vendor/engine-lock.json`.
2. Builds the Android engine and GLFW from source.
3. Extracts only the selected ABI's required native libraries and renderer.
4. Downloads Java 8/17/21/25 and the exact patched LWJGL files from the pinned engine manifest.
5. Verifies Java 17/21/25 runtime archives against MojoLauncher's RSA signing certificate.
6. Verifies archive formats, hashes, ELF files, classifier JARs, support JARs and notices.
7. Compiles an ABI-specific APK, reopens it, and verifies the embedded payload.

The large generated runtime/native payload belongs in the APK artifact, not in Git history.

## Build from a phone

1. Open **Actions → Build standalone MCLauncher 11 APK**.
2. Select **Run workflow**.
3. Keep `arm64-v8a` for modern Android phones and tablets.
4. Download the `MCLauncher-11.0-alpha01-arm64-v8a` artifact.
5. Extract it and install `MCLauncher-11.0-alpha01-arm64-v8a.apk`.
6. Follow `docs/FIRST_DEVICE_TEST.md`.

Pull requests and pushes to `main` run the default arm64 build automatically.

## Proof still required

A green workflow proves that the project compiled and the expected payload is present in the APK. It does not prove compatibility with every Minecraft version, mod, GPU or Android firmware. The next release gate is a clean vanilla launch to the main menu on the Redmi Pad Pro, followed by world rendering, audio and input checks.

## Legal

Minecraft belongs to Mojang/Microsoft. Engine, OpenJDK, LWJGL, GLFW, renderer and other third-party components retain their respective licences. See `THIRD_PARTY_NOTICES.md` and the notices embedded in generated APKs.

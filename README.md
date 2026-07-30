# MCLauncher 11.0 Alpha 05

MCLauncher is an independent Android launcher for Minecraft: Java Edition. It has a responsive Material 3 interface and its own instance, account, download, content, settings and game-screen code. It reuses third-party Android launch-engine components only where required to start the Java game.

## Current alpha scope

> **Alpha signing:** CI test APKs use a stable, public debug key so device-test
> builds can update in place. Microsoft sign-in is deliberately disabled in
> these APKs. A private release key is required before enabling real accounts.

- Mojang release and snapshot discovery with verified client, library and asset downloads.
- Separate instances, per-instance launch settings, screenshots, logs and crash diagnosis.
- Offline accounts and Microsoft device-code authentication.
- Vanilla, Fabric, Quilt, Forge and NeoForge installation paths.
- Modrinth and CurseForge content and modpack installation, with project icons and per-instance installed state.
- Java 8, 17, 21 and 25 Android runtime selection.
- ABI-specific Android LWJGL substitutions and native classifiers.
- Android Surface/JVM bridge, editable touch controls, swipe look, virtual menu mouse, physical keyboard/mouse, controller and gyroscope input.
- System, dark and light launcher themes.
- Hash-pinned MobileGlues 1.3.5 default renderer, a current source-built OpenLTW compatibility renderer, GL4ES fallback and optional manifest-driven renderer packs.
- Global and per-instance Minecraft 26.2+ graphics-API selection.
- Verified one-tap installers for selected compatible renderer packages.

Alpha 01 reached the Minecraft main menu and a playable world on a physical Android device. Alpha 02 also reached Minecraft with mods disabled, proving its engine and MobileGlues path. Alpha 03 fixed Sodium's launcher-marker block. Alpha 04 reached OpenLTW initialization on Minecraft 26.2 and exposed a missing `glGetFloatv` export plus an unreliable Compose menu-touch path. Alpha 05 directly addresses those two measured failures and requires a focused device retest.

CurseForge's official REST API requires an `x-api-key`. A private build can inject
`CURSEFORGE_API_KEY`, or the user can enter a key in Settings. Modrinth requires no key.

## Standalone engine build

The installed application does not download or install another launcher APK. GitHub Actions:

1. Checks out the exact LGPL engine revision in `vendor/engine-lock.json`.
2. Builds the Android engine, GLFW and pinned OpenLTW source.
3. Applies and verifies the narrow Minecraft 26.2 OpenLTW compatibility patch.
4. Extracts only the selected ABI's required native libraries and renderers.
5. Downloads the hash-pinned MobileGlues and Android JNA payloads, Java 8/17/21/25, and the exact patched LWJGL files.
6. Verifies Java 17/21/25 runtime archives against MojoLauncher's RSA signing certificate.
7. Verifies archive formats, hashes, ELF symbols, classifier JARs, support JARs and notices.
8. Compiles an ABI-specific APK, reopens it, and verifies the embedded payload.

The large generated runtime/native payload belongs in the APK artifact, not in Git history.

## Build from a phone

1. Open **Actions → Build standalone MCLauncher 11 APK**.
2. Select **Run workflow**.
3. Keep `arm64-v8a` for modern Android phones and tablets.
4. Download the `MCLauncher-11.0-alpha06-arm64-v8a` artifact.
5. Extract it and install `MCLauncher-11.0-alpha06-arm64-v8a.apk`.
6. Follow `docs/FIRST_DEVICE_TEST.md`.

Pull requests and pushes to `main` run the default arm64 build automatically.

## Proof still required

A green workflow proves that the project compiled and the expected payload is present in the APK. It does not prove compatibility with every Minecraft version, mod, GPU or Android firmware. Alpha 05 must pass Activity-level direct-touch menu navigation, patched OpenLTW 26.2 startup, keyboard/mouse stability, graphics-API selection and per-instance-setting checks on the target device.

## Legal

Minecraft belongs to Mojang/Microsoft. Engine, OpenJDK, LWJGL, GLFW, renderer and other third-party components retain their respective licences. See `THIRD_PARTY_NOTICES.md` and the notices embedded in generated APKs.

# MCLauncher 11.0 Alpha 23

MCLauncher is an independent Android launcher for Minecraft: Java Edition. It has a responsive Material 3 interface and its own instance, account, download, content, settings and game-screen code. It reuses third-party Android launch-engine components only where required to start the Java game.

## Current alpha scope

> **Alpha signing:** CI test APKs use a stable, public debug key so device-test
> builds can update in place. Microsoft sign-in is deliberately disabled in
> these APKs. A private account build enables Microsoft and Game Pass accounts
> only when it is signed with a private key that is not committed here.

- Mojang release and snapshot discovery with verified client, library and asset downloads.
- Separate instances, per-instance launch settings, screenshots, logs and crash diagnosis.
- Offline accounts and Microsoft device-code authentication.
- Vanilla, Fabric, Quilt, Forge and NeoForge installation paths.
- Modrinth and CurseForge content and modpack installation, with project icons and per-instance installed state.
- Java 8, 17, 21 and 25 Android runtime selection.
- ABI-specific Android LWJGL substitutions and native classifiers.
- Android Surface/JVM bridge, cursor-free direct-touch menus and inventories, tappable hotbar, editable in-world controls, swipe look, physical keyboard/mouse, controller and gyroscope input.
- System, dark and light launcher themes.
- Responsive Modrinth-style tablet navigation with quick instances, while retaining MCLauncher's independent identity and Android-native UI.
- Hash-pinned MobileGlues 1.3.5 default renderer, a current source-built OpenLTW compatibility renderer, GL4ES fallback and optional manifest-driven renderer packs.
- Global and per-instance Minecraft 26.2+ graphics-API selection.
- Verified one-tap installers for selected compatible renderer packages.
- MCL Creation Lab with a touch pixel editor, every installed item-texture PNG, 594-colour palette plus exact ARGB, validated resource-pack ZIPs, and 64×64 skin plus 64×32 cape PNG creation.
- Files-visible source workspaces with a touch code editor, source-ZIP import and verified local Gradle downloads. Compatible Fabric, Quilt, Forge and NeoForge projects can compile and install a real JAR on-device without an API key, ChatGPT subscription or GitHub mod builder.

Alpha 01 reached the Minecraft main menu and a playable world on a physical Android device. Later builds stabilized Android 16 startup, renderer selection, touch input, exact modpack installation and live download progress. Alpha 17's fixed game-buffer boundary made direct menu touch work on the Redmi Pad Pro, with a known stretched-resolution regression still to correct. Alpha 18 added validated local import and export for real Modrinth and CurseForge packs. Alpha 19 added the MCL Creation Lab. Alpha 22 added the editable source workspace and real local Gradle build path. Alpha 23 replaces the Snapshot 6 SDL skip workaround with a source-pinned Android SDL3 runtime and a unified SDL/GLFW physical-input path.

CurseForge's official REST API requires an `x-api-key`. A private build can inject
`CURSEFORGE_API_KEY`, or the user can enter a key in Settings. Modrinth requires no key.

## Standalone engine build

The installed application does not download or install another launcher APK. GitHub Actions:

1. Checks out the exact LGPL engine revision in `vendor/engine-lock.json`.
2. Builds the Android engine, GLFW, SDL3 Android bindings and pinned OpenLTW source.
3. Applies and verifies the narrow Minecraft 26.2 OpenLTW compatibility patch.
4. Extracts only the selected ABI's required native libraries and renderers.
5. Downloads the hash-pinned MobileGlues and Android JNA payloads, Java 8/17/21/25, and the exact patched LWJGL files.
6. Verifies Java 17/21/25 runtime archives against MojoLauncher's RSA signing certificate.
7. Verifies archive formats, hashes, ELF symbols, classifier JARs, support JARs and notices.
8. Compiles an ABI-specific APK, reopens it, and verifies the embedded payload, SDL ELF files and ART callback classes.

The large generated runtime/native payload belongs in the APK artifact, not in Git history.

## Build from a phone

1. Open **Actions → Build standalone MCLauncher 11 APK**.
2. Select **Run workflow**.
3. Keep `arm64-v8a` for modern Android phones and tablets.
4. Download the `MCLauncher-11.0-alpha23-arm64-v8a` artifact.
5. Extract it and install `MCLauncher-11.0-alpha23-arm64-v8a.apk`.
6. Follow `docs/FIRST_DEVICE_TEST.md`.

## Microsoft and Xbox Game Pass accounts

The Microsoft device-code implementation completes the Microsoft, Xbox Live,
XSTS and Minecraft Services authentication chain, checks
`/entitlements/mcstore`, then loads the Java profile. This accepts both a
permanently purchased Java licence and an active eligible Game Pass entitlement.

Publicly signed alpha APKs keep Microsoft sign-in disabled because their signing
key is public. To create a Microsoft-account build, configure these GitHub Actions
repository secrets with a private Android keystore that you control:

- `MCLAUNCHER_SIGNING_KEY_BASE64`
- `MCLAUNCHER_SIGNING_STORE_PASSWORD`
- `MCLAUNCHER_SIGNING_KEY_ALIAS`
- `MCLAUNCHER_SIGNING_KEY_PASSWORD`

Run **Build standalone MCLauncher 11 APK**, enable **microsoft_accounts**, and
download the artifact containing `alpha23-microsoft`. Because this APK has a
different signer, back up worlds and uninstall the public alpha before installing
it. In MCLauncher Settings, enter an authorized Microsoft OAuth public-client ID,
then use **Accounts → Microsoft account → Sign in**. Never commit the private
keystore or its passwords.

Pull requests and pushes to `main` run the default arm64 build automatically.

## Proof still required

A green workflow proves that the project compiled and the expected payload is present in the APK. It does not prove compatibility with every Minecraft version, loader plugin, source project, GPU or Android firmware. Direct menu touch is device-confirmed on the Redmi Pad Pro; Alpha 23's SDL3 launch and hardware-input path plus Alpha 22's local Gradle execution still need physical-device proof, Alpha 18's pack round trips still need desktop-client proof, and the stretched-resolution regression remains open.

## Legal

Minecraft belongs to Mojang/Microsoft. Engine, OpenJDK, LWJGL, GLFW, renderer and other third-party components retain their respective licences. See `THIRD_PARTY_NOTICES.md` and the notices embedded in generated APKs.

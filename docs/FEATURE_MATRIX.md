# MCLauncher 11.0 Alpha 23 feature matrix

| Area | Current status |
|---|---|
| Mojang version/client/library/assets download | Source implemented; JVM tests |
| Vanilla, Fabric and Quilt | Exact loader artifact verified before install completes; device validation required |
| Forge/NeoForge installer execution | Exact generated loader profile verified; device validation required |
| Offline and Microsoft accounts | Source implemented |
| Separate instances and favourite/recent ordering | Source implemented; per-instance launch settings included |
| Modrinth content | Browsing/install/update, icons, installed state and offset pagination implemented |
| CurseForge content | Browsing/install/update, icons and index pagination implemented; official API key required |
| Modpack import/export | Local `.mrpack` and CurseForge profile ZIP import; validated provider-native export with exact references and overrides |
| MCL Creation Lab | Every installed vanilla item PNG, thumbnails and in-game previews, per-pixel/animated-frame editing, 594-colour plus exact-ARGB palette, validated resource-pack ZIP, 64×64 skin PNG and 64×32 cape PNG implemented |
| Code workspace / JAR build | Java/Kotlin/Gradle/resource editing, safe source-ZIP import, wrapper-version-aware verified Gradle download, explicit local build, four loader metadata validation and optional instance install implemented; physical-device Gradle proof required |
| Java 8/17/21/25 | CI packaging required |
| Java 17/21/25 archive authenticity | RSA signature verification in CI |
| Patched LWJGL/GLFW/SDL3 | Exact LWJGL 3.4.2 SDL artifact, source-built Android SDL3 runtime and coordinate-mapped CI packaging required |
| Android JVM/Surface engine | Alpha 01 reached the Minecraft menu/world; Alpha 02 reached Minecraft with mods disabled; Alpha 05 patched-OpenLTW regression test required |
| MobileGlues default and GL4ES/OpenLTW fallback | Hash-pinned; exports and payload enforced by verifier |
| Android JNA compatibility | Version-selected JNA 6/7 dispatch libraries |
| Optional renderer/driver packs | Manifest-driven custom packs plus verified one-tap packages with canonical built-in identity; missing saved choices fall back safely |
| Touch | Direct taps are Redmi Pad Pro-confirmed with separate SurfaceView input and game-buffer coordinates; the fixed-buffer resolution/aspect regression remains open |
| Keyboard, mouse, controller and gyro | Unified GLFW/SDL Android-keycode path, absolute/relative mouse, five hardware buttons, pointer capture and menu/gamepad contexts implemented; device proof required |
| Launcher theme | System, green midnight-black dark mode and light mode implemented |
| FPS limit and performance settings | Global and per-instance settings implemented |
| Logs/crash diagnosis/screenshots | Source implemented |

# MCLauncher 11.0 Alpha 20 feature matrix

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
| MCL Creation Lab | Installed vanilla item catalog, per-pixel and animated-frame editing, validated resource-pack ZIP, 64×64 skin PNG and 64×32 cape PNG implemented |
| AI project generation | Safe UI boundary documented; provider connection and trusted compilation are not yet enabled |
| Java 8/17/21/25 | CI packaging required |
| Java 17/21/25 archive authenticity | RSA signature verification in CI |
| Patched LWJGL/GLFW | Coordinate-mapped CI packaging required |
| Android JVM/Surface engine | Alpha 01 reached the Minecraft menu/world; Alpha 02 reached Minecraft with mods disabled; Alpha 05 patched-OpenLTW regression test required |
| MobileGlues default and GL4ES/OpenLTW fallback | Hash-pinned; exports and payload enforced by verifier |
| Android JNA compatibility | Version-selected JNA 6/7 dispatch libraries |
| Optional renderer/driver packs | Manifest-driven custom packs plus verified one-tap packages with canonical built-in identity; missing saved choices fall back safely |
| Touch | Direct taps are Redmi Pad Pro-confirmed with separate SurfaceView input and game-buffer coordinates; the fixed-buffer resolution/aspect regression remains open |
| Keyboard, mouse, controller and gyro | Raw keyboard path, absolute/relative mouse and menu/gamepad contexts implemented; device proof required |
| Launcher theme | System, green midnight-black dark mode and light mode implemented |
| FPS limit and performance settings | Global and per-instance settings implemented |
| Logs/crash diagnosis/screenshots | Source implemented |

# MCLauncher 11.0 Alpha 17 feature matrix

| Area | Current status |
|---|---|
| Mojang version/client/library/assets download | Source implemented; JVM tests |
| Vanilla, Fabric and Quilt | Exact loader artifact verified before install completes; device validation required |
| Forge/NeoForge installer execution | Exact generated loader profile verified; device validation required |
| Offline and Microsoft accounts | Source implemented |
| Separate instances and favourite/recent ordering | Source implemented; per-instance launch settings included |
| Modrinth content | Browsing/install/update, icons and installed state implemented |
| CurseForge content | Browsing/install/update and icons implemented; official API key required |
| Java 8/17/21/25 | CI packaging required |
| Java 17/21/25 archive authenticity | RSA signature verification in CI |
| Patched LWJGL/GLFW | Coordinate-mapped CI packaging required |
| Android JVM/Surface engine | Alpha 01 reached the Minecraft menu/world; Alpha 02 reached Minecraft with mods disabled; Alpha 05 patched-OpenLTW regression test required |
| MobileGlues default and GL4ES/OpenLTW fallback | Hash-pinned; exports and payload enforced by verifier |
| Android JNA compatibility | Version-selected JNA 6/7 dispatch libraries |
| Optional renderer/driver packs | Manifest-driven custom packs plus verified one-tap packages with canonical built-in identity; missing saved choices fall back safely |
| Touch | SurfaceView input and fixed game-buffer coordinates are separated; confirmed coordinate-bound 33 ms taps, hold-plus-slop inventory drag, tappable nine-slot hotbar, editable controls, swipe look and optional look stick; Alpha 17 device proof required |
| Keyboard, mouse, controller and gyro | Raw keyboard path, absolute/relative mouse and menu/gamepad contexts implemented; device proof required |
| Launcher theme | System, green midnight-black dark mode and light mode implemented |
| FPS limit and performance settings | Global and per-instance settings implemented |
| Logs/crash diagnosis/screenshots | Source implemented |

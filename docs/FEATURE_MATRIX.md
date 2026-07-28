# MCLauncher 11.0 Alpha 03 feature matrix

| Area | Current status |
|---|---|
| Mojang version/client/library/assets download | Source implemented; JVM tests |
| Vanilla, Fabric and Quilt | Source implemented; device validation required |
| Forge/NeoForge installer execution | Source implemented; device validation required |
| Offline and Microsoft accounts | Source implemented |
| Separate instances and favourite/recent ordering | Source implemented |
| Modrinth content | Browsing/install/update, icons and installed state implemented |
| CurseForge content | Browsing/install/update and icons implemented; official API key required |
| Java 8/17/21/25 | CI packaging required |
| Java 17/21/25 archive authenticity | RSA signature verification in CI |
| Patched LWJGL/GLFW | Coordinate-mapped CI packaging required |
| Android JVM/Surface engine | Alpha 01 reached the Minecraft menu/world; Alpha 02 reached Minecraft with mods disabled; Alpha 03 regression test required |
| MobileGlues default and GL4ES/OpenLTW fallback | Hash-pinned; exports and payload enforced by verifier |
| Android JNA compatibility | Version-selected JNA 6/7 dispatch libraries |
| Optional renderer/driver packs | Manifest-driven plus verified one-tap packages; missing saved choices fall back safely |
| Touch | Editable controls, swipe look, optional look stick and virtual menu mouse; device proof required |
| Keyboard, mouse, controller and gyro | Raw keyboard path, absolute/relative mouse and menu/gamepad contexts implemented; device proof required |
| Launcher theme | System, dark and light modes implemented |
| FPS limit and performance settings | Source implemented |
| Logs/crash diagnosis/screenshots | Source implemented |

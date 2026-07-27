# MCLauncher 11.0 Alpha 01 feature matrix

| Area | Current status |
|---|---|
| Mojang version/client/library/assets download | Source implemented; JVM tests |
| Vanilla, Fabric and Quilt | Source implemented; device validation required |
| Forge/NeoForge installer execution | Source implemented; device validation required |
| Offline and Microsoft accounts | Source implemented |
| Separate instances and favourite/recent ordering | Source implemented |
| Modrinth and CurseForge content | Source implemented |
| Java 8/17/21/25 | CI packaging required |
| Java 17/21/25 archive authenticity | RSA signature verification in CI |
| Patched LWJGL/GLFW | Coordinate-mapped CI packaging required |
| Android JVM/Surface engine | Source build in CI; device proof required |
| MobileGlues default and GL4ES/OpenLTW fallback | Hash-pinned; exports and payload enforced by verifier |
| Android JNA compatibility | Version-selected JNA 6/7 dispatch libraries |
| Optional renderer/driver packs | Manifest-driven; missing saved choices fall back safely |
| Touch, keyboard, mouse, controller and gyro | Source implemented; device proof required |
| FPS limit and performance settings | Source implemented |
| Logs/crash diagnosis/screenshots | Source implemented |

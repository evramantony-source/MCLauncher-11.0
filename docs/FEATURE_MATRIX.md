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
| Default GL4ES/OpenLTW graphics path | Enforced by payload verifier |
| Optional renderer/driver packs | Manifest-driven; unavailable choices disabled |
| Touch, keyboard, mouse, controller and gyro | Source implemented; device proof required |
| FPS limit and performance settings | Source implemented |
| Logs/crash diagnosis/screenshots | Source implemented |

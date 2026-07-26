# Native engine design

- MCLauncher owns the UI, accounts, downloads, instances, settings and launch planning.
- `libmclauncher.so` owns environment setup, native preloading, mobile JVM invocation, output capture and lifecycle cleanup.
- CI compiles a pinned LGPL Android engine source revision and packages only the required native/support payload into MCLauncher.
- A small MCLauncher Android compatibility class implements the exact dnbootstrap GLFW callback ABI for Surface, clipboard, cursor, grab state and gamepad buffers.
- Renderer configuration occurs before GLFW loads; unselected renderer libraries are not preloaded, preventing symbol collisions. LWJGL module natives are extracted per Minecraft version and loaded by the Minecraft VM rather than preloaded into ART.
- Minecraft launches in an isolated `:minecraft` Android process so HotSpot shutdown cannot corrupt the launcher process.

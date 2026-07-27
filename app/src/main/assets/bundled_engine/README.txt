This directory is populated during the GitHub Actions build.
The final MCLauncher APK contains the compiled native engine, patched LWJGL,
GLFW, support JARs, hash-pinned MobileGlues/JNA native libraries, fallback
renderer libraries and Java 8/17/21/25 archives.
The installed app restores these local assets on first run and never downloads
or extracts another launcher APK.

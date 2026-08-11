# First device test

Use an MCLauncher 11.0 Alpha 25 installation. It can update Alpha 24 in place without deleting instances.

1. Install the GitHub Actions `MCLauncher-11.0-alpha25-arm64-v8a.apk` over Alpha 24.
2. Allow file access requested by Android.
3. Create an offline account for the first launch test.
4. Install a clean vanilla Minecraft version without mods.
5. Keep the default renderer selection and conservative RAM allocation.
6. Launch and wait for the main menu.
7. Tap several main-menu buttons directly, including quick taps with slight finger movement, and confirm the touched button opens only after finger-up and never selects a neighboring control. Open an inventory, tap multiple slots, then hold briefly before dragging an item between slots; confirm no virtual cursor is drawn.
8. Create a test world and check rendering, sound, movement, swipe look and configurable touch buttons. Tap the first, middle and last visible hotbar slots and confirm Minecraft selects slots 1, 5 and 9.
9. Test a physical keyboard for at least 15 minutes, then test mouse and controller input in both menus and a world.
10. Install one Modrinth project and verify its icon and `Installed` state survive a launcher restart.
11. If a CurseForge API key is configured, repeat the browse/install test with CurseForge.
12. Open a Minecraft 26.2 instance's **Settings** tab, enable custom settings, select **Prefer OpenGL**, OpenLTW and the System driver, then launch it.
13. Confirm the session log reports the patched bundled OpenLTW and reaches the main menu without an `nglGetFloatv` failure.
14. Switch the same instance to **Prefer Vulkan (experimental)** and verify the selected API in F3 if the device driver supports it.
15. Confirm that changing that instance does not change the global graphics API, renderer or RAM settings.
16. Export `latest-session.log` and confirm its `Android game surface input=... buffer=...` line reports the tablet View separately from the configured render buffer (for the reported Alpha 16 setup this should be approximately `input=2560x1600 buffer=896x504`).
17. If launch or input fails, export that `latest-session.log` before changing settings.
18. Open an installed instance and export both a Modrinth `.mrpack` and a CurseForge ZIP; verify Android creates non-empty files with the requested extensions.
19. Import each exported file from Library and confirm a new instance is created with the same Minecraft and exact loader versions.
20. Launch both round-trip instances and confirm configs plus managed mods are present. A CurseForge import containing provider references requires the configured API key.
21. In Browse, scroll through mods and press **Load more results**; confirm new projects append without replacing or duplicating the first page.
22. Disable network access and launch an installed Fabric snapshot instance; confirm a failed managed-content check no longer blocks Minecraft startup.
23. Open **MCL Creation Lab**, choose an installed version, edit an item texture, a block texture used by a block item and one animated texture frame, then create a ZIP. Confirm Minecraft accepts the pack and shows the edits.
24. Confirm the catalog count matches every PNG under `assets/minecraft/textures/` in that installed client; test the item, block, entity, equipment and GUI category filters, thumbnails/previews, and the 594-colour palette.
25. Create and re-import one 64×64 skin and one 64×32 cape PNG, then confirm every edited pixel survives the round trip.
26. Open **Code workspace**, select an installed loader instance, create a starter, edit and save its Java file, then press **Create JAR on this tablet**. Confirm the first build verifies/downloads Gradle, the finished JAR contains classes plus the selected loader metadata, and it appears under Android Files → MCLauncher → MCL Creation Lab → outputs.
27. Import a trusted source-project ZIP containing `gradle/wrapper/gradle-wrapper.properties`, confirm importing does not execute code, then explicitly build it and confirm MCLauncher uses its declared Gradle version.
28. Launch Fabric 26.3 Snapshot 6 and confirm the session log reports `Resolved window backend=sdl3`, the prepared LWJGL SDL3 library, `Using pinned MojoExec renderer API` and `skipped GLFW bridge initialization`, then reaches the title screen without exit code 31, a missing `org.lwjgl:lwjgl-sdl:3.4.2` substitution or a `libSDL3.so` error.
29. Launch one pre-SDL version and confirm the log reports `Resolved window backend=glfw` and `Initialized bundled dnbootstrap GLFW bridge`, then reaches the title screen without exit code 32.
30. With Snapshot 6 running, connect a physical keyboard and mouse. Verify WASD, modifiers, number/function keys, relative captured look, uncaptured menu motion, wheel, left/right/middle buttons and any available back/forward buttons. Open a menu and return to the world to verify pointer capture releases and reacquires.

Record the Android version, device model, Minecraft version, selected Java version, renderer and the final visible error.

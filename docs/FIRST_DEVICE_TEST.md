# First device test

Use a clean MCLauncher 11.0 Alpha 04 installation.

1. Install the GitHub Actions `MCLauncher-11.0-alpha04-arm64-v8a.apk`.
2. Allow file access requested by Android.
3. Create an offline account for the first launch test.
4. Install a clean vanilla Minecraft version without mods.
5. Keep the default renderer selection and conservative RAM allocation.
6. Launch and wait for the main menu.
7. Navigate every main-menu button with the virtual cursor, including left click and long-press right click. If Minecraft reports the cursor as grabbed, tap `Mouse` in the top row first.
8. Create a test world and check rendering, sound, movement, swipe look and configurable touch buttons.
9. Test a physical keyboard for at least 15 minutes, then test mouse and controller input in both menus and a world.
10. Install one Modrinth project and verify its icon and `Installed` state survive a launcher restart.
11. If a CurseForge API key is configured, repeat the browse/install test with CurseForge.
12. Open an instance's **Settings** tab, enable custom settings, select OpenLTW with the System driver, and launch it.
13. Confirm that changing that instance does not change the global renderer/RAM settings.
14. If launch or input fails, export `latest-session.log` before changing settings.

Record the Android version, device model, Minecraft version, selected Java version, renderer and the final visible error.

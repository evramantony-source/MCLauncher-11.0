# First device test

Use a clean MCLauncher 11.0 Alpha 16 installation.

1. Install the GitHub Actions `MCLauncher-11.0-alpha16-arm64-v8a.apk`.
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
16. If launch or input fails, export `latest-session.log` before changing settings.

Record the Android version, device model, Minecraft version, selected Java version, renderer and the final visible error.

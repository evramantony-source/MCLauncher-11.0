# MCLauncher 11.0 milestones

## Alpha 01 — reproducible standalone build

- [x] Import the latest MCLauncher source baseline.
- [x] Rebrand project and artifacts as MCLauncher 11.0.
- [x] Compile the pinned Android engine from source in GitHub Actions.
- [x] Vendor Java 8/17/21/25, patched LWJGL and ABI-native libraries during CI.
- [x] Verify Java 17/21/25 runtime signatures with the pinned engine certificate.
- [x] Reject unsafe vendored paths and incomplete payloads.
- [x] Run source, Python and JVM tests on pull requests.
- [x] Obtain a fully green standalone-APK workflow.
- [x] Install the artifact on the Android test device.
- [x] Reach the Minecraft 1.21.11/Fabric main menu.
- [x] Verify world rendering and stable touch play.

## Alpha 02 — input and content usability

- [x] Add virtual-mouse menu navigation and a visible cursor.
- [x] Make swipe look the default and retain the optional look joystick.
- [x] Match the pinned engine's raw physical-keyboard event path.
- [x] Add contextual physical mouse and controller menu input.
- [x] Expand the editable touch-control layout and actions.
- [x] Add project icons and per-instance installed/installing state.
- [x] Add system, dark and light themes.
- [x] Add verified one-tap renderer packages and hide dead placeholders.
- [ ] Pass the Alpha 02 CI build.
- [ ] Verify menu, keyboard, mouse, controller, content and renderer behavior on-device.

## Alpha 12 — direct touchscreen interaction

- [x] Remove the visible virtual cursor from touchscreen navigation.
- [x] Translate finger-down, move and up into a held Minecraft inventory drag.
- [x] Map direct taps over the real hotbar to slots 1 through 9.
- [x] Preserve physical mouse, keyboard, controller and existing in-world controls.
- [ ] Verify menu taps, inventory drag and all hotbar slots on the Redmi Pad Pro.

## Alpha 13 — exact runtime and calibrated taps

- [x] Reject modpacks that do not declare one exact loader version.
- [x] Verify the installed profile contains the exact declared loader artifact.
- [x] Send each direct press/release atomically with its mapped SurfaceView coordinate.
- [x] Replace purple selection roles with green and use a midnight-black dark palette.
- [ ] Verify exact modpack runtime and direct taps on the Redmi Pad Pro.

## Alpha 14 — SurfaceView-local direct touch

- [x] Stop reconstructing SurfaceView coordinates from Activity/window offsets.
- [x] Deliver touch events directly to the rendered game view in its local coordinate space.
- [x] Position on finger-down, click on confirmed tap release and begin inventory drag only after touch slop.
- [x] Keep direct-touch menus active when the visible in-world control overlay is hidden.
- [x] Add pure gesture-state regression tests and bounded native coordinate traces.
- [ ] Verify exact menu targets and inventory drag on the Redmi Pad Pro.

## Alpha 15 — anchored mobile taps

- [x] Press immediately at finger-down and release quick gestures at that same coordinate.
- [x] Start inventory pointer movement only after a deliberate hold plus Android touch slop.
- [x] Prevent Compose parents from intercepting an active SurfaceView touch stream.
- [x] Record bounded Android local/raw gesture traces in the exported session log.
- [x] Reject GLFW input after queue shutdown without touching destroyed synchronization primitives.
- [ ] Verify quick menu taps, held inventory drag and clean game exit on the Redmi Pad Pro.

## After main-menu confirmation

- Pin the observed Java 8 release ZIP digest in `vendor/engine-lock.json`.
- Add signed release builds and upgrade/migration tests.
- Test a representative Minecraft and loader compatibility matrix.
- Add device-specific renderer recommendations.
- Profile startup time, memory and sustained performance.

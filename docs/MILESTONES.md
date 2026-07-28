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

## After main-menu confirmation

- Pin the observed Java 8 release ZIP digest in `vendor/engine-lock.json`.
- Add signed release builds and upgrade/migration tests.
- Test a representative Minecraft and loader compatibility matrix.
- Add device-specific renderer recommendations.
- Profile startup time, memory and sustained performance.

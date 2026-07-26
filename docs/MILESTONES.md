# MCLauncher 11.0 milestones

## Alpha 01 — reproducible standalone build

- [x] Import the latest MCLauncher source baseline.
- [x] Rebrand project and artifacts as MCLauncher 11.0.
- [x] Compile the pinned Android engine from source in GitHub Actions.
- [x] Vendor Java 8/17/21/25, patched LWJGL and ABI-native libraries during CI.
- [x] Verify Java 17/21/25 runtime signatures with the pinned engine certificate.
- [x] Reject unsafe vendored paths and incomplete payloads.
- [x] Run source, Python and JVM tests on pull requests.
- [ ] Obtain a fully green standalone-APK workflow.
- [ ] Install the artifact on the Redmi Pad Pro.
- [ ] Reach the vanilla Minecraft main menu.
- [ ] Verify world rendering, audio, touch, keyboard, mouse and controller input.

## After main-menu confirmation

- Pin the observed Java 8 release ZIP digest in `vendor/engine-lock.json`.
- Add signed release builds and upgrade/migration tests.
- Test a representative Minecraft and loader compatibility matrix.
- Add device-specific renderer recommendations.
- Profile startup time, memory and sustained performance.

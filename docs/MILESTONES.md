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

## Alpha 16 — confirmed coordinate-bound taps

- [x] Position the GUI cursor on finger-down without holding Minecraft's left mouse button for the whole finger gesture.
- [x] Convert an ordinary finger-up into a coordinate-bound press/release at the original touch point with a 33 ms hold.
- [x] Keep deliberate hold-plus-touch-slop movement as a real inventory drag with stable Android pointer-ID tracking.
- [x] Retain Alpha 15's atomic cursor-plus-button native event and shutdown-safe GLFW queue.
- [x] Capture a Redmi Pad Pro trace proving Android and native receive identical tap coordinates and exposing the `2560×1600` Surface versus `896×504` game-window mismatch.

## Alpha 17 — one Surface/input coordinate model

- [x] Fix the Android Surface buffer to Minecraft's configured render resolution before launch.
- [x] Keep full-screen Android View coordinates separate from the smaller game-buffer coordinate space.
- [x] Scale relative mouse/look/gyro motion into the game buffer while keeping absolute direct touch normalized to the visible View.
- [x] Add the exact Redmi Pad Pro `2560×1600` input → `896×504` buffer trace as a regression test.
- [x] Verify exact menu and inventory targets on the Redmi Pad Pro.

## Alpha 18 — real modpack interchange

- [x] Import local Modrinth and CurseForge archives into new exact-runtime instances.
- [x] Export provider-native manifests with validated references, hashes and overrides.
- [x] Recover export provenance from older managed-content indexes and original pack archives.
- [x] Exclude volatile and personal instance data from default exports.
- [ ] Round-trip both formats through the official desktop clients on a physical device.

## Alpha 19 — MCL Creation Lab and reliable discovery

- [x] Add a sidebar Creation Lab with per-pixel item, skin and cape editing.
- [x] Read the complete item texture catalog from each installed client JAR.
- [x] Export version-correct, validated resource-pack ZIPs and dimension-checked PNGs.
- [x] Preserve animation metadata and edit vertical animation sheets frame by frame.
- [x] Add real Modrinth and CurseForge result pagination.
- [x] Resolve snapshot loader profiles correctly and keep offline content checks from blocking Play.
- [ ] Connect a user-selected AI provider and export reviewed mod-source or shader-pack projects.
- [ ] Compile generated mod JARs only through a pinned, sandboxed toolchain or explicitly trusted remote builder.

## Alpha 20 — local project creation and Android Files

- [x] Remove API-key and GitHub-token requirements from the Creation Lab.
- [x] Generate Fabric, Quilt, Forge and NeoForge data-driven projects on-device.
- [x] Assemble loader JARs directly on-device without relying on a missing Android JDK compiler.
- [x] Attach and locally inspect images, logs, source archives and mod JARs.
- [x] Expose instances, generated projects, outputs and logs in Android Files.
- [x] Copy completed JARs and shader ZIPs directly into the selected instance.
- [ ] Verify one locally assembled JAR for every loader on the Redmi Pad Pro.
- [ ] Expand the verified mechanic library beyond the Minecraft 1.20.1 grappling hook.

## After main-menu confirmation

- Pin the observed Java 8 release ZIP digest in `vendor/engine-lock.json`.
- Add signed release builds and upgrade/migration tests.
- Test a representative Minecraft and loader compatibility matrix.
- Add device-specific renderer recommendations.
- Profile startup time, memory and sustained performance.

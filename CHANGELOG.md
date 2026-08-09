# Changelog

## 11.0.0-alpha19

- Add the MCL Creation Lab sidebar destination with a touch-first ARGB pixel editor, pencil, eraser, bounded fill, color picker, undo/redo, transparent checkerboard and frame-by-frame editing for animated vanilla textures.
- Read every item PNG directly from an installed Minecraft client JAR and export edited files as a validated resource-pack ZIP, retaining vanilla animation metadata and using the installed client's declared resource-pack version.
- Emit modern `min_format`/`max_format` metadata for resource pack version 65 and newer, including the 94.0 format required by Minecraft 26.3 Snapshot 6, while retaining legacy metadata for older clients.
- Add 64×64 Java skin and 64×32 cape creation, PNG import, pixel editing, dimension validation and Android save-picker export.
- Resolve Fabric snapshot profile IDs back to the exact base game version and let Play continue when an automatic Modrinth content check fails because the device is offline or DNS is unavailable.
- Add real Modrinth offset and CurseForge index pagination with an in-launcher **Load more results** action.
- Add a non-executing AI project-workshop boundary that documents the API-key and trusted-build requirements without pretending arbitrary generated code can safely compile itself on-device.

## 11.0.0-alpha18

- Add local Modrinth `.mrpack` and CurseForge profile-ZIP import from Android's document picker, creating a separate instance with the exact Minecraft and loader versions declared by the archive.
- Add per-instance Modrinth and CurseForge exports through Android's save picker, with format-specific root manifests, exact loader metadata, provider project/file references, portable overrides and post-write archive validation.
- Persist Modrinth URLs, SHA-1/SHA-512 hashes, environment metadata and CurseForge project/file provenance; recover older pack metadata from the original archive before exporting an Alpha 17 instance.
- Embed changed, local and cross-provider files instead of emitting broken remote references, while excluding worlds, logs, crash reports, screenshots, server lists and launcher-private metadata.
- Roll back a newly created imported instance if runtime or pack installation fails, and retain safe ZIP path checks for both downloaded files and overrides.

## 11.0.0-alpha17

- Fix the on-device input/render-size split exposed by the Alpha 16 Redmi Pad Pro trace: Minecraft was launched at `896×504` while Android silently supplied a `2560×1600` game Surface.
- Fix the SurfaceHolder to the exact configured Minecraft render resolution so GLFW's window, framebuffer and cursor coordinate space begin with one size instead of being resized underneath the game.
- Track Android View pixels and game-buffer pixels separately; absolute touch/mouse input stays normalized to the visible View while relative mouse, look and gyro deltas are scaled into the render buffer.
- Add the exact `2560×1600` → `896×504` device case as a regression test and log both View and buffer dimensions in every test session.

## 11.0.0-alpha16

- Position Minecraft's GUI cursor on finger-down without pressing the mouse button, then emit the click only after Android confirms an ordinary tap.
- Keep both click edges anchored to the original finger-down coordinate and separate press/release by 33 ms, so every quick tap reaches GLFW as a short coordinate-bound desktop click instead of a finger-duration hold.
- Preserve Alpha 15's Android pointer-ID tracking and deliberate hold-plus-touch-slop inventory drag, including safe release on cancellation and mode changes.
- Add regression coverage that distinguishes tap positioning, confirmed taps and real held drags so press-on-down behavior cannot silently return.

## 11.0.0-alpha15

- Anchor every ordinary touchscreen press and release to the original finger-down coordinate, preventing a noisy or displaced Android `ACTION_UP` from selecting a different Minecraft button or inventory slot.
- Require a deliberate system-length hold plus movement beyond touch slop before forwarding inventory drag movement; quick gestures remain reliable taps while held drag-and-drop still works.
- Keep the SurfaceView's touch stream owned for the full gesture and add bounded raw/local Android event traces for exact on-device verification.
- Make the patched GLFW input queue reject events before initialization and after termination without destroying its process-lifetime synchronization primitives, fixing the shutdown-time `pthread_mutex_lock called on a destroyed mutex` abort captured by Alpha 14.

## 11.0.0-alpha14

- Deliver direct touchscreen events to the game SurfaceView itself so taps use coordinates already local to the rendered Minecraft content instead of reconstructing them from Activity/window offsets.
- Match the pinned Android launcher's proven GUI gesture behavior: position on finger-down, click on confirmed tap release, and begin a held-button inventory drag only after Android touch slop is crossed.
- Keep direct-touch menus active even when the visible in-world controls are hidden, and release every pending tap or drag safely across focus and mode changes.
- Add gesture-state regression tests plus bounded native traces containing normalized, surface-pixel and press/release information for device-only diagnosis.

## 11.0.0-alpha13

- Made modpack runtime setup fail closed unless the archive declares one exact loader version, and verify the downloaded/generated profile contains that exact Fabric, Quilt, Forge or NeoForge artifact before marking the instance installed.
- Replaced mixed screen/window touch arithmetic with one inset-safe SurfaceView mapping and added an atomic native position-plus-button event so a tap cannot activate a stale or different Minecraft control.
- Replaced Material's remaining purple selection roles with green and moved dark mode to a midnight-black surface palette.
- Display the exact loader, loader version and base Minecraft version in Home, Library and quick-instance summaries.

## 11.0.0-alpha12

- Replaced the visible virtual cursor with cursor-free direct taps on Minecraft menus and inventory slots.
- Kept Minecraft's left button held from finger-down through movement so inventory items can be dragged naturally, then released it safely on finger-up, cancellation or mode changes.
- Added a transparent nine-slot touch target over Minecraft's real in-world hotbar, mapped to the standard `1` through `9` keys without drawing a duplicate HUD.
- Preserved the Alpha 11 grab-release and serialized native input ordering fixes for physical mouse, controller and touch input.

## 11.0.0-alpha11

- Restored the pinned Android GLFW grab-release contract so opening an inventory or menu recenters both MCLauncher's visible arrow and Minecraft's hover cursor together.
- Separated the latest requested pointer from GLFW's last-applied pointer, preventing an older queued move from changing the coordinate captured by a newer click.
- Serialized input-queue publication and buffer swaps so position and button events cannot be lost, observed half-written or reset during concurrent Android/GLFW access.

## 11.0.0-alpha10

- Added a responsive Modrinth-style tablet sidebar with primary navigation, quick instances, active account and settings while retaining compact Android bottom navigation.
- Added an explicit `AndroidLauncherEngine` boundary so the application shell prepares launches through MCLauncher's proven Android-native engine rather than Modrinth's desktop process launcher.
- Preserved deterministic local/offline accounts as first-class selectable profiles for single-player and offline-mode testing.
- Recorded the exact Modrinth App upstream revision used for workflow and information-architecture mapping, while excluding its restricted name, logo and branding assets.

## 11.0.0-alpha09

- Queued every virtual-mouse position through GLFW before its matching button event, keeping Minecraft hover and the visible cursor in the same coordinate stream.
- Added a dedicated `Esc` touch button so inventories and other Minecraft screens can close without opening MCLauncher's game overlay.
- Added live byte and file progress for Minecraft, loader, content and modpack downloads.
- Made project cards open compatible release, beta and alpha version lists while keeping the main button as latest-compatible install.
- Made Modrinth and CurseForge modpacks create their own named/iconized instance with the exact Minecraft, loader and loader version declared by the pack.

## 11.0.0-alpha08

- Made every queued GLFW mouse press carry the cursor position that was visible when the press happened, preventing a tap from activating a stale, far-away menu target.
- Unified Activity touch and SurfaceView bounds in screen coordinates so immersive-mode and transient system-bar offsets cannot skew the virtual pointer.
- Aligned the drawn arrow hotspot exactly with the coordinate sent to Minecraft, including at the screen edges.

## 11.0.0-alpha07

- Serialized bundled-engine restoration so startup inspection, Repair and Play cannot delete or count the engine payload concurrently.
- Disabled Play while a new APK bundle version is being restored and exposed that restoration state in Library and Home.
- Made Play perform a fresh serialized engine inspection instead of reusing a stale failed startup result.
- Replaced the misleading generic “APK is missing” toast with the exact engine inspection failure when restoration genuinely fails.

## 11.0.0-alpha06

- Stopped the Activity-wide virtual-mouse stream from intercepting launcher-owned game-menu and launch-overlay buttons.
- Suspended game touch controls while the launcher game menu is open so touch ownership changes cleanly in both directions.
- Matched the upstream Android GLFW click contract by holding virtual mouse presses for 33 milliseconds before release.
- Applied the same timed click path to look-pad double taps and long presses.

## 11.0.0-alpha05

- Rebuilt OpenLTW from the pinned current upstream source and added direct `glGetFloatv`/`glGetBooleanv` exports required by Minecraft 26.2's OpenGL startup path.
- Removed the obsolete 2025 OpenLTW one-tap package so it cannot replace the fixed bundled renderer.
- Moved virtual-menu-mouse touch capture into the Activity dispatch path, ahead of Compose hit-testing, while preserving the top Mouse/Look/Keyboard controls.
- Added global and per-instance `Default`, `Prefer OpenGL` and experimental `Prefer Vulkan` choices for Minecraft 26.2 and newer.
- Writes Minecraft's real `preferredGraphicsBackend` instance option and keeps the selected OpenGL renderer ready for Vulkan fallback.
- Added an original purple-and-emerald MCLauncher app icon and matching Home-screen branding.
- Added targeted LTW 26.2, graphics-API, version-capability and native-Vulkan tests and diagnostics.

## 11.0.0-alpha04

- Fixed OpenLTW/LTW launch preparation by keeping each built-in renderer's native token authoritative instead of accepting stale ANGLE metadata from an imported package.
- Made curated renderer and driver packages inherit their verified catalogue identity while retaining manifest-defined identities for manual custom packs.
- Replaced competing Compose tap/drag recognizers with one normalized Android touch stream for reliable menu cursor movement, left click and long-press right click.
- Added a visible `Mouse`/`Look` fallback toggle so the virtual mouse can be forced on when a mod or game screen reports the wrong GLFW cursor-grab state.
- Added first-pointer and first-mouse-button native diagnostics to exported session logs.
- Added per-instance Java, renderer, graphics-driver, RAM, performance, FPS, resolution, render-scale and JVM-argument settings with safe global inheritance.
- Added a specific renderer-package metadata diagnosis instead of reporting an unrelated renderer incompatibility.

## 11.0.0-alpha03

- Replaced the limited right-side menu touchpad with full-screen direct-touch cursor positioning, tap-to-click and long-press right click.
- Hidden in-world movement/action controls while Minecraft owns an ungrabbed menu cursor.
- Kept Java and native cursor state synchronized so touch, physical mouse and GLFW mode changes share one position.
- Stopped exposing the legacy `POJAV_RENDERER` marker to Minecraft after native renderer setup, preventing Sodium 0.8.x from aborting solely on that marker.
- Corrected crash analysis so Sodium's explicit Android block and harmless Android CPU-telemetry warnings are not mislabeled as renderer failures.

## 11.0.0-alpha02

- Replaced the duplicated physical-keyboard event path with the pinned GLFW engine's raw Android key path and added mouse-generated Back/right-click handling.
- Added a visible virtual cursor and touchpad-style menu navigation, including tap-to-click and long-press right click.
- Made free swipe look the mobile default while retaining the optional look joystick.
- Expanded the touch-control editor with add, duplicate, delete, label, action, raw key/mouse, visibility, toggle, size, position and opacity controls.
- Added controller cursor navigation in menus while preserving in-world movement, camera, triggers and remappable buttons.
- Added Modrinth and CurseForge project icons plus persistent per-instance `Installed` and `Installing` states.
- Added a guided CurseForge API-key state and optional build-time key injection.
- Added system, dark and light launcher themes.
- Replaced dead renderer/driver choices with ready states and checksum-pinned one-tap installers for compatible OpenLTW, ANGLE and Zink packages.
- Added compatibility tests for Alpha 01 content indexes and mobile-friendly settings defaults.

## 11.0.0-alpha01

- Imported the MCLauncher 6.0 Beta 05 source baseline into the 11.0 development line.
- Rebranded application, launch properties, workflow artifacts and documentation.
- Added pull-request source/JVM validation before the expensive native build.
- Added RSA verification for Java 17, 21 and 25 runtime archives.
- Added path-traversal checks for vendored library/support-JAR paths.
- Added unit coverage for ABI parsing, classifier selection and runtime signature manifests.
- Removed the unpinned optional renderer-artifact injection from the standalone build.
- Kept native/runtime binaries out of Git and made the APK workflow responsible for producing and verifying them.
- Added the Android AWT runtime overlay required by the pinned MojoLauncher compatibility path.
- Disabled native heap pointer tagging for the Minecraft compatibility process after Android 16 detected a truncated tag inside the embedded desktop-native stack.
- Bundled the hash-pinned MobileGlues 1.3.5 ARM renderer and made it the automatic first choice for modern Minecraft OpenGL calls.
- Added version-matched Android JNA 6/7 dispatch libraries and classpath-driven selection.
- Fixed stale renderer settings falling through to the wrong backend and removed the false account-expired crash diagnosis.

## 6.0.0-beta05

- Removed the installed-app launcher-APK downloader and replaced it with a standalone APK payload built in GitHub Actions.
- Added pinned source build and ABI-specific packaging for the Android JVM engine and dnbootstrap GLFW.
- Added Java 8, 17, 21 and 25 build-time runtime vendoring using the current split runtime feeds.
- Added coordinate mapping for older and modded LWJGL versions through `artifactMapping`.
- Added exact ABI classifier extraction and verification for patched LWJGL native libraries.
- Separated Java 8 and Java 17+ Caciocavallo components and added the required Java desktop module access.
- Fixed the Logs screen compile error.
- Activated favourite ordering, keep-launcher-open, FPS options, runtime/component catalogues and Microsoft skin-head display.
- Added configurable movement and look joysticks.
- Added physical keyboard, relative mouse capture, extra mouse buttons, wheel and cursor integration.
- Added direct controller movement, look, D-pad, trigger and button handling.
- Disabled renderer and driver choices when their native pack is not installed, preventing dead settings.
- Added strict source, payload and final-APK verification.
- Added ABI-selectable GitHub Actions builds; arm64-v8a is the default.

## 6.0.0-beta02

- Added Microsoft authentication, loaders, Modrinth/CurseForge content, controls, logs and expanded settings.

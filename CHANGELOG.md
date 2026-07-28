# Changelog

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

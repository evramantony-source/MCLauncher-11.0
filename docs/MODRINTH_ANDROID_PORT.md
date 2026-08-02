# Modrinth-style Android port boundary

## Upstream reference

The application structure was compared against the official Modrinth monorepo:

- repository: `https://github.com/modrinth/code`
- reviewed commit: `8b753a52ad5ca2a820bc4189e207728e405d7870`
- relevant packages: `apps/app`, `apps/app-frontend`, `packages/app-lib`
- source licence: GPL-3.0-only

Modrinth's `apps/app/COPYING.md` and `apps/app-frontend/COPYING.md` reserve its
name, wrench-in-labyrinth logo, landing image and other branding assets. Those
assets are intentionally absent. The APK remains an independent MCLauncher
build with its original icon and package name.

## Why the desktop backend is not used on Android

The Modrinth App starts Minecraft as a desktop child process from its Rust/Tauri
backend. Android cannot execute that desktop Java/LWJGL process unchanged. A
working Android launcher must instead provide Android Java runtimes, patched
LWJGL/GLFW natives, an Android `Surface`, renderer translation and touch/input
bridges.

Alpha 10 therefore preserves the tested MCLauncher engine and maps the Modrinth
application experience onto it:

| Modrinth App responsibility | Android implementation |
| --- | --- |
| App shell and navigation | Native Compose shell with Home, Library, Browse, quick instances, Accounts and Settings |
| Instance list and details | MCLauncher instance store and per-instance directories |
| Project discovery | Modrinth API repository plus the existing CurseForge integration |
| Install and update progress | MCLauncher byte/file progress state |
| Desktop process launch | `MCLauncherAndroidEngine` and `NativeEngineCoordinator` |
| Java selection | Bundled Android Java 8, 17, 21 and 25 runtimes |
| Touch, keyboard and mouse | Android GLFW bridge, direct-touch menus/inventories, hotbar mapping and physical input routing |
| Account selection | Microsoft authentication plus deterministic local/offline profiles |

## Launch contract

The UI calls `AndroidLauncherEngine.prepareLaunch`. The concrete
`MCLauncherAndroidEngine` resolves the selected Minecraft instance, substitutes
the Android LWJGL stack and writes a launch plan. `GameActivity` then gives that
plan and its Android surface to `NativeEngineCoordinator`, which starts the JVM
in-process through the pinned native engine.

Local/offline profiles use the standard deterministic
`OfflinePlayer:<username>` UUID. They pass no Microsoft access token and are
labelled for single-player or offline-mode testing; they do not claim ownership
or unlock authenticated Minecraft services.

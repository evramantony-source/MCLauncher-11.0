# Architecture

## Modules

- `app`: Compose UI, persistence, runtime/engine imports, fullscreen game activity, and JNI orchestration.
- `core-model`: Serializable launcher, account, instance, settings, and progress models.
- `core-minecraft`: Mojang metadata, resumable verified downloads, filesystem layout, rules, and launch-plan creation.

## Launch flow

1. The user selects an offline account.
2. Discover reads Mojang's official version manifest.
3. Installation downloads the version JSON, client, Java libraries, assets and logging configuration with integrity checks; Android-native LWJGL comes from the bundled engine.
4. Play creates a complete serialized `LaunchPlan`.
5. `GameActivity` starts in the isolated `:minecraft` process and creates a `SurfaceView`.
6. `NativeEngineCoordinator` validates the APK-bundled JRE/engine payload, places Android engine natives before desktop natives, configures renderer variables, and preloads libraries.
7. `libmclauncher.so` attaches the surface, captures logs, and calls the mobile OpenJDK `JLI_Launch` entry point.
8. Minecraft's exit code and logs return to the activity and are stored in `latest-session.log`.

No UI state is treated as proof of successful rendering; the native logs and real device output are authoritative.

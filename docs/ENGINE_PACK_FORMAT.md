# Engine, runtime, and graphics import formats

## Runtime import

Accepted containers: ZIP, APK, TAR.XZ, and TAR.GZ. Nested archives are scanned to a limited depth.

The selected archive must contain:

- `libjvm.so`
- `libjava.so`
- preferably `libjli.so`
- the rest of the matching mobile OpenJDK libraries
- a `release` file when available

The importer checks the selected Java major and ELF machine architecture, then installs the runtime under the launcher data directory.

## Engine pack import

The engine archive must contain:

- Android-patched LWJGL JARs;
- matching native `.so` libraries for the device ABI;
- a GLFW/window bridge suitable for an Android `ANativeWindow`;
- Cacio/AWT support when required;
- OpenAL/native audio components when required.

A compatible launcher APK can be selected because APK files are ZIP containers. Graphics libraries found inside a full APK are preserved in legacy renderer/driver directories instead of being mixed with JVM natives.

## Renderer and driver pack import

Renderer and driver packs use the format documented in `GRAPHICS_PACK_SCHEMA.md`. A manifest is recommended but common packages can be identified from their library names and paths.

Supported renderer profiles:

- MobileGlues
- direct ANGLE renderer
- OpenLTW / LTW
- NG-GL4ES
- GL4ES
- Zink
- VirGL
- native Vulkan
- Krypton Wrapper
- manifest-driven custom renderer plugins

Supported driver profiles:

- ANGLE
- Turnip
- PanVK
- SwiftShader
- Android system driver (no import needed)

## Safety checks

- Absolute paths and `..` archive traversal are rejected.
- Archive entry counts and total extracted bytes are limited.
- TAR links are constrained to the extraction root.
- Native binaries are checked against the device ABI.
- Selected renderer and driver packs are checked again before launch.

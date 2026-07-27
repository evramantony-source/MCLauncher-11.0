# Renderer and graphics-driver system

Beta 04 separates the Minecraft renderer from the optional Android graphics-driver layer.

## Bundled path

The CI build packages a hash-pinned MobileGlues release for every supported ABI, plus the compatible GL4ES/OpenLTW fallback produced by the pinned engine source. Automatic mode prefers MobileGlues and the System driver. The native bridge configures the renderer before loading GLFW and deliberately avoids preloading unselected backends, preventing GL4ES/Mesa/ANGLE symbol collisions.

Additional renderer entries remain available only when a compatible `mclauncher-graphics.json` pack is present:

- OpenLTW/LTW
- NG-GL4ES
- Zink/Mesa
- ANGLE
- MobileGlues
- VirGL legacy override
- Native Vulkan
- Krypton or custom backends

An unavailable explicit backend is marked **not installed**. If an older saved setting points to a missing pack, the launch resolver safely falls back to the first installed backend instead of blocking Minecraft startup.

## Driver layers

- System GLES/Vulkan
- ANGLE
- Turnip for supported Qualcomm Adreno devices
- PanVK for supported Mali devices
- SwiftShader software fallback

Optional driver packs require compatible native libraries and, for Vulkan ICD drivers, their JSON metadata. GPU capability limits still apply.

## Pack layout

```text
mclauncher-graphics.json
libSomething.so
optional-icd.json
optional-config.conf
```

Native files are checked for ELF ABI compatibility. A pack manifest controls renderer id, native token, preload names and environment. Developer imports never replace the APK's bundled fallback unless explicitly selected.

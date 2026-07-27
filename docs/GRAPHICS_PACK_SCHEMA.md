# `mclauncher-graphics.json` schema

Required fields:

- `schemaVersion`: currently `1`
- `id`: stable lowercase pack id; it does not have to equal the enum id
- `name`: display name
- `kind`: `renderer` or `driver`
- `renderer`: one renderer enum when kind is `renderer`
- `driver`: one driver enum when kind is `driver`

Renderer enum values:

`MOBILE_GLUES`, `ANGLE`, `OPEN_LTW`, `NG_GL4ES`, `GL4ES`, `ZINK`, `VIRGL`, `VULKAN`, `KRYPTON`, `CUSTOM`.

Driver enum values:

`SYSTEM`, `ANGLE`, `TURNIP`, `PANVK`, `SWIFTSHADER`.

Optional fields:

- `version`
- `architecture` (`arm64-v8a`, `armeabi-v7a`, or `x86_64`)
- `pojavRenderer`
- `preload`: filename tokens used to order native preloading
- `environment`: environment variables applied before the JVM starts; `${cache}` expands to the instance cache directory
- `sourceProject`
- `license`

`CUSTOM` renderer packs must provide `pojavRenderer`. For every renderer, the value in the manifest overrides the launcher's fallback token.

Example:

```json
{
  "schemaVersion": 1,
  "id": "mobileglues-release-package",
  "name": "MobileGlues",
  "version": "replace-me",
  "kind": "renderer",
  "architecture": "arm64-v8a",
  "renderer": "MOBILE_GLUES",
  "pojavRenderer": "mobileglues",
  "preload": ["libmobileglues.so"],
  "environment": {
    "LIBGL_ES": "3",
    "MG_DIR_PATH": "${cache}/mobileglues"
  },
  "license": "LGPL-2.1"
}
```

An unknown renderer can still be installed with:

```json
{
  "schemaVersion": 1,
  "id": "future-renderer",
  "name": "Future renderer",
  "kind": "renderer",
  "renderer": "CUSTOM",
  "pojavRenderer": "the-token-required-by-that-plugin",
  "preload": ["future_renderer"]
}
```

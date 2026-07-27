#!/usr/bin/env python3
"""Fast source-level checks that run before the Android build."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"Missing required source file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require_contains(relative: str, *needles: str) -> None:
    content = text(relative)
    for needle in needles:
        if needle not in content:
            errors.append(f"{relative}: missing {needle!r}")


def require_absent(relative: str, *needles: str) -> None:
    content = text(relative)
    for needle in needles:
        if needle in content:
            errors.append(f"{relative}: obsolete text remains: {needle!r}")


def require_before(relative: str, first: str, second: str) -> None:
    content = text(relative)
    first_index = content.find(first)
    second_index = content.find(second)
    if first_index < 0 or second_index < 0 or first_index >= second_index:
        errors.append(f"{relative}: expected {first!r} before {second!r}")


# Structured files must parse.
for path in ROOT.rglob("*.json"):
    try:
        json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        errors.append(f"Invalid JSON {path.relative_to(ROOT)}: {exc}")
for path in ROOT.rglob("*.xml"):
    try:
        ElementTree.parse(path)
    except Exception as exc:  # noqa: BLE001
        errors.append(f"Invalid XML {path.relative_to(ROOT)}: {exc}")

lock = json.loads(text("vendor/engine-lock.json") or "{}")
engine_commit = lock.get("engine", {}).get("commit", "")
if not re.fullmatch(r"[0-9a-f]{40}", engine_commit):
    errors.append("vendor/engine-lock.json: engine commit must be a full 40-character SHA")
for major in (8, 17, 21, 25):
    source = lock.get("runtimes", {}).get("sources", {}).get(str(major))
    if not source:
        errors.append(f"vendor/engine-lock.json: Java {major} source is missing")

require_contains(
    "app/build.gradle.kts",
    'versionName = "11.0.0-alpha01"',
    'versionCode = 11',
    'mclauncherAbi',
    'abiFilters += targetAbi',
)
require_contains(
    ".github/workflows/android.yml",
    "scripts/vendor_engine.py",
    "scripts/verify_bundled_engine.py",
    "scripts/source_sanity.py",
    'assembleNoruntimeDebug',
    'assembleDebug -PmclauncherAbi=',
    'MCLauncher-11.0-alpha01-',
    "pull_request:",
)
if engine_commit and engine_commit not in text("vendor/engine-lock.json"):
    errors.append("Engine commit was not retained in the lock file")

require_contains(
    "core-minecraft/src/main/kotlin/com/mclauncher/minecraft/LaunchPlanBuilder.kt",
    'artifactMapping',
    'No Android LWJGL substitution is defined',
    'prepareAndroidNatives',
    'applyMinecraftOptions',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/engine/NativeEngineCoordinator.kt",
    'caciocavallo17',
    'addJavaDesktopModuleAccess',
    'libpojavexec.so',
    'POJAV_RENDERER',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/engine/NativeEngineCoordinator.kt",
    "isUnsupportedProcessHookLibrary",
    "MCLAUNCHER_SESSION_LOG",
)
require_before(
    "app/src/main/java/com/mclauncher/app/engine/NativeEngineCoordinator.kt",
    'name.contains("awt_headless"',
    'name.contains("awt_xawt"',
)
require_contains(
    "scripts/vendor_engine.py",
    "verify_runtime_signatures",
    "parse_signature_bundle",
    "mojo-runtime-signing-cert.pem",
)
require_contains(
    "app/src/main/java/com/mclauncher/app/ui/game/TouchControls.kt",
    'MovementJoystick',
    'LookJoystick',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/ui/screens/SettingsScreen.kt",
    'enabled = renderer == Renderer.AUTO || status?.installed == true',
    'enabled = driver in listOf(GraphicsDriver.AUTO, GraphicsDriver.SYSTEM) || status?.installed == true',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/engine/GameInputBridge.kt",
    'SOURCE_MOUSE_RELATIVE',
    'SOURCE_JOYSTICK',
    'AXIS_HSCROLL',
    'AndroidGlfwKeyMapper',
)
require_contains(
    "app/src/main/cpp/native_engine.cpp",
    'Java_git_artdeell_dnbootstrap_glfw_GLFW_initialize',
    'Java_git_artdeell_dnbootstrap_glfw_GLFW_nativeSurfaceCreated',
    'Java_git_artdeell_dnbootstrap_glfw_GLFW_sendKeyEvent',
    'JLI_Launch',
    'JNI_CreateJavaVM',
)
require_absent(
    "app/src/main/java/com/mclauncher/app/ui/screens/LogsScreen.kt",
    'result.recommendation',
)
require_contains(
    "core-minecraft/src/test/kotlin/com/mclauncher/minecraft/LaunchPlanBuilderAndroidLwjglTest.kt",
    'artifactMapping',
    'liblwjgl.so',
    'maxFps:60',
)

# Settings requested by the user must have both a model field and a use site.
model = text("core-model/src/main/kotlin/com/mclauncher/model/LauncherModels.kt")
app_sources = "\n".join(
    path.read_text(encoding="utf-8")
    for path in (ROOT / "app/src/main/java").rglob("*.kt")
)
for field in (
    "keepLauncherOpen",
    "runtimeCatalogUrl",
    "fpsLimit",
    "movementJoystickEnabled",
    "lookJoystickEnabled",
    "physicalMouseCapture",
    "hideTouchControlsWithExternalInput",
    "sustainedPerformanceMode",
):
    if field not in model:
        errors.append(f"LauncherSettings field is missing: {field}")
    if app_sources.count(field) < 2:
        errors.append(f"LauncherSettings field is not meaningfully used: {field}")

# The installed application must not contain the old runtime launcher downloader.
for path in (ROOT / "app/src/main").rglob("*"):
    if not path.is_file() or path.suffix.lower() not in {".kt", ".java", ".cpp", ".xml", ".txt"}:
        continue
    content = path.read_text(encoding="utf-8", errors="replace").lower()
    for forbidden in ("zalithlauncher", "api.github.com/repos/zalith", "install official launch engine"):
        if forbidden in content:
            errors.append(f"Runtime source still contains old launcher dependency in {path.relative_to(ROOT)}: {forbidden}")

if errors:
    print("Source sanity checks FAILED:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)
print("Source sanity checks passed")

#!/usr/bin/env python3
"""Fast source-level checks that run before the Android build."""
from __future__ import annotations

import base64
import hashlib
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
TEXT_SUFFIXES = {
    ".cpp", ".gradle", ".java", ".json", ".kts", ".kt", ".md",
    ".pro", ".properties", ".py", ".txt", ".xml", ".yml",
}


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


# Every repository-owned source/config/document file must be valid UTF-8 and
# free of embedded NUL bytes. Generated artifacts and signing material are
# validated separately or by the final-APK verifier.
for path in ROOT.rglob("*"):
    if not path.is_file() or any(
        part in {".git", ".ci-signing", "__pycache__", "artifacts", "signing"}
        for part in path.relative_to(ROOT).parts
    ):
        continue
    if path.suffix.lower() not in TEXT_SUFFIXES and path.name != ".gitignore":
        continue
    try:
        content = path.read_bytes()
        content.decode("utf-8")
        if b"\x00" in content:
            errors.append(f"Embedded NUL byte in {path.relative_to(ROOT)}")
    except UnicodeDecodeError as exc:
        errors.append(f"Invalid UTF-8 in {path.relative_to(ROOT)}: {exc}")

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

renderer_enums = {
    "MOBILE_GLUES", "ANGLE", "OPEN_LTW", "NG_GL4ES", "GL4ES",
    "ZINK", "VIRGL", "VULKAN", "KRYPTON", "CUSTOM",
}
driver_enums = {"SYSTEM", "ANGLE", "TURNIP", "PANVK", "SWIFTSHADER"}
for manifest_path in (ROOT / "graphics-pack-examples").glob("*/mclauncher-graphics.json"):
    document = json.loads(manifest_path.read_text(encoding="utf-8"))
    label = manifest_path.relative_to(ROOT)
    if document.get("schemaVersion") != 1:
        errors.append(f"{label}: schemaVersion must be 1")
    if document.get("kind") not in {"renderer", "driver"}:
        errors.append(f"{label}: kind must be renderer or driver")
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", str(document.get("id", ""))):
        errors.append(f"{label}: id is not safe")
    architecture = document.get("architecture")
    if architecture not in {"arm64-v8a", "armeabi-v7a", "x86_64"}:
        errors.append(f"{label}: unsupported architecture {architecture!r}")
    if document.get("kind") == "renderer":
        if document.get("renderer") not in renderer_enums:
            errors.append(f"{label}: invalid renderer enum")
        if not str(document.get("pojavRenderer", "")).strip():
            errors.append(f"{label}: renderer token is missing")
    if document.get("kind") == "driver" and document.get("driver") not in driver_enums:
        errors.append(f"{label}: invalid driver enum")

alpha_signer = ROOT / "signing/mclauncher-alpha-debug.jks.b64"
try:
    alpha_signer_bytes = base64.b64decode(
        "".join(alpha_signer.read_text(encoding="ascii").split()),
        validate=True,
    )
    if hashlib.sha256(alpha_signer_bytes).hexdigest() != "b97d99ee1cb0f58fce04a2267aa4185f405ea8f30d646116483657e20ee34a93":
        errors.append("Stable alpha signing key digest changed")
except Exception as exc:  # noqa: BLE001
    errors.append(f"Stable alpha signing key is invalid: {exc}")

lock = json.loads(text("vendor/engine-lock.json") or "{}")
engine_commit = lock.get("engine", {}).get("commit", "")
if not re.fullmatch(r"[0-9a-f]{40}", engine_commit):
    errors.append("vendor/engine-lock.json: engine commit must be a full 40-character SHA")
for major in (8, 17, 21, 25):
    source = lock.get("runtimes", {}).get("sources", {}).get(str(major))
    if not source:
        errors.append(f"vendor/engine-lock.json: Java {major} source is missing")
mobileglues = lock.get("renderers", {}).get("mobileGlues", {})
jna = lock.get("nativeCompatibility", {}).get("jna", {})
for label, document, digest_keys in (
    ("MobileGlues", mobileglues, ("releaseSha256", "licenseSha256")),
    ("JNA", jna, ("aarSha256", "licenseSha256")),
):
    if not document:
        errors.append(f"vendor/engine-lock.json: {label} source is missing")
    for key in digest_keys:
        if not re.fullmatch(r"[0-9a-f]{64}", str(document.get(key, ""))):
            errors.append(f"vendor/engine-lock.json: {label} {key} must be SHA-256")
if not re.fullmatch(r"[0-9a-f]{40}", str(mobileglues.get("sourceCommit", ""))):
    errors.append("vendor/engine-lock.json: MobileGlues source commit must be a full SHA")

require_contains(
    "app/build.gradle.kts",
    'versionName = "11.0.0-alpha03"',
    'versionCode = 13',
    'mclauncherAbi',
    'abiFilters += targetAbi',
    'CURSEFORGE_API_KEY',
    'coil-compose:3.1.0',
)
require_contains(
    ".github/workflows/android.yml",
    "scripts/vendor_engine.py",
    "scripts/verify_bundled_engine.py",
    "scripts/source_sanity.py",
    'assembleNoruntimeDebug',
    'assembleDebug -PmclauncherAbi=',
    'MCLauncher-11.0-alpha03-',
    "pull_request:",
    "mclauncher-alpha-debug.jks.b64",
    "CURSEFORGE_API_KEY",
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
    'MCLAUNCHER_RENDERER_TOKEN',
    'environment.remove("POJAV_RENDERER")',
)
require_absent(
    "app/src/main/java/com/mclauncher/app/engine/NativeEngineCoordinator.kt",
    'environment["POJAV_RENDERER"] =',
)
require_contains(
    "app/src/main/cpp/native_engine.cpp",
    'getenv("MCLAUNCHER_RENDERER_TOKEN")',
    'unsetenv("POJAV_RENDERER")',
)
require_contains(
    "app/build.gradle.kts",
    'buildConfigField("boolean", "PUBLIC_ALPHA_SIGNER", "true")',
    'signingConfigs.getByName("alphaDebug")',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/ui/screens/AccountsScreen.kt",
    "BuildConfig.PUBLIC_ALPHA_SIGNER",
)
require_contains(
    "app/src/main/java/com/mclauncher/app/engine/NativeEngineCoordinator.kt",
    "isUnsupportedProcessHookLibrary",
    "isConflictingAwtStubLibrary",
    "MCLAUNCHER_SESSION_LOG",
    "prepareRuntimeAwtCompatibility",
    "resolveLwjglOpenGlLibrary",
    '"org.lwjgl.opengl.libname"',
    '"jna.boot.library.path"',
    "selectJnaNativeDirectory",
    "lwjglExtractDirectory",
    "StandardCopyOption.ATOMIC_MOVE",
    'File(engineNatives, "libawt_xawt.so")',
    'findFile(javaHome, "libawt.so")',
)
require_absent(
    "app/src/main/java/com/mclauncher/app/engine/NativeEngineCoordinator.kt",
    '"glfwstub.initEgl"',
)
require_absent(
    "app/src/main/java/com/mclauncher/app/GameActivity.kt",
    "LaunchedEffect(running, surface)",
)
require_absent(
    "app/src/main/java/com/mclauncher/app/engine/NativeEngineCoordinator.kt",
    "libraryPathIndex",
)
require_contains(
    "app/src/main/AndroidManifest.xml",
    'android:launchMode="singleTask"',
    'android:allowNativeHeapPointerTagging="false"',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/ui/LauncherViewModel.kt",
    "lastPlayRequestAt",
    "Minecraft is already being prepared",
)
require_contains(
    "app/src/main/java/com/mclauncher/app/engine/NativeLaunchBridge.kt",
    "kotlinLaunchRunning",
    "tryClaimLaunch",
    "releaseLaunch",
)
require_contains(
    "app/src/main/java/com/mclauncher/app/GameActivity.kt",
    "Ignored duplicate UI launch request",
)
require_contains(
    "scripts/vendor_engine.py",
    "verify_runtime_signatures",
    "parse_signature_bundle",
    "mojo-runtime-signing-cert.pem",
    "vendor_mobileglues",
    "vendor_jna_dispatch",
    "bundle_version",
)
require_contains(
    "app/src/main/java/com/mclauncher/app/ui/game/TouchControls.kt",
    'MovementJoystick',
    'LookJoystick',
    'LookPad',
    'VirtualMouseCursor',
    'virtualMouseEnabled',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/ui/screens/SettingsScreen.kt",
    'LauncherThemeMode.entries',
    'TouchLookMode.entries',
    'Text("Install")',
    'Text("Installed"',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/engine/GameInputBridge.kt",
    'SOURCE_MOUSE_RELATIVE',
    'SOURCE_JOYSTICK',
    'AXIS_HSCROLL',
    'AndroidGlfwKeyMapper',
    'nativeSendRawKey',
    'cursorPosition',
)
require_contains(
    "app/src/main/cpp/native_engine.cpp",
    'Java_git_artdeell_dnbootstrap_glfw_GLFW_initialize',
    'Java_git_artdeell_dnbootstrap_glfw_GLFW_nativeSurfaceCreated',
    'Java_git_artdeell_dnbootstrap_glfw_GLFW_sendKeyEvent',
    'Java_git_artdeell_dnbootstrap_glfw_GLFW_sendRawKeyEvent',
    'Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendCursorPosition',
    'JNI_CreateJavaVM',
    'Java_net_kdt_pojavlaunch_utils_jre_JavaRunner_nativeLoadJVM',
    'Java_net_kdt_pojavlaunch_utils_jre_JavaRunner_nativeSetupExit',
    'libmobileglues.so',
    'getenv("LIBGL_ES")',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/ui/screens/DiscoverScreen.kt",
    'AsyncImage',
    'CheckCircle',
    'Text("Installed"',
    'state.curseForgeAvailable',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/engine/PackageCatalogManager.kt",
    'LTW-2025.7.16.apk',
    'ANGLE.Renderer.apk',
    'Zink.Mesa25.apk',
    'f36d7145da5188f83225aa97fc8422aa909308819e8fb2f4b97e1d253c48b1f5',
)
require_absent(
    "app/src/main/cpp/native_engine.cpp",
    'JLI_Launch',
    '/ "bin" / "java"',
)
require_contains(
    "app/src/main/java/net/kdt/pojavlaunch/ExitActivity.java",
    'showExitMessage(Context context, int code, boolean isSignal)',
    'Process.killProcess(Process.myPid())',
)
require_absent(
    "app/src/main/java/com/mclauncher/app/ui/LauncherViewModel.kt",
    "The bundled ${snapshot.settings.renderer.displayName} renderer is missing",
)
require_contains(
    "app/src/main/java/com/mclauncher/app/engine/CrashAnalyzer.kt",
    '"OpenGL renderer too old"',
    '"Sodium blocked the Android launch"',
    '"invalid session"',
)
require_contains(
    "app/src/main/java/com/mclauncher/app/ui/game/TouchControls.kt",
    "MenuTouchSurface",
    "GameInputBridge.cursorPosition(position.x, position.y)",
    "pointerState.grabbed && settings.movementJoystickEnabled",
)
require_absent(
    "app/src/main/java/com/mclauncher/app/engine/CrashAnalyzer.kt",
    '"authentication" in text',
)
require_contains(
    "vendor/patches/mojo-single-destroy.patch",
    '-    (*java_vm.vm)->DestroyJavaVM(java_vm.vm);',
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
    "touchLookMode",
    "virtualMouseEnabled",
    "themeMode",
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

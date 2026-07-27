#!/usr/bin/env python3
"""Fail a build when MCLauncher's embedded engine payload is incomplete."""
from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path

SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
JAVA_VERSIONS = (8, 17, 21, 25)
XZ_MAGIC = bytes((0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00))
ELF_MAGIC = b"\x7fELF"


def parse_abis(value: str) -> tuple[str, ...]:
    result = tuple(dict.fromkeys(item.strip() for item in value.split(",") if item.strip()))
    invalid = sorted(set(result) - set(SUPPORTED_ABIS))
    if not result:
        raise ValueError("At least one ABI is required")
    if invalid:
        raise ValueError(f"Unsupported ABI(s): {', '.join(invalid)}")
    return result


def is_nonempty(path: Path) -> bool:
    return path.is_file() and path.stat().st_size > 0


def has_magic(path: Path, magic: bytes) -> bool:
    if not is_nonempty(path):
        return False
    with path.open("rb") as stream:
        return stream.read(len(magic)) == magic


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def contains_bytes(path: Path, value: bytes) -> bool:
    return is_nonempty(path) and value in path.read_bytes()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("app/src/main/assets/bundled_engine"))
    parser.add_argument("--abis", default="arm64-v8a")
    args = parser.parse_args()
    abis = parse_abis(args.abis)
    root = args.root
    errors: list[str] = []

    version_file = root / "bundle-version.txt"
    manifest_file = root / "bundle-manifest.json"
    if not is_nonempty(version_file):
        errors.append("bundle-version.txt is missing or empty")
    if not is_nonempty(manifest_file):
        errors.append("bundle-manifest.json is missing or empty")
        manifest: dict = {}
    else:
        try:
            manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001 - this is a build verifier
            errors.append(f"bundle-manifest.json is invalid: {exc}")
            manifest = {}

    if manifest.get("schemaVersion") != 2:
        errors.append(f"Expected bundle manifest schemaVersion 2, got {manifest.get('schemaVersion')!r}")
    declared_abis = tuple(manifest.get("architectures") or ())
    if set(declared_abis) != set(abis):
        errors.append(f"Manifest ABIs {declared_abis!r} do not match requested ABIs {abis!r}")
    if not manifest.get("engine", {}).get("commit"):
        errors.append("Pinned engine commit is missing from manifest")

    declared_files = manifest.get("files")
    if not isinstance(declared_files, dict) or not declared_files:
        errors.append("payload file digest index is missing")
        declared_files = {}
    actual_files = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file() and path.name != "bundle-manifest.json"
    }
    declared_paths = set(declared_files)
    for relative in sorted(declared_paths - actual_files):
        errors.append(f"Manifest payload file is missing: {relative}")
    for relative in sorted(actual_files - declared_paths):
        errors.append(f"Unexpected payload file is not in manifest: {relative}")
    for relative in sorted(actual_files & declared_paths):
        expected = str(declared_files[relative]).lower()
        if len(expected) != 64 or sha256(root / relative) != expected:
            errors.append(f"Payload file digest mismatch: {relative}")

    substitutions = root / "common/jars/substitutions.json"
    if not is_nonempty(substitutions):
        errors.append("patched LWJGL substitutions.json is missing")
    else:
        try:
            json.loads(substitutions.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            errors.append(f"substitutions.json is invalid: {exc}")

    runtime_certificate = root / "common/trust/mojo-runtime-signing-cert.pem"
    if not is_nonempty(runtime_certificate):
        errors.append("runtime signing certificate is missing")

    patched = manifest.get("patchedLibraries") or []
    patched_natives = manifest.get("patchedNativeLibraries") or []
    artifact_entries = [entry for entry in patched if entry.get("kind") == "artifact"]
    if not any(str(entry.get("coordinate", "")).startswith("org.lwjgl:lwjgl:") for entry in artifact_entries):
        errors.append("patched LWJGL core artifact is missing")
    if not any("lwjgl-glfw" in Path(str(entry.get("path", ""))).name for entry in artifact_entries):
        errors.append("patched LWJGL GLFW artifact is missing")

    for entry in patched:
        relative = str(entry.get("path") or "")
        target = root / "common/jars" / relative
        if not relative or not is_nonempty(target):
            errors.append(f"Patched library file is missing: {relative or '<unknown>'}")
            continue
        if target.suffix.lower() == ".jar":
            try:
                with zipfile.ZipFile(target) as archive:
                    if archive.testzip() is not None:
                        errors.append(f"Corrupt patched library JAR: {relative}")
            except zipfile.BadZipFile:
                errors.append(f"Invalid patched library JAR: {relative}")

    support_jars = manifest.get("supportJars") or []
    if not support_jars:
        errors.append("support JAR records are missing")
    for relative in support_jars:
        target = root / "common/jars/support" / str(relative)
        if not is_nonempty(target):
            errors.append(f"Support JAR is missing: {relative}")
    if not any("cacio" in Path(str(relative)).name.lower() for relative in support_jars):
        errors.append("Cacio/Caciocavallo support JAR is missing")

    runtime_records = {int(entry.get("java", -1)): entry for entry in (manifest.get("runtimes") or [])}
    for major in JAVA_VERSIONS:
        record = runtime_records.get(major)
        if record is None:
            errors.append(f"Java {major} runtime record is missing")
            continue
        if major >= 17 and record.get("signatureVerified") is not True:
            errors.append(f"Java {major} runtime signature was not verified")
        if major >= 17 and not record.get("signatureCertificateSha256"):
            errors.append(f"Java {major} runtime signing-certificate digest is missing")
        if (
            major >= 17
            and is_nonempty(runtime_certificate)
            and record.get("signatureCertificateSha256") != sha256(runtime_certificate)
        ):
            errors.append(f"Java {major} runtime signing-certificate digest does not match")
        archives = record.get("archives") or {}
        for abi in abis:
            relative_paths = archives.get(abi) or []
            if len(relative_paths) < 2:
                errors.append(f"Java {major}/{abi}: universal and platform archives are required")
                continue
            for relative in relative_paths:
                target = root / str(relative)
                if not has_magic(target, XZ_MAGIC):
                    errors.append(f"Java {major}/{abi}: missing or invalid XZ archive {relative}")
            if len(relative_paths) >= 2:
                universal = root / str(relative_paths[0])
                platform = root / str(relative_paths[1])
                if is_nonempty(universal) and record.get("universalSha256") != sha256(universal):
                    errors.append(f"Java {major}/{abi}: universal archive digest does not match")
                expected_platform = (record.get("platformSha256") or {}).get(abi)
                if is_nonempty(platform) and expected_platform != sha256(platform):
                    errors.append(f"Java {major}/{abi}: platform archive digest does not match")

    renderer_records = manifest.get("rendererPacks") or []
    native_records = manifest.get("nativeLibraries") or {}
    jna_dispatch = manifest.get("jnaDispatch") or {}
    if jna_dispatch.get("version") != "5.17.0":
        errors.append("Pinned JNA 5.17.0 Android compatibility record is missing")
    if len(str(jna_dispatch.get("sourceSha256") or "")) != 64:
        errors.append("Pinned JNA Android source digest is missing")
    for abi in abis:
        native_dir = root / abi / "natives"
        names = set(native_records.get(abi) or [])
        for required in ("libpojavexec.so", "libpojavexec_awt.so", "libglfw.so"):
            target = native_dir / required
            if required not in names or not has_magic(target, ELF_MAGIC):
                errors.append(f"{abi}: required ELF library is missing or invalid: {required}")

        jna6 = native_dir / "jna-6/libjnidispatch.so"
        jna7 = native_dir / "jna-7/libjnidispatch.so"
        if (native_dir / "libjnidispatch.so").exists():
            errors.append(f"{abi}: ambiguous root-level libjnidispatch.so must not be packaged")
        for relative, target in (
            ("jna-6/libjnidispatch.so", jna6),
            ("jna-7/libjnidispatch.so", jna7),
        ):
            if relative not in names or not has_magic(target, ELF_MAGIC):
                errors.append(f"{abi}: JNA compatibility ELF is missing or invalid: {relative}")
        if has_magic(jna7, ELF_MAGIC) and not contains_bytes(jna7, b"7.0.4"):
            errors.append(f"{abi}: JNA 7 native does not report ABI version 7.0.4")
        jna_record = (jna_dispatch.get("libraries") or {}).get(abi) or {}
        if is_nonempty(jna6) and jna_record.get("jna6Sha256") != sha256(jna6):
            errors.append(f"{abi}: JNA 6 native digest does not match")
        if is_nonempty(jna7) and jna_record.get("jna7Sha256") != sha256(jna7):
            errors.append(f"{abi}: JNA 7 native digest does not match")

        mobile_records = [
            entry for entry in renderer_records
            if entry.get("abi") == abi and entry.get("id") == "mobileglues"
        ]
        if len(mobile_records) != 1:
            errors.append(f"{abi}: exactly one MobileGlues renderer pack is required")
        else:
            mobile_pack = root / abi / "renderers/mobileglues"
            mobile_manifest_path = mobile_pack / "mclauncher-graphics.json"
            try:
                mobile_manifest = json.loads(
                    mobile_manifest_path.read_text(encoding="utf-8")
                )
            except Exception as exc:  # noqa: BLE001
                errors.append(f"{abi}: invalid MobileGlues manifest: {exc}")
                mobile_manifest = {}
            if mobile_manifest.get("renderer") != "MOBILE_GLUES":
                errors.append(f"{abi}: MobileGlues renderer enum is incorrect")
            if mobile_manifest.get("pojavRenderer") != "mobileglues":
                errors.append(f"{abi}: MobileGlues renderer token is incorrect")
            if (mobile_manifest.get("environment") or {}).get("MG_DIR_PATH") != "${cache}/mobileglues":
                errors.append(f"{abi}: MobileGlues cache directory is not configured")
            mobile_library = mobile_pack / "libmobileglues.so"
            if not has_magic(mobile_library, ELF_MAGIC):
                errors.append(f"{abi}: MobileGlues ELF is missing or invalid")
            for symbol in (b"glGenSamplers", b"glBindSampler"):
                if has_magic(mobile_library, ELF_MAGIC) and not contains_bytes(mobile_library, symbol):
                    errors.append(f"{abi}: MobileGlues is missing required symbol {symbol.decode()}")

        default_renderers = [
            entry for entry in renderer_records
            if entry.get("abi") == abi and entry.get("id") in {"mobileglues", "gl4es", "openltw"}
        ]
        if not default_renderers:
            errors.append(f"{abi}: no default MobileGlues/GL4ES/OpenLTW renderer pack is present")
        for renderer in default_renderers:
            pack = root / abi / "renderers" / str(renderer.get("id"))
            manifest_path = pack / "mclauncher-graphics.json"
            if not is_nonempty(manifest_path):
                errors.append(f"{abi}: renderer manifest missing for {renderer.get('id')}")
            for filename in renderer.get("files") or []:
                if not has_magic(pack / str(filename), ELF_MAGIC):
                    errors.append(f"{abi}: renderer ELF missing or invalid: {renderer.get('id')}/{filename}")

        abi_classifier_records = [entry for entry in patched_natives if entry.get("abi") == abi]
        if not abi_classifier_records:
            errors.append(f"{abi}: patched LWJGL native classifier records are missing")
        classifier_paths = {str(entry.get("classifierJar") or "") for entry in abi_classifier_records}
        for relative in classifier_paths:
            target = root / "common/jars" / relative
            if not is_nonempty(target):
                errors.append(f"{abi}: native classifier JAR is missing: {relative}")
                continue
            try:
                with zipfile.ZipFile(target) as archive:
                    if not any(info.filename.lower().endswith(".so") for info in archive.infolist()):
                        errors.append(f"{abi}: classifier JAR contains no native .so: {relative}")
            except zipfile.BadZipFile:
                errors.append(f"{abi}: invalid native classifier JAR: {relative}")

    license_paths = [root / str(relative) for relative in (manifest.get("licenses") or [])]
    if not any("lgpl" in path.name.lower() and is_nonempty(path) for path in license_paths):
        errors.append("LGPL license notice is missing")
    if not any("glfw" in path.name.lower() and is_nonempty(path) for path in license_paths):
        errors.append("GLFW license notice is missing")
    if not any("mobileglues" in path.name.lower() and is_nonempty(path) for path in license_paths):
        errors.append("MobileGlues license notice is missing")
    if not any("jna" in path.name.lower() and is_nonempty(path) for path in license_paths):
        errors.append("JNA license notice is missing")

    if errors:
        print("Bundled-engine verification FAILED:")
        for error in errors:
            print(f" - {error}")
        return 1

    print(f"Bundled-engine verification passed for {', '.join(abis)}")
    print(f"Bundle: {version_file.read_text(encoding='utf-8').strip()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

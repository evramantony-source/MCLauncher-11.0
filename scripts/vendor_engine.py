#!/usr/bin/env python3
"""Build MCLauncher's self-contained Android engine asset tree.

This script runs in GitHub Actions *before* MCLauncher is compiled. It consumes a
pinned, source-built MojoLauncher no-runtime APK only as a convenient container
for the compiled LGPL launch engine/native renderer outputs. The installed
MCLauncher APK never downloads, installs, or opens another launcher APK.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Iterable

SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
RUNTIME_VERSIONS = (8, 17, 21, 25)
USER_AGENT = "MCLauncher-engine-vendor/11.0-alpha01"


def digest(path: Path, algorithm: str) -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sha1(path: Path) -> str:
    return digest(path, "sha1")


def sha256(path: Path) -> str:
    return digest(path, "sha256")


def payload_file_index(root: Path) -> dict[str, str]:
    return {
        path.relative_to(root).as_posix(): sha256(path)
        for path in sorted(root.rglob("*"))
        if path.is_file() and path.name != "bundle-manifest.json"
    }


def download(
    url: str,
    destination: Path,
    *,
    expected_sha1: str | None = None,
    expected_sha256: str | None = None,
    attempts: int = 4,
) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)

    def valid(path: Path) -> bool:
        if not path.is_file() or path.stat().st_size <= 0:
            return False
        if expected_sha1 and sha1(path).lower() != expected_sha1.lower():
            return False
        if expected_sha256 and sha256(path).lower() != expected_sha256.lower():
            return False
        return True

    if valid(destination):
        return

    temporary = destination.with_suffix(destination.suffix + ".part")
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        temporary.unlink(missing_ok=True)
        try:
            request = urllib.request.Request(
                url,
                headers={
                    "User-Agent": USER_AGENT,
                    "Accept": "application/octet-stream,*/*;q=0.8",
                },
            )
            with urllib.request.urlopen(request, timeout=240) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output, length=1024 * 1024)
            if not valid(temporary):
                raise RuntimeError(f"Downloaded file failed integrity validation: {url}")
            temporary.replace(destination)
            return
        except (OSError, urllib.error.URLError, urllib.error.HTTPError, RuntimeError) as exc:
            last_error = exc
            temporary.unlink(missing_ok=True)
            if attempt < attempts:
                time.sleep(min(10, attempt * 2))
    raise RuntimeError(f"Could not download {url}: {last_error}")


def parse_abis(value: str) -> tuple[str, ...]:
    requested = tuple(dict.fromkeys(part.strip() for part in value.split(",") if part.strip()))
    if not requested:
        raise ValueError("At least one ABI is required")
    invalid = sorted(set(requested) - set(SUPPORTED_ABIS))
    if invalid:
        raise ValueError(f"Unsupported ABI(s): {', '.join(invalid)}")
    return requested


def safe_relative_path(value: str, *, label: str) -> Path:
    path = Path(value)
    if not value or path.is_absolute() or ".." in path.parts:
        raise RuntimeError(f"Unsafe {label}: {value!r}")
    return path


def parse_signature_bundle(document: str) -> dict[str, bytes]:
    signatures: dict[str, bytes] = {}
    for line_number, raw_line in enumerate(document.splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        try:
            name, encoded = line.split(":", 1)
        except ValueError as exc:
            raise RuntimeError(f"Invalid runtime signature line {line_number}") from exc
        safe_relative_path(name, label="runtime signature filename")
        try:
            signature = base64.b64decode(encoded, validate=True)
        except ValueError as exc:
            raise RuntimeError(f"Invalid base64 runtime signature for {name}") from exc
        if len(signature) != 512:
            raise RuntimeError(f"Runtime signature for {name} is not RSA-4096")
        if name in signatures:
            raise RuntimeError(f"Duplicate runtime signature for {name}")
        signatures[name] = signature
    if not signatures:
        raise RuntimeError("Runtime signature bundle is empty")
    return signatures


def verify_runtime_signatures(
    certificate: Path,
    signatures: dict[str, bytes],
    archives: dict[str, Path],
) -> None:
    if not certificate.is_file():
        raise RuntimeError(f"Runtime signing certificate is missing: {certificate}")
    missing = sorted(set(archives) - set(signatures))
    if missing:
        raise RuntimeError(f"Runtime signature bundle is missing: {', '.join(missing)}")

    with tempfile.TemporaryDirectory(prefix="mclauncher-runtime-signatures-") as temporary:
        temporary_root = Path(temporary)
        public_key = temporary_root / "runtime-public-key.pem"
        with public_key.open("wb") as output:
            result = subprocess.run(
                ["openssl", "x509", "-in", str(certificate), "-pubkey", "-noout"],
                stdout=output,
                stderr=subprocess.PIPE,
                check=False,
            )
        if result.returncode != 0:
            raise RuntimeError(
                f"Could not read runtime signing certificate: {result.stderr.decode(errors='replace').strip()}"
            )

        for name, archive in archives.items():
            signature_file = temporary_root / f"{hashlib.sha256(name.encode()).hexdigest()}.sig"
            signature_file.write_bytes(signatures[name])
            result = subprocess.run(
                [
                    "openssl",
                    "dgst",
                    "-sha256",
                    "-verify",
                    str(public_key),
                    "-signature",
                    str(signature_file),
                    str(archive),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            if result.returncode != 0:
                detail = result.stdout.decode(errors="replace").strip()
                raise RuntimeError(f"Runtime signature verification failed for {name}: {detail}")


def copy_apk_native_libraries(apk: Path, output: Path, abis: Iterable[str]) -> dict[str, list[str]]:
    selected = tuple(abis)
    copied: dict[str, list[str]] = {abi: [] for abi in selected}
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            parts = Path(info.filename).parts
            if (
                info.is_dir()
                or len(parts) != 3
                or parts[0] != "lib"
                or parts[1] not in copied
                or not parts[2].endswith(".so")
            ):
                continue
            abi, filename = parts[1], parts[2]
            target = output / abi / "natives" / filename
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, target.open("wb") as destination:
                shutil.copyfileobj(source, destination)
            copied[abi].append(filename)

    for abi, names in copied.items():
        required = {"libpojavexec.so", "libpojavexec_awt.so", "libglfw.so"}
        missing = required - set(names)
        if missing:
            raise RuntimeError(f"Pinned engine build lacks {sorted(missing)} for {abi}")
    return {abi: sorted(set(names)) for abi, names in copied.items()}


def write_graphics_packs(output: Path, copied: dict[str, list[str]]) -> list[dict]:
    # Renderer dependencies remain available in <abi>/natives and are included in
    # LD_LIBRARY_PATH. Each pack copies only the selected renderer entry library.
    presets = (
        ("openltw", "OPEN_LTW", "opengles3_ltw", ("libltw",)),
        ("gl4es", "GL4ES", "opengles2", ("libgl4es",)),
        ("zink", "ZINK", "vulkan_zink", ("libegl_mesa", "libosmesa", "zink")),
    )
    records: list[dict] = []
    for abi, names in copied.items():
        native_root = output / abi / "natives"
        for renderer_id, renderer_enum, pojav_renderer, tokens in presets:
            selected = sorted({name for name in names if any(token in name.lower() for token in tokens)})
            if not selected:
                continue
            pack = output / abi / "renderers" / renderer_id
            pack.mkdir(parents=True, exist_ok=True)
            for name in selected:
                shutil.copy2(native_root / name, pack / name)
            manifest = {
                "schemaVersion": 1,
                "id": f"bundled-{renderer_id}",
                "name": f"Bundled {renderer_id}",
                "version": "pinned-source-build",
                "kind": "renderer",
                "architecture": abi,
                "renderer": renderer_enum,
                "driver": None,
                "pojavRenderer": pojav_renderer,
                "preload": selected,
                "environment": {},
                "files": selected,
                "sourceName": "MCLauncher bundled engine",
                "sourceProject": "MojoLauncher/MojoLauncher",
                "license": "LGPL-3.0 and component licenses",
                "importedAtEpochMs": 0,
            }
            (pack / "mclauncher-graphics.json").write_text(
                json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
            )
            records.append({"abi": abi, "id": renderer_id, "files": selected})

        if not any(record["abi"] == abi and record["id"] in {"gl4es", "openltw"} for record in records):
            raise RuntimeError(f"No usable default GL4ES/OpenLTW renderer was produced for {abi}")
    return records


def classifier_matches_abi(classifier: str, abi: str) -> bool:
    value = classifier.lower().replace("_", "-")
    if abi == "arm64-v8a":
        return "arm64" in value or "aarch64" in value
    if abi == "armeabi-v7a":
        return "arm32" in value or "aarch32" in value or "armeabi" in value
    if abi == "x86_64":
        if any(token in value for token in ("x86-64", "x86_64", "amd64")):
            return True
        return value.endswith("natives-linux") and not any(token in value for token in ("arm", "x86"))
    return False


def inspect_classifier_natives(
    native_jar: Path,
    classifier: str,
    relative_path: str,
    abis: Iterable[str],
) -> list[dict]:
    records: list[dict] = []
    matching_abis = [abi for abi in abis if classifier_matches_abi(classifier, abi)]
    if not matching_abis:
        return records
    with zipfile.ZipFile(native_jar) as archive:
        libraries = sorted(
            {
                Path(info.filename).name
                for info in archive.infolist()
                if not info.is_dir() and info.filename.lower().endswith(".so")
            }
        )
    for abi in matching_abis:
        for filename in libraries:
            records.append(
                {
                    "abi": abi,
                    "classifier": classifier,
                    "library": filename,
                    "classifierJar": relative_path,
                    "classifierJarSha256": sha256(native_jar),
                }
            )
    return records


def vendor_patched_libraries(
    mojo_root: Path,
    output: Path,
    cache: Path,
    abis: Iterable[str],
) -> tuple[list[dict], list[dict]]:
    source = mojo_root / "app_pojavlauncher/src/main/assets/substitutions.json"
    if not source.is_file():
        raise RuntimeError(f"Pinned Mojo source is missing {source}")
    document = json.loads(source.read_text(encoding="utf-8"))
    records: list[dict] = []
    native_records: list[dict] = []
    seen: set[tuple[str, str]] = set()

    def vendor_descriptor(
        coordinate: str,
        kind: str,
        descriptor: dict,
        classifier: str | None = None,
    ) -> None:
        url = descriptor.get("url")
        relative = descriptor.get("path")
        expected = descriptor.get("sha1")
        if not url or not relative:
            return
        relative_path = safe_relative_path(relative, label="patched-library path")
        key = (relative, expected or "")
        if key in seen:
            return
        seen.add(key)
        cached = cache / "patched-libraries" / relative_path
        download(url, cached, expected_sha1=expected)
        target = output / "common/jars" / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(cached, target)
        record = {
            "coordinate": coordinate,
            "kind": kind,
            "classifier": classifier,
            "path": relative,
            "sha1": sha1(target),
            "sha256": sha256(target),
            "source": url,
        }
        records.append(record)
        if classifier:
            native_records.extend(inspect_classifier_natives(target, classifier, relative, abis))

    for coordinate, replacement in document.get("libraries", {}).items():
        if not coordinate.startswith(("org.lwjgl:", "org.lwjgl.lwjgl:")):
            continue
        downloads = replacement.get("downloads") or {}
        vendor_descriptor(coordinate, "artifact", downloads.get("artifact") or {})
        for classifier, descriptor in (downloads.get("classifiers") or {}).items():
            vendor_descriptor(coordinate, "classifier", descriptor or {}, classifier)

    if not records:
        raise RuntimeError("No patched LWJGL artifacts were discovered in substitutions.json")
    for abi in abis:
        if not any(record["abi"] == abi for record in native_records):
            raise RuntimeError(f"No patched LWJGL native classifier was discovered for {abi}")
    shutil.copy2(source, output / "common/jars/substitutions.json")
    return records, native_records


def vendor_support_jars(mojo_apk: Path, output: Path) -> list[str]:
    copied: list[str] = []
    prefix = Path("assets/components")
    with zipfile.ZipFile(mojo_apk) as archive:
        for info in archive.infolist():
            path = Path(info.filename)
            if info.is_dir() or len(path.parts) < 3 or path.parts[:2] != prefix.parts:
                continue
            if path.suffix.lower() != ".jar":
                continue
            relative = safe_relative_path(
                str(Path(*path.parts[2:])),
                label="support-JAR archive path",
            )
            target = output / "common/jars/support" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, target.open("wb") as destination:
                shutil.copyfileobj(source, destination)
            copied.append(str(relative))

    names = [Path(item).name.lower() for item in copied]
    if not any("cacio" in name for name in names):
        raise RuntimeError("Pinned engine build did not package Cacio/Caciocavallo support JARs")
    return sorted(set(copied))


def ensure_xz(path: Path) -> None:
    with path.open("rb") as stream:
        magic = stream.read(6)
    if magic != bytes((0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)):
        raise RuntimeError(f"Expected an XZ archive: {path}")


def copy_runtime_archive(source: Path, target: Path) -> None:
    ensure_xz(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def vendor_java8_zip(
    source: dict,
    output: Path,
    cache: Path,
    abis: Iterable[str],
) -> dict:
    url = source["url"]
    runtime_zip = cache / "runtimes/jre8-pojav.zip"
    download(url, runtime_zip, expected_sha256=source.get("sha256"))
    architecture_tokens = {
        "arm64-v8a": ("arm64", "aarch64"),
        "armeabi-v7a": ("arm32", "aarch32", "armeabi", "bin-arm."),
        "x86_64": ("x86_64", "x86-64", "amd64"),
    }
    archives: dict[str, list[str]] = {}
    platform_digests: dict[str, str] = {}
    with zipfile.ZipFile(runtime_zip) as archive:
        candidates = [
            info
            for info in archive.infolist()
            if not info.is_dir() and info.filename.lower().endswith(".tar.xz")
        ]
        universal = next((item for item in candidates if "universal" in item.filename.lower()), None)
        if universal is None:
            raise RuntimeError("Java 8 package has no universal.tar.xz")
        common_target = output / "common/runtimes/java-8/00-universal.tar.xz"
        common_target.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(universal) as input_stream, common_target.open("wb") as destination:
            shutil.copyfileobj(input_stream, destination)
        ensure_xz(common_target)

        for abi in abis:
            tokens = architecture_tokens[abi]
            platform = next(
                (item for item in candidates if any(token in item.filename.lower() for token in tokens)),
                None,
            )
            if platform is None:
                raise RuntimeError(f"Java 8 package has no platform archive for {abi}")
            target = output / abi / "runtimes/java-8/01-platform.tar.xz"
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(platform) as input_stream, target.open("wb") as destination:
                shutil.copyfileobj(input_stream, destination)
            ensure_xz(target)
            archives[abi] = [
                str(common_target.relative_to(output)),
                str(target.relative_to(output)),
            ]
            platform_digests[abi] = sha256(target)
    return {
        "java": 8,
        "source": url,
        "sourceSha256": sha256(runtime_zip),
        "universalSha256": sha256(common_target),
        "platformSha256": platform_digests,
        "archives": archives,
    }


def vendor_split_runtime(
    major: int,
    source: dict,
    output: Path,
    cache: Path,
    abis: Iterable[str],
    certificate: Path,
) -> dict:
    base_url = source["baseUrl"].rstrip("/")
    version_file = cache / f"runtimes/java-{major}/version"
    download(f"{base_url}/version", version_file)

    common_cached = cache / f"runtimes/java-{major}/universal.tar.xz"
    download(f"{base_url}/universal.tar.xz", common_cached)
    platform_names = {
        "arm64-v8a": "bin-arm64.tar.xz",
        "armeabi-v7a": "bin-arm.tar.xz",
        "x86_64": "bin-x86_64.tar.xz",
    }
    downloaded_archives: dict[str, Path] = {"universal.tar.xz": common_cached}
    platform_cache: dict[str, Path] = {}
    for abi in abis:
        filename = platform_names[abi]
        cached = cache / f"runtimes/java-{major}/{filename}"
        download(f"{base_url}/{filename}", cached)
        downloaded_archives[filename] = cached
        platform_cache[abi] = cached

    signatures = parse_signature_bundle(version_file.read_text(encoding="utf-8"))
    verify_runtime_signatures(certificate, signatures, downloaded_archives)

    common_target = output / f"common/runtimes/java-{major}/00-universal.tar.xz"
    copy_runtime_archive(common_cached, common_target)

    archives: dict[str, list[str]] = {}
    platform_digests: dict[str, str] = {}
    for abi in abis:
        cached = platform_cache[abi]
        target = output / abi / f"runtimes/java-{major}/01-platform.tar.xz"
        copy_runtime_archive(cached, target)
        archives[abi] = [str(common_target.relative_to(output)), str(target.relative_to(output))]
        platform_digests[abi] = sha256(cached)

    return {
        "java": major,
        "source": base_url,
        "versionManifestSha256": sha256(version_file),
        "signatureCertificateSha256": sha256(certificate),
        "signatureVerified": True,
        "universalSha256": sha256(common_cached),
        "platformSha256": platform_digests,
        "archives": archives,
    }


def vendor_runtimes(
    output: Path,
    cache: Path,
    runtime_lock: dict,
    abis: Iterable[str],
    certificate: Path,
) -> list[dict]:
    records: list[dict] = []
    sources = runtime_lock.get("sources") or {}
    for major in RUNTIME_VERSIONS:
        source = sources.get(str(major))
        if not source:
            raise RuntimeError(f"Runtime source for Java {major} is not pinned")
        kind = source.get("kind")
        if kind == "zip":
            if major != 8:
                raise RuntimeError(f"ZIP runtime source is only supported for Java 8, not Java {major}")
            records.append(vendor_java8_zip(source, output, cache, abis))
        elif kind == "split":
            records.append(vendor_split_runtime(major, source, output, cache, abis, certificate))
        else:
            raise RuntimeError(f"Unsupported runtime source kind for Java {major}: {kind}")
    return records


def vendor_licenses(mojo_root: Path, output: Path) -> list[str]:
    license_dir = output / "common/licenses"
    license_dir.mkdir(parents=True, exist_ok=True)
    copied: list[str] = []
    candidates = (
        (mojo_root / "LICENSE", "MojoLauncher-LGPL-3.0.txt"),
        (mojo_root / "glfw/LICENSE.md", "dnbootstrap-GLFW-license.md"),
        (mojo_root / "glfw/LICENSE", "dnbootstrap-GLFW-license.txt"),
    )
    for source, name in candidates:
        if not source.is_file():
            continue
        target = license_dir / name
        shutil.copy2(source, target)
        copied.append(str(target.relative_to(output)))
    if not (license_dir / "MojoLauncher-LGPL-3.0.txt").is_file():
        raise RuntimeError("Pinned Mojo source did not contain its LGPL license")

    notice = license_dir / "MCLauncher-engine-sources.txt"
    notice.write_text(
        "Engine source: https://github.com/MojoLauncher/MojoLauncher\n"
        "Runtime source: https://github.com/MojoLauncher/android-openjdk-build-multiarch\n"
        "Patched LWJGL source records: ../jars/substitutions.json and bundle-manifest.json\n",
        encoding="utf-8",
    )
    copied.append(str(notice.relative_to(output)))
    return copied


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mojo-root", type=Path, required=True)
    parser.add_argument("--mojo-apk", type=Path, required=True)
    parser.add_argument("--abis", default="arm64-v8a")
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/bundled_engine"))
    parser.add_argument("--cache", type=Path, default=Path(".engine-cache"))
    parser.add_argument("--lock", type=Path, default=Path("vendor/engine-lock.json"))
    args = parser.parse_args()

    abis = parse_abis(args.abis)
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    output = args.output.resolve()
    if output == Path(output.anchor) or len(output.parts) < 3:
        raise RuntimeError(f"Refusing unsafe output directory: {output}")
    if output.exists():
        shutil.rmtree(output)
    (output / "common/jars/support").mkdir(parents=True, exist_ok=True)
    args.cache.mkdir(parents=True, exist_ok=True)

    natives = copy_apk_native_libraries(args.mojo_apk, output, abis)
    renderers = write_graphics_packs(output, natives)
    patched, patched_natives = vendor_patched_libraries(args.mojo_root, output, args.cache, abis)
    support = vendor_support_jars(args.mojo_apk, output)
    runtime_certificate = (
        args.mojo_root / "app_pojavlauncher/src/main/assets/cert.pem"
    ).resolve()
    runtimes = vendor_runtimes(
        output,
        args.cache,
        lock["runtimes"],
        abis,
        runtime_certificate,
    )
    trust_directory = output / "common/trust"
    trust_directory.mkdir(parents=True, exist_ok=True)
    shutil.copy2(runtime_certificate, trust_directory / "mojo-runtime-signing-cert.pem")
    licenses = vendor_licenses(args.mojo_root, output)

    version = f"mojo-{lock['engine']['commit'][:12]}-mclauncher-11.0-alpha01"
    (output / "bundle-version.txt").write_text(version + "\n", encoding="utf-8")
    manifest = {
        "schemaVersion": 2,
        "bundleVersion": version,
        "architectures": list(abis),
        "engine": lock["engine"],
        "nativeLibraries": natives,
        "rendererPacks": renderers,
        "patchedLibraries": patched,
        "patchedNativeLibraries": patched_natives,
        "supportJars": support,
        "runtimes": runtimes,
        "licenses": licenses,
    }
    manifest["files"] = payload_file_index(output)
    (output / "bundle-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Created bundled engine {version} for {', '.join(abis)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

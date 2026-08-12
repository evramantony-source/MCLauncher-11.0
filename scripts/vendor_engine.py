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
import io
import json
import shutil
import struct
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
USER_AGENT = "MCLauncher-engine-vendor/11.0-alpha28"


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


def bundle_version(lock: dict) -> str:
    lock_digest = hashlib.sha256(
        json.dumps(lock, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()[:12]
    return (
        f"mojo-{lock['engine']['commit'][:12]}-"
        f"lock-{lock_digest}-mclauncher-11.0-alpha28"
    )


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



def _read_u2(data: bytes | bytearray, offset: int) -> int:
    return struct.unpack_from(">H", data, offset)[0]


def _read_u4(data: bytes | bytearray, offset: int) -> int:
    return struct.unpack_from(">I", data, offset)[0]


def _parse_class_constant_pool(
    data: bytes,
    count: int,
    offset: int,
) -> tuple[dict[int, str], int]:
    utf8: dict[int, str] = {}
    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = _read_u2(data, offset)
            offset += 2
            utf8[index] = data[offset:offset + length].decode("utf-8")
            offset += length
        elif tag in (3, 4):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag == 15:
            offset += 3
        else:
            raise RuntimeError(f"Unsupported class constant-pool tag {tag}")
        index += 1
    return utf8, offset


def _skip_class_attributes(data: bytes | bytearray, offset: int, count: int) -> int:
    for _ in range(count):
        offset += 2
        length = _read_u4(data, offset)
        offset += 4 + length
    return offset


def patch_sdl_video_class(data: bytes) -> bytes:
    """Redirect SDL's one strict identity probe to LWJGL's own GL provider.

    Minecraft 26.3 Snapshot 6 compares the raw pointer returned by LWJGL's
    OpenGL provider with SDL_GL_GetProcAddress("glGetError"). Equivalent
    OpenLTW entry points are rejected if the pointers differ. The generated
    LWJGL method is branch-free and ten bytes long, so replace it with an
    equally sized verified call to MCLauncherSDLCompat and leave every public
    signature, stack-map and native SDL function unchanged.
    """
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise RuntimeError("SDLVideo.class is not a Java class file")
    old_count = _read_u2(data, 8)
    utf8, constant_pool_end = _parse_class_constant_pool(data, old_count, 10)

    additions = bytearray()
    helper_name = old_count
    raw_name = b"org/lwjgl/sdl/MCLauncherSDLCompat"
    additions += b"\x01" + struct.pack(">H", len(raw_name)) + raw_name
    helper_class = old_count + 1
    additions += b"\x07" + struct.pack(">H", helper_name)
    method_name = old_count + 2
    raw_method = b"getProcAddress"
    additions += b"\x01" + struct.pack(">H", len(raw_method)) + raw_method
    descriptor = old_count + 3
    additions += b"\x01\x00\x04(J)J"
    name_and_type = old_count + 4
    additions += b"\x0c" + struct.pack(">HH", method_name, descriptor)
    method_ref = old_count + 5
    additions += b"\x0a" + struct.pack(">HH", helper_class, name_and_type)

    patched = bytearray(data[:8])
    patched += struct.pack(">H", old_count + 6)
    patched += data[10:constant_pool_end]
    patched += additions
    patched += data[constant_pool_end:]

    offset = constant_pool_end + len(additions)
    offset += 6  # access_flags, this_class, super_class
    interface_count = _read_u2(patched, offset)
    offset += 2 + interface_count * 2
    field_count = _read_u2(patched, offset)
    offset += 2
    for _ in range(field_count):
        offset += 6
        attribute_count = _read_u2(patched, offset)
        offset += 2
        offset = _skip_class_attributes(patched, offset, attribute_count)

    method_count = _read_u2(patched, offset)
    offset += 2
    found = False
    for _ in range(method_count):
        name_index = _read_u2(patched, offset + 2)
        descriptor_index = _read_u2(patched, offset + 4)
        attribute_count = _read_u2(patched, offset + 6)
        offset += 8
        for _ in range(attribute_count):
            attribute_name_index = _read_u2(patched, offset)
            attribute_length = _read_u4(patched, offset + 2)
            body = offset + 6
            if (
                utf8.get(name_index) == "nSDL_GL_GetProcAddress"
                and utf8.get(descriptor_index) == "(J)J"
                and utf8.get(attribute_name_index) == "Code"
            ):
                code_length = _read_u4(patched, body + 4)
                code_start = body + 8
                original = bytes(patched[code_start:code_start + code_length])
                if code_length != 10 or original[0] != 0xB2 or original[-1] != 0xAD:
                    raise RuntimeError(
                        "Unexpected LWJGL SDL_GL_GetProcAddress bytecode: "
                        + original.hex()
                    )
                # lload_0; invokestatic helper; lstore_2; lload_2;
                # nop; nop; nop; lreturn -- exactly the original ten bytes.
                patched[code_start:code_start + code_length] = bytes((
                    0x1E,
                    0xB8, (method_ref >> 8) & 0xFF, method_ref & 0xFF,
                    0x41, 0x20,
                    0x00, 0x00, 0x00,
                    0xAD,
                ))
                found = True
            offset = body + attribute_length
    if not found:
        raise RuntimeError("nSDL_GL_GetProcAddress(J)J was not found")
    return bytes(patched)


def patch_sdl_proc_identity_jar(
    sdl_jar: Path,
    core_jar: Path,
    opengl_jar: Path,
    source: Path,
) -> str:
    if not source.is_file():
        raise RuntimeError(f"SDL/OpenGL compatibility source is missing: {source}")
    compiler = shutil.which("javac")
    if not compiler:
        raise RuntimeError("javac is required to build the SDL/OpenGL compatibility bridge")

    with tempfile.TemporaryDirectory(prefix="mclauncher-sdl-proc-") as temporary:
        root = Path(temporary)
        classes = root / "classes"
        classes.mkdir()
        result = subprocess.run(
            [
                compiler,
                "--release", "8",
                "-classpath",
                str(core_jar) + ":" + str(opengl_jar) + ":" + str(sdl_jar),
                "-d", str(classes),
                str(source),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(
                "Could not compile SDL/OpenGL compatibility bridge:\n"
                + result.stdout.strip()
            )
        helper = classes / "org/lwjgl/sdl/MCLauncherSDLCompat.class"
        if not helper.is_file():
            raise RuntimeError("javac did not produce MCLauncherSDLCompat.class")

        temporary_jar = root / sdl_jar.name
        patched_sdl_video = False
        with zipfile.ZipFile(sdl_jar) as input_jar, zipfile.ZipFile(temporary_jar, "w") as output_jar:
            for info in input_jar.infolist():
                payload = input_jar.read(info.filename)
                if info.filename == "org/lwjgl/sdl/SDLVideo.class":
                    payload = patch_sdl_video_class(payload)
                    patched_sdl_video = True
                output_jar.writestr(info, payload)
            helper_info = zipfile.ZipInfo(
                "org/lwjgl/sdl/MCLauncherSDLCompat.class",
                date_time=(1980, 1, 1, 0, 0, 0),
            )
            helper_info.compress_type = zipfile.ZIP_DEFLATED
            helper_info.external_attr = 0o100644 << 16
            output_jar.writestr(helper_info, helper.read_bytes())
        if not patched_sdl_video:
            raise RuntimeError(f"{sdl_jar.name} does not contain SDLVideo.class")
        with zipfile.ZipFile(temporary_jar) as check:
            if check.testzip() is not None:
                raise RuntimeError(f"Patched SDL JAR is corrupt: {sdl_jar.name}")
            names = set(check.namelist())
            if "org/lwjgl/sdl/MCLauncherSDLCompat.class" not in names:
                raise RuntimeError("Patched SDL JAR lacks MCLauncherSDLCompat.class")
            if b"MCLauncherSDLCompat" not in check.read("org/lwjgl/sdl/SDLVideo.class"):
                raise RuntimeError("SDLVideo.class does not reference the compatibility bridge")
        shutil.copy2(temporary_jar, sdl_jar)
    return sha256(source)


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
            # OpenLTW is built separately from the pinned current source plus the
            # Minecraft 26.2 compatibility patch. Never retain a stale AAR that
            # may happen to be present in the launch-engine APK.
            if "ltw" in filename.lower():
                continue
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


def vendor_android_sdl_runtime(
    mojo_apk: Path,
    bindings_aar: Path,
    destination: Path,
    abis: Iterable[str],
) -> dict:
    """Prepare the ART bindings and loadable SDL3 libraries used by GameActivity.

    Minecraft 26.3 Snapshot 4 and newer initialize SDL from inside the embedded
    HotSpot VM. SDL's Android port then calls back into ordinary Android/ART Java
    classes. Those classes and libSDL3 therefore need to be packaged in the
    MCLauncher APK itself, in addition to the engine copy stored in assets.
    """
    if not bindings_aar.is_file():
        raise RuntimeError(f"Pinned SDL Android bindings AAR is missing: {bindings_aar}")
    if not mojo_apk.is_file():
        raise RuntimeError(f"Pinned Mojo APK is missing: {mojo_apk}")
    if destination == Path(destination.anchor) or len(destination.parts) < 3:
        raise RuntimeError(f"Refusing unsafe SDL output directory: {destination}")

    required_classes = {
        "git/mojo/sdl/GrabListener.class",
        "git/mojo/sdl/SDL.class",
        "git/mojo/sdl/SDLActivity.class",
        "git/mojo/sdl/SDLClipboard.class",
        "git/mojo/sdl/SDLInputConnection.class",
    }
    try:
        with zipfile.ZipFile(bindings_aar) as aar:
            classes_jar = aar.read("classes.jar")
        with zipfile.ZipFile(io.BytesIO(classes_jar)) as classes:
            missing_classes = required_classes - set(classes.namelist())
    except (KeyError, zipfile.BadZipFile) as exc:
        raise RuntimeError(f"Invalid SDL Android bindings AAR: {bindings_aar}") from exc
    if missing_classes:
        raise RuntimeError(
            f"SDL Android bindings AAR lacks {sorted(missing_classes)}"
        )

    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)
    packaged_aar = destination / "mojo-sdl-bindings.aar"
    shutil.copy2(bindings_aar, packaged_aar)

    required_libraries = ("libSDL3.so", "libmojoexec.so")
    native_digests: dict[str, dict[str, str]] = {}
    with zipfile.ZipFile(mojo_apk) as archive:
        for abi in abis:
            native_digests[abi] = {}
            for filename in required_libraries:
                target = destination / "jniLibs" / abi / filename
                extract_archive_member(archive, f"lib/{abi}/{filename}", target)
                if target.read_bytes()[:4] != b"\x7fELF":
                    raise RuntimeError(f"Pinned SDL runtime is not ELF: {abi}/{filename}")
                native_digests[abi][filename] = sha256(target)

    return {
        "bindingsAarSha256": sha256(packaged_aar),
        "nativeLibraries": native_digests,
    }



def vendor_bundled_turnip(
    output: Path,
    native_libraries: dict[str, list[str]],
    abis: Iterable[str],
    source: dict,
) -> list[dict]:
    """Promote MojoLauncher's pinned Turnip ELF into a selectable driver pack."""
    records: list[dict] = []
    filename = "libvulkan_freedreno.so"
    version_marker = str(source["versionMarker"]).encode("utf-8")
    for abi in abis:
        native_root = output / abi / "natives"
        bundled = native_root / filename
        if not bundled.is_file():
            if abi == "arm64-v8a":
                raise RuntimeError("Pinned arm64 engine build lacks its Turnip Vulkan driver")
            continue
        if version_marker not in bundled.read_bytes():
            raise RuntimeError(
                f"Pinned Turnip/{abi} does not contain {source['versionMarker']!r}"
            )

        pack = output / abi / "drivers/turnip"
        pack.mkdir(parents=True, exist_ok=True)
        target = pack / filename
        bundled.replace(target)
        native_libraries[abi].remove(filename)
        manifest = {
            "schemaVersion": 1,
            "id": "turnip",
            "name": "Bundled Turnip",
            "version": str(source["version"]),
            "kind": "driver",
            "architecture": abi,
            "renderer": None,
            "driver": "TURNIP",
            "pojavRenderer": None,
            "preload": [filename],
            "environment": {
                "MCLAUNCHER_VULKAN_DRIVER": "turnip",
            },
            "files": [filename],
            "sourceName": "MCLauncher pinned MojoLauncher engine build",
            "sourceProject": str(source["sourceProject"]),
            "license": str(source["license"]),
            "importedAtEpochMs": 0,
        }
        (pack / "mclauncher-graphics.json").write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )
        records.append({
            "abi": abi,
            "id": "turnip",
            "version": str(source["version"]),
            "files": [filename],
            "librarySha256": sha256(target),
            "versionMarker": str(source["versionMarker"]),
            "sourceProject": str(source["sourceProject"]),
        })
    return records


def write_graphics_packs(output: Path, copied: dict[str, list[str]]) -> list[dict]:
    # Renderer dependencies remain available in <abi>/natives and are included in
    # LD_LIBRARY_PATH. Each pack copies only the selected renderer entry library.
    presets = (
        ("gl4es", "GL4ES", "opengles3", ("libgl4es",)),
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
                "environment": {"LIBGL_ES": "3"},
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

        if not any(record["abi"] == abi and record["id"] == "gl4es" for record in records):
            raise RuntimeError(f"No usable default GL4ES renderer was produced for {abi}")
    return records


def vendor_openltw(
    aar: Path,
    source_root: Path,
    source: dict,
    output: Path,
    abis: Iterable[str],
) -> tuple[list[dict], str]:
    if not aar.is_file():
        raise RuntimeError(f"Pinned OpenLTW AAR is missing: {aar}")
    if not source_root.is_dir():
        raise RuntimeError(f"Pinned OpenLTW source tree is missing: {source_root}")

    expected_commit = str(source["commit"])
    resolved_commit = subprocess.run(
        ["git", "-C", str(source_root), "rev-parse", "HEAD"],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    if resolved_commit != expected_commit:
        raise RuntimeError(
            f"OpenLTW source commit is {resolved_commit}, expected {expected_commit}"
        )

    license_source = source_root / "LICENSE"
    if (
        not license_source.is_file()
        or sha256(license_source) != str(source["licenseSha256"])
    ):
        raise RuntimeError("OpenLTW source license failed integrity validation")

    records: list[dict] = []
    with zipfile.ZipFile(aar) as archive:
        for abi in abis:
            pack = output / abi / "renderers/openltw"
            library = pack / "libltw.so"
            extract_archive_member(archive, f"jni/{abi}/libltw.so", library)
            symbols = library.read_bytes()
            for required in (b"glGetFloatv", b"glGetBooleanv"):
                if required not in symbols:
                    raise RuntimeError(
                        f"Patched OpenLTW/{abi} is missing {required.decode()}"
                    )
            manifest = {
                "schemaVersion": 1,
                "id": "bundled-openltw",
                "name": "Bundled OpenLTW / LTW",
                "version": str(source["version"]),
                "kind": "renderer",
                "architecture": abi,
                "renderer": "OPEN_LTW",
                "driver": None,
                "pojavRenderer": "opengles3_ltw",
                "preload": ["libltw.so"],
                "environment": {
                    "LIBGL_ES": "3",
                    "LTW_LOG_DIR": "${cache}/openltw",
                },
                "files": ["libltw.so"],
                "sourceName": "MCLauncher pinned source build",
                "sourceProject": str(source["sourceRepository"]),
                "license": str(source["license"]),
                "importedAtEpochMs": 0,
            }
            (pack / "mclauncher-graphics.json").write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )
            records.append(
                {
                    "abi": abi,
                    "id": "openltw",
                    "version": str(source["version"]),
                    "files": ["libltw.so"],
                    "sourceCommit": expected_commit,
                    "sourceAarSha256": sha256(aar),
                    "librarySha256": sha256(library),
                    "compatibilityPatch": str(source["patch"]),
                }
            )

    license_target = output / "common/licenses/OpenLTW-LGPL-3.0.txt"
    license_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(license_source, license_target)
    return records, str(license_target.relative_to(output))


def extract_archive_member(archive: zipfile.ZipFile, member: str, target: Path) -> None:
    try:
        info = archive.getinfo(member)
    except KeyError as exc:
        raise RuntimeError(f"Pinned archive is missing {member}") from exc
    target.parent.mkdir(parents=True, exist_ok=True)
    with archive.open(info) as source, target.open("wb") as destination:
        shutil.copyfileobj(source, destination)


def vendor_mobileglues(
    source: dict,
    output: Path,
    cache: Path,
    abis: Iterable[str],
) -> tuple[list[dict], str]:
    version = str(source["version"])
    release = cache / f"renderers/MobileGlues_{version}.apk"
    download(
        source["releaseUrl"],
        release,
        expected_sha256=source["releaseSha256"],
    )
    records: list[dict] = []
    with zipfile.ZipFile(release) as archive:
        for abi in abis:
            pack = output / abi / "renderers/mobileglues"
            files = ("libmobileglues.so", "libmobileglues_info_getter.so")
            for filename in files:
                extract_archive_member(
                    archive,
                    f"lib/{abi}/{filename}",
                    pack / filename,
                )
            manifest = {
                "schemaVersion": 1,
                "id": "bundled-mobileglues",
                "name": "Bundled MobileGlues",
                "version": version,
                "kind": "renderer",
                "architecture": abi,
                "renderer": "MOBILE_GLUES",
                "driver": None,
                "pojavRenderer": "mobileglues",
                "preload": ["libmobileglues.so"],
                "environment": {
                    "LIBGL_ES": "3",
                    "MG_DIR_PATH": "${cache}/mobileglues",
                },
                "files": list(files),
                "sourceName": "MobileGlues official release",
                "sourceProject": source["sourceRepository"],
                "license": source["license"],
                "importedAtEpochMs": 0,
            }
            (pack / "mclauncher-graphics.json").write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )
            records.append(
                {
                    "abi": abi,
                    "id": "mobileglues",
                    "version": version,
                    "files": list(files),
                    "sourceSha256": sha256(release),
                    "sourceCommit": source["sourceCommit"],
                }
            )

    license_target = output / "common/licenses/MobileGlues-LGPL-2.1.txt"
    license_cache = cache / "licenses/MobileGlues-LGPL-2.1.txt"
    download(
        source["licenseUrl"],
        license_cache,
        expected_sha256=source["licenseSha256"],
    )
    license_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(license_cache, license_target)
    return records, str(license_target.relative_to(output))


def vendor_jna_dispatch(
    source: dict,
    output: Path,
    cache: Path,
    abis: Iterable[str],
    native_libraries: dict[str, list[str]],
) -> tuple[dict, str]:
    version = str(source["version"])
    aar = cache / f"native-compatibility/jna-{version}.aar"
    download(source["aarUrl"], aar, expected_sha256=source["aarSha256"])
    records: dict[str, dict[str, str]] = {}
    with zipfile.ZipFile(aar) as archive:
        for abi in abis:
            native_root = output / abi / "natives"
            legacy = native_root / "libjnidispatch.so"
            if not legacy.is_file():
                raise RuntimeError(
                    f"Pinned engine build lacks its JNA 6 Android native for {abi}"
                )
            jna6 = native_root / "jna-6/libjnidispatch.so"
            jna6.parent.mkdir(parents=True, exist_ok=True)
            legacy.replace(jna6)

            jna7 = native_root / "jna-7/libjnidispatch.so"
            extract_archive_member(
                archive,
                f"jni/{abi}/libjnidispatch.so",
                jna7,
            )
            names = native_libraries[abi]
            names.remove("libjnidispatch.so")
            names.extend(
                ("jna-6/libjnidispatch.so", "jna-7/libjnidispatch.so")
            )
            native_libraries[abi] = sorted(set(names))
            records[abi] = {
                "jna6Sha256": sha256(jna6),
                "jna7Sha256": sha256(jna7),
            }

    license_target = output / "common/licenses/JNA-LICENSE.txt"
    license_cache = cache / "licenses/JNA-LICENSE.txt"
    download(
        source["licenseUrl"],
        license_cache,
        expected_sha256=source["licenseSha256"],
    )
    license_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(license_cache, license_target)
    return {
        "version": version,
        "source": source["aarUrl"],
        "sourceSha256": sha256(aar),
        "libraries": records,
    }, str(license_target.relative_to(output))


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

    compatibility_source = (
        Path(__file__).resolve().parents[1]
        / "vendor/sdl-compat/org/lwjgl/sdl/MCLauncherSDLCompat.java"
    )
    artifacts = {
        str(record["coordinate"]): record
        for record in records
        if record["kind"] == "artifact"
    }
    for coordinate, record in sorted(artifacts.items()):
        if not coordinate.startswith("org.lwjgl:lwjgl-sdl:"):
            continue
        version = coordinate.rsplit(":", 1)[-1]
        core = artifacts.get(f"org.lwjgl:lwjgl:{version}")
        opengl = artifacts.get(f"org.lwjgl:lwjgl-opengl:{version}")
        if core is None or opengl is None:
            raise RuntimeError(
                f"SDL {version} compatibility patch requires matching LWJGL core and OpenGL JARs"
            )
        target = output / "common/jars" / safe_relative_path(
            str(record["path"]),
            label="SDL patched-library path",
        )
        source_digest = patch_sdl_proc_identity_jar(
            target,
            output / "common/jars" / safe_relative_path(str(core["path"]), label="LWJGL core path"),
            output / "common/jars" / safe_relative_path(str(opengl["path"]), label="LWJGL OpenGL path"),
            compatibility_source,
        )
        record["upstreamSha1"] = record["sha1"]
        record["sha1"] = sha1(target)
        record["sha256"] = sha256(target)
        record["compatibilityPatch"] = (
            "vendor/sdl-compat/org/lwjgl/sdl/MCLauncherSDLCompat.java"
        )
        record["compatibilityPatchSha256"] = source_digest

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
        "OpenLTW source: https://github.com/MojoLauncher/LTW\n"
        "OpenLTW compatibility patch: vendor/patches/ltw-minecraft-26.2.patch\n"
        "Runtime source: https://github.com/MojoLauncher/android-openjdk-build-multiarch\n"
        "Bundled Turnip driver: pinned MojoLauncher engine build (Mesa MIT)\n"
        "Patched LWJGL source records: ../jars/substitutions.json and bundle-manifest.json\n",
        encoding="utf-8",
    )
    copied.append(str(notice.relative_to(output)))
    return copied


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mojo-root", type=Path, required=True)
    parser.add_argument("--mojo-apk", type=Path, required=True)
    parser.add_argument("--sdl-bindings-aar", type=Path, required=True)
    parser.add_argument("--ltw-root", type=Path, required=True)
    parser.add_argument("--ltw-aar", type=Path, required=True)
    parser.add_argument("--abis", default="arm64-v8a")
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/bundled_engine"))
    parser.add_argument("--sdl-output", type=Path, default=Path("app/build/generated/sdl"))
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
    drivers = vendor_bundled_turnip(
        output,
        natives,
        abis,
        lock["drivers"]["turnip"],
    )
    android_sdl_runtime = vendor_android_sdl_runtime(
        args.mojo_apk,
        args.sdl_bindings_aar,
        args.sdl_output.resolve(),
        abis,
    )
    renderers = write_graphics_packs(output, natives)
    openltw, openltw_license = vendor_openltw(
        args.ltw_aar,
        args.ltw_root,
        lock["renderers"]["openLTW"],
        output,
        abis,
    )
    mobileglues, mobileglues_license = vendor_mobileglues(
        lock["renderers"]["mobileGlues"],
        output,
        args.cache,
        abis,
    )
    renderers = mobileglues + openltw + renderers
    jna_dispatch, jna_license = vendor_jna_dispatch(
        lock["nativeCompatibility"]["jna"],
        output,
        args.cache,
        abis,
        natives,
    )
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
    licenses.extend((openltw_license, mobileglues_license, jna_license))

    version = bundle_version(lock)
    (output / "bundle-version.txt").write_text(version + "\n", encoding="utf-8")
    manifest = {
        "schemaVersion": 2,
        "bundleVersion": version,
        "architectures": list(abis),
        "engine": lock["engine"],
        "androidSdlRuntime": android_sdl_runtime,
        "nativeLibraries": natives,
        "rendererPacks": renderers,
        "driverPacks": drivers,
        "patchedLibraries": patched,
        "patchedNativeLibraries": patched_natives,
        "jnaDispatch": jna_dispatch,
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

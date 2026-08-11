#!/usr/bin/env python3
"""Verify that the final APK contains SDL's ART callbacks and native runtime."""
from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path

ELF_MAGIC = b"\x7fELF"
REQUIRED_DEX_TYPES = (
    b"Lgit/mojo/sdl/SDL;",
    b"Lgit/mojo/sdl/SDLActivity;",
    b"Lgit/mojo/sdl/SDLInputConnection;",
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--abi", required=True)
    args = parser.parse_args()

    errors: list[str] = []
    try:
        with zipfile.ZipFile(args.apk) as archive:
            names = set(archive.namelist())
            try:
                engine_manifest = json.loads(
                    archive.read("assets/bundled_engine/bundle-manifest.json")
                )
                expected_natives = (
                    engine_manifest.get("androidSdlRuntime", {})
                    .get("nativeLibraries", {})
                    .get(args.abi, {})
                )
            except (KeyError, json.JSONDecodeError) as exc:
                errors.append(f"Final APK has no valid engine manifest: {exc}")
                expected_natives = {}
            for filename in ("libSDL3.so", "libmojoexec.so"):
                member = f"lib/{args.abi}/{filename}"
                if member not in names:
                    errors.append(f"Final APK is missing {member}")
                else:
                    payload = archive.read(member)
                    if payload[:4] != ELF_MAGIC:
                        errors.append(f"Final APK contains an invalid ELF: {member}")
                    expected = expected_natives.get(filename)
                    if expected != hashlib.sha256(payload).hexdigest():
                        errors.append(f"Final APK SDL digest does not match: {member}")

            dex_payload = b"".join(
                archive.read(name)
                for name in sorted(names)
                if name.startswith("classes") and name.endswith(".dex")
            )
            if not dex_payload:
                errors.append("Final APK has no classes.dex payload")
            for descriptor in REQUIRED_DEX_TYPES:
                if descriptor not in dex_payload:
                    errors.append(
                        f"Final APK is missing SDL ART type {descriptor.decode()}"
                    )
    except (OSError, zipfile.BadZipFile) as exc:
        errors.append(f"Could not inspect final APK: {exc}")

    if errors:
        print("SDL APK verification failed:")
        for error in errors:
            print(f"- {error}")
        return 1
    print(f"SDL APK verification passed for {args.abi}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

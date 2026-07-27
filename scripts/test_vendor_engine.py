#!/usr/bin/env python3
from __future__ import annotations

import base64
import hashlib
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("vendor_engine.py")
SPEC = importlib.util.spec_from_file_location("vendor_engine", MODULE_PATH)
assert SPEC and SPEC.loader
vendor_engine = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(vendor_engine)


class VendorEngineTest(unittest.TestCase):
    def test_parse_abis_deduplicates_in_order(self) -> None:
        self.assertEqual(
            vendor_engine.parse_abis("arm64-v8a,x86_64,arm64-v8a"),
            ("arm64-v8a", "x86_64"),
        )

    def test_parse_abis_rejects_unknown_architecture(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported ABI"):
            vendor_engine.parse_abis("mips")

    def test_safe_relative_path_rejects_traversal(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "Unsafe"):
            vendor_engine.safe_relative_path("../../payload.so", label="test path")

    def test_signature_bundle_requires_rsa4096_signature(self) -> None:
        signature = base64.b64encode(b"x" * 512).decode()
        parsed = vendor_engine.parse_signature_bundle(
            f"universal.tar.xz:{signature}\n"
        )
        self.assertEqual(parsed["universal.tar.xz"], b"x" * 512)

        short_signature = base64.b64encode(b"x" * 64).decode()
        with self.assertRaisesRegex(RuntimeError, "RSA-4096"):
            vendor_engine.parse_signature_bundle(
                f"universal.tar.xz:{short_signature}\n"
            )

    def test_classifier_mapping(self) -> None:
        self.assertTrue(
            vendor_engine.classifier_matches_abi(
                "natives-linux-arm64",
                "arm64-v8a",
            )
        )
        self.assertTrue(
            vendor_engine.classifier_matches_abi(
                "natives-linux",
                "x86_64",
            )
        )
        self.assertFalse(
            vendor_engine.classifier_matches_abi(
                "natives-linux-arm32",
                "x86_64",
            )
        )

    def test_payload_file_index_excludes_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "engine.so").write_bytes(b"engine")
            (root / "bundle-manifest.json").write_text("{}")
            index = vendor_engine.payload_file_index(root)
        self.assertEqual(
            index,
            {"engine.so": hashlib.sha256(b"engine").hexdigest()},
        )

    def test_bundle_version_changes_when_dependency_lock_changes(self) -> None:
        lock = {"engine": {"commit": "a" * 40}, "renderer": {"version": "1"}}
        first = vendor_engine.bundle_version(lock)
        lock["renderer"]["version"] = "2"
        second = vendor_engine.bundle_version(lock)

        self.assertNotEqual(first, second)
        self.assertIn("lock-", first)

    def test_mobileglues_and_jna_are_vendored_by_abi(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "output"
            cache = root / "cache"
            abi = "arm64-v8a"

            mobile_apk = cache / "renderers/MobileGlues_1.3.5.apk"
            mobile_apk.parent.mkdir(parents=True)
            with zipfile.ZipFile(mobile_apk, "w") as archive:
                archive.writestr(f"lib/{abi}/libmobileglues.so", b"\x7fELFglGenSamplersglBindSampler")
                archive.writestr(f"lib/{abi}/libmobileglues_info_getter.so", b"\x7fELFinfo")
            mobile_license = cache / "licenses/MobileGlues-LGPL-2.1.txt"
            mobile_license.parent.mkdir(parents=True)
            mobile_license.write_bytes(b"mobile license")
            mobile_config = {
                "version": "1.3.5",
                "releaseUrl": "https://invalid.example/mobile.apk",
                "releaseSha256": vendor_engine.sha256(mobile_apk),
                "sourceRepository": "https://example.invalid/source",
                "sourceCommit": "b" * 40,
                "license": "LGPL-2.1",
                "licenseUrl": "https://invalid.example/license",
                "licenseSha256": vendor_engine.sha256(mobile_license),
            }
            records, license_path = vendor_engine.vendor_mobileglues(
                mobile_config,
                output,
                cache,
                (abi,),
            )
            self.assertEqual(records[0]["id"], "mobileglues")
            self.assertTrue((output / abi / "renderers/mobileglues/libmobileglues.so").is_file())
            self.assertEqual(license_path, "common/licenses/MobileGlues-LGPL-2.1.txt")

            native_root = output / abi / "natives"
            native_root.mkdir(parents=True)
            (native_root / "libjnidispatch.so").write_bytes(b"\x7fELFold")
            jna_aar = cache / "native-compatibility/jna-5.17.0.aar"
            jna_aar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jna_aar, "w") as archive:
                archive.writestr(f"jni/{abi}/libjnidispatch.so", b"\x7fELF7.0.4")
            jna_license = cache / "licenses/JNA-LICENSE.txt"
            jna_license.write_bytes(b"jna license")
            jna_config = {
                "version": "5.17.0",
                "aarUrl": "https://invalid.example/jna.aar",
                "aarSha256": vendor_engine.sha256(jna_aar),
                "licenseUrl": "https://invalid.example/jna-license",
                "licenseSha256": vendor_engine.sha256(jna_license),
            }
            native_records = {abi: ["libjnidispatch.so"]}
            jna_record, _ = vendor_engine.vendor_jna_dispatch(
                jna_config,
                output,
                cache,
                (abi,),
                native_records,
            )
            self.assertFalse((native_root / "libjnidispatch.so").exists())
            self.assertTrue((native_root / "jna-6/libjnidispatch.so").is_file())
            self.assertTrue((native_root / "jna-7/libjnidispatch.so").is_file())
            self.assertEqual(jna_record["version"], "5.17.0")
            self.assertIn("jna-7/libjnidispatch.so", native_records[abi])


if __name__ == "__main__":
    unittest.main()

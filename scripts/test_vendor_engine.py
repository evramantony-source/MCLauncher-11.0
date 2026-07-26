#!/usr/bin/env python3
from __future__ import annotations

import base64
import hashlib
import importlib.util
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()

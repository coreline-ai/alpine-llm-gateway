from __future__ import annotations

import copy
import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_script(name: str, file_name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / file_name)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(module)
    return module


VERIFY = load_script("license_compliance", "verify-license-compliance.py")
INVENTORY = load_script("alpine_inventory", "generate-alpine-package-inventory.py")
BUNDLE = load_script("oss_source_bundle", "build-oss-source-bundle.py")
COMMON = load_script("compliance_common_test", "compliance_common.py")


class LicenseComplianceTests(unittest.TestCase):
    def test_repository_compliance_is_valid_but_external_release_stays_closed(self):
        report = VERIFY.verify()
        self.assertEqual("INTERNAL_ONLY", report["distribution_mode"])
        self.assertEqual("GPL-2.0-or-later", report["metadata"]["proot_declared"])
        self.assertEqual("NOASSERTION", report["metadata"]["proot_concluded"])
        self.assertEqual(["alpine-runtime-pty"], report["boundaries"]["loaded_native_libraries"])
        self.assertFalse(report["provenance"]["complete"])
        self.assertFalse(report["external_distribution_ready"])

    def test_provenance_duplicate_and_unapproved_license_metadata_are_rejected(self):
        provenance = json.loads((ROOT / "compliance/code-provenance.json").read_text())
        provenance["entries"].append(copy.deepcopy(provenance["entries"][0]))
        with self.assertRaises(AssertionError):
            VERIFY.verify_provenance(provenance)

        policy = json.loads((ROOT / "compliance/component-license-policy.json").read_text())
        policy["components"]["proot"]["license_declared"] = "GPL-2.0-only"
        with self.assertRaises(AssertionError):
            VERIFY.verify_component_metadata(policy)

    def test_forbidden_native_link_boundary_is_fail_closed(self):
        policy = json.loads((ROOT / "compliance/component-license-policy.json").read_text())
        policy["boundaries"]["forbidden_system_load_libraries"].append("alpine-runtime-pty")
        with self.assertRaises(AssertionError):
            VERIFY.verify_process_boundaries(policy)

    def test_arm64_and_x86_inventories_are_abi_specific_and_incomplete(self):
        cases = (
            (
                ROOT / "alpine-runtime-pack-bundled/src/main/assets/alpine-minirootfs.tar.gz.asset",
                "arm64-v8a",
                "ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea",
                "aarch64",
            ),
            (
                ROOT / "alpine-runtime-pack-x86_64/src/main/assets/alpine-minirootfs-x86_64.tar.gz.asset",
                "x86_64",
                "1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239",
                "x86_64",
            ),
        )
        for rootfs, abi, digest, architecture in cases:
            with self.subTest(abi=abi):
                document = INVENTORY.build_inventory(rootfs, abi, digest)
                self.assertEqual([architecture], document["rootfs"]["architectures"])
                self.assertGreater(document["source_review_package_count"], 0)
                self.assertFalse(document["source_mirror_complete"])
                self.assertTrue(document["missing_source_origins"])

    def test_inventory_rejects_rootfs_checksum_mismatch(self):
        rootfs = ROOT / "alpine-runtime-pack-bundled/src/main/assets/alpine-minirootfs.tar.gz.asset"
        with self.assertRaises(AssertionError):
            INVENTORY.build_inventory(rootfs, "arm64-v8a", "0" * 64)

    def test_unknown_and_custom_package_licenses_require_manual_source_review(self):
        for identifier in ("NOASSERTION", "UNKNOWN", "custom", "Custom:Vendor"):
            with self.subTest(identifier=identifier):
                self.assertTrue(COMMON.is_source_review_license(identifier))

    def _write_bundle(self, path: Path, tamper: bool = False) -> None:
        root = "alpine-oss-native-sources-0.3.0"
        files = {
            "manifest.json": json.dumps(
                {
                    "schema_version": 1,
                    "bundle_kind": "NATIVE_CORRESPONDING_SOURCE",
                    "release_version": "0.3.0",
                    "complete_components": ["proot", "talloc"],
                    "native_components_complete": True,
                    "runtime_source_complete": False,
                    "external_release_complete": False,
                    "components": {"talloc": {"modified_official_files": []}},
                    "binary_targets": [
                        {"abi": "arm64-v8a"},
                        {"abi": "x86_64"},
                    ],
                }
            ).encode(),
            "sources/proot/src/cli/proot.c": b"proot source\n",
            "sources/proot/COPYING": b"GPL text\n",
            "sources/talloc/talloc-2.4.2/talloc.c": b"talloc source\n",
            "build-input/talloc-standalone/replace.h": b"replace source\n",
            "scripts/runtime/build-proot-android.sh": b"#!/bin/sh\n",
            "scripts/runtime/patches/proot-android-winsize.patch": b"patch\n",
        }
        sums = "".join(
            f"{hashlib.sha256(payload).hexdigest()}  {name}\n"
            for name, payload in sorted(files.items())
        ).encode()
        files["SHA256SUMS"] = sums
        if tamper:
            files["sources/proot/src/cli/proot.c"] = b"tampered\n"
        with tarfile.open(path, "w:gz") as archive:
            for name, payload in sorted(files.items()):
                info = tarfile.TarInfo(f"{root}/{name}")
                info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))

    def test_source_bundle_verifier_accepts_complete_native_bundle_and_rejects_tamper(self):
        with tempfile.TemporaryDirectory() as temporary:
            valid = Path(temporary) / "valid.tar.gz"
            invalid = Path(temporary) / "invalid.tar.gz"
            self._write_bundle(valid)
            self._write_bundle(invalid, tamper=True)
            report = BUNDLE.verify_bundle(valid)
            self.assertEqual(["proot", "talloc"], report["complete_components"])
            self.assertTrue(report["native_components_complete"])
            self.assertFalse(report["runtime_source_complete"])
            self.assertFalse(report["external_release_complete"])
            with self.assertRaises(AssertionError):
                BUNDLE.verify_bundle(invalid)


if __name__ == "__main__":
    unittest.main()

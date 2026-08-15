from __future__ import annotations

import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/verify-codex-appserver.py"
SPEC = importlib.util.spec_from_file_location("codex_appserver_verifier", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CodexAppServerVerifierTest(unittest.TestCase):
    def test_tracked_metadata_and_production_source_pass(self) -> None:
        lock = MODULE.verify_metadata()
        MODULE.verify_production_source()
        MODULE.verify_android_policy()
        MODULE.verify_rollback_policy()
        self.assertEqual(lock["binary_sha256"], MODULE.EXPECTED_BINARY_SHA256)

    def test_same_package_rollback_gate_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            integrated = root / "integrated.gradle.kts"
            pack = root / "pack.gradle.kts"
            integrated.write_text("codexAppServerEnabled")
            pack.write_text("packEnabled")
            with self.assertRaisesRegex(MODULE.VerificationError, "rollback"):
                MODULE.verify_rollback_policy(integrated, pack)

    def test_forbidden_anyclaw_and_auth_file_markers_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "bad.kt").write_text('val copied = "AnyClaw auth.json"')
            with self.assertRaisesRegex(MODULE.VerificationError, "forbidden"):
                MODULE.verify_production_source((root,))

    def test_elf_16k_alignment_is_enforced(self) -> None:
        def elf(alignment: int) -> bytes:
            data = bytearray(120)
            data[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", data, 18, 183)
            struct.pack_into("<Q", data, 32, 64)
            struct.pack_into("<HH", data, 54, 56, 1)
            struct.pack_into("<I", data, 64, 1)
            struct.pack_into("<Q", data, 64 + 48, alignment)
            return bytes(data)

        self.assertEqual(MODULE.elf_load_alignments(elf(16 * 1024)), [16 * 1024])
        with self.assertRaisesRegex(MODULE.VerificationError, "16 KiB"):
            MODULE.elf_load_alignments(elf(4 * 1024))

    def test_feature_off_archive_rejects_any_codex_named_entry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "app.apk"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("lib/arm64-v8a/libcodex_app_server.so", b"not-approved")
            with self.assertRaisesRegex(MODULE.VerificationError, "feature-off"):
                MODULE.verify_archive(archive, expect_binary=False)

    def test_target_sdk_downgrade_and_global_cleartext_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gradle = root / "build.gradle.kts"
            manifest = root / "AndroidManifest.xml"
            shares = root / "paths.xml"
            layout = root / "Layout.kt"
            shares.write_text("<paths><cache-path /></paths>")
            layout.write_text(
                'val noBackupRoot = context.noBackupFilesDir\n'
                'child(noBackupRoot, "codex-app-server")'
            )

            gradle.write_text("targetSdk = 35")
            manifest.write_text('<application android:allowBackup="false" />')
            with self.assertRaisesRegex(MODULE.VerificationError, "targetSdk"):
                MODULE.verify_android_policy(gradle, manifest, shares, layout)

            gradle.write_text("targetSdk = 36")
            manifest.write_text(
                '<application android:allowBackup="false" '
                'android:usesCleartextTraffic="true" />'
            )
            with self.assertRaisesRegex(MODULE.VerificationError, "cleartext"):
                MODULE.verify_android_policy(gradle, manifest, shares, layout)


if __name__ == "__main__":
    unittest.main()

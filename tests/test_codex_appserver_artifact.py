import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "import-codex-appserver-artifact.py"
SPEC = importlib.util.spec_from_file_location("codex_artifact_import", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class CodexArtifactImportTest(unittest.TestCase):
    def test_rejects_unlocked_binary_before_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "codex"
            source.write_bytes(b"\x7fELF\x02\x01" + b"\0" * 64)
            with self.assertRaisesRegex(ValueError, "size mismatch"):
                MODULE.import_artifact(source, root)
            self.assertFalse(
                (root / ".codex-artifacts" / MODULE.VERSION / "linux-arm64" / "codex").exists()
            )

    def test_constants_match_tracked_lock(self):
        lock = (
            Path(__file__).parents[1]
            / "alpine-codex-appserver-pack-android"
            / "src/main/resources/META-INF/codex-appserver/artifact-lock.json"
        ).read_text()
        self.assertIn(MODULE.BINARY_SHA256, lock)
        self.assertIn(MODULE.TARBALL_SHA256, lock)
        self.assertIn(f'"binary_size": {MODULE.BINARY_SIZE}', lock)


if __name__ == "__main__":
    unittest.main()

import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/build-current-state-public-release.sh"
BUILD = ROOT / "integrated-app/build.gradle.kts"


class CurrentStateReleaseBuilderTest(unittest.TestCase):
    def test_script_has_valid_bash_syntax(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)

    def test_builder_supports_secure_release_signing_and_never_uses_debug_key(self):
        source = SCRIPT.read_text()
        self.assertIn("--unsigned-candidate", source)
        self.assertIn("RELEASE_SIGNING_NOT_CONFIGURED", source)
        self.assertIn("ALPINE_RELEASE_KEYSTORE", source)
        self.assertIn("SIGNED_APK_VERIFICATION_FAILED", source)
        self.assertIn("SIGNED_AAB_VERIFICATION_FAILED", source)
        self.assertNotIn("debug.keystore", source)
        self.assertIn("upload_ready\": false", source)

    def test_gradle_release_override_is_explicit_and_owner_decision_gated(self):
        source = BUILD.read_text()
        self.assertIn("codexCurrentStatePublicRelease", source)
        self.assertIn("verifyCurrentStatePublicReleaseDecision", source)
        self.assertIn("verify-current-state-release-decision.py", source)
        self.assertIn("releaseSigningConfigured", source)
        self.assertIn("ALPINE_RELEASE_KEYSTORE", source)


if __name__ == "__main__":
    unittest.main()

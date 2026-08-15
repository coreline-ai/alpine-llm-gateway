import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/build-current-state-public-release.sh"
BUILD = ROOT / "integrated-app/build.gradle.kts"
MACOS_CONFIGURE = ROOT / "scripts/configure-macos-release-signing.sh"
MACOS_BUILD = ROOT / "scripts/build-current-state-signed-release-macos.sh"
GITHUB_PUBLISH = ROOT / "scripts/publish-github-current-state-release.sh"


class CurrentStateReleaseBuilderTest(unittest.TestCase):
    def test_script_has_valid_bash_syntax(self):
        for script in (SCRIPT, MACOS_CONFIGURE, MACOS_BUILD, GITHUB_PUBLISH):
            subprocess.run(["bash", "-n", str(script)], check=True)

    def test_builder_supports_secure_release_signing_and_never_uses_debug_key(self):
        source = SCRIPT.read_text()
        self.assertIn("--unsigned-candidate", source)
        self.assertIn("RELEASE_SIGNING_NOT_CONFIGURED", source)
        self.assertIn("ALPINE_RELEASE_KEYSTORE", source)
        self.assertIn("SIGNED_APK_VERIFICATION_FAILED", source)
        self.assertIn("SIGNED_AAB_VERIFICATION_FAILED", source)
        self.assertIn("RELEASE_SIGNING_CERTIFICATE_MISMATCH", source)
        self.assertIn("SIGNED_RELEASE_REQUIRES_CLEAN_WORKTREE", source)
        self.assertIn("signing_certificate_sha256", source)
        self.assertNotIn("debug.keystore", source)
        self.assertIn("upload_ready\": false", source)

    def test_macos_signing_uses_keychain_without_persisting_secret_in_repository(self):
        configure = MACOS_CONFIGURE.read_text()
        build = MACOS_BUILD.read_text()
        self.assertIn("security add-generic-password", configure)
        self.assertIn("rand -hex", configure)
        self.assertIn("security find-generic-password", build)
        self.assertNotIn("debug.keystore", configure + build)
        self.assertNotIn("ALPINE_BOOTSTRAP_SIGNING_SECRET=\"fixed", configure + build)

    def test_github_publisher_requires_signed_clean_tagged_artifact(self):
        source = GITHUB_PUBLISH.read_text()
        self.assertIn("artifact_upload_ready", source)
        self.assertIn("git status --porcelain", source)
        self.assertIn("git credential fill", source)
        self.assertIn("github-release-publication.json", source)

    def test_gradle_release_override_is_explicit_and_owner_decision_gated(self):
        source = BUILD.read_text()
        self.assertIn("codexCurrentStatePublicRelease", source)
        self.assertIn("verifyCurrentStatePublicReleaseDecision", source)
        self.assertIn("verify-current-state-release-decision.py", source)
        self.assertIn("releaseSigningConfigured", source)
        self.assertIn("ALPINE_RELEASE_KEYSTORE", source)


if __name__ == "__main__":
    unittest.main()

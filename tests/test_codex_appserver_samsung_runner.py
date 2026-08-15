import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts/run-codex-appserver-samsung-e2e.sh"


class CodexSamsungRunnerTest(unittest.TestCase):
    def test_runner_has_valid_bash_syntax(self):
        subprocess.run(["bash", "-n", str(RUNNER)], check=True)

    def test_runner_is_samsung_fixed_and_approval_gated(self):
        source = RUNNER.read_text()
        self.assertIn('SAMSUNG_SERIAL="R3CY40PXCAP"', source)
        self.assertIn('EXPECTED_MODEL="SM-S931N"', source)
        self.assertIn('"$ADB" -s "$SAMSUNG_SERIAL"', source)
        self.assertIn("--approve-account-logout", source)
        self.assertIn("--approve-feature-off-rollback", source)
        self.assertIn("CODEX_ROLLBACK_REQUIRES_APPROVED_LOGOUT", source)
        self.assertIn("approveCodexLogout true", source)
        self.assertIn("approveCodexRollback true", source)
        self.assertIn("CodexUiAccessibilityInstrumentedTest", source)

    def test_runner_never_uses_unapproved_destructive_adb_actions(self):
        source = RUNNER.read_text()
        for forbidden in (" uninstall ", " pm clear ", " force-stop ", " reboot ", "dumpsys deviceidle force-idle"):
            self.assertNotIn(forbidden, source)

    def test_runner_does_not_persist_or_echo_raw_instrumentation(self):
        source = RUNNER.read_text()
        self.assertIn('output="$(adb_target shell am instrument', source)
        self.assertNotIn('echo "$output"', source)
        self.assertNotIn("tee ", source)
        self.assertIn("SAMSUNG_INSTRUMENTATION_ASSERTION_FAILED", source)


if __name__ == "__main__":
    unittest.main()

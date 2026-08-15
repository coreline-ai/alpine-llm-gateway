import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "codex_e2e_report", ROOT / "scripts/verify-codex-appserver-e2e-report.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class CodexAppServerE2EReportTest(unittest.TestCase):
    def setUp(self):
        self.template = json.loads(
            (ROOT / "integration-fixtures/codex-appserver-e2e/report.template.json").read_text()
        )

    def test_unexecuted_template_is_valid_and_fail_closed(self):
        self.assertEqual(
            {
                "executed": False,
                "check_count": 0,
                "destructive_check_count": 0,
                "passed": False,
            },
            MODULE.verify(self.template),
        )

    def test_executed_report_requires_every_check_and_samsung_metadata(self):
        report = copy.deepcopy(self.template)
        report.update(
            {
                "executed": True,
                "executed_at": "2026-08-15T00:00:00Z",
                "build_commit": "abcdef1",
                "checks": {
                    name: {"status": "PASS"} for name in MODULE.REQUIRED_CHECKS
                },
                "destructive_checks": {
                    name: {"status": "NOT_RUN"}
                    for name in MODULE.DESTRUCTIVE_CHECKS
                },
            }
        )
        report["device"].update(
            {"model": "SM-S931N", "api_level": 36, "page_size": 4096}
        )
        self.assertTrue(MODULE.verify(report)["passed"])

    def test_unapproved_destructive_check_must_be_not_run(self):
        report = self._executed_report()
        report["destructive_checks"]["process_kill"] = {"status": "PASS"}
        with self.assertRaises(AssertionError):
            MODULE.verify(report)

    def test_approved_destructive_failure_fails_report(self):
        report = self._executed_report()
        report["destructive_tests_approved"] = True
        report["destructive_checks"] = {
            name: {"status": "PASS"} for name in MODULE.DESTRUCTIVE_CHECKS
        }
        report["destructive_checks"]["network_loss"] = {
            "status": "FAIL",
            "error_code": "NETWORK_RECOVERY_FAILED",
        }
        self.assertFalse(MODULE.verify(report)["passed"])

    def test_sensitive_evidence_is_rejected(self):
        with self.assertRaises(AssertionError):
            MODULE.scan_sensitive({"authorization_query": "redacted"})
        with self.assertRaises(AssertionError):
            MODULE.scan_sensitive({"note": "https://example.test/oauth/authorize?state=value"})

    def _executed_report(self):
        report = copy.deepcopy(self.template)
        report.update(
            {
                "executed": True,
                "executed_at": "2026-08-15T00:00:00Z",
                "build_commit": "abcdef1",
                "checks": {
                    name: {"status": "PASS"} for name in MODULE.REQUIRED_CHECKS
                },
                "destructive_checks": {
                    name: {"status": "NOT_RUN"}
                    for name in MODULE.DESTRUCTIVE_CHECKS
                },
            }
        )
        report["device"].update(
            {"model": "SM-S931N", "api_level": 36, "page_size": 4096}
        )
        return report


if __name__ == "__main__":
    unittest.main()

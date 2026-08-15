import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "codex_16k_report", ROOT / "scripts/verify-codex-appserver-16k-report.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class CodexAppServer16KReportTest(unittest.TestCase):
    def setUp(self):
        self.template = json.loads(
            (ROOT / "integration-fixtures/codex-appserver-16k/report.template.json").read_text()
        )

    def test_unexecuted_template_is_valid_and_fail_closed(self):
        self.assertEqual(
            {
                "executed": False,
                "check_count": 0,
                "page_size": None,
                "passed": False,
            },
            MODULE.verify(self.template),
        )
        with self.assertRaises(AssertionError):
            MODULE.verify(self.template, require_executed=True)

    def test_executed_arm64_16k_report_passes(self):
        report = self._executed_report()
        summary = MODULE.verify(report, require_executed=True)
        self.assertTrue(summary["passed"])
        self.assertEqual(16384, summary["page_size"])

    def test_4k_or_x86_report_is_rejected(self):
        report = self._executed_report()
        report["device"]["page_size"] = 4096
        with self.assertRaises(AssertionError):
            MODULE.verify(report)
        report = self._executed_report()
        report["device"]["abi"] = "x86_64"
        with self.assertRaises(AssertionError):
            MODULE.verify(report)

    def test_missing_measurement_and_sensitive_evidence_are_rejected(self):
        report = self._executed_report()
        report["measurements"].pop("download_bytes")
        with self.assertRaises(AssertionError):
            MODULE.verify(report)
        with self.assertRaises(AssertionError):
            MODULE.scan_sensitive({"raw_response": "redacted"})

    def _executed_report(self):
        report = copy.deepcopy(self.template)
        report.update(
            {
                "executed": True,
                "executed_at": "2026-08-15T00:00:00Z",
                "build_commit": "abcdef1",
                "artifact_sha256": "a" * 64,
                "measurements": {
                    "aab_bytes": 100,
                    "download_bytes": 90,
                    "installed_bytes": 200,
                },
                "checks": {
                    name: {"status": "PASS"} for name in MODULE.REQUIRED_CHECKS
                },
            }
        )
        report["device"].update(
            {
                "manufacturer": "RemoteLab",
                "model": "ARM64_16K",
                "api_level": 36,
                "page_size": 16384,
                "abi": "arm64-v8a",
            }
        )
        return report


if __name__ == "__main__":
    unittest.main()

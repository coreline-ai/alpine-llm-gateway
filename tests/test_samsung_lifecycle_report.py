import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "samsung_lifecycle_report",
    ROOT / "scripts/verify-samsung-lifecycle-report.py",
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class SamsungLifecycleReportTest(unittest.TestCase):
    def test_template_is_valid_and_not_executed(self):
        report = json.loads(
            (ROOT / "integration-fixtures/samsung-lifecycle/report.template.json").read_text()
        )
        self.assertEqual(
            {"executed": False, "check_count": 0, "passed": False},
            MODULE.verify(report, False),
        )

    def test_not_run_is_valid_but_does_not_pass(self):
        report = {
            "schema_version": 1,
            "executed": True,
            "executed_at": "2026-08-02T03:00:00Z",
            "approval_reference": "SAMSUNG-WINDOW-1",
            "device": {"model": "SM-S931N", "api_level": 36},
            "checks": {name: {"status": "PASS"} for name in MODULE.REQUIRED_CHECKS},
        }
        self.assertTrue(MODULE.verify(report, True)["passed"])
        report["checks"]["reboot"] = {"status": "NOT_RUN"}
        self.assertFalse(MODULE.verify(report, True)["passed"])

    def test_prompt_and_command_fields_are_rejected(self):
        with self.assertRaises(AssertionError):
            MODULE.scan_sensitive({"command": "redacted"})
        with self.assertRaises(AssertionError):
            MODULE.scan_sensitive({"prompt": "redacted"})


if __name__ == "__main__":
    unittest.main()

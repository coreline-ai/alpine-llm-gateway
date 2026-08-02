import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "play_e2e_report",
    ROOT / "scripts/verify-play-e2e-report.py",
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class PlayE2EReportTest(unittest.TestCase):
    def test_template_is_valid_and_not_executed(self):
        report = json.loads(
            (ROOT / "integration-fixtures/play-e2e/report.template.json").read_text()
        )
        self.assertEqual(
            {"executed": False, "check_count": 0, "passed": False},
            MODULE.verify(report, False),
        )

    def test_executed_report_requires_every_check(self):
        report = {
            "schema_version": 1,
            "executed": True,
            "executed_at": "2026-08-02T02:00:00Z",
            "approval_reference": "PLAY-OWNER-1",
            "track": "internal",
            "application_id": "dev.alpine.runtime.probe",
            "app_version": "0.3.0-test",
            "asset_pack_name": "alpine_runtime",
            "device": {"model": "SM-S931N", "api_level": 36},
            "checks": {name: {"status": "PASS"} for name in MODULE.REQUIRED_CHECKS},
        }
        self.assertTrue(MODULE.verify(report, True)["passed"])
        report["checks"].pop("rollback")
        with self.assertRaises(AssertionError):
            MODULE.verify(report, True)

    def test_sensitive_signing_fields_are_rejected(self):
        with self.assertRaises(AssertionError):
            MODULE.scan_sensitive({"signing_key": "redacted"})


if __name__ == "__main__":
    unittest.main()

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "provider_e2e_report",
    ROOT / "scripts/verify-provider-e2e-report.py",
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class ProviderE2EReportTest(unittest.TestCase):
    def test_template_is_valid_and_not_executed(self):
        report = json.loads(
            (ROOT / "integration-fixtures/provider-e2e/report.template.json").read_text()
        )
        self.assertEqual(
            {"executed": False, "provider_count": 0, "passed": False},
            MODULE.verify(report, False),
        )

    def test_token_like_values_and_sensitive_keys_are_rejected(self):
        with self.assertRaises(AssertionError):
            MODULE.scan_sensitive({"access_token": "redacted"})
        with self.assertRaises(AssertionError):
            MODULE.scan_sensitive({"message": "Bearer abcdefghijklmnop"})

    def test_executed_report_requires_exact_checks(self):
        report = {
            "schema_version": 1,
            "executed": True,
            "executed_at": "2026-08-01T12:00:00Z",
            "approval_reference": "OWNER-APPROVED-1",
            "providers": [{"provider_id": "openai", "checks": {}}],
        }
        with self.assertRaises(AssertionError):
            MODULE.verify(report, True)

    def test_executed_report_requires_reviewed_fail_closed_idempotency_contract(self):
        checks = {
            name: {"status": "PASS"}
            for name in MODULE.REQUIRED_CHECKS
        }
        report = {
            "schema_version": 1,
            "executed": True,
            "executed_at": "2026-08-02T01:00:00Z",
            "approval_reference": "OWNER-APPROVED-2",
            "providers": [
                {
                    "provider_id": "example",
                    "checks": checks,
                    "idempotency": {
                        "policy": "NEVER_AUTOMATIC",
                        "automatic_retry_enabled": False,
                        "review_status": "REVIEWED",
                        "evidence_reference": "OWNER-CONTRACT-1",
                        "header_name": None,
                    },
                }
            ],
        }

        self.assertEqual(
            {"executed": True, "provider_count": 1, "passed": True},
            MODULE.verify(report, True),
        )
        report["providers"][0]["idempotency"]["automatic_retry_enabled"] = True
        with self.assertRaises(AssertionError):
            MODULE.verify(report, True)


if __name__ == "__main__":
    unittest.main()

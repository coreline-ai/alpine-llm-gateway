import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "release_readiness", ROOT / "scripts/verify-release-readiness.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class ReleaseReadinessTest(unittest.TestCase):
    def setUp(self):
        self.report = json.loads(
            (ROOT / "distribution/release-readiness.json").read_text()
        )

    def test_repository_report_is_fail_closed_and_evidence_exists(self):
        summary = MODULE.verify(self.report, ROOT)
        self.assertEqual(10, summary["gate_count"])
        self.assertFalse(summary["release_ready"])
        self.assertGreater(summary["release_blocker_count"], 0)

    def test_ready_requires_owner_and_evidence(self):
        report = copy.deepcopy(self.report)
        report["gates"][0].update(
            {"state": "READY", "owner": "UNASSIGNED", "blocker_code": None}
        )
        with self.assertRaises(AssertionError):
            MODULE.verify(report)

    def test_sensitive_fields_and_duplicate_gates_are_rejected(self):
        with self.assertRaises(AssertionError):
            MODULE.scan_sensitive({"api_key": "redacted"})
        report = copy.deepcopy(self.report)
        report["gates"][1]["gate_id"] = report["gates"][0]["gate_id"]
        with self.assertRaises(AssertionError):
            MODULE.verify(report)


if __name__ == "__main__":
    unittest.main()

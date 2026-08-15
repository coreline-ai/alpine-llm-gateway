import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "codex_legal_status", ROOT / "scripts/verify-codex-appserver-legal-status.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class CodexAppServerLegalStatusTest(unittest.TestCase):
    def setUp(self):
        self.report = json.loads(
            (ROOT / "distribution/codex-appserver-legal-status.json").read_text()
        )
        self.artifact_lock = json.loads(
            (
                ROOT
                / "alpine-codex-appserver-pack-android/src/main/resources/"
                "META-INF/codex-appserver/artifact-lock.json"
            ).read_text()
        )

    def test_repository_status_is_valid_and_blocked(self):
        summary = MODULE.verify(self.report, self.artifact_lock)
        self.assertFalse(summary["approved"])
        with self.assertRaises(AssertionError):
            MODULE.verify(self.report, self.artifact_lock, require_approved=True)

    def test_approved_status_requires_reviewer_reference_and_all_decisions(self):
        report = self._approved_report()
        self.assertTrue(MODULE.verify(report, self.artifact_lock, True)["approved"])
        report["reviewer"] = "UNASSIGNED"
        with self.assertRaises(AssertionError):
            MODULE.verify(report, self.artifact_lock)
        report = self._approved_report()
        report["decisions"]["source_provenance"] = "BLOCKED"
        with self.assertRaises(AssertionError):
            MODULE.verify(report, self.artifact_lock)
        report = self._approved_report()
        report["approval_reference"] = "../approval.md"
        with self.assertRaises(AssertionError):
            MODULE.verify(report, self.artifact_lock)

    def test_artifact_lock_drift_is_rejected(self):
        report = copy.deepcopy(self.report)
        report["artifact"]["binary_sha256"] = "a" * 64
        with self.assertRaises(AssertionError):
            MODULE.verify(report, self.artifact_lock)

    def _approved_report(self):
        report = copy.deepcopy(self.report)
        report.update(
            {
                "review_state": "APPROVED",
                "reviewed_at": "2026-08-15T00:00:00Z",
                "reviewer": "LEGAL_REVIEWER",
                "approval_reference": "distribution/evidence/codex-legal-approval.md",
            }
        )
        report["decisions"] = {
            name: "APPROVED" for name in MODULE.DECISION_FIELDS
        }
        return report


if __name__ == "__main__":
    unittest.main()

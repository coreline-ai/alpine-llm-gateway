import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "current_state_release_decision",
    ROOT / "scripts/verify-current-state-release-decision.py",
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class CurrentStateReleaseDecisionTest(unittest.TestCase):
    def setUp(self):
        self.decision = json.loads(
            (ROOT / "distribution/current-state-release-decision.json").read_text()
        )

    def test_repository_decision_is_authorized_and_evidence_exists(self):
        summary = MODULE.verify(self.decision, ROOT)
        self.assertTrue(summary["authorized"])
        self.assertEqual("CURRENT_STATE_OWNER_DECISION", summary["mode"])

    def test_decision_cannot_relabel_missing_evidence(self):
        decision = copy.deepcopy(self.decision)
        decision["acceptance"]["unexecuted_checks_remain_not_run"] = False
        with self.assertRaises(AssertionError):
            MODULE.verify(decision, ROOT)

    def test_missing_owner_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(AssertionError):
                MODULE.verify(self.decision, Path(directory))

    def test_sensitive_fields_are_rejected(self):
        decision = copy.deepcopy(self.decision)
        decision["private_key"] = "redacted"
        with self.assertRaises(AssertionError):
            MODULE.verify(decision)


if __name__ == "__main__":
    unittest.main()

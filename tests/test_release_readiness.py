import copy
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "release_readiness", ROOT / "scripts/verify-release-readiness.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


def load_verifier(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(module)
    return module


ACCOUNT_E2E = load_verifier(
    "account_e2e", ROOT / "scripts/verify-codex-appserver-e2e-report.py"
)
ARM64_16K = load_verifier(
    "arm64_16k", ROOT / "scripts/verify-codex-appserver-16k-report.py"
)
LEGAL_STATUS = load_verifier(
    "legal_status", ROOT / "scripts/verify-codex-appserver-legal-status.py"
)


class ReleaseReadinessTest(unittest.TestCase):
    def setUp(self):
        self.report = json.loads(
            (ROOT / "distribution/release-readiness.json").read_text()
        )

    def test_repository_report_is_fail_closed_and_evidence_exists(self):
        summary = MODULE.verify(self.report, ROOT)
        self.assertEqual(13, summary["gate_count"])
        self.assertFalse(summary["release_ready"])
        self.assertGreater(summary["release_blocker_count"], 0)

    def test_owner_current_state_decision_authorizes_without_fabricating_ready_gates(self):
        summary = MODULE.verify(self.report, ROOT)
        decision = MODULE.verify_current_state_release_decision(ROOT)
        self.assertFalse(summary["release_ready"])
        self.assertTrue(decision["authorized"])
        self.assertEqual("CURRENT_STATE_OWNER_DECISION", decision["mode"])

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

    def test_codex_ready_gate_requires_executed_non_template_evidence(self):
        for gate_id in (
            "codex_appserver_e2e",
            "codex_appserver_16k",
            "codex_appserver_legal",
        ):
            report = copy.deepcopy(self.report)
            gate = next(gate for gate in report["gates"] if gate["gate_id"] == gate_id)
            gate.update(
                {
                    "state": "READY",
                    "owner": "LOCAL_ENGINEERING",
                    "blocker_code": None,
                }
            )
            with self.assertRaises(AssertionError):
                MODULE.verify(report, ROOT)

    def test_codex_ready_evidence_uses_strict_domain_verifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_verifier(root, "verify-codex-appserver-e2e-report.py")
            account_path = Path(MODULE.CODEX_EXECUTED_EVIDENCE["codex_appserver_e2e"][0])
            self._write_json(root / account_path, self._account_report())
            MODULE.verify_executed_codex_evidence(
                "codex_appserver_e2e", [str(account_path)], root
            )

            self._copy_verifier(root, "verify-codex-appserver-16k-report.py")
            sixteen_path = Path(MODULE.CODEX_EXECUTED_EVIDENCE["codex_appserver_16k"][0])
            report_16k = self._sixteen_k_report()
            self._write_json(root / sixteen_path, report_16k)
            MODULE.verify_executed_codex_evidence(
                "codex_appserver_16k", [str(sixteen_path)], root
            )

            report_16k["device"]["page_size"] = 4096
            self._write_json(root / sixteen_path, report_16k)
            with self.assertRaises(AssertionError):
                MODULE.verify_executed_codex_evidence(
                    "codex_appserver_16k", [str(sixteen_path)], root
                )

            self._copy_verifier(root, "verify-codex-appserver-legal-status.py")
            legal_path = root / MODULE.CODEX_LEGAL_STATUS
            lock_path = root / MODULE.CODEX_ARTIFACT_LOCK
            approval_path = root / "distribution/evidence/codex-legal-approval.md"
            legal_report = json.loads(
                (ROOT / "distribution/codex-appserver-legal-status.json").read_text()
            )
            legal_report.update(
                {
                    "review_state": "APPROVED",
                    "reviewed_at": "2026-08-15T00:00:00Z",
                    "reviewer": "LEGAL_REVIEWER",
                    "approval_reference": str(approval_path.relative_to(root)),
                    "decisions": {
                        name: "APPROVED" for name in LEGAL_STATUS.DECISION_FIELDS
                    },
                }
            )
            self._write_json(legal_path, legal_report)
            lock_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / MODULE.CODEX_ARTIFACT_LOCK, lock_path)
            approval_path.parent.mkdir(parents=True, exist_ok=True)
            approval_path.write_text("approved\n")
            legal_evidence = [
                MODULE.CODEX_LEGAL_STATUS,
                MODULE.CODEX_ARTIFACT_LOCK,
                str(approval_path.relative_to(root)),
            ]
            MODULE.verify_codex_legal_evidence(legal_evidence, root)
            approval_path.unlink()
            with self.assertRaises(AssertionError):
                MODULE.verify_codex_legal_evidence(legal_evidence, root)

    def _account_report(self):
        report = json.loads(
            (ROOT / "integration-fixtures/codex-appserver-e2e/report.template.json").read_text()
        )
        report.update(
            {
                "executed": True,
                "executed_at": "2026-08-15T00:00:00Z",
                "build_commit": "abcdef1",
                "checks": {
                    name: {"status": "PASS"} for name in ACCOUNT_E2E.REQUIRED_CHECKS
                },
                "destructive_checks": {
                    name: {"status": "NOT_RUN"}
                    for name in ACCOUNT_E2E.DESTRUCTIVE_CHECKS
                },
            }
        )
        report["device"].update(
            {"model": "SM-S931N", "api_level": 36, "page_size": 4096}
        )
        return report

    def _sixteen_k_report(self):
        report = json.loads(
            (ROOT / "integration-fixtures/codex-appserver-16k/report.template.json").read_text()
        )
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
                    name: {"status": "PASS"} for name in ARM64_16K.REQUIRED_CHECKS
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

    @staticmethod
    def _copy_verifier(root, name):
        destination = root / "scripts" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "scripts" / name, destination)

    @staticmethod
    def _write_json(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Validate fail-closed legal/provenance approval for the pinned Codex artifact."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


ARTIFACT_FIELDS = {
    "codex_version",
    "source_tag",
    "source_tag_object",
    "source_commit",
    "source_tag_signature",
    "npm_package",
    "binary_sha256",
    "schema_sha256",
    "license_sha256",
    "notice_sha256",
}
DECISION_FIELDS = {
    "binary_redistribution",
    "license_notice",
    "sbom",
    "source_provenance",
    "unsigned_tag_exception",
}
ALLOWED_REVIEW_STATES = {"BLOCKED", "APPROVED"}
ALLOWED_DECISIONS = {"BLOCKED", "APPROVED"}
REVIEWER = re.compile(r"[A-Z0-9_.@-]{2,80}")
SHA256 = re.compile(r"[0-9a-f]{64}")
REVIEW_TIME = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
APPROVAL_REFERENCE = re.compile(r"distribution/evidence/[A-Za-z0-9._/-]{1,200}")
SENSITIVE = re.compile(
    r"(^|_)(token|authorization|cookie|password|secret|api_key|private_key)($|_)",
    re.IGNORECASE,
)


def fail(message: str) -> None:
    raise AssertionError(message)


def scan_sensitive(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if SENSITIVE.search(str(key)):
                fail(f"forbidden sensitive field at {path}.{key}")
            scan_sensitive(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            scan_sensitive(child, f"{path}[{index}]")
    elif isinstance(value, str) and len(value) > 512:
        fail(f"oversized value at {path}")


def verify(
    report: dict[str, Any],
    artifact_lock: dict[str, Any],
    require_approved: bool = False,
) -> dict[str, Any]:
    scan_sensitive(report)
    if report.get("schema_version") != 1:
        fail("schema_version must be 1")
    state = report.get("review_state")
    if state not in ALLOWED_REVIEW_STATES:
        fail("review_state must be BLOCKED or APPROVED")
    reviewer = report.get("reviewer")
    if not isinstance(reviewer, str) or not REVIEWER.fullmatch(reviewer):
        fail("reviewer is invalid")
    artifact = report.get("artifact")
    if not isinstance(artifact, dict) or set(artifact) != ARTIFACT_FIELDS:
        fail(f"artifact must contain exactly {sorted(ARTIFACT_FIELDS)}")
    for field in ARTIFACT_FIELDS:
        if artifact.get(field) != artifact_lock.get(field):
            fail(f"artifact field {field} does not match artifact lock")
    for field in ("binary_sha256", "schema_sha256", "license_sha256", "notice_sha256"):
        if not isinstance(artifact[field], str) or not SHA256.fullmatch(artifact[field]):
            fail(f"artifact field {field} must be a lowercase SHA-256")

    decisions = report.get("decisions")
    if not isinstance(decisions, dict) or set(decisions) != DECISION_FIELDS:
        fail(f"decisions must contain exactly {sorted(DECISION_FIELDS)}")
    if any(value not in ALLOWED_DECISIONS for value in decisions.values()):
        fail("every legal decision must be BLOCKED or APPROVED")

    reviewed_at = report.get("reviewed_at")
    approval_reference = report.get("approval_reference")
    if state == "BLOCKED":
        if reviewer != "UNASSIGNED":
            fail("a BLOCKED review must remain UNASSIGNED")
        if reviewed_at is not None or approval_reference is not None:
            fail("a BLOCKED review must not claim approval metadata")
        if any(value != "BLOCKED" for value in decisions.values()):
            fail("a BLOCKED review must keep every decision BLOCKED")
    else:
        if reviewer == "UNASSIGNED":
            fail("an APPROVED review requires an assigned reviewer")
        if not isinstance(reviewed_at, str) or not REVIEW_TIME.fullmatch(reviewed_at):
            fail("an APPROVED review requires UTC reviewed_at")
        if (
            not isinstance(approval_reference, str)
            or not APPROVAL_REFERENCE.fullmatch(approval_reference)
            or Path(approval_reference).is_absolute()
            or ".." in Path(approval_reference).parts
        ):
            fail("an APPROVED review requires a safe distribution evidence reference")
        if any(value != "APPROVED" for value in decisions.values()):
            fail("an APPROVED review requires every decision APPROVED")
        if artifact["source_tag_signature"] == "unsigned" and (
            decisions["unsigned_tag_exception"] != "APPROVED"
        ):
            fail("unsigned source tag requires an explicit approved exception")
    if require_approved and state != "APPROVED":
        fail("an APPROVED legal/provenance review is required")
    return {
        "artifact_version": artifact["codex_version"],
        "review_state": state,
        "approved": state == "APPROVED",
        "approval_reference": approval_reference,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "report",
        type=Path,
        nargs="?",
        default=Path("distribution/codex-appserver-legal-status.json"),
    )
    parser.add_argument(
        "--artifact-lock",
        type=Path,
        default=Path(
            "alpine-codex-appserver-pack-android/src/main/resources/"
            "META-INF/codex-appserver/artifact-lock.json"
        ),
    )
    parser.add_argument("--require-approved", action="store_true")
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text())
        artifact_lock = json.loads(args.artifact_lock.read_text())
        if not isinstance(report, dict) or not isinstance(artifact_lock, dict):
            fail("report and artifact lock roots must be objects")
        summary = verify(report, artifact_lock, args.require_approved)
    except (OSError, json.JSONDecodeError, AssertionError) as error:
        print(f"Codex App Server legal status verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

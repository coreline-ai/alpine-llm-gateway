#!/usr/bin/env python3
"""Validate the explicit project-owner decision to distribute the current state."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


EXPECTED_ACCEPTANCE = {
    "current_evidence_as_is": True,
    "unexecuted_checks_remain_not_run": True,
    "blocked_reviews_remain_blocked": True,
    "signing_and_destination_still_required_for_upload": True,
}
UTC_TIME = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
SAFE_REFERENCE = re.compile(r"distribution/evidence/[A-Za-z0-9._/-]{1,200}")
SENSITIVE = re.compile(
    r"(^|_)(token|cookie|password|secret|api_key|private_key)($|_)",
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


def verify(decision: dict[str, Any], root: Path | None = None) -> dict[str, Any]:
    scan_sensitive(decision)
    if decision.get("schema_version") != 1:
        fail("schema_version must be 1")
    if decision.get("decision_state") != "PROCEED_CURRENT_STATE":
        fail("decision_state must be PROCEED_CURRENT_STATE")
    if decision.get("scope") != "FULL_PRODUCT_PUBLIC_DISTRIBUTION":
        fail("scope must be FULL_PRODUCT_PUBLIC_DISTRIBUTION")
    if decision.get("decided_by_role") != "PROJECT_OWNER":
        fail("decided_by_role must be PROJECT_OWNER")
    decided_at = decision.get("decided_at")
    if not isinstance(decided_at, str) or not UTC_TIME.fullmatch(decided_at):
        fail("decided_at must be a UTC timestamp")
    reference = decision.get("evidence_reference")
    if (
        not isinstance(reference, str)
        or not SAFE_REFERENCE.fullmatch(reference)
        or Path(reference).is_absolute()
        or ".." in Path(reference).parts
    ):
        fail("evidence_reference must be a safe distribution evidence path")
    if decision.get("acceptance") != EXPECTED_ACCEPTANCE:
        fail("acceptance must preserve the exact current-state decision contract")
    if root is not None and not (root / reference).is_file():
        fail("release owner decision evidence is missing")
    return {
        "authorized": True,
        "mode": "CURRENT_STATE_OWNER_DECISION",
        "scope": decision["scope"],
        "decided_at": decided_at,
        "evidence_reference": reference,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "decision",
        type=Path,
        nargs="?",
        default=Path("distribution/current-state-release-decision.json"),
    )
    parser.add_argument("--check-evidence", action="store_true")
    args = parser.parse_args()
    try:
        decision = json.loads(args.decision.read_text())
        if not isinstance(decision, dict):
            fail("decision root must be an object")
        root = Path(__file__).resolve().parents[1] if args.check_evidence else None
        summary = verify(decision, root)
    except (OSError, json.JSONDecodeError, AssertionError) as error:
        print(f"Current-state release decision verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


EXPECTED_GATES = {
    "project_license",
    "corresponding_source",
    "provider_accounts",
    "play_test_track",
    "samsung_lifecycle_window",
    "github_remote_ci",
    "release_destination",
    "terminal_dynamic_resize",
    "x86_64_emulator",
    "gradle9_migration",
}
ALLOWED_STATES = {"READY", "BLOCKED", "NOT_REQUIRED"}
ID_PATTERN = re.compile(r"[a-z0-9_]{2,64}")
OWNER_PATTERN = re.compile(r"[A-Z0-9_.@-]{2,80}")
BLOCKER_PATTERN = re.compile(r"[A-Z0-9_]{3,100}")
SENSITIVE = re.compile(
    r"(^|_)(token|authorization|cookie|password|secret|api_key|private_key)($|_)",
    re.IGNORECASE,
)
TOKEN_LIKE = (
    re.compile(r"\bBearer\s+[A-Za-z0-9._~+/-]+=*", re.IGNORECASE),
    re.compile(r"\bsk-[A-Za-z0-9_-]{12,}"),
    re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"),
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
    elif isinstance(value, str):
        if len(value) > 1024:
            fail(f"oversized value at {path}")
        if any(pattern.search(value) for pattern in TOKEN_LIKE):
            fail(f"token-like value found at {path}")


def verify(report: dict[str, Any], root: Path | None = None) -> dict[str, Any]:
    scan_sensitive(report)
    if report.get("schema_version") != 1:
        fail("schema_version must be 1")
    if not isinstance(report.get("updated_at"), str) or not report["updated_at"]:
        fail("updated_at is required")
    gates = report.get("gates")
    if not isinstance(gates, list):
        fail("gates must be an array")

    seen: set[str] = set()
    blockers: list[str] = []
    release_blockers: list[str] = []
    for index, gate in enumerate(gates):
        if not isinstance(gate, dict):
            fail(f"gate {index} must be an object")
        gate_id = gate.get("gate_id")
        if not isinstance(gate_id, str) or not ID_PATTERN.fullmatch(gate_id):
            fail(f"gate {index} has an invalid gate_id")
        if gate_id in seen:
            fail(f"duplicate gate_id: {gate_id}")
        seen.add(gate_id)
        state = gate.get("state")
        owner = gate.get("owner")
        release_blocking = gate.get("release_blocking")
        blocker_code = gate.get("blocker_code")
        evidence = gate.get("evidence")
        if state not in ALLOWED_STATES:
            fail(f"gate {gate_id} has an invalid state")
        if not isinstance(owner, str) or not OWNER_PATTERN.fullmatch(owner):
            fail(f"gate {gate_id} has an invalid owner")
        if not isinstance(release_blocking, bool):
            fail(f"gate {gate_id} release_blocking must be boolean")
        if not isinstance(evidence, list) or any(not isinstance(path, str) for path in evidence):
            fail(f"gate {gate_id} evidence must be an array of paths")
        if any(Path(path).is_absolute() or ".." in Path(path).parts for path in evidence):
            fail(f"gate {gate_id} evidence must use safe repository-relative paths")
        if state == "BLOCKED":
            if not isinstance(blocker_code, str) or not BLOCKER_PATTERN.fullmatch(blocker_code):
                fail(f"gate {gate_id} requires a stable blocker_code")
            blockers.append(blocker_code)
            if release_blocking:
                release_blockers.append(blocker_code)
        elif blocker_code is not None:
            fail(f"gate {gate_id} must not have blocker_code when state is {state}")
        if state == "READY" and owner == "UNASSIGNED":
            fail(f"gate {gate_id} cannot be READY without an owner")
        if state == "READY" and not evidence:
            fail(f"gate {gate_id} cannot be READY without evidence")
        if root is not None:
            for relative in evidence:
                if not (root / relative).is_file():
                    fail(f"gate {gate_id} evidence is missing: {relative}")

    if seen != EXPECTED_GATES:
        fail(f"gates must contain exactly {sorted(EXPECTED_GATES)}")
    return {
        "gate_count": len(gates),
        "blocked_count": len(blockers),
        "release_blocker_count": len(release_blockers),
        "release_ready": not release_blockers,
        "blocker_codes": sorted(blockers),
        "release_blocker_codes": sorted(release_blockers),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--check-evidence", action="store_true")
    parser.add_argument("--require-release-ready", action="store_true")
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text())
        if not isinstance(report, dict):
            fail("report root must be an object")
        root = Path(__file__).resolve().parents[1] if args.check_evidence else None
        summary = verify(report, root)
        if args.require_release_ready and not summary["release_ready"]:
            fail("release-blocking gates remain")
    except (OSError, json.JSONDecodeError, AssertionError) as error:
        print(f"Release readiness verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

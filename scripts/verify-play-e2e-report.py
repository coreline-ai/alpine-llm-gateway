#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


REQUIRED_CHECKS = {
    "install_fetch",
    "runtime_start",
    "terminal",
    "app_update",
    "offline_restart",
    "fetch_cancel",
    "network_loss",
    "checksum_mismatch",
    "native_boundary",
    "rollback",
}
ALLOWED_STATUS = {"PASS", "FAIL", "NOT_RUN"}
ALLOWED_TRACKS = {"internal", "closed", "open"}
FORBIDDEN_KEYS = re.compile(
    r"(^|_)(password|secret|token|authorization|cookie|signing_key|keystore|raw_request|raw_response|prompt|command)($|_)",
    re.IGNORECASE,
)
TOKEN_VALUES = (
    re.compile(r"\bBearer\s+[A-Za-z0-9._~+/-]+=*", re.IGNORECASE),
    re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"),
)


def fail(message: str) -> None:
    raise AssertionError(message)


def scan_sensitive(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if FORBIDDEN_KEYS.search(str(key)):
                fail(f"forbidden sensitive field at {path}.{key}")
            scan_sensitive(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            scan_sensitive(child, f"{path}[{index}]")
    elif isinstance(value, str):
        if len(value) > 1024:
            fail(f"oversized value at {path}")
        if any(pattern.search(value) for pattern in TOKEN_VALUES):
            fail(f"token-like value found at {path}")


def verify(report: dict[str, Any], require_executed: bool) -> dict[str, Any]:
    scan_sensitive(report)
    if report.get("schema_version") != 1:
        fail("schema_version must be 1")
    executed = report.get("executed")
    if not isinstance(executed, bool):
        fail("executed must be boolean")
    checks = report.get("checks")
    if not isinstance(checks, dict):
        fail("checks must be an object")
    if not executed:
        if require_executed:
            fail("an executed Play report is required")
        if checks:
            fail("a non-executed report must not contain check results")
        return {"executed": False, "check_count": 0, "passed": False}

    for field in ("executed_at", "approval_reference", "application_id", "app_version", "asset_pack_name"):
        value = report.get(field)
        if not isinstance(value, str) or not value.strip() or len(value) > 256:
            fail(f"executed report requires bounded {field}")
    if report.get("track") not in ALLOWED_TRACKS:
        fail("executed report requires a Play test track")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", report["application_id"]):
        fail("application_id is invalid")
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,49}", report["asset_pack_name"]):
        fail("asset_pack_name is invalid")
    device = report.get("device")
    if not isinstance(device, dict) or not isinstance(device.get("model"), str):
        fail("executed report requires device metadata")
    if not isinstance(device.get("api_level"), int) or device["api_level"] < 26:
        fail("device api_level is invalid")
    if set(checks) != REQUIRED_CHECKS:
        fail(f"checks must contain exactly {sorted(REQUIRED_CHECKS)}")
    passed = True
    for name, check in checks.items():
        if not isinstance(check, dict) or check.get("status") not in ALLOWED_STATUS:
            fail(f"check {name} has an invalid status")
        if check["status"] != "PASS":
            passed = False
        if "error_code" in check and not re.fullmatch(r"[A-Z0-9_]{2,80}", check["error_code"]):
            fail(f"check {name} has an invalid error_code")
    return {"executed": True, "check_count": len(checks), "passed": passed}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--require-executed", action="store_true")
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text())
        if not isinstance(report, dict):
            fail("report root must be an object")
        summary = verify(report, args.require_executed)
    except (OSError, json.JSONDecodeError, AssertionError) as error:
        print(f"Play E2E report verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

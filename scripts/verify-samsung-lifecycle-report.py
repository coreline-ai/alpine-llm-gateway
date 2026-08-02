#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


REQUIRED_CHECKS = {
    "foreground_background",
    "notification_stop",
    "ui_stop",
    "process_kill",
    "reboot",
    "doze",
    "battery_restricted",
    "notification_denied",
    "no_auto_replay",
    "no_stale_running",
}
ALLOWED_STATUS = {"PASS", "FAIL", "NOT_RUN"}
FORBIDDEN_KEYS = re.compile(
    r"(^|_)(token|authorization|cookie|password|secret|api_key|prompt|command|raw_log|raw_request|raw_response)($|_)",
    re.IGNORECASE,
)
TOKEN_VALUE = re.compile(r"\b(Bearer\s+|sk-|eyJ)[A-Za-z0-9._~+/-]{12,}", re.IGNORECASE)


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
        if TOKEN_VALUE.search(value):
            fail(f"token-like value found at {path}")


def verify(report: dict[str, Any], require_executed: bool) -> dict[str, Any]:
    scan_sensitive(report)
    if report.get("schema_version") != 1:
        fail("schema_version must be 1")
    executed = report.get("executed")
    checks = report.get("checks")
    if not isinstance(executed, bool):
        fail("executed must be boolean")
    if not isinstance(checks, dict):
        fail("checks must be an object")
    if not executed:
        if require_executed:
            fail("an executed Samsung lifecycle report is required")
        if checks:
            fail("a non-executed report must not contain check results")
        return {"executed": False, "check_count": 0, "passed": False}

    for field in ("executed_at", "approval_reference"):
        value = report.get(field)
        if not isinstance(value, str) or not value.strip() or len(value) > 256:
            fail(f"executed report requires bounded {field}")
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
        print(f"Samsung lifecycle report verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Validate a redacted Samsung Codex App Server E2E evidence report."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


REQUIRED_CHECKS = {
    "install_upgrade",
    "browser_login_callback",
    "browser_cancel_timeout",
    "model_list",
    "first_turn",
    "second_turn",
    "stop_interrupt_once",
    "next_turn_no_replay",
    "background_foreground",
    "rotation_recreation",
    "cold_start_account_read",
    "credential_refresh",
    "controlled_restart",
    "logout_data_isolation",
    "no_orphan_process",
    "runtime_isolation",
    "redaction_audit",
    "feature_off_rollback",
}
DESTRUCTIVE_CHECKS = {
    "process_kill",
    "doze",
    "network_loss",
    "force_stop",
}
ALLOWED_STATUS = {"PASS", "FAIL", "NOT_RUN", "BLOCKED"}
FORBIDDEN_KEYS = re.compile(
    r"(^|_)(token|authorization|cookie|password|secret|api_key|prompt|query|"
    r"account_email|account_id|raw_log|raw_request|raw_response)($|_)",
    re.IGNORECASE,
)
TOKEN_VALUE = re.compile(
    r"\b(Bearer\s+|sk-|eyJ)[A-Za-z0-9._~+/-]{12,}",
    re.IGNORECASE,
)
COMMIT = re.compile(r"[0-9a-f]{7,40}")


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
        if len(value) > 512:
            fail(f"oversized evidence value at {path}")
        if TOKEN_VALUE.search(value) or "redirect_uri=" in value or "oauth/authorize?" in value:
            fail(f"credential or auth URL material found at {path}")


def verify(report: dict[str, Any], require_executed: bool = False) -> dict[str, Any]:
    scan_sensitive(report)
    if report.get("schema_version") != 1:
        fail("schema_version must be 1")
    executed = report.get("executed")
    if not isinstance(executed, bool):
        fail("executed must be boolean")
    checks = report.get("checks")
    if not isinstance(checks, dict):
        fail("checks must be an object")
    destructive_approved = report.get("destructive_tests_approved")
    if not isinstance(destructive_approved, bool):
        fail("destructive_tests_approved must be boolean")
    destructive_checks = report.get("destructive_checks")
    if not isinstance(destructive_checks, dict):
        fail("destructive_checks must be an object")
    if not executed:
        if require_executed:
            fail("an executed Codex App Server E2E report is required")
        if checks:
            fail("a non-executed report must not contain checks")
        if destructive_approved or destructive_checks:
            fail("a non-executed report must not approve or contain destructive checks")
        return {
            "executed": False,
            "check_count": 0,
            "destructive_check_count": 0,
            "passed": False,
        }

    if not isinstance(report.get("executed_at"), str) or not report["executed_at"]:
        fail("executed_at is required")
    if not isinstance(report.get("build_commit"), str) or not COMMIT.fullmatch(
        report["build_commit"]
    ):
        fail("build_commit must be a git commit")
    device = report.get("device")
    if not isinstance(device, dict):
        fail("device metadata is required")
    if str(device.get("manufacturer", "")).lower() != "samsung":
        fail("Samsung device evidence is required")
    if not isinstance(device.get("model"), str) or not device["model"].strip():
        fail("device model is required")
    if not isinstance(device.get("api_level"), int) or device["api_level"] < 26:
        fail("device API level is invalid")
    if device.get("page_size") not in {4096, 16384}:
        fail("device page_size must be 4096 or 16384")
    if set(checks) != REQUIRED_CHECKS:
        fail(f"checks must contain exactly {sorted(REQUIRED_CHECKS)}")
    if set(destructive_checks) != DESTRUCTIVE_CHECKS:
        fail(
            "destructive_checks must contain exactly "
            f"{sorted(DESTRUCTIVE_CHECKS)}"
        )

    passed = True
    for name, check in checks.items():
        if not isinstance(check, dict) or check.get("status") not in ALLOWED_STATUS:
            fail(f"check {name} has an invalid status")
        if check["status"] != "PASS":
            passed = False
        if "error_code" in check and not re.fullmatch(
            r"[A-Z0-9_]{2,100}", str(check["error_code"])
        ):
            fail(f"check {name} has an invalid error_code")
    for name, check in destructive_checks.items():
        if not isinstance(check, dict) or check.get("status") not in ALLOWED_STATUS:
            fail(f"destructive check {name} has an invalid status")
        if "error_code" in check and not re.fullmatch(
            r"[A-Z0-9_]{2,100}", str(check["error_code"])
        ):
            fail(f"destructive check {name} has an invalid error_code")
        if not destructive_approved and check["status"] != "NOT_RUN":
            fail(f"unapproved destructive check {name} must be NOT_RUN")
        if destructive_approved and check["status"] != "PASS":
            passed = False
    return {
        "executed": True,
        "check_count": len(checks),
        "destructive_check_count": len(destructive_checks),
        "destructive_tests_approved": destructive_approved,
        "passed": passed,
    }


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
        print(f"Codex App Server E2E report verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

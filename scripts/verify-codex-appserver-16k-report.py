#!/usr/bin/env python3
"""Validate redacted ARM64 16 KiB Codex App Server release evidence."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


REQUIRED_CHECKS = {
    "apk_16k_alignment",
    "install_start",
    "browser_login_callback",
    "account_read",
    "model_list",
    "first_turn_stream",
    "stop_interrupt_once",
    "logout",
    "no_orphan_process",
    "aab_size_measurement",
    "aab_delivery_install",
    "low_storage_install",
    "update_install",
    "feature_off_rollback",
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
SHA256 = re.compile(r"[0-9a-f]{64}")
MEASUREMENT_KEYS = {"aab_bytes", "download_bytes", "installed_bytes"}


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
    measurements = report.get("measurements")
    if not isinstance(checks, dict):
        fail("checks must be an object")
    if not isinstance(measurements, dict):
        fail("measurements must be an object")
    if not executed:
        if require_executed:
            fail("an executed ARM64 16 KiB E2E report is required")
        if checks or measurements:
            fail("a non-executed report must not contain checks or measurements")
        return {
            "executed": False,
            "check_count": 0,
            "page_size": None,
            "passed": False,
        }

    if not isinstance(report.get("executed_at"), str) or not report["executed_at"]:
        fail("executed_at is required")
    if not isinstance(report.get("build_commit"), str) or not COMMIT.fullmatch(
        report["build_commit"]
    ):
        fail("build_commit must be a git commit")
    if not isinstance(report.get("artifact_sha256"), str) or not SHA256.fullmatch(
        report["artifact_sha256"]
    ):
        fail("artifact_sha256 must be a lowercase SHA-256")
    device = report.get("device")
    if not isinstance(device, dict):
        fail("device metadata is required")
    if not isinstance(device.get("manufacturer"), str) or not device["manufacturer"].strip():
        fail("device manufacturer is required")
    if not isinstance(device.get("model"), str) or not device["model"].strip():
        fail("device model is required")
    if not isinstance(device.get("api_level"), int) or device["api_level"] < 26:
        fail("device API level is invalid")
    if device.get("page_size") != 16384:
        fail("device page_size must be exactly 16384")
    if device.get("abi") != "arm64-v8a":
        fail("device abi must be arm64-v8a")
    if set(checks) != REQUIRED_CHECKS:
        fail(f"checks must contain exactly {sorted(REQUIRED_CHECKS)}")
    if set(measurements) != MEASUREMENT_KEYS:
        fail(f"measurements must contain exactly {sorted(MEASUREMENT_KEYS)}")
    for name, value in measurements.items():
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            fail(f"measurement {name} must be a positive integer")

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
    return {
        "executed": True,
        "check_count": len(checks),
        "page_size": device["page_size"],
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
        print(f"Codex App Server 16 KiB E2E report verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

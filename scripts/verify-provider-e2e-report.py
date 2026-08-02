#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


FORBIDDEN_KEYS = re.compile(
    r"(^|_)(access_token|refresh_token|id_token|authorization|cookie|password|secret|api_key|raw_request|raw_response)($|_)",
    re.IGNORECASE,
)
FORBIDDEN_VALUES = (
    re.compile(r"\bBearer\s+[A-Za-z0-9._~+/-]+=*", re.IGNORECASE),
    re.compile(r"\bsk-[A-Za-z0-9_-]{12,}"),
    re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"),
)
REQUIRED_CHECKS = {"models", "non_stream", "stream", "cancel", "logout"}
ALLOWED_STATUS = {"PASS", "FAIL", "NOT_RUN"}
IDEMPOTENCY_POLICIES = {"NEVER_AUTOMATIC", "IDEMPOTENT_WITH_STABLE_KEY"}
IDEMPOTENCY_REVIEW_STATUS = {"REVIEWED", "BLOCKED"}


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
        if any(pattern.search(value) for pattern in FORBIDDEN_VALUES):
            fail(f"token-like value found at {path}")
        if len(value) > 2048:
            fail(f"oversized free-form value at {path}")


def verify(report: dict[str, Any], require_executed: bool) -> dict[str, Any]:
    scan_sensitive(report)
    if report.get("schema_version") != 1:
        fail("schema_version must be 1")
    executed = report.get("executed")
    if not isinstance(executed, bool):
        fail("executed must be boolean")
    providers = report.get("providers")
    if not isinstance(providers, list):
        fail("providers must be an array")
    if not executed:
        if require_executed:
            fail("an executed report is required")
        if providers:
            fail("a non-executed report must not contain provider results")
        return {"executed": False, "provider_count": 0, "passed": False}
    if not report.get("executed_at") or not report.get("approval_reference"):
        fail("executed reports require executed_at and approval_reference")
    if not providers:
        fail("executed reports require at least one provider")

    all_passed = True
    seen = set()
    for index, provider in enumerate(providers):
        if not isinstance(provider, dict):
            fail(f"provider {index} must be an object")
        provider_id = provider.get("provider_id")
        if not isinstance(provider_id, str) or not re.fullmatch(r"[a-z0-9_-]{2,40}", provider_id):
            fail(f"provider {index} has an invalid provider_id")
        if provider_id in seen:
            fail(f"duplicate provider_id: {provider_id}")
        seen.add(provider_id)
        checks = provider.get("checks")
        if not isinstance(checks, dict) or set(checks) != REQUIRED_CHECKS:
            fail(f"provider {provider_id} must contain exactly {sorted(REQUIRED_CHECKS)}")
        for name, check in checks.items():
            if not isinstance(check, dict) or check.get("status") not in ALLOWED_STATUS:
                fail(f"provider {provider_id}/{name} has an invalid status")
            if check["status"] != "PASS":
                all_passed = False
            if "error_code" in check and not re.fullmatch(r"[A-Z0-9_]{2,80}", check["error_code"]):
                fail(f"provider {provider_id}/{name} has an invalid error_code")
        idempotency = provider.get("idempotency")
        if not isinstance(idempotency, dict):
            fail(f"provider {provider_id} requires an idempotency contract")
        expected_idempotency_keys = {
            "policy",
            "automatic_retry_enabled",
            "review_status",
            "evidence_reference",
            "header_name",
        }
        if set(idempotency) != expected_idempotency_keys:
            fail(f"provider {provider_id} has an invalid idempotency contract shape")
        policy = idempotency.get("policy")
        review_status = idempotency.get("review_status")
        automatic_retry = idempotency.get("automatic_retry_enabled")
        evidence = idempotency.get("evidence_reference")
        header_name = idempotency.get("header_name")
        if policy not in IDEMPOTENCY_POLICIES:
            fail(f"provider {provider_id} has an invalid idempotency policy")
        if review_status not in IDEMPOTENCY_REVIEW_STATUS:
            fail(f"provider {provider_id} has an invalid idempotency review status")
        if not isinstance(automatic_retry, bool):
            fail(f"provider {provider_id} automatic_retry_enabled must be boolean")
        if not isinstance(evidence, str) or not 1 <= len(evidence) <= 512:
            fail(f"provider {provider_id} requires a bounded evidence reference")
        if policy == "IDEMPOTENT_WITH_STABLE_KEY":
            if not automatic_retry or not isinstance(header_name, str) or not re.fullmatch(
                r"[A-Za-z0-9-]{1,80}", header_name
            ):
                fail(f"provider {provider_id} stable-key retry contract is incomplete")
        elif automatic_retry or header_name is not None:
            fail(f"provider {provider_id} fail-closed policy cannot enable automatic retry")
        if review_status != "REVIEWED":
            all_passed = False
    return {"executed": True, "provider_count": len(providers), "passed": all_passed}


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
        print(f"Provider E2E report verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

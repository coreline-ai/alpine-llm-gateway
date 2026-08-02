#!/usr/bin/env python3
"""Generate an ABI-specific Alpine package/source compliance inventory."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from compliance_common import alpine_package_inventory, sha256  # noqa: E402


def load_source_mirror(path: Path | None) -> tuple[Path | None, dict[tuple[str, str], dict[str, str]]]:
    if path is None:
        return None, {}
    document = json.loads(path.read_text())
    if document.get("schema_version") != 1 or not isinstance(document.get("sources"), list):
        raise AssertionError("source mirror manifest must use schema_version 1 and sources[]")
    entries: dict[tuple[str, str], dict[str, str]] = {}
    for item in document["sources"]:
        if not isinstance(item, dict):
            raise AssertionError("source mirror entries must be objects")
        key = (item.get("origin"), item.get("package_commit"))
        if not all(isinstance(value, str) and value for value in key):
            raise AssertionError("source mirror origin and package_commit are required")
        if key in entries:
            raise AssertionError(f"duplicate source mirror entry: {key}")
        relative = Path(item.get("path", ""))
        if relative.is_absolute() or ".." in relative.parts:
            raise AssertionError(f"unsafe source mirror path: {relative}")
        source = path.parent / relative
        if not source.is_file():
            raise AssertionError(f"source mirror file is missing: {relative}")
        if sha256(source) != item.get("sha256"):
            raise AssertionError(f"source mirror checksum mismatch: {relative}")
        entries[key] = item
    return path, entries


def build_inventory(
    rootfs: Path,
    abi: str,
    expected_sha256: str | None = None,
    source_mirror: Path | None = None,
) -> dict[str, Any]:
    actual_sha256 = sha256(rootfs)
    if expected_sha256 is not None and actual_sha256 != expected_sha256:
        raise AssertionError(
            f"rootfs checksum mismatch: expected {expected_sha256}, got {actual_sha256}"
        )
    _, mirror = load_source_mirror(source_mirror)
    packages = alpine_package_inventory(rootfs)
    if not packages:
        raise AssertionError("rootfs package inventory is empty")
    architectures = sorted({item["architecture"] for item in packages})
    for item in packages:
        mirror_entry = mirror.get((item["origin"], item["package_commit"]))
        if mirror_entry is not None:
            item["source_mirror_status"] = "VERIFIED"
            item["source_archive"] = mirror_entry["path"]
            item["source_sha256"] = mirror_entry["sha256"]
    required = [item for item in packages if item["source_review_required"]]
    missing = [item for item in required if item["source_mirror_status"] != "VERIFIED"]
    return {
        "schema_version": 1,
        "abi": abi,
        "rootfs": {
            "sha256": actual_sha256,
            "architectures": architectures,
        },
        "package_count": len(packages),
        "source_review_package_count": len(required),
        "source_mirror_verified_count": len(required) - len(missing),
        "source_mirror_complete": not missing,
        "missing_source_origins": sorted(
            {f'{item["origin"]}@{item["package_commit"]}' for item in missing}
        ),
        "packages": packages,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rootfs", type=Path, required=True)
    parser.add_argument("--abi", required=True)
    parser.add_argument("--expected-sha256")
    parser.add_argument("--source-mirror", type=Path)
    parser.add_argument("--require-source-mirror", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        document = build_inventory(
            args.rootfs,
            args.abi,
            args.expected_sha256,
            args.source_mirror,
        )
        if args.require_source_mirror and not document["source_mirror_complete"]:
            raise AssertionError("required Alpine package source mirror is incomplete")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
    except (AssertionError, OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"Alpine inventory generation failed: {error}", file=sys.stderr)
        return 1
    print(
        json.dumps(
            {
                "abi": document["abi"],
                "package_count": document["package_count"],
                "source_mirror_complete": document["source_mirror_complete"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

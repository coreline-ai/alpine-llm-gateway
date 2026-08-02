#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


DEPRECATION = re.compile(r"deprecated|incompatible with Gradle 9", re.IGNORECASE)
PROJECT_MARKER = re.compile(r"(?:^|[/\\])(?:build\.gradle\.kts|settings\.gradle\.kts|buildSrc)(?::|\b)")


def audit(text: str) -> dict[str, object]:
    warnings = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped and DEPRECATION.search(stripped) and stripped not in warnings:
            warnings.append(stripped[:1000])
    project_owned = [line for line in warnings if PROJECT_MARKER.search(line)]
    external = [line for line in warnings if line not in project_owned]
    return {
        "schema_version": 1,
        "warning_count": len(warnings),
        "project_owned_warning_count": len(project_owned),
        "external_or_unattributed_warning_count": len(external),
        "project_owned_warnings": project_owned,
        "external_or_unattributed_warnings": external,
        "gradle9_ready": len(warnings) == 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--strict-project", action="store_true")
    args = parser.parse_args()
    try:
        report = audit(args.input.read_text(errors="replace"))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    except OSError as error:
        print(f"Gradle 9 readiness audit failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 1 if args.strict_project and report["project_owned_warning_count"] else 0


if __name__ == "__main__":
    raise SystemExit(main())

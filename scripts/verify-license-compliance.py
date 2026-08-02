#!/usr/bin/env python3
"""Verify release-critical license metadata, provenance, and process boundaries."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from compliance_common import sha256  # noqa: E402


LOCKS = (
    ROOT / "runtime/alpine-3.21.3-arm64.lock.json",
    ROOT / "runtime/alpine-3.21.3-x86_64.lock.json",
    ROOT / "runtime/probe/artifacts.lock.json",
)
SBOMS = (
    ROOT / "alpine-runtime-pack-bundled/src/main/resources/META-INF/alpine-runtime/sbom.spdx.json",
    ROOT / "alpine-runtime-pack-x86_64/src/main/resources/META-INF/alpine-runtime/x86_64/sbom.spdx.json",
)
INVENTORIES = (
    ROOT / "runtime/alpine-package-inventory-arm64-v8a.json",
    ROOT / "runtime/alpine-package-inventory-x86_64.json",
)
ALLOWED_PROVENANCE_CLASSIFICATIONS = {
    "PROJECT_ORIGINAL",
    "THIRD_PARTY_UNMODIFIED",
    "THIRD_PARTY_PATCHED",
    "GENERATED",
    "REFERENCE_ONLY",
    "REVIEW_REQUIRED",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def load_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        fail(f"JSON root must be an object: {path}")
    return value


def verify_provenance(document: dict[str, Any]) -> dict[str, Any]:
    if document.get("schema_version") != 1:
        fail("provenance schema_version must be 1")
    entries = document.get("entries")
    if not isinstance(entries, list) or not entries:
        fail("provenance entries must be a non-empty array")
    seen: set[str] = set()
    pending = 0
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            fail(f"provenance entry {index} must be an object")
        relative = entry.get("path")
        if not isinstance(relative, str) or not relative:
            fail(f"provenance entry {index} path is required")
        path = Path(relative)
        if path.is_absolute() or ".." in path.parts:
            fail(f"unsafe provenance path: {relative}")
        if relative in seen:
            fail(f"duplicate provenance path: {relative}")
        seen.add(relative)
        if not (ROOT / path).is_file():
            fail(f"provenance path is missing: {relative}")
        classification = entry.get("classification")
        if classification not in ALLOWED_PROVENANCE_CLASSIFICATIONS:
            fail(f"invalid provenance classification for {relative}")
        if not isinstance(entry.get("review_status"), str) or not entry["review_status"]:
            fail(f"review_status is required for {relative}")
        if classification == "REVIEW_REQUIRED" or "REQUIRED" in entry["review_status"]:
            pending += 1
    complete = document.get("coverage_status") == "COMPLETE" and pending == 0
    if complete and document.get("external_release_blocking") is True:
        fail("complete provenance cannot remain externally blocking")
    return {"entry_count": len(entries), "pending_count": pending, "complete": complete}


def verify_component_metadata(policy: dict[str, Any]) -> dict[str, Any]:
    if policy.get("schema_version") != 1:
        fail("component policy schema_version must be 1")
    components = policy.get("components")
    if not isinstance(components, dict):
        fail("component policy components are required")
    proot_policy = components["proot"]
    talloc_policy = components["talloc"]
    for lock_path in LOCKS:
        lock = load_object(lock_path)
        proot = lock["proot"]
        talloc = lock["talloc"]
        if proot.get("commit") != proot_policy["revision"]:
            fail(f"PRoot revision mismatch: {lock_path}")
        if proot.get("license_declared") != proot_policy["license_declared"]:
            fail(f"PRoot declared license mismatch: {lock_path}")
        if proot.get("license_concluded") != proot_policy["license_concluded"]:
            fail(f"PRoot concluded license mismatch: {lock_path}")
        if proot.get("license_review_status") != proot_policy["review_status"]:
            fail(f"PRoot review status mismatch: {lock_path}")
        if "license" in proot:
            fail(f"ambiguous legacy PRoot license field remains: {lock_path}")
        if talloc.get("license_declared") != talloc_policy["license_declared"]:
            fail(f"talloc declared license mismatch: {lock_path}")
        if talloc.get("source_sha256") != talloc_policy["source_sha256"]:
            fail(f"talloc source checksum mismatch: {lock_path}")
        if "license" in talloc:
            fail(f"ambiguous legacy talloc license field remains: {lock_path}")

    for sbom_path in SBOMS:
        sbom = load_object(sbom_path)
        packages = {item["SPDXID"]: item for item in sbom["packages"]}
        proot = packages["SPDXRef-PRoot"]
        talloc = packages["SPDXRef-Talloc"]
        if proot.get("licenseDeclared") != proot_policy["license_declared"]:
            fail(f"SBOM PRoot declared license mismatch: {sbom_path}")
        if proot.get("licenseConcluded") != proot_policy["license_concluded"]:
            fail(f"SBOM PRoot concluded license mismatch: {sbom_path}")
        if talloc.get("licenseDeclared") != talloc_policy["license_declared"]:
            fail(f"SBOM talloc license mismatch: {sbom_path}")

    arm_lock = load_object(LOCKS[0])
    x86_lock = load_object(LOCKS[1])
    if arm_lock["sbom"]["sha256"] != sha256(SBOMS[0]):
        fail("arm64 SBOM checksum lock is stale")
    if x86_lock["sbom"]["sha256"] != sha256(SBOMS[1]):
        fail("x86_64 SBOM checksum lock is stale")
    runtime_metadata_files = (
        ROOT / "alpine-runtime-pack-bundled/build.gradle.kts",
        ROOT / "alpine-runtime-pack-bundled/src/main/kotlin/dev/alpine/runtime/pack/bundled/BundledRuntimeArtifactProvider.kt",
        ROOT / "alpine-runtime-pack-x86_64/build.gradle.kts",
        ROOT / "alpine-runtime-pack-x86_64/src/main/kotlin/dev/alpine/runtime/pack/x8664/X8664RuntimeArtifactProvider.kt",
    )
    metadata_text = "\n".join(path.read_text() for path in runtime_metadata_files)
    for checksum in (arm_lock["sbom"]["sha256"], x86_lock["sbom"]["sha256"]):
        if metadata_text.count(checksum) < 2:
            fail(f"SBOM checksum is not synchronized across build/provider metadata: {checksum}")
    if metadata_text.count(proot_policy["license_declared"]) < 4:
        fail("runtime providers do not expose the reviewed PRoot declared license")
    notice_files = (
        ROOT / "distribution/THIRD_PARTY_NOTICES.md",
        ROOT / "alpine-runtime-pack-bundled/SOURCE_OFFER.md",
        ROOT / "alpine-runtime-pack-x86_64/SOURCE_OFFER.md",
    )
    notice_text = "\n".join(path.read_text() for path in notice_files)
    if "PRoot-GPL-2.0-only" in notice_text or re.search(
        r"PRoot Android fork[^\n]*GPL-2\.0-only",
        notice_text,
    ):
        fail("stale PRoot GPL-2.0-only metadata remains in source notices")
    if not (ROOT / "distribution/licenses/PRoot-GPL-2.0-or-later.txt").is_file():
        fail("PRoot GPL-2.0-or-later license text is missing")
    source_mirror_states: list[bool] = []
    for inventory_path, lock in zip(INVENTORIES, (arm_lock, x86_lock), strict=True):
        inventory = load_object(inventory_path)
        if inventory["rootfs"]["sha256"] != lock["rootfs"]["sha256"]:
            fail(f"rootfs inventory checksum mismatch: {inventory_path}")
        mirror_complete = inventory.get("source_mirror_complete")
        if not isinstance(mirror_complete, bool):
            fail(f"rootfs source_mirror_complete must be boolean: {inventory_path}")
        if mirror_complete:
            required = [
                package
                for package in inventory.get("packages", [])
                if package.get("source_review_required") is True
            ]
            if any(
                package.get("source_mirror_status") != "VERIFIED"
                or not package.get("source_archive")
                or not package.get("source_sha256")
                for package in required
            ):
                fail(f"rootfs source mirror completeness is not supported by package entries: {inventory_path}")
        source_mirror_states.append(mirror_complete)
    rootfs_source_mirror_complete = all(source_mirror_states)
    return {
        "proot_declared": proot_policy["license_declared"],
        "proot_concluded": proot_policy["license_concluded"],
        "talloc_source_sha256": talloc_policy["source_sha256"],
        "rootfs_source_mirror_complete": rootfs_source_mirror_complete,
    }


def module_from_build(path: Path) -> str:
    return path.parent.name


def verify_process_boundaries(policy: dict[str, Any]) -> dict[str, Any]:
    boundaries = policy["boundaries"]
    forbidden = set(boundaries["forbidden_system_load_libraries"])
    load_pattern = re.compile(r'System\.loadLibrary\("([^"]+)"\)')
    loaded: set[str] = set()
    for source_root in (ROOT / "alpine-runtime-android/src/main", ROOT / "android/src/main"):
        for path in source_root.rglob("*"):
            if path.suffix not in {".kt", ".java"}:
                continue
            loaded.update(load_pattern.findall(path.read_text(errors="replace")))
    overlap = loaded & forbidden
    if overlap:
        fail(f"forbidden native libraries are loaded by the app: {sorted(overlap)}")

    for path in ROOT.rglob("CMakeLists.txt"):
        if "build" in path.parts:
            continue
        text = path.read_text(errors="replace")
        for library in forbidden:
            if re.search(rf"target_link_libraries\([^)]*\b{re.escape(library)}\b", text, re.S):
                fail(f"forbidden native link to {library}: {path.relative_to(ROOT)}")

    launcher = (
        ROOT
        / "alpine-runtime-android/src/main/kotlin/dev/alpine/runtime/android/internal/ProotProcessLauncher.kt"
    ).read_text()
    if boundaries["required_launcher_boundary"] not in launcher:
        fail("PRoot launcher no longer uses the required process boundary")

    allowed_consumers = set(boundaries["allowed_runtime_pack_consumers"])
    actual_consumers: set[str] = set()
    for build in ROOT.glob("*/build.gradle.kts"):
        text = build.read_text(errors="replace")
        if 'project(":alpine-runtime-pack-bundled")' in text or 'project(":alpine-runtime-pack-x86_64")' in text:
            actual_consumers.add(module_from_build(build))
    unexpected = actual_consumers - allowed_consumers
    if unexpected:
        fail(f"unapproved runtime pack consumers: {sorted(unexpected)}")
    for module in boundaries["fast_chat_modules"]:
        build = (ROOT / module / "build.gradle.kts").read_text(errors="replace")
        if "alpine-runtime-pack" in build:
            fail(f"fast-chat module depends on a runtime payload: {module}")
    return {"loaded_native_libraries": sorted(loaded), "runtime_pack_consumers": sorted(actual_consumers)}


def verify() -> dict[str, Any]:
    policy = load_object(ROOT / "compliance/component-license-policy.json")
    provenance = load_object(ROOT / "compliance/code-provenance.json")
    provenance_summary = verify_provenance(provenance)
    metadata_summary = verify_component_metadata(policy)
    boundary_summary = verify_process_boundaries(policy)
    project_ready = (
        policy["project_code"]["license"] != "UNDECLARED"
        and policy["project_code"]["review_status"] == "APPROVED"
    )
    external_ready = (
        policy.get("distribution_mode") == "EXTERNAL_RELEASE"
        and project_ready
        and provenance_summary["complete"]
        and metadata_summary["proot_concluded"] != "NOASSERTION"
        and metadata_summary["rootfs_source_mirror_complete"]
    )
    return {
        "schema_version": 1,
        "distribution_mode": policy.get("distribution_mode"),
        "project_license_ready": project_ready,
        "provenance": provenance_summary,
        "metadata": metadata_summary,
        "boundaries": boundary_summary,
        "external_distribution_ready": external_ready,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path)
    parser.add_argument("--require-external-ready", action="store_true")
    args = parser.parse_args()
    try:
        report = verify()
        if args.require_external_ready and not report["external_distribution_ready"]:
            fail("external distribution compliance gates remain")
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    except (AssertionError, OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"License compliance verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

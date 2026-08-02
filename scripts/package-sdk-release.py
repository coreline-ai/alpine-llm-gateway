#!/usr/bin/env python3
"""Create a checksummed internal SDK release directory."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

from compliance_common import safe_tar_member


ROOT = Path(__file__).resolve().parents[1]
VERSION = next(
    line.split("=", 1)[1].strip()
    for line in (ROOT / "gradle.properties").read_text().splitlines()
    if line.startswith("VERSION_NAME=")
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def source_bundle_manifest(path: Path) -> dict[str, object]:
    with tarfile.open(path, "r:gz") as archive:
        all_members = archive.getmembers()
        if any(not safe_tar_member(member) for member in all_members):
            raise RuntimeError("native OSS source bundle contains unsafe paths")
        members = [member for member in all_members if member.name.endswith("/manifest.json")]
        if len(members) != 1:
            raise RuntimeError("native OSS source bundle must contain exactly one manifest.json")
        handle = archive.extractfile(members[0])
        if handle is None:
            raise RuntimeError("native OSS source bundle manifest cannot be read")
        manifest = json.loads(handle.read())
    if manifest.get("bundle_kind") != "NATIVE_CORRESPONDING_SOURCE":
        raise RuntimeError("unexpected native OSS source bundle kind")
    if manifest.get("release_version") != VERSION:
        raise RuntimeError("native OSS source bundle release version mismatch")
    if manifest.get("native_components_complete") is not True:
        raise RuntimeError("native OSS source bundle is incomplete")
    return manifest


def main() -> int:
    publication_report = ROOT / "build/reports/phase7-publication.json"
    consumer_report = ROOT / "build/reports/phase7-consumer-matrix.json"
    readiness_report = ROOT / "distribution/release-readiness.json"
    compliance_report = ROOT / "build/reports/license-compliance.json"
    maven_repository = ROOT / "build/maven-repo"
    inventories = (
        ROOT / "runtime/alpine-package-inventory-arm64-v8a.json",
        ROOT / "runtime/alpine-package-inventory-x86_64.json",
    )
    required = (
        publication_report,
        consumer_report,
        readiness_report,
        compliance_report,
        maven_repository,
        *inventories,
    )
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        raise SystemExit(f"Run Phase 7 publication verification first; missing: {missing}")

    stage = ROOT / "build/release-stage" / f"alpine-sdk-{VERSION}"
    destination = ROOT / "dist" / f"alpine-sdk-{VERSION}"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)

    shutil.copytree(maven_repository, stage / "maven")
    shutil.copytree(ROOT / "distribution", stage / "notices")
    shutil.copytree(ROOT / "compliance", stage / "compliance-policy")
    shutil.copytree(ROOT / "runtime", stage / "runtime-locks")
    shutil.copy2(publication_report, stage / publication_report.name)
    shutil.copy2(consumer_report, stage / consumer_report.name)
    shutil.copy2(readiness_report, stage / readiness_report.name)
    shutil.copy2(compliance_report, stage / compliance_report.name)
    shutil.copy2(
        ROOT / "alpine-runtime-pack-bundled/src/main/resources/META-INF/alpine-runtime/sbom.spdx.json",
        stage / "runtime-sbom-arm64.spdx.json",
    )
    shutil.copy2(
        ROOT / "alpine-runtime-pack-x86_64/src/main/resources/META-INF/alpine-runtime/x86_64/sbom.spdx.json",
        stage / "runtime-sbom-x86_64-experimental.spdx.json",
    )
    for report_name in (
        "gradle9-readiness.json",
        "x86_64-emulator-gate.json",
    ):
        source = ROOT / "build/reports" / report_name
        if source.is_file():
            shutil.copy2(source, stage / report_name)
    for document in (
        "docs/alpine-runtime-sdk-modules.md",
        "docs/alpine-runtime-host-integration.md",
        "docs/alpine-runtime-0x-migration.md",
        "docs/sdk-publication-and-distribution.md",
        "docs/provider-account-e2e-runbook.md",
        "docs/play-asset-delivery-e2e.md",
        "docs/samsung-background-lifecycle-e2e.md",
    ):
        source = ROOT / document
        shutil.copy2(source, stage / source.name)
    validation_templates = stage / "validation-templates"
    validation_templates.mkdir()
    for output_name, template in {
        "provider.json": "integration-fixtures/provider-e2e/report.template.json",
        "play.json": "integration-fixtures/play-e2e/report.template.json",
        "samsung-lifecycle.json":
            "integration-fixtures/samsung-lifecycle/report.template.json",
    }.items():
        source = ROOT / template
        shutil.copy2(source, validation_templates / output_name)

    inventory_documents = []
    for inventory in inventories:
        target = stage / inventory.name
        shutil.copy2(inventory, target)
        inventory_documents.append(json.loads(inventory.read_text()))

    configured_source_bundle = os.environ.get("ALPINE_OSS_SOURCE_BUNDLE")
    source_bundle = (
        Path(configured_source_bundle).expanduser()
        if configured_source_bundle
        else ROOT / "build/compliance" / f"alpine-oss-native-sources-{VERSION}.tar.gz"
    )
    source_bundle_metadata = None
    if configured_source_bundle and not source_bundle.is_file():
        raise RuntimeError(f"configured native OSS source bundle is missing: {source_bundle}")
    if source_bundle.is_file():
        subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts/build-oss-source-bundle.py"),
                "verify",
                str(source_bundle),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        source_bundle_metadata = source_bundle_manifest(source_bundle)
        source_destination = stage / "oss-sources/native" / source_bundle.name
        source_destination.parent.mkdir(parents=True)
        shutil.copy2(source_bundle, source_destination)

    publication = json.loads(publication_report.read_text())
    readiness = json.loads(readiness_report.read_text())
    compliance = json.loads(compliance_report.read_text())
    component_policy = json.loads(
        (ROOT / "compliance/component-license-policy.json").read_text()
    )
    release_blockers = sorted(
        gate["blocker_code"]
        for gate in readiness["gates"]
        if gate["state"] == "BLOCKED" and gate["release_blocking"]
    )
    rootfs_source_mirror_complete = all(
        inventory["source_mirror_complete"] for inventory in inventory_documents
    )
    external_ready = (
        not release_blockers
        and compliance["external_distribution_ready"]
        and rootfs_source_mirror_complete
        and source_bundle_metadata is not None
        and source_bundle_metadata["native_components_complete"] is True
    )
    manifest = {
        "schema_version": 2,
        "version": VERSION,
        "group": "dev.alpine.llm",
        "artifact_count": len(publication["artifacts"]),
        "consumer_matrix_count": 8,
        "supported_runtime_abis": ["arm64-v8a"],
        "experimental_runtime_abis": ["x86_64"],
        "min_sdk": 26,
        "target_sdk_verified": 36,
        "project_license": component_policy["project_code"]["license"],
        "distribution_mode": compliance["distribution_mode"],
        "internal_distribution_ready": True,
        "external_distribution_ready": external_ready,
        "remote_distribution_ready": external_ready,
        "remote_distribution_blockers": release_blockers,
        "rootfs_source_mirror_complete": rootfs_source_mirror_complete,
        "native_source_bundle": (
            {
                "path": f"oss-sources/native/{source_bundle.name}",
                "sha256": sha256(source_bundle),
                "complete_components": source_bundle_metadata["complete_components"],
                "native_components_complete": source_bundle_metadata["native_components_complete"],
                "runtime_source_complete": source_bundle_metadata["runtime_source_complete"],
                "external_release_complete": source_bundle_metadata["external_release_complete"],
            }
            if source_bundle_metadata is not None
            else None
        ),
    }
    (stage / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")

    files = sorted(path for path in stage.rglob("*") if path.is_file())
    sums = "".join(f"{sha256(path)}  {path.relative_to(stage).as_posix()}\n" for path in files)
    (stage / "SHA256SUMS").write_text(sums)

    if destination.exists():
        shutil.rmtree(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    stage.replace(destination)
    print(f"Created internal SDK bundle: {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

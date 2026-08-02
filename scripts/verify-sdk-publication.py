#!/usr/bin/env python3
"""Verify Phase 7 Maven publications without resolving repository source projects."""

from __future__ import annotations

import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
GROUP_PATH = Path("dev/alpine/llm")
VERSION = next(
    line.split("=", 1)[1].strip()
    for line in (ROOT / "gradle.properties").read_text().splitlines()
    if line.startswith("VERSION_NAME=")
)

ARTIFACTS = {
    "alpine-llm-android": ("aar", set()),
    "alpine-runtime-api": ("jar", set()),
    "alpine-runtime-android": ("aar", {"alpine-runtime-api"}),
    "alpine-runtime-background-android": ("aar", {"alpine-runtime-api"}),
    "alpine-runtime-artifact-play": ("aar", {"alpine-runtime-api"}),
    "alpine-runtime-pack-bundled": ("aar", {"alpine-runtime-api"}),
    "alpine-runtime-pack-x86_64": ("aar", {"alpine-runtime-api"}),
    "alpine-runtime-host": ("jar", {"alpine-runtime-api"}),
    "alpine-runtime-ui-compose": ("aar", {"alpine-runtime-host"}),
    "alpine-runtime-testkit": ("jar", {"alpine-runtime-api"}),
    "alpine-llm-bridge": ("aar", {"alpine-llm-android", "alpine-runtime-api"}),
    "alpine-llm-gateway-pack-bundled": ("aar", {"alpine-llm-bridge"}),
    "alpine-chat-routing": ("jar", set()),
    "alpine-chat-backend-direct": ("aar", {"alpine-chat-routing", "alpine-llm-android"}),
    "alpine-chat-backend-alpine": ("aar", {"alpine-chat-routing", "alpine-llm-bridge"}),
    "alpine-workspace-api": ("jar", set()),
    "alpine-workspace-android": ("aar", {"alpine-workspace-api"}),
}

ARM64_ROOTFS = "assets/alpine-minirootfs.tar.gz.asset"
ARM64_PROOT = "jni/arm64-v8a/libproot.so"
ARM64_LOADER = "jni/arm64-v8a/libproot-loader.so"
ARM64_PTY = "jni/arm64-v8a/libalpine-runtime-pty.so"
X86_64_ROOTFS = "assets/alpine-minirootfs-x86_64.tar.gz.asset"
X86_64_PROOT = "jni/x86_64/libproot.so"
X86_64_LOADER = "jni/x86_64/libproot-loader.so"
X86_64_PTY = "jni/x86_64/libalpine-runtime-pty.so"
GATEWAY = "assets/alpine-llm-gateway.tar.gz.asset"
NATIVE_PAYLOADS = {
    ARM64_PROOT,
    ARM64_LOADER,
    ARM64_PTY,
    X86_64_PROOT,
    X86_64_LOADER,
    X86_64_PTY,
}


def fail(message: str) -> None:
    raise AssertionError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_alignments(payload: bytes) -> list[int]:
    if payload[:4] != b"\x7fELF" or payload[4] != 2 or payload[5] != 1:
        fail("Expected a little-endian ELF64 native artifact")
    program_offset = struct.unpack_from("<Q", payload, 32)[0]
    entry_size = struct.unpack_from("<H", payload, 54)[0]
    entry_count = struct.unpack_from("<H", payload, 56)[0]
    alignments: list[int] = []
    for index in range(entry_count):
        offset = program_offset + index * entry_size
        program_type = struct.unpack_from("<I", payload, offset)[0]
        if program_type == 1:  # PT_LOAD
            alignments.append(struct.unpack_from("<Q", payload, offset + 48)[0])
    if not alignments:
        fail("ELF does not contain PT_LOAD segments")
    return alignments


def project_dependencies(pom: Path) -> set[str]:
    root = ElementTree.parse(pom).getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    actual_group = root.findtext("m:groupId", namespaces=namespace)
    actual_artifact = root.findtext("m:artifactId", namespaces=namespace)
    actual_version = root.findtext("m:version", namespaces=namespace)
    if actual_group != "dev.alpine.llm" or actual_version != VERSION:
        fail(f"Invalid Maven coordinates in {pom}")
    if actual_artifact != pom.parent.parent.name:
        fail(f"POM artifactId does not match repository path: {pom}")
    if root.findtext("m:url", namespaces=namespace) != "https://github.com/coreline-ai/alpine-llm-gateway":
        fail(f"Missing project URL in {pom}")
    dependencies = set()
    for dependency in root.findall("m:dependencies/m:dependency", namespace):
        if dependency.findtext("m:groupId", namespaces=namespace) == "dev.alpine.llm":
            dependency_version = dependency.findtext("m:version", namespaces=namespace)
            if dependency_version != VERSION:
                fail(f"Unpinned SDK dependency in {pom}")
            dependencies.add(dependency.findtext("m:artifactId", namespaces=namespace) or "")
    return dependencies


def verify() -> dict[str, object]:
    repository = ROOT / "build/maven-repo" / GROUP_PATH
    if not repository.is_dir():
        fail("Maven repository is missing; run ./gradlew publishPhase7Artifacts")

    report: dict[str, object] = {"version": VERSION, "artifacts": {}}
    payload_owners: dict[str, set[str]] = {
        ARM64_ROOTFS: set(),
        ARM64_PROOT: set(),
        ARM64_LOADER: set(),
        ARM64_PTY: set(),
        X86_64_ROOTFS: set(),
        X86_64_PROOT: set(),
        X86_64_LOADER: set(),
        X86_64_PTY: set(),
        GATEWAY: set(),
    }

    for artifact, (extension, expected_dependencies) in ARTIFACTS.items():
        version_directory = repository / artifact / VERSION
        main = version_directory / f"{artifact}-{VERSION}.{extension}"
        sources = version_directory / f"{artifact}-{VERSION}-sources.jar"
        pom = version_directory / f"{artifact}-{VERSION}.pom"
        module = version_directory / f"{artifact}-{VERSION}.module"
        for path in (main, sources, pom, module):
            if not path.is_file() or path.stat().st_size == 0:
                fail(f"Missing publication file: {path}")
            sidecar = Path(f"{path}.sha256")
            if not sidecar.is_file() or sidecar.read_text().strip() != sha256(path):
                fail(f"Invalid SHA-256 sidecar: {sidecar}")

        actual_dependencies = project_dependencies(pom)
        if actual_dependencies != expected_dependencies:
            fail(
                f"Unexpected project dependencies for {artifact}: "
                f"expected={sorted(expected_dependencies)} actual={sorted(actual_dependencies)}"
            )
        json.loads(module.read_text())
        with zipfile.ZipFile(sources) as archive:
            if not any(name.endswith((".kt", ".java", ".c")) for name in archive.namelist()):
                fail(f"Sources archive has no source files: {sources}")

        if extension == "aar":
            with zipfile.ZipFile(main) as archive:
                names = set(archive.namelist())
                for required in ("AndroidManifest.xml", "classes.jar"):
                    if required not in names:
                        fail(f"{artifact} is missing {required}")
                for payload in payload_owners:
                    if payload in names:
                        payload_owners[payload].add(artifact)
                for native in NATIVE_PAYLOADS:
                    if native in names:
                        alignments = load_alignments(archive.read(native))
                        if any(alignment < 16 * 1024 for alignment in alignments):
                            fail(f"{artifact}/{native} is not 16 KiB aligned: {alignments}")

        report["artifacts"][artifact] = {
            "type": extension,
            "bytes": main.stat().st_size,
            "sha256": sha256(main),
            "project_dependencies": sorted(actual_dependencies),
        }

    expected_owners = {
        ARM64_ROOTFS: {"alpine-runtime-pack-bundled"},
        ARM64_PROOT: {"alpine-runtime-pack-bundled"},
        ARM64_LOADER: {"alpine-runtime-pack-bundled"},
        ARM64_PTY: {"alpine-runtime-android"},
        X86_64_ROOTFS: {"alpine-runtime-pack-x86_64"},
        X86_64_PROOT: {"alpine-runtime-pack-x86_64"},
        X86_64_LOADER: {"alpine-runtime-pack-x86_64"},
        X86_64_PTY: {"alpine-runtime-android"},
        GATEWAY: {"alpine-llm-gateway-pack-bundled"},
    }
    if payload_owners != expected_owners:
        fail(f"Optional payload isolation failed: {payload_owners}")
    report["payload_owners"] = {key: sorted(value) for key, value in payload_owners.items()}
    return report


def main() -> int:
    try:
        report = verify()
    except (AssertionError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"publication verification failed: {error}", file=sys.stderr)
        return 1
    report_path = ROOT / "build/reports/phase7-publication.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(f"Verified {len(ARTIFACTS)} SDK publications: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Verify the pinned Codex App Server metadata, source boundary, ELF, and APK/AAB entry."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
META = ROOT / "alpine-codex-appserver-pack-android/src/main/resources/META-INF/codex-appserver"
LOCK = META / "artifact-lock.json"
INTEGRATED_GRADLE = ROOT / "integrated-app/build.gradle.kts"
PACK_GRADLE = ROOT / "alpine-codex-appserver-pack-android/build.gradle.kts"
INTEGRATED_MANIFEST = ROOT / "integrated-app/src/main/AndroidManifest.xml"
SHARE_PATHS = ROOT / "integrated-app/src/main/res/xml/workspace_share_paths.xml"
CODEX_LAYOUT_SOURCE = (
    ROOT / "alpine-codex-appserver-android/src/main/kotlin/dev/alpine/codex/appserver/runtime/CodexAppServerLayout.kt"
)
EXPECTED_VERSION = "0.147.0"
EXPECTED_BINARY_SIZE = 222_231_296
EXPECTED_BINARY_SHA256 = "e23d0be344d2496986c985cd3db61e6f649b1ddd900e6afc1b5aaabbffcbb4e2"
EXPECTED_SCHEMA_SHA256 = "f3dec1e031d99a420b137b903f02196d4325eece57620c925bb7130b25f168d2"
EXPECTED_LICENSE_SHA256 = "d17f227e4df5da1600391338865ce0f3055211760a36688f816941d58232d8dc"
EXPECTED_NOTICE_SHA256 = "9d71575ecfd9a843fc1677b0efb08053c6ba9fd686a0de1a6f5382fd3c220915"
PRODUCTION_ROOTS = (
    ROOT / "alpine-codex-appserver-android/src/main",
    ROOT / "alpine-chat-backend-codex/src/main",
    ROOT / "integrated-app/src/main",
)
FORBIDDEN_SOURCE_MARKERS = {
    b"auth.json": "credential file access",
    b"OAuthTokenStore": "direct Provider token store dependency",
    b"auth-none": "auth-none gateway",
    b"device-auth-disable": "device auth bypass",
    b"@latest": "unpinned runtime dependency",
    b"WebSocket": "unstable App Server transport",
    b"openclaw": "OpenClaw profile/source",
    b"anyclaw": "AnyClaw source copy",
    b"apt-get --allow-unauthenticated": "insecure package install",
    b"usesCleartextTraffic=\"true\"": "global cleartext traffic",
    b"/bin/sh": "shell execution",
    b"sh -c": "shell command assembly",
    b"Runtime.getRuntime().exec": "runtime shell execution",
}


class VerificationError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_metadata() -> dict[str, object]:
    required = [LOCK, META / "codex_app_server_protocol.v2.schemas.json", META / "LICENSE", META / "NOTICE", META / "sbom.spdx.json"]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise VerificationError(f"missing metadata: {missing}")
    lock = json.loads(LOCK.read_text())
    expected = {
        "codex_version": EXPECTED_VERSION,
        "binary_size": EXPECTED_BINARY_SIZE,
        "binary_sha256": EXPECTED_BINARY_SHA256,
        "schema_sha256": EXPECTED_SCHEMA_SHA256,
        "license_sha256": EXPECTED_LICENSE_SHA256,
        "notice_sha256": EXPECTED_NOTICE_SHA256,
    }
    for key, value in expected.items():
        if lock.get(key) != value:
            raise VerificationError(f"artifact lock mismatch: {key}")
    actual = {
        "schema_sha256": sha256(required[1]),
        "license_sha256": sha256(required[2]),
        "notice_sha256": sha256(required[3]),
    }
    for key, value in actual.items():
        if value != expected[key]:
            raise VerificationError(f"metadata checksum mismatch: {key}")
    sbom = json.loads(required[4].read_text())
    if sbom.get("spdxVersion") != "SPDX-2.3" or not sbom.get("packages"):
        raise VerificationError("invalid SPDX SBOM")
    checksums = json.dumps(sbom, sort_keys=True)
    if EXPECTED_BINARY_SHA256 not in checksums or "Apache-2.0" not in checksums:
        raise VerificationError("SBOM is not bound to the pinned binary/license")
    return lock


def verify_production_source(roots: tuple[Path, ...] = PRODUCTION_ROOTS) -> None:
    for root in roots:
        if not root.is_dir():
            raise VerificationError(f"missing production root: {root}")
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            data = path.read_bytes().lower()
            for marker, label in FORBIDDEN_SOURCE_MARKERS.items():
                if marker.lower() in data:
                    raise VerificationError(f"{path}: forbidden {label}")


def verify_android_policy(
    gradle_path: Path = INTEGRATED_GRADLE,
    manifest_path: Path = INTEGRATED_MANIFEST,
    share_paths_path: Path = SHARE_PATHS,
    layout_path: Path = CODEX_LAYOUT_SOURCE,
) -> None:
    gradle = gradle_path.read_text()
    target_matches = re.findall(r"\btargetSdk\s*=\s*(\d+)", gradle)
    if target_matches != ["36"]:
        raise VerificationError("integrated targetSdk must remain exactly 36")

    manifest = manifest_path.read_text().lower()
    if 'android:allowbackup="false"' not in manifest:
        raise VerificationError("integrated app backup must remain disabled")
    if 'android:usescleartexttraffic="true"' in manifest:
        raise VerificationError("integrated app enables global cleartext traffic")
    if 'android:debuggable="true"' in manifest:
        raise VerificationError("production manifest is explicitly debuggable")

    share_paths = share_paths_path.read_text().lower()
    if "codex" in share_paths or "root-path" in share_paths or "files-path" in share_paths:
        raise VerificationError("FileProvider path may expose app-private Codex state")

    layout = layout_path.read_text()
    if "context.noBackupFilesDir" not in layout or 'child(noBackupRoot, "codex-app-server")' not in layout:
        raise VerificationError("Codex home is not rooted in noBackupFilesDir")


def verify_rollback_policy(
    integrated_gradle_path: Path = INTEGRATED_GRADLE,
    pack_gradle_path: Path = PACK_GRADLE,
) -> None:
    integrated = integrated_gradle_path.read_text()
    integrated_markers = (
        'gradleProperty("codexAppServerRollbackBuild")',
        "codexEnabled && codexRollback",
        'applicationIdSuffix = if (codexDebugPackage) ".codexdebug" else ".debug"',
        '"CODEX_APP_SERVER_ROLLBACK_BUILD"',
        'it.name == "preReleaseBuild"',
        "Codex App Server release build is blocked by unresolved distribution gates",
    )
    if any(marker not in integrated for marker in integrated_markers):
        raise VerificationError("same-package Codex rollback build gate is incomplete")

    pack = pack_gradle_path.read_text()
    pack_markers = (
        'gradleProperty("codexAppServerRollbackBuild")',
        "featureEnabled.get() && !packEnabled.get()",
        "cannot disable the Codex executable pack",
        "rollbackBuild.get() && packEnabled.get()",
        "cannot include the Codex executable pack",
    )
    if any(marker not in pack for marker in pack_markers):
        raise VerificationError("Codex rollback executable-pack exclusion is incomplete")


def elf_load_alignments(data: bytes) -> list[int]:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:6] != b"\x02\x01":
        raise VerificationError("binary is not little-endian ELF64")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != 183:
        raise VerificationError("binary is not AArch64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or not 1 <= count <= 1024 or offset + entry_size * count > len(data):
        raise VerificationError("invalid ELF program headers")
    alignments = [
        struct.unpack_from("<Q", data, offset + index * entry_size + 48)[0]
        for index in range(count)
        if struct.unpack_from("<I", data, offset + index * entry_size)[0] == 1
    ]
    if not alignments or any(value < 16 * 1024 for value in alignments):
        raise VerificationError(f"ELF PT_LOAD is not 16 KiB aligned: {alignments}")
    return alignments


def verify_binary(path: Path) -> list[int]:
    if not path.is_file() or path.stat().st_size != EXPECTED_BINARY_SIZE:
        raise VerificationError("binary size mismatch")
    if sha256(path) != EXPECTED_BINARY_SHA256:
        raise VerificationError("binary checksum mismatch")
    return elf_load_alignments(path.read_bytes())


def is_codex_member(name: str) -> bool:
    return PurePosixPath(name).parts[-3:] == (
        "lib", "arm64-v8a", "libcodex_app_server.so",
    )


def verify_archive(path: Path, expect_binary: bool) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            members = [member for member in archive.infolist() if is_codex_member(member.filename)]
            if expect_binary and len(members) != 1:
                raise VerificationError(f"expected one Codex binary, found {len(members)}")
            if not expect_binary and members:
                raise VerificationError("feature-off archive contains Codex binary")
            for member in members:
                data = archive.read(member)
                if len(data) != EXPECTED_BINARY_SIZE or hashlib.sha256(data).hexdigest() != EXPECTED_BINARY_SHA256:
                    raise VerificationError("archive Codex binary checksum/size mismatch")
                elf_load_alignments(data)
    except zipfile.BadZipFile as error:
        raise VerificationError("invalid APK/AAB archive") from error


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path)
    parser.add_argument("--archive", type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--expect-binary", action="store_true")
    mode.add_argument("--forbid-binary", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        lock = verify_metadata()
        verify_production_source()
        verify_android_policy()
        verify_rollback_policy()
        alignments = verify_binary(args.binary) if args.binary else None
        if args.archive:
            if not args.expect_binary and not args.forbid_binary:
                raise VerificationError("archive mode must be explicit")
            verify_archive(args.archive, expect_binary=args.expect_binary)
        print(json.dumps({
            "status": "PASS",
            "version": lock["codex_version"],
            "binary_verified": args.binary is not None,
            "archive_verified": args.archive is not None,
            "load_alignments": alignments,
        }, sort_keys=True))
        return 0
    except (OSError, ValueError, VerificationError) as error:
        print(f"Codex App Server verification FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

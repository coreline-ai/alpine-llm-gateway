#!/usr/bin/env python3
"""Build or verify a deterministic native OSS source bundle for PRoot/talloc."""

from __future__ import annotations

import argparse
import difflib
import gzip
import json
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from compliance_common import safe_tar_member, sha256  # noqa: E402


def fail(message: str) -> None:
    raise AssertionError(message)


def load_policy() -> dict[str, Any]:
    return json.loads((ROOT / "compliance/component-license-policy.json").read_text())


def version() -> str:
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if line.startswith("VERSION_NAME="):
            return line.split("=", 1)[1].strip()
    fail("VERSION_NAME is missing")


def run_git(source: Path, *args: str) -> str:
    process = subprocess.run(
        ["git", "-C", str(source), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return process.stdout.strip()


def copy_proot_source(source: Path, destination: Path, expected_revision: str) -> int:
    if not (source / "src/cli/proot.c").is_file() or not (source / "COPYING").is_file():
        fail("invalid PRoot source directory")
    if (source / ".git").exists():
        revision = run_git(source, "rev-parse", "HEAD")
        if revision != expected_revision:
            fail(f"PRoot revision mismatch: expected {expected_revision}, got {revision}")
        if run_git(source, "status", "--porcelain"):
            fail("PRoot source worktree must be clean")
        files = [PurePosixPath(item) for item in run_git(source, "ls-files").splitlines()]
    else:
        marker = source / "SOURCE_REVISION"
        if not marker.is_file() or marker.read_text().strip() != expected_revision:
            fail("non-git PRoot source requires an exact SOURCE_REVISION marker")
        files = [path.relative_to(source) for path in source.rglob("*") if path.is_file()]
    copied = 0
    for relative in sorted(files, key=lambda item: item.as_posix()):
        if relative.is_absolute() or ".." in relative.parts or ".git" in relative.parts:
            fail(f"unsafe PRoot source path: {relative}")
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / relative, target)
        copied += 1
    header = (destination / "src/cli/proot.c").read_text(errors="replace")
    normalized_header = re.sub(r"\s+", " ", header)
    if not (
        "either version 2 of the" in normalized_header
        and "any later version" in normalized_header
    ):
        fail("pinned PRoot license grant was not found in src/cli/proot.c")
    return copied


def extract_talloc_source(archive_path: Path, destination: Path, expected_sha256: str) -> Path:
    if sha256(archive_path) != expected_sha256:
        fail("talloc source archive checksum mismatch")
    with tarfile.open(archive_path, "r:gz") as archive:
        members = archive.getmembers()
        if not members or any(
            not safe_tar_member(member) or not (member.isfile() or member.isdir())
            for member in members
        ):
            fail("unsafe talloc source archive")
        roots = {PurePosixPath(member.name).parts[0] for member in members if member.name}
        if roots != {"talloc-2.4.2"}:
            fail(f"unexpected talloc archive root: {sorted(roots)}")
        archive.extractall(destination)
    extracted = destination / "talloc-2.4.2"
    if not (extracted / "talloc.c").is_file() or not (extracted / "talloc.h").is_file():
        fail("talloc archive is missing required source files")
    return extracted


def verify_talloc_build_input(
    build_source: Path,
    extracted: Path,
    destination: Path,
) -> dict[str, Any]:
    for name in ("talloc.c", "talloc.h", "replace.h"):
        if not (build_source / name).is_file():
            fail(f"talloc build input is missing {name}")
    destination.mkdir(parents=True)
    for name in ("talloc.c", "talloc.h", "replace.h"):
        shutil.copy2(build_source / name, destination / name)
    modified: list[str] = []
    patches: list[str] = []
    for name in ("talloc.c", "talloc.h"):
        official = (extracted / name).read_text(errors="surrogateescape").splitlines(keepends=True)
        build_input = (build_source / name).read_text(errors="surrogateescape").splitlines(keepends=True)
        if official == build_input:
            continue
        modified.append(name)
        patches.extend(
            difflib.unified_diff(
                official,
                build_input,
                fromfile=f"official/talloc-2.4.2/{name}",
                tofile=f"build-input/talloc-standalone/{name}",
            )
        )
    if patches:
        (destination / "talloc-source-delta.patch").write_text("".join(patches))
    return {
        "modified_official_files": modified,
        "build_input_sha256": {
            name: sha256(build_source / name)
            for name in ("talloc.c", "talloc.h", "replace.h")
        },
    }


def verify_project_build_inputs(locks: list[dict[str, Any]]) -> dict[str, Any]:
    build_script = ROOT / "scripts/runtime/build-proot-android.sh"
    android_apis = {lock["proot"]["build_android_api"] for lock in locks}
    if len(android_apis) != 1:
        fail("arm64 and x86_64 Android build API metadata must agree")
    for lock in locks:
        patches = lock["proot"].get("patches")
        if patches != []:
            fail(f"runtime lock must declare no local PRoot patch: {lock['abi']}")
    script_text = build_script.read_text()
    android_api = next(iter(android_apis))
    for lock in locks:
        if lock["android"]["ndk"] not in script_text:
            fail(f"native build script lacks the locked NDK for {lock['abi']}")
    if f'ANDROID_API="${{ANDROID_API:-{android_api}}}"' not in script_text:
        fail("native build script Android API differs from the runtime lock")
    return {
        "patches": [],
        "android_api": android_api,
        "targets": [
            {
                "abi": lock["abi"],
                "ndk_version": lock["android"]["ndk"],
            }
            for lock in locks
        ],
    }


def scan_stage(stage: Path) -> None:
    local_path = re.compile(r"/Users/|/Volumes/|[A-Za-z]:\\\\Users\\\\")
    token_like = (
        re.compile(r"\bBearer\s+[A-Za-z0-9._~+/-]{16,}", re.IGNORECASE),
        re.compile(r"\bsk-[A-Za-z0-9_-]{16,}"),
    )
    scan_roots = [
        stage / "build-input",
        stage / "scripts",
        stage / "runtime",
        stage / "compliance",
        stage / "distribution",
        stage / "manifest.json",
        stage / "README.md",
    ]
    paths = []
    for root in scan_roots:
        paths.extend(root.rglob("*") if root.is_dir() else (root,))
    for path in paths:
        if not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
            continue
        payload = path.read_bytes()
        if b"\x00" in payload:
            continue
        text = payload.decode("utf-8", errors="replace")
        if local_path.search(text):
            fail(f"local absolute path found in source bundle: {path.relative_to(stage)}")
        if any(pattern.search(text) for pattern in token_like):
            fail(f"token-like value found in source bundle: {path.relative_to(stage)}")


def deterministic_tar_gz(source: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as archive:
                for path in sorted(source.rglob("*"), key=lambda item: item.relative_to(source).as_posix()):
                    relative = path.relative_to(source.parent).as_posix()
                    info = archive.gettarinfo(str(path), relative)
                    info.uid = info.gid = 0
                    info.uname = info.gname = "root"
                    info.mtime = 0
                    if path.is_file():
                        with path.open("rb") as handle:
                            archive.addfile(info, handle)
                    else:
                        archive.addfile(info)


def build_bundle(
    proot_source: Path,
    talloc_archive: Path,
    talloc_build_source: Path,
    output: Path,
) -> dict[str, Any]:
    policy = load_policy()
    proot_policy = policy["components"]["proot"]
    talloc_policy = policy["components"]["talloc"]
    bundle_name = f"alpine-oss-native-sources-{version()}"
    with tempfile.TemporaryDirectory(prefix="alpine-oss-sources-") as temporary:
        stage = Path(temporary) / bundle_name
        proot_destination = stage / "sources/proot"
        talloc_destination = stage / "sources/talloc"
        proot_destination.mkdir(parents=True)
        proot_count = copy_proot_source(
            proot_source,
            proot_destination,
            proot_policy["revision"],
        )
        extracted = extract_talloc_source(
            talloc_archive,
            talloc_destination,
            talloc_policy["source_sha256"],
        )
        shutil.copy2(talloc_archive, talloc_destination / talloc_archive.name)
        talloc_build = verify_talloc_build_input(
            talloc_build_source,
            extracted,
            stage / "build-input/talloc-standalone",
        )
        for relative in (
            "scripts/runtime/build-proot-android.sh",
            "scripts/verify-runtime-toolchain.py",
            "runtime/alpine-3.21.3-arm64.lock.json",
            "runtime/alpine-3.21.3-x86_64.lock.json",
            "compliance/component-license-policy.json",
            "distribution/THIRD_PARTY_NOTICES.md",
            "distribution/licenses/PRoot-GPL-2.0-or-later.txt",
            "distribution/licenses/LGPL-3.0-or-later.txt",
        ):
            source = ROOT / relative
            if not source.is_file():
                fail(f"required bundle input is missing: {relative}")
            target = stage / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

        locks = [json.loads((ROOT / path).read_text()) for path in (
            "runtime/alpine-3.21.3-arm64.lock.json",
            "runtime/alpine-3.21.3-x86_64.lock.json",
        )]
        build_inputs = verify_project_build_inputs(locks)
        manifest = {
            "schema_version": 1,
            "bundle_kind": "NATIVE_CORRESPONDING_SOURCE",
            "release_version": version(),
            "complete_components": ["proot", "talloc"],
            "excluded_components": ["alpine-rootfs-package-sources"],
            "native_components_complete": True,
            "runtime_source_complete": False,
            "external_release_complete": False,
            "toolchain": build_inputs,
            "components": {
                "proot": {
                    "revision": proot_policy["revision"],
                    "license_declared": proot_policy["license_declared"],
                    "license_concluded": proot_policy["license_concluded"],
                    "tracked_file_count": proot_count,
                },
                "talloc": {
                    "version": talloc_policy["version"],
                    "license_declared": talloc_policy["license_declared"],
                    "source_sha256": talloc_policy["source_sha256"],
                    "modified_official_files": talloc_build["modified_official_files"],
                    "build_input_sha256": talloc_build["build_input_sha256"],
                },
            },
            "binary_targets": [
                {
                    "abi": lock["abi"],
                    "proot_sha256": lock["proot"]["sha256"],
                    "loader_sha256": lock["loader"]["sha256"],
                }
                for lock in locks
            ],
        }
        (stage / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        (stage / "README.md").write_text(
            "# Native OSS source bundle\n\n"
            "This archive contains the pinned PRoot source, the complete official talloc source "
            "archive, the exact standalone talloc build inputs, reproducible build scripts, "
            "runtime locks, and license notices.\n\n"
            "It does not contain the package-level sources for the Alpine rootfs. Therefore this "
            "archive alone does not make the complete Android runtime externally releasable.\n\n"
            "Build from the archive root with:\n\n"
            "```sh\n"
            "scripts/runtime/build-proot-android.sh arm64-v8a sources/proot "
            "build-input/talloc-standalone build/runtime-toolchain/arm64-v8a\n"
            "scripts/runtime/build-proot-android.sh x86_64 sources/proot "
            "build-input/talloc-standalone build/runtime-toolchain/x86_64\n"
            "```\n"
        )
        scan_stage(stage)
        files = sorted(path for path in stage.rglob("*") if path.is_file())
        sums = "".join(
            f"{sha256(path)}  {path.relative_to(stage).as_posix()}\n"
            for path in files
        )
        (stage / "SHA256SUMS").write_text(sums)
        deterministic_tar_gz(stage, output)
    return verify_bundle(output)


def verify_bundle(path: Path) -> dict[str, Any]:
    with tarfile.open(path, "r:gz") as archive:
        members = archive.getmembers()
        if not members or any(not safe_tar_member(member) for member in members):
            fail("unsafe OSS source bundle")
        roots = {PurePosixPath(member.name).parts[0] for member in members if member.name}
        if len(roots) != 1:
            fail("OSS source bundle must have one root directory")
        root = next(iter(roots))
        by_name = {member.name: member for member in members}
        if len(by_name) != len(members):
            fail("OSS source bundle contains duplicate paths")
        required = {
            f"{root}/manifest.json",
            f"{root}/SHA256SUMS",
            f"{root}/sources/proot/src/cli/proot.c",
            f"{root}/sources/proot/COPYING",
            f"{root}/sources/talloc/talloc-2.4.2/talloc.c",
            f"{root}/build-input/talloc-standalone/replace.h",
            f"{root}/scripts/runtime/build-proot-android.sh",
        }
        missing = required - set(by_name)
        if missing:
            fail(f"OSS source bundle is incomplete: {sorted(missing)}")
        manifest_handle = archive.extractfile(by_name[f"{root}/manifest.json"])
        sums_handle = archive.extractfile(by_name[f"{root}/SHA256SUMS"])
        if manifest_handle is None or sums_handle is None:
            fail("OSS source bundle metadata cannot be read")
        manifest = json.loads(manifest_handle.read())
        if manifest.get("bundle_kind") != "NATIVE_CORRESPONDING_SOURCE":
            fail("unexpected OSS source bundle kind")
        if manifest.get("native_components_complete") is not True:
            fail("native OSS source bundle is not complete for PRoot/talloc")
        if manifest.get("runtime_source_complete") is not False:
            fail("native-only source bundle must not claim complete rootfs sources")
        if {target.get("abi") for target in manifest.get("binary_targets", [])} != {
            "arm64-v8a",
            "x86_64",
        }:
            fail("OSS source bundle must map both native binary targets")
        modified_talloc = manifest.get("components", {}).get("talloc", {}).get(
            "modified_official_files",
            [],
        )
        if modified_talloc and f"{root}/build-input/talloc-standalone/talloc-source-delta.patch" not in by_name:
            fail("modified talloc build input is missing its source delta patch")
        checksummed: set[str] = set()
        for line in sums_handle.read().decode().splitlines():
            if "  " not in line:
                fail("invalid SHA256SUMS line")
            expected, relative = line.split("  ", 1)
            if relative in checksummed:
                fail(f"duplicate SHA256SUMS path: {relative}")
            checksummed.add(relative)
            member = by_name.get(f"{root}/{relative}")
            if member is None:
                fail(f"checksummed source file is missing: {relative}")
            handle = archive.extractfile(member)
            if handle is None:
                fail(f"checksummed source file cannot be read: {relative}")
            import hashlib

            if hashlib.sha256(handle.read()).hexdigest() != expected:
                fail(f"source bundle checksum mismatch: {relative}")
        regular_files = {
            member.name.removeprefix(f"{root}/")
            for member in members
            if member.isfile() and member.name != f"{root}/SHA256SUMS"
        }
        if checksummed != regular_files:
            fail("SHA256SUMS does not cover every source bundle file exactly once")
    return {
        "archive": path.name,
        "sha256": sha256(path),
        "release_version": manifest["release_version"],
        "complete_components": manifest["complete_components"],
        "native_components_complete": manifest["native_components_complete"],
        "runtime_source_complete": manifest["runtime_source_complete"],
        "external_release_complete": manifest["external_release_complete"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    build = subparsers.add_parser("build")
    build.add_argument("--proot-source", type=Path, required=True)
    build.add_argument("--talloc-archive", type=Path, required=True)
    build.add_argument("--talloc-build-source", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    verify = subparsers.add_parser("verify")
    verify.add_argument("archive", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "build":
            report = build_bundle(
                args.proot_source,
                args.talloc_archive,
                args.talloc_build_source,
                args.output,
            )
        else:
            report = verify_bundle(args.archive)
    except (
        AssertionError,
        OSError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
        subprocess.CalledProcessError,
        tarfile.TarError,
    ) as error:
        print(f"OSS source bundle failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

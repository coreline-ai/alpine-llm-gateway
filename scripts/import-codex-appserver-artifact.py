#!/usr/bin/env python3
"""Import a pinned Codex arm64 executable from a local npm tarball or raw file.

This script never downloads artifacts. It accepts only the exact 0.147.0 artifact recorded in
alpine-codex-appserver-pack-android's lock and writes to the gitignored local artifact directory.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import tarfile
import tempfile
from pathlib import Path


VERSION = "0.147.0"
TARBALL_SHA256 = "4d09bf000597768dfa070b0e7c612f0fc258ac7fab364e570104ea625f918546"
BINARY_SHA256 = "e23d0be344d2496986c985cd3db61e6f649b1ddd900e6afc1b5aaabbffcbb4e2"
BINARY_SIZE = 222_231_296
TARBALL_MEMBER = "package/vendor/aarch64-unknown-linux-musl/bin/codex"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_binary(path: Path) -> None:
    if not path.is_file() or path.is_symlink():
        raise ValueError("Codex artifact must be a regular file")
    if path.stat().st_size != BINARY_SIZE:
        raise ValueError("Codex artifact size mismatch")
    if sha256(path) != BINARY_SHA256:
        raise ValueError("Codex artifact checksum mismatch")
    with path.open("rb") as source:
        header = source.read(20)
    if header[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Codex artifact must be little-endian ELF64")
    if int.from_bytes(header[18:20], "little") != 183:
        raise ValueError("Codex artifact must target AArch64")


def extract_tarball(source: Path, destination: Path) -> None:
    if sha256(source) != TARBALL_SHA256:
        raise ValueError("Codex npm tarball checksum mismatch")
    with tarfile.open(source, mode="r:gz") as archive:
        member = archive.getmember(TARBALL_MEMBER)
        if not member.isfile() or member.issym() or member.islnk():
            raise ValueError("Codex npm member must be a regular file")
        extracted = archive.extractfile(member)
        if extracted is None:
            raise ValueError("Codex npm member cannot be read")
        with destination.open("wb") as output:
            shutil.copyfileobj(extracted, output, length=1024 * 1024)


def import_artifact(source: Path, repository: Path) -> Path:
    source = source.expanduser().resolve(strict=True)
    output = repository / ".codex-artifacts" / VERSION / "linux-arm64" / "codex"
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as temporary:
        staged = Path(temporary.name)
    try:
        if tarfile.is_tarfile(source):
            extract_tarball(source, staged)
        else:
            shutil.copyfile(source, staged)
        validate_binary(staged)
        staged.chmod(stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
        os.replace(staged, output)
    finally:
        staged.unlink(missing_ok=True)
    return output


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Pinned npm .tgz or extracted codex executable")
    parser.add_argument(
        "--repository",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Repository root (defaults to this script's parent repository)",
    )
    args = parser.parse_args()
    output = import_artifact(args.source, args.repository.resolve())
    print(f"Imported pinned Codex {VERSION} artifact: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

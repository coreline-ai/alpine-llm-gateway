#!/usr/bin/env python3
"""Shared, dependency-free helpers for release compliance tooling."""

from __future__ import annotations

import hashlib
import tarfile
from pathlib import Path, PurePosixPath
from typing import Any


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_tar_member(member: tarfile.TarInfo) -> bool:
    path = PurePosixPath(member.name)
    if path.is_absolute() or ".." in path.parts:
        return False
    if member.issym() or member.islnk():
        target = PurePosixPath(member.linkname)
        if target.is_absolute() or ".." in target.parts:
            return False
    return True


def read_alpine_installed_database(rootfs: Path) -> str:
    with tarfile.open(rootfs, "r:gz") as archive:
        names = set(archive.getnames())
        database = next(
            (
                candidate
                for candidate in ("./lib/apk/db/installed", "lib/apk/db/installed")
                if candidate in names
            ),
            None,
        )
        if database is None:
            raise AssertionError("Alpine package database is missing")
        handle = archive.extractfile(database)
        if handle is None:
            raise AssertionError("Alpine package database cannot be read")
        payload = handle.read()
        if len(payload) > 8 * 1024 * 1024:
            raise AssertionError("Alpine package database is oversized")
        return payload.decode("utf-8")


def is_source_review_license(identifier: str) -> bool:
    upper = identifier.upper()
    if not upper or upper in {"NOASSERTION", "UNKNOWN", "NONE"} or "CUSTOM" in upper:
        return True
    return any(marker in upper for marker in ("GPL", "LGPL", "AGPL", "MPL", "CDDL"))


def alpine_package_inventory(rootfs: Path) -> list[dict[str, Any]]:
    packages: list[dict[str, Any]] = []
    for paragraph in read_alpine_installed_database(rootfs).split("\n\n"):
        values: dict[str, str] = {}
        for line in paragraph.splitlines():
            if len(line) > 2 and line[1] == ":":
                values[line[0]] = line[2:]
        if "P" not in values:
            continue
        license_id = values.get("L", "NOASSERTION")
        packages.append(
            {
                "name": values["P"],
                "version": values.get("V", "UNKNOWN"),
                "architecture": values.get("A", "UNKNOWN"),
                "license_declared": license_id,
                "origin": values.get("o", values["P"]),
                "package_commit": values.get("c", "NOASSERTION"),
                "source_url": values.get("U", "NOASSERTION"),
                "installed_checksum": values.get("C", "NOASSERTION"),
                "source_review_required": is_source_review_license(license_id),
                "source_mirror_status": "NOT_VERIFIED",
            }
        )
    return sorted(packages, key=lambda item: (item["name"], item["architecture"]))

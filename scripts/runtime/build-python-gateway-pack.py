#!/usr/bin/env python3
"""Build the Python Gateway as a deterministic, rootfs-independent tar.gz layer."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile


VERSION = "0.3.0"
PROTOCOL_VERSION = "1"
MINIMUM_PYTHON = "3.11"
FIXED_MTIME = 0


def _entry(name: str, data: bytes, mode: int) -> tuple[str, bytes, int]:
    return name, data, mode


def build(repo: Path, output: Path, lock_path: Path) -> None:
    entries: list[tuple[str, bytes, int]] = []
    for source in sorted((repo / "alpine_llm").rglob("*.py")):
        relative = source.relative_to(repo).as_posix()
        entries.append(_entry(f"opt/alpine-llm-gateway/{relative}", source.read_bytes(), 0o644))

    manifest = {
        "schema_version": "1",
        "package_id": "alpine-llm-gateway",
        "package_version": VERSION,
        "protocol_version": PROTOCOL_VERSION,
        "minimum_python_version": MINIMUM_PYTHON,
        "entrypoints": {
            "llmctl": "/usr/local/bin/llmctl",
            "gatewayd": "/usr/local/bin/llm-gatewayd",
        },
        "source": "repository alpine_llm package",
        "license": "NOASSERTION",
    }
    entries.append(_entry(
        "opt/alpine-llm-gateway/manifest.json",
        (json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode(),
        0o644,
    ))
    entries.append(_entry(
        "opt/alpine-llm-gateway/NOTICE.txt",
        (
            "Alpine LLM Gateway source is packaged from this repository.\n"
            "No project-level license file was present when this artifact was locked; "
            "license is recorded as NOASSERTION.\n"
        ).encode(),
        0o644,
    ))
    wrapper = (
        "#!/bin/sh\n"
        "export PYTHONPATH=/opt/alpine-llm-gateway\n"
        "exec python3 -m alpine_llm.cli \"$@\"\n"
    ).encode()
    entries.append(_entry("usr/local/bin/llmctl", wrapper, 0o755))
    entries.append(_entry("usr/local/bin/llm-gatewayd", wrapper, 0o755))

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=FIXED_MTIME) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
                for name, data, mode in sorted(entries):
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    info.mode = mode
                    info.mtime = FIXED_MTIME
                    info.uid = 0
                    info.gid = 0
                    info.uname = "root"
                    info.gname = "root"
                    archive.addfile(info, io.BytesIO(data))

    payload = output.read_bytes()
    lock = {
        **manifest,
        "artifact": {
            "path": output.relative_to(repo).as_posix(),
            "format": "tar.gz",
            "sha256": hashlib.sha256(payload).hexdigest(),
            "size_bytes": len(payload),
        },
    }
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    lock_path.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("alpine-llm-gateway-pack-bundled/src/main/assets/alpine-llm-gateway.tar.gz.asset"),
    )
    parser.add_argument(
        "--lock",
        type=Path,
        default=Path("runtime/alpine-llm-gateway-0.3.0.lock.json"),
    )
    args = parser.parse_args()
    repo = args.repo.resolve()
    output = args.output if args.output.is_absolute() else repo / args.output
    lock = args.lock if args.lock.is_absolute() else repo / args.lock
    build(repo, output, lock)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

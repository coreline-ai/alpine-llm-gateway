#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path


def fail(message: str) -> None:
    raise AssertionError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def elf_machine_and_alignments(path: Path) -> tuple[int, list[int]]:
    payload = path.read_bytes()
    if payload[:6] != b"\x7fELF\x02\x01":
        fail(f"not little-endian ELF64: {path}")
    machine = struct.unpack_from("<H", payload, 18)[0]
    program_offset = struct.unpack_from("<Q", payload, 32)[0]
    entry_size = struct.unpack_from("<H", payload, 54)[0]
    entry_count = struct.unpack_from("<H", payload, 56)[0]
    alignments = []
    for index in range(entry_count):
        offset = program_offset + index * entry_size
        if struct.unpack_from("<I", payload, offset)[0] == 1:
            alignments.append(struct.unpack_from("<Q", payload, offset + 48)[0])
    return machine, alignments


def verify(directory: Path) -> dict[str, object]:
    lock = json.loads((directory / "toolchain-lock.json").read_text())
    abi = lock["abi"]
    expected_machine = {"arm64-v8a": 183, "x86_64": 62}.get(abi)
    if expected_machine is None:
        fail(f"unsupported ABI in lock: {abi}")
    result = {"abi": abi, "artifacts": {}}
    for name, metadata in lock["artifacts"].items():
        path = directory / "artifacts" / name
        if not path.is_file() or path.stat().st_size != metadata["size_bytes"]:
            fail(f"missing or invalid size: {path}")
        if sha256(path) != metadata["sha256"]:
            fail(f"checksum mismatch: {path}")
        machine, alignments = elf_machine_and_alignments(path)
        if machine != expected_machine or not alignments or min(alignments) < 16 * 1024:
            fail(f"ABI/alignment mismatch: {path} machine={machine} alignments={alignments}")
        result["artifacts"][name] = {"machine": machine, "load_alignments": alignments}
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.directory)
    except (AssertionError, OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"runtime toolchain verification failed: {error}", file=sys.stderr)
        return 1
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

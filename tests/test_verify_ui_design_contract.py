from __future__ import annotations

import importlib.util
import struct
import tempfile
import unittest
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify-ui-design-contract.py"
SPEC = importlib.util.spec_from_file_location("ui_design_contract", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


def chunk(name: str, payload: bytes) -> bytes:
    encoded = name.encode("ascii")
    return (
        struct.pack(">I", len(payload))
        + encoded
        + payload
        + struct.pack(">I", zlib.crc32(encoded + payload) & 0xFFFFFFFF)
    )


def write_png(path: Path, *extra_chunks: tuple[str, bytes]) -> None:
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    payload = (
        b"\x89PNG\r\n\x1a\n"
        + chunk("IHDR", ihdr)
        + b"".join(chunk(name, value) for name, value in extra_chunks)
        + chunk("IDAT", zlib.compress(b"\x00\x00\x00\x00"))
        + chunk("IEND", b"")
    )
    path.write_bytes(payload)


class ScreenshotPngContractTests(unittest.TestCase):
    allowed = {"IHDR", "sBIT", "sRGB", "IDAT", "IEND"}
    max_file_bytes = 1024

    def test_accepts_pixel_and_color_chunks_without_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "safe.png"
            write_png(path, ("sRGB", b"\x00"), ("sBIT", b"\x08\x08\x08"))

            MODULE.verify_png_chunk_contract(path, self.allowed, self.max_file_bytes)

    def test_rejects_text_metadata_chunk(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "with-text.png"
            write_png(path, ("tEXt", b"Author\x00private"))

            with self.assertRaisesRegex(AssertionError, "metadata/unknown chunk.*tEXt"):
                MODULE.verify_png_chunk_contract(path, self.allowed, self.max_file_bytes)

    def test_rejects_corrupt_chunk_crc(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad-crc.png"
            write_png(path)
            data = bytearray(path.read_bytes())
            data[-1] ^= 0x01
            path.write_bytes(data)

            with self.assertRaisesRegex(AssertionError, "CRC mismatch"):
                MODULE.verify_png_chunk_contract(path, self.allowed, self.max_file_bytes)

    def test_rejects_oversized_png_before_parsing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "oversized.png"
            write_png(path)

            with self.assertRaisesRegex(AssertionError, "exceeds size limit"):
                MODULE.verify_png_chunk_contract(path, self.allowed, 8)

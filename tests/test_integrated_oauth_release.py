from __future__ import annotations

import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify-integrated-oauth-release.py"


def run_scan(path: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run([sys.executable, str(SCRIPT), str(path)], check=False, capture_output=True, text=True)


def test_integrated_product_scan_rejects_probe_package_in_apk() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-product.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("classes.dex", b"dev.alpine.llm.runtimeprobe")
        result = run_scan(apk)
        assert result.returncode == 1
        assert "runtime probe package in integrated product" in result.stderr


def test_integrated_product_scan_rejects_probe_only_proot_launcher() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-product.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("lib/arm64-v8a/libproot_tty_resize_relay.so", b"test")
        result = run_scan(apk)
        assert result.returncode == 1
        assert "Probe-only PRoot resize relay launcher" in result.stderr


def test_integrated_product_scan_rejects_provider_secret() -> None:
    with tempfile.TemporaryDirectory() as directory:
        archive = Path(directory) / "integrated-product.apk"
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr("assets/config.txt", "sk-ant-not-a-real-key-but-long-enough-for-the-scanner")
        result = run_scan(archive)
        assert result.returncode == 1
        assert "probable Anthropic API key" in result.stderr

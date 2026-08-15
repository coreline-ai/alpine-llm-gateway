from __future__ import annotations

import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify-integrated-oauth-release.py"


def run_scan(path: Path, *, allow_approved_openminis_debug: bool = False) -> subprocess.CompletedProcess[str]:
    command = [sys.executable, str(SCRIPT)]
    if allow_approved_openminis_debug:
        command.append("--allow-approved-openminis-debug")
    command.append(str(path))
    return subprocess.run(command, check=False, capture_output=True, text=True)


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


def test_openminis_xai_material_is_allowed_only_for_an_explicit_debug_scan() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-app-debug.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("classes.dex", b"grok-cli:access referrer=minis")

        strict = run_scan(apk)
        approved_debug = run_scan(apk, allow_approved_openminis_debug=True)

        assert strict.returncode == 1
        assert "first-party Grok CLI scope" in strict.stderr
        assert approved_debug.returncode == 0


def test_openminis_material_in_debug_aab_requires_explicit_approval() -> None:
    with tempfile.TemporaryDirectory() as directory:
        aab = Path(directory) / "integrated-app-debug.aab"
        with zipfile.ZipFile(aab, "w") as archive:
            archive.writestr("base/dex/classes.dex", b"grok-cli:access")
        assert run_scan(aab).returncode == 1
        assert run_scan(aab, allow_approved_openminis_debug=True).returncode == 0


def test_openminis_claude_material_is_allowed_only_for_an_explicit_debug_scan() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-app-debug.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("classes.dex", b"claude.ai/oauth/authorize")

        strict = run_scan(apk)
        approved_debug = run_scan(apk, allow_approved_openminis_debug=True)

        assert strict.returncode == 1
        assert "Claude consumer compatibility endpoint" in strict.stderr
        assert approved_debug.returncode == 0


def test_codex_named_binary_requires_exact_opaque_artifact() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-product.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "lib/arm64-v8a/libcodex_app_server.so",
                b"codex_cli_rs chatgpt.com/backend-api wrong-binary",
            )
        result = run_scan(apk)
        assert result.returncode == 1
        assert "unapproved Codex executable checksum/size" in result.stderr

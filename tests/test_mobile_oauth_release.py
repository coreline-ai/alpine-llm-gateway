from __future__ import annotations

import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify-mobile-oauth-release.py"


def run_scan(path: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), str(path)],
        check=False,
        capture_output=True,
        text=True,
    )


def run_integrated_scan(path: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--integrated-product", str(path)],
        check=False,
        capture_output=True,
        text=True,
    )


def test_clean_mobile_client_passes() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "config.dart").write_text(
            "const issuer = 'https://auth.mobileagent.example';\n",
            encoding="utf-8",
        )

        result = run_scan(root)

        assert result.returncode == 0, result.stderr
        assert "PASS" in result.stdout


def test_private_endpoint_and_first_party_fingerprint_fail() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "bad.txt").write_text(
            "https://chatgpt.com/backend-api/codex/responses codex_cli_rs",
            encoding="utf-8",
        )

        result = run_scan(root)

        assert result.returncode == 1
        assert "private OpenAI consumer endpoint" in result.stderr
        assert "first-party Codex CLI fingerprint" in result.stderr


def test_provider_api_key_fails() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "secret.env").write_text(
            "ANTHROPIC_API_KEY=sk-ant-not-a-real-key-but-long-enough-for-the-scanner",
            encoding="utf-8",
        )

        result = run_scan(root)

        assert result.returncode == 1
        assert "probable Anthropic API key" in result.stderr


def test_known_copied_registration_fails_without_retaining_literal_in_scanner() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        copied_registration = "app_" + "EMoamEEZ73f0CkXaXp7hrann"
        (root / "oauth.properties").write_text(
            f"client_id={copied_registration}",
            encoding="utf-8",
        )

        result = run_scan(root)

        assert result.returncode == 1
        assert "copied third-party OAuth client registration" in result.stderr


def test_missing_explicit_path_fails_closed() -> None:
    with tempfile.TemporaryDirectory() as directory:
        result = run_scan(Path(directory) / "missing")

        assert result.returncode == 2
        assert "MISSING" in result.stderr


def test_forbidden_material_inside_compressed_apk_fails() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "mobile-agent.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(
                "assets/config.txt",
                "https://chatgpt.com/backend-api/codex/responses",
            )

        result = run_scan(apk)

        assert result.returncode == 1
        assert "assets/config.txt" in result.stderr
        assert "private OpenAI consumer endpoint" in result.stderr


def test_integrated_product_scan_rejects_probe_package_in_apk() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-product.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("classes.dex", b"dev.alpine.llm.runtimeprobe")

        result = run_integrated_scan(apk)

        assert result.returncode == 1
        assert "runtime probe package in integrated product" in result.stderr


def test_integrated_product_scan_rejects_probe_only_proot_diagnostic_launcher() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-product.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("lib/arm64-v8a/libproot_tty_trace.so", b"test")

        result = run_integrated_scan(apk)

        assert result.returncode == 1
        assert "Probe-only PRoot diagnostic launcher in integrated product" in result.stderr


def test_integrated_product_scan_rejects_probe_only_proot_resize_relay_launcher() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-product.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("lib/arm64-v8a/libproot_tty_resize_relay.so", b"test")

        result = run_integrated_scan(apk)

        assert result.returncode == 1
        assert "Probe-only PRoot resize relay launcher in integrated product" in result.stderr


def test_integrated_product_scan_rejects_probe_only_proot_session_relay_launcher() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-product.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("lib/arm64-v8a/libtty_session_relay_launcher.so", b"test")

        result = run_integrated_scan(apk)

        assert result.returncode == 1
        assert "Probe-only PRoot session relay launcher in integrated product" in result.stderr


def test_integrated_product_scan_rejects_probe_only_guest_winsize_helper() -> None:
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "integrated-product.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("lib/arm64-v8a/libtty_winsize_probe.so", b"test")

        result = run_integrated_scan(apk)

        assert result.returncode == 1
        assert "Probe-only guest winsize helper in integrated product" in result.stderr

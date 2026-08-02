#!/usr/bin/env python3
"""Verify payload, ABI, and permission isolation in published-artifact fixtures."""

from __future__ import annotations

import json
import re
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "integration-fixtures/published-consumer"
MARKERS = {
    "arm64_rootfs": "assets/alpine-minirootfs.tar.gz.asset",
    "arm64_proot": "lib/arm64-v8a/libproot.so",
    "arm64_loader": "lib/arm64-v8a/libproot-loader.so",
    "arm64_pty": "lib/arm64-v8a/libalpine-runtime-pty.so",
    "x86_64_rootfs": "assets/alpine-minirootfs-x86_64.tar.gz.asset",
    "x86_64_proot": "lib/x86_64/libproot.so",
    "x86_64_loader": "lib/x86_64/libproot-loader.so",
    "x86_64_pty": "lib/x86_64/libalpine-runtime-pty.so",
    "gateway": "assets/alpine-llm-gateway.tar.gz.asset",
}
ARM64_RUNTIME = {"arm64_rootfs", "arm64_proot", "arm64_loader", "arm64_pty"}
X86_64_RUNTIME = {"x86_64_rootfs", "x86_64_proot", "x86_64_loader", "x86_64_pty"}
EXPECTED = {
    "no-runtime": set(),
    "runtime-only": ARM64_RUNTIME,
    "runtime-ui": ARM64_RUNTIME,
    "runtime-llm": ARM64_RUNTIME | {"gateway"},
    "full": ARM64_RUNTIME | {"gateway"},
    "runtime-background": set(),
    "runtime-play-workspace": set(),
    "runtime-x86_64": X86_64_RUNTIME,
}
NETWORK_STATE_MODULES = {
    "runtime-only",
    "runtime-ui",
    "runtime-llm",
    "full",
    "runtime-background",
    "runtime-play-workspace",
    "runtime-x86_64",
}
BACKGROUND_MODULES = {"runtime-background", "full"}
PLAY_ASSET_MODULES = {"runtime-play-workspace", "full"}


def find_manifest(module: str) -> Path:
    candidates = sorted((FIXTURE / module / "build/intermediates").glob("**/AndroidManifest.xml"))
    candidates = [path for path in candidates if "merged_manifest" in str(path) or "merged_manifests" in str(path)]
    if not candidates:
        raise AssertionError(f"Merged manifest is missing for {module}")
    return candidates[-1]


def verify() -> dict[str, object]:
    report: dict[str, object] = {"matrix": {}}
    for module, expected in EXPECTED.items():
        apk = FIXTURE / module / "build/outputs/apk/release" / f"{module}-release-unsigned.apk"
        if not apk.is_file():
            raise AssertionError(f"Release APK is missing: {apk}")
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
        actual = {name for name, marker in MARKERS.items() if marker in names}
        if actual != expected:
            raise AssertionError(
                f"Payload matrix mismatch for {module}: expected={sorted(expected)} actual={sorted(actual)}"
            )
        alpine_native = {
            name for name in names
            if name.endswith(("libproot.so", "libproot-loader.so", "libalpine-runtime-pty.so"))
        }
        expected_prefix = "lib/x86_64/" if module == "runtime-x86_64" else "lib/arm64-v8a/"
        if any(not name.startswith(expected_prefix) for name in alpine_native):
            raise AssertionError(f"Unsupported Alpine ABI packaged in {module}: {sorted(alpine_native)}")

        manifest_text = find_manifest(module).read_text(errors="replace")
        if not re.search(r"minSdkVersion=[\"']26[\"']", manifest_text):
            raise AssertionError(f"minSdk 26 was not preserved in {module}")
        has_network_state = "android.permission.ACCESS_NETWORK_STATE" in manifest_text
        if has_network_state != (module in NETWORK_STATE_MODULES):
            raise AssertionError(f"Runtime permission isolation failed for {module}")
        has_fgs = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in manifest_text
        has_notifications = "android.permission.POST_NOTIFICATIONS" in manifest_text
        if has_fgs != (module in BACKGROUND_MODULES) or has_notifications != has_fgs:
            raise AssertionError(f"Background permission isolation failed for {module}")
        has_data_sync_fgs = "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in manifest_text
        if has_data_sync_fgs != (module in PLAY_ASSET_MODULES):
            raise AssertionError(f"Play Asset permission isolation failed for {module}")

        report["matrix"][module] = {
            "apk_bytes": apk.stat().st_size,
            "payloads": sorted(actual),
            "alpine_native": sorted(alpine_native),
            "access_network_state": has_network_state,
            "foreground_service_special_use": has_fgs,
            "post_notifications": has_notifications,
            "foreground_service_data_sync": has_data_sync_fgs,
        }
    return report


def main() -> int:
    try:
        report = verify()
    except (AssertionError, OSError, zipfile.BadZipFile) as error:
        print(f"consumer matrix verification failed: {error}", file=sys.stderr)
        return 1
    report_path = ROOT / "build/reports/phase7-consumer-matrix.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(f"Verified {len(EXPECTED)} published consumer variants: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

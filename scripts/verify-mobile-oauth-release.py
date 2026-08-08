#!/usr/bin/env python3
"""Fail closed when MobileAgent client sources/artifacts contain forbidden OAuth material."""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ROOTS = (
    REPOSITORY_ROOT / "apps" / "mobile_agent",
    REPOSITORY_ROOT / "packages" / "mobile_agent_auth",
    REPOSITORY_ROOT / "packages" / "mobile_agent_llm_transport",
    REPOSITORY_ROOT / "backend" / "mobile_agent_bff",
)
INTEGRATED_PRODUCT_SOURCE_ROOTS = (
    REPOSITORY_ROOT / "android" / "src" / "main",
    REPOSITORY_ROOT / "alpine-chat-provider-android" / "src" / "main",
    REPOSITORY_ROOT / "integrated-app" / "src" / "main",
)
INTEGRATED_PRODUCT_APK = (
    REPOSITORY_ROOT
    / "integrated-app"
    / "build"
    / "outputs"
    / "apk"
    / "debug"
    / "integrated-app-debug.apk"
)
SKIPPED_DIRECTORIES = {
    ".dart_tool",
    ".git",
    ".gradle",
    ".idea",
    ".venv",
    "build",
    "DerivedData",
    "Pods",
    "venv",
}

# Hashes intentionally avoid retaining copied third-party registrations in this repository.
FORBIDDEN_REGISTRATION_SHA256 = {
    "584341c2f0e88ad1f7c6856553d81dc4776ff42c43951daed3e2d8d91552eaa2",
    "61a78c7973731798b0a57ea32dfdf330dc5f2274df5a85e6798f0c38d66f24ee",
    "473668f2b13c71009d028ff0ef74c2cf76e71cbdd33b76e69fcc42d7e59aca4b",
}
FORBIDDEN_FRAGMENTS = {
    b"chatgpt.com/backend-api": "private OpenAI consumer endpoint",
    b"codex_cli_rs": "first-party Codex CLI fingerprint",
    b"grok-cli:access": "first-party Grok CLI scope",
    b"claude.ai/oauth/authorize": "Claude consumer compatibility endpoint",
}
FORBIDDEN_INTEGRATED_TEST_APP_FRAGMENTS = {
    b"dev.alpine.llm.demo": "demo app package in integrated product",
    b"dev/alpine/llm/demo": "demo app package in integrated product",
    b"dev.alpine.llm.runtimeprobe": "runtime probe package in integrated product",
    b"dev/alpine/llm/runtimeprobe": "runtime probe package in integrated product",
    b"dev.alpine.llm.bridgeprobe": "bridge probe package in integrated product",
    b"dev/alpine/llm/bridgeprobe": "bridge probe package in integrated product",
    b"dev.alpine.runtime.sample": "runtime sample package in integrated product",
    b"dev/alpine/runtime/sample": "runtime sample package in integrated product",
    b"libproot_tty_trace.so": "Probe-only PRoot diagnostic launcher in integrated product",
    b"libproot_tty_resize_relay.so": "Probe-only PRoot resize relay launcher in integrated product",
    b"libtty_session_relay_launcher.so": "Probe-only PRoot session relay launcher in integrated product",
    b"libtty_session_tracee_foreground_launcher.so": "Probe-only PRoot foreground session launcher in integrated product",
    b"libtty_session_virtual_resize_launcher.so": "Probe-only virtual winsize session launcher in integrated product",
    b"libtty_winsize_probe.so": "Probe-only guest winsize helper in integrated product",
}
SECRET_PATTERNS = (
    (re.compile(rb"sk-ant-[A-Za-z0-9_-]{20,}"), "Anthropic API key"),
    (re.compile(rb"xai-[A-Za-z0-9_-]{20,}"), "xAI API key"),
    (re.compile(rb"sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}"), "OpenAI API key"),
    (re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"), "private key"),
)
REGISTRATION_CANDIDATE = re.compile(rb"[A-Za-z0-9_-]{20,80}")


def iter_files(root: Path):
    if root.is_file():
        yield root
        return
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative_parts = path.relative_to(root).parts
        if any(
            part in SKIPPED_DIRECTORIES or part.endswith(".egg-info")
            for part in relative_parts
        ):
            continue
        yield path


def scan_bytes(
    source_label: str,
    data: bytes,
    *,
    integrated_product: bool = False,
) -> list[str]:
    findings: list[str] = []
    for fragment, rule_label in FORBIDDEN_FRAGMENTS.items():
        if fragment in data:
            findings.append(f"{source_label}: {rule_label}")
    if integrated_product:
        for fragment, rule_label in FORBIDDEN_INTEGRATED_TEST_APP_FRAGMENTS.items():
            if fragment in data:
                findings.append(f"{source_label}: {rule_label}")
    for pattern, rule_label in SECRET_PATTERNS:
        if pattern.search(data):
            findings.append(f"{source_label}: probable {rule_label}")
    for candidate in REGISTRATION_CANDIDATE.findall(data):
        digest = hashlib.sha256(candidate).hexdigest()
        if digest in FORBIDDEN_REGISTRATION_SHA256:
            findings.append(
                f"{source_label}: copied third-party OAuth client registration"
            )
            break
    return findings


def scan_file(path: Path, *, integrated_product: bool = False) -> list[str]:
    try:
        data = path.read_bytes()
    except OSError as error:
        return [f"{path}: cannot read ({error})"]

    findings = scan_bytes(str(path), data, integrated_product=integrated_product)
    if path.suffix.lower() not in {".apk", ".aab", ".ipa", ".zip"}:
        return findings

    try:
        with zipfile.ZipFile(path) as archive:
            for member in archive.infolist():
                if member.is_dir():
                    continue
                try:
                    member_data = archive.read(member)
                except (OSError, RuntimeError, zipfile.BadZipFile) as error:
                    findings.append(f"{path}!{member.filename}: cannot read ({error})")
                    continue
                findings.extend(
                    scan_bytes(
                        f"{path}!{member.filename}",
                        member_data,
                        integrated_product=integrated_product,
                    )
                )
    except zipfile.BadZipFile as error:
        findings.append(f"{path}: invalid release archive ({error})")
    return findings


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "paths",
        nargs="*",
        type=Path,
        help="Client source directories or built APK/AAB/IPA files to scan.",
    )
    parser.add_argument(
        "--integrated-product",
        action="store_true",
        help=(
            "Scan the integrated app production sources and debug APK, and reject "
            "demo/probe/sample package fingerprints. Extra paths add release artifacts."
        ),
    )
    parser.add_argument(
        "--require-default-roots",
        action="store_true",
        help="Fail when a default MobileAgent source root does not exist.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    explicit_roots = tuple(path.resolve() for path in args.paths)
    roots = (
        INTEGRATED_PRODUCT_SOURCE_ROOTS + (INTEGRATED_PRODUCT_APK,) + explicit_roots
        if args.integrated_product
        else explicit_roots or DEFAULT_ROOTS
    )
    missing = [root for root in roots if not root.exists()]
    if missing and (args.paths or args.require_default_roots or args.integrated_product):
        for root in missing:
            print(f"MISSING: {root}", file=sys.stderr)
        return 2

    files = [path for root in roots if root.exists() for path in iter_files(root)]
    findings = [
        finding
        for path in files
        for finding in scan_file(path, integrated_product=args.integrated_product)
    ]
    if findings:
        print("OAuth release scan FAILED:", file=sys.stderr)
        for finding in findings:
            print(f"- {finding}", file=sys.stderr)
        return 1

    label = "Integrated product OAuth release scan" if args.integrated_product else "MobileAgent OAuth release scan"
    print(f"{label} PASS ({len(files)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

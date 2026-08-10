#!/usr/bin/env python3
"""Fail closed when the Alpine integrated Android product contains forbidden OAuth material."""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ROOTS = (
    REPOSITORY_ROOT / "android" / "src" / "main",
    REPOSITORY_ROOT / "alpine-chat-provider-android" / "src" / "main",
    REPOSITORY_ROOT / "integrated-app" / "src" / "main",
    REPOSITORY_ROOT / "integrated-app" / "build" / "outputs" / "apk" / "debug" / "integrated-app-debug.apk",
)
SKIPPED_DIRECTORIES = {".git", ".gradle", ".idea", "build", "DerivedData", "Pods", "venv"}
FORBIDDEN_REGISTRATION_SHA256 = {
    "584341c2f0e88ad1f7c6856553d81dc4776ff42c43951daed3e2d8d91552eaa2",
    "61a78c7973731798b0a57ea32dfdf330dc5f2274df5a85e6798f0c38d66f24ee",
    "473668f2b13c71009d028ff0ef74c2cf76e71cbdd33b76e69fcc42d7e59aca4b",
}
FORBIDDEN_FRAGMENTS = {
    b"chatgpt.com/backend-api": "private OpenAI consumer endpoint",
    b"codex_cli_rs": "first-party Codex CLI fingerprint",
    b"grok-cli:access": "first-party Grok CLI scope",
    b"referrer=minis": "OpenMinis xAI consumer attribution",
    b"claude.ai/oauth/authorize": "Claude consumer compatibility endpoint",
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
APPROVED_OPENMINIS_DEBUG_FRAGMENTS = {
    b"chatgpt.com/backend-api",
    b"codex_cli_rs",
    b"grok-cli:access",
    b"referrer=minis",
    b"claude.ai/oauth/authorize",
}
APPROVED_OPENMINIS_DEBUG_REGISTRATION_SHA256 = {
    "584341c2f0e88ad1f7c6856553d81dc4776ff42c43951daed3e2d8d91552eaa2",
    "61a78c7973731798b0a57ea32dfdf330dc5f2274df5a85e6798f0c38d66f24ee",
    "473668f2b13c71009d028ff0ef74c2cf76e71cbdd33b76e69fcc42d7e59aca4b",
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
        parts = path.relative_to(root).parts
        if any(part in SKIPPED_DIRECTORIES or part.endswith(".egg-info") for part in parts):
            continue
        yield path


def scan_bytes(
    source_label: str,
    data: bytes,
    allowed_fragments: set[bytes] | frozenset[bytes] = frozenset(),
    allowed_registration_sha256: set[str] | frozenset[str] = frozenset(),
) -> list[str]:
    findings: list[str] = []
    for fragment, label in FORBIDDEN_FRAGMENTS.items():
        if fragment in data and fragment not in allowed_fragments:
            findings.append(f"{source_label}: {label}")
    for pattern, label in SECRET_PATTERNS:
        if pattern.search(data):
            findings.append(f"{source_label}: probable {label}")
    for candidate in REGISTRATION_CANDIDATE.findall(data):
        candidate_hash = hashlib.sha256(candidate).hexdigest()
        if (
            candidate_hash in FORBIDDEN_REGISTRATION_SHA256
            and candidate_hash not in allowed_registration_sha256
        ):
            findings.append(f"{source_label}: copied third-party OAuth client registration")
            break
    return findings


def scan_file(path: Path, allow_approved_openminis_debug: bool = False) -> list[str]:
    try:
        data = path.read_bytes()
    except OSError as error:
        return [f"{path}: cannot read ({error})"]
    debug_compatibility_path = path.name == "integrated-app-debug.apk" or (
        "src" in path.parts and "debug" in path.parts
    )
    allowed_fragments = (
        APPROVED_OPENMINIS_DEBUG_FRAGMENTS
        if allow_approved_openminis_debug and debug_compatibility_path
        else frozenset()
    )
    allowed_registration_sha256 = (
        APPROVED_OPENMINIS_DEBUG_REGISTRATION_SHA256
        if allow_approved_openminis_debug and debug_compatibility_path
        else frozenset()
    )
    findings = scan_bytes(
        str(path),
        data,
        allowed_fragments,
        allowed_registration_sha256,
    )
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
                        allowed_fragments,
                        allowed_registration_sha256,
                    )
                )
    except zipfile.BadZipFile as error:
        findings.append(f"{path}: invalid release archive ({error})")
    return findings


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="*", type=Path, help="Integrated sources or built APK/AAB/IPA files to scan.")
    parser.add_argument("--require-default-roots", action="store_true", help="Fail when an expected integrated source or APK is missing.")
    parser.add_argument(
        "--allow-approved-openminis-debug",
        action="store_true",
        help="Allow only approved OpenMinis consumer OAuth material in debug source/APK paths.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    roots = tuple(path.resolve() for path in args.paths) or DEFAULT_ROOTS
    missing = [root for root in roots if not root.exists()]
    if missing and (args.paths or args.require_default_roots):
        for root in missing:
            print(f"MISSING: {root}", file=sys.stderr)
        return 2
    files = [path for root in roots if root.exists() for path in iter_files(root)]
    findings = [
        finding
        for path in files
        for finding in scan_file(path, args.allow_approved_openminis_debug)
    ]
    if findings:
        print("Integrated product OAuth release scan FAILED:", file=sys.stderr)
        for finding in findings:
            print(f"- {finding}", file=sys.stderr)
        return 1
    print(f"Integrated product OAuth release scan PASS ({len(files)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

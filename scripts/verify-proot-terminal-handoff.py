#!/usr/bin/env python3
"""Verify the pinned PRoot source boundary for terminal-resize investigations.

This is intentionally a static source audit, not a dynamic-terminal acceptance
test.  It verifies the current unpatched PRoot handoff points without running a
guest, observing terminal data, or producing process/file-descriptor details.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = ROOT / "runtime" / "alpine-3.21.3-arm64.lock.json"


def fail(message: str) -> None:
    raise AssertionError(message)


def read_source(source: Path, relative: str) -> str:
    path = source / relative
    if not path.is_file():
        fail(f"required PRoot source file is missing: {relative}")
    return path.read_text(encoding="utf-8")


def require(text: str, pattern: str, invariant: str) -> None:
    if re.search(pattern, text, re.DOTALL) is None:
        fail(f"PRoot terminal handoff invariant failed: {invariant}")


def source_revision(source: Path) -> str | None:
    result = subprocess.run(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    revision = result.stdout.strip()
    return revision or None


def audit(source: Path, expected_commit: str | None = None) -> dict[str, Any]:
    """Return fixed, non-sensitive invariants for the pinned upstream source."""

    event = read_source(source, "src/tracee/event.c")
    enter = read_source(source, "src/syscall/enter.c")
    exit_source = read_source(source, "src/syscall/exit.c")
    seccomp = read_source(source, "src/syscall/seccomp.c")
    syscall = read_source(source, "src/syscall/syscall.c")

    revision = source_revision(source)
    if expected_commit is not None:
        if revision is None:
            fail("PRoot source revision cannot be verified")
        if revision != expected_commit:
            fail("PRoot source revision does not match the runtime lock")

    # PRoot's tracer owns the physical controlling terminal before it forks the
    # first ptrace tracee.  The event loop explicitly ignores every signal that
    # is not in its small job-control exception list; SIGWINCH is not an
    # exception.  This is an intentionally narrow source fact: it does *not*
    # establish why Android did not deliver a resize signal to the tracee in a
    # particular run, and it is not permission to synthesize or relay one.
    event_loop_start = event.find("int event_loop()")
    if event_loop_start < 0:
        fail("PRoot terminal handoff invariant failed: event_loop_missing")
    event_loop_setup = event[event_loop_start:].split("while (1)", 1)[0]
    require(
        event_loop_setup,
        r"for \(signum = 0; signum < SIGRTMAX; signum\+\+\).*?"
        r"case SIGCHLD:.*?case SIGTTOU:.*?continue;.*?"
        r"default:\s*/\* Ignore all other signals.*?\*/\s*"
        r"signal_action\.sa_sigaction = \(void \*\)SIG_IGN",
        "tracer_default_signal_policy_is_ignore",
    )
    if re.search(r"case\s+SIGWINCH\s*:", event_loop_setup):
        fail("PRoot terminal handoff invariant failed: tracer_sigwinch_is_not_ignored")

    # A tracee signal stop falls through this default case unless a syscall
    # chain is active.  The signal is then passed verbatim into ptrace restart.
    # This proves only the generic handoff point; it does not prove that the
    # Android kernel delivers SIGWINCH to this particular terminal topology.
    require(
        event,
        r"default:\s*/\* Deliver this signal as-is,\s*\* unless we're chaining syscall\.\s*\*/"
        r"\s*if \(tracee->chain\.syscalls != NULL \|\| tracee->restore_original_regs_after_seccomp_event\)",
        "generic_tracee_signal_is_not_unconditionally_suppressed",
    )
    require(
        event,
        r"ptrace\(tracee->restart_how,\s*tracee->pid,\s*NULL,\s*signal\)",
        "ptrace_restart_receives_pending_tracee_signal",
    )

    # Android ioctl is deliberately traced through syscall exit.  The stock
    # source has termios compatibility rewrites only: no TIOCGWINSZ/TIOCSWINSZ
    # mutation or signal synthesis belongs to the upstream runtime contract.
    require(
        seccomp,
        r"#ifdef __ANDROID__\s*\{\s*PR_ioctl,\s*FILTER_SYSEXIT\s*\},\s*#endif",
        "android_ioctl_has_sysexit_trace",
    )
    require(
        enter,
        r"#ifdef __ANDROID__\s*case PR_ioctl:.*?TCGETS2.*?TCSETSF2.*?break;\s*#endif",
        "android_ioctl_entry_is_termios_compatibility_scope",
    )
    if "TIOCGWINSZ" in enter or "TIOCSWINSZ" in enter:
        fail("PRoot terminal handoff invariant failed: unexpected_winsize_entry_rewrite")
    require(
        exit_source,
        r"case PR_ioctl:.*?FICLONE.*?goto end;",
        "ioctl_exit_has_only_existing_generic_compatibility_path",
    )
    if "TIOCGWINSZ" in exit_source or "TIOCSWINSZ" in exit_source:
        fail("PRoot terminal handoff invariant failed: unexpected_winsize_exit_rewrite")
    require(
        syscall,
        r"void translate_syscall\(Tracee \*tracee\).*?translate_syscall_exit\(tracee\);",
        "traced_syscall_exit_is_translated_before_resume",
    )

    return {
        "schema_version": 1,
        "audit": "proot_terminal_handoff_static",
        "expected_commit": expected_commit,
        "source_revision_match": expected_commit is None or revision == expected_commit,
        "invariants": {
            "android_ioctl_has_sysexit_trace": True,
            "generic_tracee_signal_handoff": True,
            "ptrace_restart_receives_pending_signal": True,
            "stock_entry_has_no_winsize_rewrite": True,
            "stock_exit_has_no_winsize_rewrite": True,
            "tracer_default_signal_policy_is_ignore": True,
            "tracer_sigwinch_is_not_special_cased": True,
        },
        "conclusion": "STATIC_HANDOFF_PRESENT_TRACER_SIGWINCH_IGNORED_NO_STOCK_TTY_HOOK",
        "dynamic_resize_status": "NOT_PROVEN",
    }


def expected_commit_from_lock(lock_path: Path) -> str:
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    commit = lock.get("proot", {}).get("commit")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        fail("runtime lock has no valid pinned PRoot commit")
    return commit


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--proot-source", type=Path, required=True)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--skip-revision-check", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    try:
        expected = None if args.skip_revision_check else expected_commit_from_lock(args.lock)
        report = audit(args.proot_source, expected)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except (AssertionError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"PRoot terminal handoff audit failed: {error}", file=sys.stderr)
        return 1

    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

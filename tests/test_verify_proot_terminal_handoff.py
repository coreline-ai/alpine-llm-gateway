from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify-proot-terminal-handoff.py"
SPEC = importlib.util.spec_from_file_location("proot_terminal_handoff", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


def write_source(
    root: Path,
    *,
    exit_winsize_rewrite: bool = False,
    tracer_handles_winch: bool = False,
) -> None:
    (root / "src/tracee").mkdir(parents=True)
    (root / "src/syscall").mkdir(parents=True)
    (root / "src/tracee/event.c").write_text(
        f"""
        int event_loop() {{
            for (signum = 0; signum < SIGRTMAX; signum++) {{
                switch (signum) {{
                case SIGCHLD:
                case SIGCONT:
                case SIGSTOP:
                case SIGTSTP:
                case SIGTTIN:
                case SIGTTOU:
                    continue;
                {'case SIGWINCH: continue;' if tracer_handles_winch else ''}
                default:
                    /* Ignore all other signals, including terminating ones. */
                    signal_action.sa_sigaction = (void *)SIG_IGN;
                    break;
                }}
                sigaction(signum, &signal_action, NULL);
            }}
            while (1) {{ break; }}
        }}

        default:
            /* Deliver this signal as-is,
             * unless we're chaining syscall.  */
            if (tracee->chain.syscalls != NULL || tracee->restore_original_regs_after_seccomp_event) {{
                signal = 0;
            }}
        bool restart_tracee(Tracee *tracee, int signal) {{
            return ptrace(tracee->restart_how, tracee->pid, NULL, signal) == 0;
        }}
        """,
        encoding="utf-8",
    )
    (root / "src/syscall/enter.c").write_text(
        """
        #ifdef __ANDROID__
        case PR_ioctl:
            if (request == TCGETS2) { request = TCGETS; }
            if (request == TCSETSF2) { request = TCSETSF; }
            break;
        #endif
        """,
        encoding="utf-8",
    )
    winsize = " TIOCGWINSZ;" if exit_winsize_rewrite else ""
    (root / "src/syscall/exit.c").write_text(
        f"""
        case PR_ioctl:
            if (request == FICLONE) {{ compatibility(); }}
            goto end;{winsize}
        """,
        encoding="utf-8",
    )
    (root / "src/syscall/seccomp.c").write_text(
        """
        #ifdef __ANDROID__
        { PR_ioctl, FILTER_SYSEXIT },
        #endif
        """,
        encoding="utf-8",
    )
    (root / "src/syscall/syscall.c").write_text(
        """
        void translate_syscall(Tracee *tracee) {
            translate_syscall_exit(tracee);
        }
        """,
        encoding="utf-8",
    )


class PRootTerminalHandoffAuditTests(unittest.TestCase):
    def test_audit_accepts_stock_handoff_shape_without_revision_check(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            write_source(source)

            report = MODULE.audit(source)

        self.assertEqual(
            "STATIC_HANDOFF_PRESENT_TRACER_SIGWINCH_IGNORED_NO_STOCK_TTY_HOOK",
            report["conclusion"],
        )
        self.assertEqual("NOT_PROVEN", report["dynamic_resize_status"])
        self.assertTrue(report["invariants"]["generic_tracee_signal_handoff"])
        self.assertTrue(report["invariants"]["tracer_sigwinch_is_not_special_cased"])

    def test_audit_fails_closed_when_exit_mutates_guest_winsize(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            write_source(source, exit_winsize_rewrite=True)

            with self.assertRaisesRegex(AssertionError, "unexpected_winsize_exit_rewrite"):
                MODULE.audit(source)

    def test_audit_fails_closed_when_tracer_special_cases_sigwinch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            write_source(source, tracer_handles_winch=True)

            with self.assertRaisesRegex(AssertionError, "tracer_sigwinch_is_not_ignored"):
                MODULE.audit(source)

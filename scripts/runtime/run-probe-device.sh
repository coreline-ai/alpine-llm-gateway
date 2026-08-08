#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
SERIAL="${1:-${ANDROID_SERIAL:-}}"
RESET_AFTER="${2:-false}"
TTY_DIAGNOSTIC="${3:-false}"
TTY_RESIZE_STRESS="${4:-false}"
TTY_DISABLE_PROOT_SECCOMP="${5:-false}"
TTY_VIRTUAL_WINSIZE_NO_WRITE="${6:-false}"
TTY_VIRTUAL_WINSIZE_NO_REQUEST="${7:-false}"
TTY_VIRTUAL_WINSIZE_SKIP_RESIZE="${8:-false}"
TTY_DISABLE_PRIMARY_TRACEE_FOREGROUND="${9:-false}"
TTY_USE_PATCHED_PROOT="${10:-true}"
TTY_SKIP_TERMINAL_WINSIZE_READS="${11:-false}"
APK="$ROOT/alpine-runtime-probe/build/outputs/apk/debug/alpine-runtime-probe-debug.apk"

[[ -x "$ADB" ]] || { echo "adb not found: $ADB" >&2; exit 2; }
[[ -f "$APK" ]] || { echo "probe APK not found: $APK" >&2; exit 2; }
if [[ -z "$SERIAL" ]]; then
    SERIAL="$($ADB devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')"
fi
[[ -n "$SERIAL" ]] || { echo "No Android device is connected" >&2; exit 2; }

PACKAGE="dev.alpine.llm.runtimeprobe"
ACTIVITY="$PACKAGE/.RuntimeProbeActivity"

"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
"$ADB" -s "$SERIAL" shell run-as "$PACKAGE" rm -f files/runtime-probe-result.json || true
"$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY" \
    --ez auto_run true --ez reset_after "$RESET_AFTER" \
    --ez tty_diagnostic "$TTY_DIAGNOSTIC" \
    --ez tty_resize_stress "$TTY_RESIZE_STRESS" \
    --ez tty_disable_proot_seccomp "$TTY_DISABLE_PROOT_SECCOMP" \
    --ez tty_virtual_winsize_no_write "$TTY_VIRTUAL_WINSIZE_NO_WRITE" \
    --ez tty_virtual_winsize_no_request "$TTY_VIRTUAL_WINSIZE_NO_REQUEST" \
    --ez tty_virtual_winsize_skip_resize "$TTY_VIRTUAL_WINSIZE_SKIP_RESIZE" \
    --ez tty_disable_primary_tracee_foreground "$TTY_DISABLE_PRIMARY_TRACEE_FOREGROUND" \
    --ez tty_use_patched_proot "$TTY_USE_PATCHED_PROOT" \
    --ez tty_skip_terminal_winsize_reads "$TTY_SKIP_TERMINAL_WINSIZE_READS" >/dev/null

for _ in $(seq 1 45); do
    RESULT="$($ADB -s "$SERIAL" shell run-as "$PACKAGE" \
        cat files/runtime-probe-result.json 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$RESULT" ]]; then
        printf '%s\n' "$RESULT"
        if printf '%s' "$RESULT" | grep -q '"success": true'; then
            exit 0
        fi
        exit 1
    fi
    sleep 2
done

echo "Runtime probe timed out on $SERIAL" >&2
exit 1

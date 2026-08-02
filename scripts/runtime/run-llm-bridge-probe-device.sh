#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
SERIAL="${1:-${ANDROID_SERIAL:-}}"
APK="$ROOT/alpine-llm-bridge-probe/build/outputs/apk/debug/alpine-llm-bridge-probe-debug.apk"

[[ -x "$ADB" ]] || { echo "adb not found: $ADB" >&2; exit 2; }
[[ -f "$APK" ]] || { echo "bridge probe APK not found: $APK" >&2; exit 2; }
if [[ -z "$SERIAL" ]]; then
    SERIAL="$($ADB devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')"
fi
[[ -n "$SERIAL" ]] || { echo "No Android device is connected" >&2; exit 2; }

PACKAGE="dev.alpine.llm.bridgeprobe"
ACTIVITY="$PACKAGE/.LlmBridgeProbeActivity"

"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
"$ADB" -s "$SERIAL" shell run-as "$PACKAGE" rm -f files/llm-bridge-probe-result.json || true
"$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY" --ez auto_run true >/dev/null

for _ in $(seq 1 180); do
    RESULT="$($ADB -s "$SERIAL" shell run-as "$PACKAGE" \
        cat files/llm-bridge-probe-result.json 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$RESULT" ]]; then
        printf '%s\n' "$RESULT"
        if printf '%s' "$RESULT" | grep -q '"success": true'; then
            exit 0
        fi
        exit 1
    fi
    sleep 2
done

echo "LLM bridge probe timed out on $SERIAL" >&2
exit 1

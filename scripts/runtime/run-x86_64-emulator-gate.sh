#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
REPORT="${X86_64_GATE_REPORT:-$ROOT/build/reports/x86_64-emulator-gate.json}"
BOOT_TIMEOUT_SECONDS="${X86_64_BOOT_TIMEOUT_SECONDS:-180}"
ALLOW_SKIP="false"
[[ "${1:-}" == "--allow-skip" ]] && ALLOW_SKIP="true"

mkdir -p "$(dirname "$REPORT")"
write_report() {
  local status="$1" reason="${2:-}" api_level="${3:-}" model="${4:-}"
  python3 - "$ROOT" "$REPORT" "$status" "$reason" "$api_level" "$model" <<'PY'
import json
import sys
from pathlib import Path

root, output, status, reason, api_level, model = sys.argv[1:]
lock = json.loads((Path(root) / "runtime/alpine-3.21.3-x86_64.lock.json").read_text())
report = {
    "schema_version": 3,
    "abi": "x86_64",
    "status": status,
    "reason": reason,
    "device_class": "android_emulator",
    "device": {
        "model": model or None,
        "api_level": int(api_level) if api_level.isdigit() else None,
    },
    "artifact_provenance": {
        "runtime_version": lock["runtime_version"],
        "rootfs_sha256": lock["rootfs"]["sha256"],
        "proot_sha256": lock["proot"]["sha256"],
        "loader_sha256": lock["loader"]["sha256"],
        "sbom_sha256": lock["sbom"]["sha256"],
    },
    "required_probe_checks": [
        "success",
        "healthy",
        "machine_x86_64",
        "terminal",
        "restart",
    ],
}
Path(output).write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
PY
  cat "$REPORT"
}

if [[ ! -x "$ADB" ]]; then
  write_report "BLOCKED" "ADB_NOT_FOUND"
  exit 2
fi

SERIAL=""
if ! DEVICE_LIST="$("$ADB" devices 2>/dev/null)"; then
  write_report "BLOCKED" "ADB_UNAVAILABLE"
  exit 2
fi
while read -r candidate state; do
  [[ "$state" == "device" ]] || continue
  [[ "$candidate" =~ ^[A-Za-z0-9._:-]+$ ]] || continue
  ABI_LIST="$($ADB -s "$candidate" shell getprop ro.product.cpu.abilist 2>/dev/null | tr -d '\r')"
  IS_EMULATOR="$($ADB -s "$candidate" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
  if [[ ",$ABI_LIST," == *,x86_64,* && "$IS_EMULATOR" == "1" ]]; then
    SERIAL="$candidate"
    break
  fi
done < <(printf '%s\n' "$DEVICE_LIST" | awk 'NR > 1 {print $1, $2}')

if [[ -z "$SERIAL" ]]; then
  write_report "SKIP_NO_X86_64_EMULATOR" "NO_CONNECTED_X86_64_EMULATOR"
  [[ "$ALLOW_SKIP" == "true" ]] && exit 0
  exit 3
fi

API_LEVEL="$($ADB -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -dc '0-9')"
MODEL="$($ADB -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' | tr -c 'A-Za-z0-9._:-' '_')"
BOOT_DEADLINE=$((SECONDS + BOOT_TIMEOUT_SECONDS))
while [[ "$($ADB -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; do
  if (( SECONDS >= BOOT_DEADLINE )); then
    write_report "FAILED" "EMULATOR_BOOT_TIMEOUT" "$API_LEVEL" "$MODEL"
    exit 1
  fi
  sleep 2
done

if ! PROBE_RESULT="$($ROOT/scripts/runtime/run-probe-device.sh "$SERIAL" false)"; then
  write_report "FAILED" "RUNTIME_PROBE_FAILED" "$API_LEVEL" "$MODEL"
  exit 1
fi
if ! printf '%s' "$PROBE_RESULT" | python3 -c '
import json, sys
result = json.load(sys.stdin)
assert result.get("success") is True
assert result.get("healthy") is True
assert result.get("exit_code") == 0
assert result.get("command_probe_ok") is True
assert result.get("guest_machine_matches_primary_abi") is True
assert result.get("guest_alpine_release_present") is True
assert result.get("terminal_responded") is True
assert result.get("terminal_prompted") is True
assert result.get("restart_probe_ok") is True
'; then
  write_report "FAILED" "RUNTIME_PROBE_DID_NOT_PASS" "$API_LEVEL" "$MODEL"
  exit 1
fi
write_report "PASSED" "" "$API_LEVEL" "$MODEL"

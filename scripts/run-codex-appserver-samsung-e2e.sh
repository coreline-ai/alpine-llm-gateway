#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SAMSUNG_SERIAL="R3CY40PXCAP"
EXPECTED_MODEL="SM-S931N"
APP_PACKAGE="dev.alpine.integrated.codexdebug"
TEST_RUNNER="$APP_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
ON_APK="/tmp/alpine-codex-final.apk"
ON_TEST_APK="/tmp/alpine-codex-final-androidTest.apk"
ROLLBACK_APK="/tmp/alpine-codex-rollback.apk"
ROLLBACK_TEST_APK="/tmp/alpine-codex-rollback-androidTest.apk"
APPROVE_LOGOUT=false
APPROVE_ROLLBACK=false

usage() {
  cat <<'EOF'
Usage: scripts/run-codex-appserver-samsung-e2e.sh [options]

Default behavior is non-destructive: verify/install the ON APK and run redacted account, refresh,
turn, Stop, restart, lifecycle, Runtime-isolation, and orphan checks on Samsung R3CY40PXCAP.

Options:
  --on-apk PATH                 Codex ON APK (default: /tmp/alpine-codex-final.apk)
  --on-test-apk PATH            Codex ON AndroidTest APK
  --rollback-apk PATH           Same-package feature-OFF rollback APK
  --rollback-test-apk PATH      Rollback AndroidTest APK
  --approve-account-logout      Run official logout/data-isolation test; removes current Codex login
  --approve-feature-off-rollback
                                Install feature-OFF rollback after approved logout and leave it OFF
  -h, --help                    Show this help

Rollback requires both approval flags. The script never uninstalls, clears app data, force-stops,
reboots, changes network/Doze state, reads Codex auth files, or prints raw instrumentation output.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --on-apk)
      ON_APK="${2:?missing --on-apk value}"
      shift 2
      ;;
    --on-test-apk)
      ON_TEST_APK="${2:?missing --on-test-apk value}"
      shift 2
      ;;
    --rollback-apk)
      ROLLBACK_APK="${2:?missing --rollback-apk value}"
      shift 2
      ;;
    --rollback-test-apk)
      ROLLBACK_TEST_APK="${2:?missing --rollback-test-apk value}"
      shift 2
      ;;
    --approve-account-logout)
      APPROVE_LOGOUT=true
      shift
      ;;
    --approve-feature-off-rollback)
      APPROVE_ROLLBACK=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$APPROVE_ROLLBACK" == true && "$APPROVE_LOGOUT" != true ]]; then
  echo "CODEX_ROLLBACK_REQUIRES_APPROVED_LOGOUT" >&2
  exit 2
fi

if [[ -n "${ADB_BIN:-}" ]]; then
  ADB="$ADB_BIN"
elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
  ADB="$HOME/Library/Android/sdk/platform-tools/adb"
else
  echo "ADB_NOT_FOUND" >&2
  exit 2
fi

PYTHON_BIN="${PYTHON_BIN:-python3.11}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "PYTHON_NOT_FOUND" >&2
  exit 2
fi

for artifact in "$ON_APK" "$ON_TEST_APK"; do
  if [[ ! -f "$artifact" ]]; then
    echo "CODEX_ON_ARTIFACT_MISSING" >&2
    exit 2
  fi
done
if [[ "$APPROVE_ROLLBACK" == true ]]; then
  for artifact in "$ROLLBACK_APK" "$ROLLBACK_TEST_APK"; do
    if [[ ! -f "$artifact" ]]; then
      echo "CODEX_ROLLBACK_ARTIFACT_MISSING" >&2
      exit 2
    fi
  done
fi

adb_target() {
  "$ADB" -s "$SAMSUNG_SERIAL" "$@"
}

stable_property() {
  adb_target shell getprop "$1" | tr -d '\r'
}

if [[ "$(adb_target get-state 2>/dev/null)" != "device" ]]; then
  echo "SAMSUNG_DEVICE_UNAVAILABLE" >&2
  exit 3
fi
manufacturer="$(stable_property ro.product.manufacturer)"
manufacturer_lower="$(printf '%s' "$manufacturer" | tr '[:upper:]' '[:lower:]')"
model="$(stable_property ro.product.model)"
api_level="$(stable_property ro.build.version.sdk)"
abi_list="$(stable_property ro.product.cpu.abilist)"
page_size="$(adb_target shell getconf PAGESIZE | tr -d '\r')"
if [[ "$manufacturer_lower" != "samsung" || "$model" != "$EXPECTED_MODEL" ]]; then
  echo "SAMSUNG_DEVICE_IDENTITY_MISMATCH" >&2
  exit 3
fi
if ! [[ "$api_level" =~ ^[0-9]+$ ]] || (( api_level < 36 )); then
  echo "SAMSUNG_API_LEVEL_UNSUPPORTED" >&2
  exit 3
fi
if [[ ",$abi_list," != *",arm64-v8a,"* ]]; then
  echo "SAMSUNG_ARM64_ABI_MISSING" >&2
  exit 3
fi
if [[ "$page_size" != "4096" && "$page_size" != "16384" ]]; then
  echo "SAMSUNG_PAGE_SIZE_INVALID" >&2
  exit 3
fi
echo "device_check=PASS manufacturer=Samsung model=$EXPECTED_MODEL api_level=$api_level page_size=$page_size"

cd "$PROJECT_DIR"
"$PYTHON_BIN" scripts/verify-codex-appserver.py \
  --binary .codex-artifacts/0.147.0/linux-arm64/codex \
  --archive "$ON_APK" \
  --expect-binary >/dev/null
echo "on_artifact_check=PASS"

run_instrumentation() {
  local classes="$1"
  shift
  local output
  if ! output="$(adb_target shell am instrument -w -r \
      -e class "$classes" "$@" "$TEST_RUNNER" 2>/dev/null)"; then
    echo "SAMSUNG_INSTRUMENTATION_FAILED" >&2
    return 1
  fi
  if ! grep -Eq 'OK \([1-9][0-9]* tests?\)' <<<"$output"; then
    echo "SAMSUNG_INSTRUMENTATION_ASSERTION_FAILED" >&2
    return 1
  fi
}

adb_target install -r "$ON_APK" >/dev/null
adb_target install -r "$ON_TEST_APK" >/dev/null
echo "install_upgrade=PASS"

SAFE_CLASSES="dev.alpine.integrated.CodexAccountProbeInstrumentedTest,dev.alpine.integrated.CodexLiveTurnInstrumentedTest,dev.alpine.integrated.CodexLiveLifecycleInstrumentedTest,dev.alpine.integrated.CodexUiAccessibilityInstrumentedTest"
run_instrumentation "$SAFE_CLASSES"
echo "account_model_refresh_turn_stop_restart_lifecycle_runtime_ui_accessibility=PASS"

# Instrumentation may either retain or tear down the target process. Accept exactly one owned
# child while the app is alive, or zero children after the owner exits; reject every orphan or
# duplicate shape without printing process identifiers.
app_pid="$(adb_target shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
total_codex_children="$(adb_target shell "ps -A -o NAME | grep -c '^libcodex_app_server.so\$' || true" | tr -d '\r')"
if [[ -z "$app_pid" ]]; then
  if [[ "$total_codex_children" != "0" ]]; then
    echo "CODEX_ORPHAN_PROCESS_DETECTED" >&2
    exit 4
  fi
else
  owned_codex_children="$(adb_target shell "ps -A -o PPID,NAME | grep -c '^ *$app_pid libcodex_app_server.so\$' || true" | tr -d '\r')"
  if [[ "$owned_codex_children" != "1" || "$total_codex_children" != "1" ]]; then
    echo "CODEX_PROCESS_OWNERSHIP_INVALID" >&2
    exit 4
  fi
fi
echo "post_instrumentation_orphan_check=PASS"

if [[ "$APPROVE_LOGOUT" == true ]]; then
  # This argument is consumed only by the separately gated test class.
  run_instrumentation \
    "dev.alpine.integrated.CodexLogoutIsolationInstrumentedTest" \
    -e approveCodexLogout true
  echo "logout_data_isolation=PASS"
fi

if [[ "$APPROVE_ROLLBACK" == true ]]; then
  "$PYTHON_BIN" scripts/verify-codex-appserver.py \
    --archive "$ROLLBACK_APK" \
    --forbid-binary >/dev/null
  adb_target install -r "$ROLLBACK_APK" >/dev/null
  adb_target install -r "$ROLLBACK_TEST_APK" >/dev/null
  run_instrumentation \
    "dev.alpine.integrated.CodexFeatureOffRollbackInstrumentedTest" \
    -e approveCodexRollback true
  adb_target shell monkey -p "$APP_PACKAGE" -c android.intent.category.LAUNCHER 1 \
    >/dev/null 2>&1
  rollback_child_count="$(adb_target shell "ps -A -o NAME | grep -c '^libcodex_app_server.so\$' || true" | tr -d '\r')"
  if [[ "$rollback_child_count" != "0" ]]; then
    echo "CODEX_ROLLBACK_PROCESS_PRESENT" >&2
    exit 4
  fi
  echo "feature_off_rollback=PASS"
else
  adb_target shell monkey -p "$APP_PACKAGE" -c android.intent.category.LAUNCHER 1 \
    >/dev/null 2>&1
  sleep 2
  app_pid="$(adb_target shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$app_pid" ]]; then
    echo "CODEX_ON_APP_PROCESS_MISSING" >&2
    exit 4
  fi
  on_child_count="$(adb_target shell "ps -A -o PPID,NAME | grep -c '^ *$app_pid libcodex_app_server.so\$' || true" | tr -d '\r')"
  total_codex_children="$(adb_target shell "ps -A -o NAME | grep -c '^libcodex_app_server.so\$' || true" | tr -d '\r')"
  if [[ "$on_child_count" != "1" || "$total_codex_children" != "1" ]]; then
    echo "CODEX_ON_PROCESS_COUNT_INVALID" >&2
    exit 4
  fi
  echo "on_process_singleton=PASS"
fi

echo "samsung_codex_e2e_runner=PASS logout_executed=$APPROVE_LOGOUT rollback_executed=$APPROVE_ROLLBACK"

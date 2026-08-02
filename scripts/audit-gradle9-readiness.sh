#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/build/reports/gradle9-warning-mode-all.log"
REPORT="$ROOT/build/reports/gradle9-readiness.json"
AUDIT_TASK="${GRADLE9_AUDIT_TASK:-:alpine-runtime-host:generatePomFileForReleasePublication}"
mkdir -p "$(dirname "$LOG")"
"$ROOT/gradlew" "$AUDIT_TASK" --rerun-tasks --warning-mode all --console plain 2>&1 | tee "$LOG"
python3 "$ROOT/scripts/verify-gradle9-readiness.py" "$LOG" --output "$REPORT" --strict-project

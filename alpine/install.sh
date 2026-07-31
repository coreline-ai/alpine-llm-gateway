#!/bin/sh
set -eu

PREFIX="${PREFIX:-/opt/alpine-llm-gateway}"
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required; install it with: apk add python3" >&2
  exit 1
}

mkdir -p "$PREFIX"
cp -R "$ROOT/alpine_llm" "$PREFIX/"
cp -R "$ROOT/bin" "$PREFIX/"
cp "$ROOT/config.example.json" "$PREFIX/"
chmod 0755 "$PREFIX/bin/llmctl" "$PREFIX/bin/llm-gatewayd"

echo "installed alpine-llm-gateway to $PREFIX"
echo "run: PYTHONPATH=$PREFIX python3 -m alpine_llm.cli serve --config $PREFIX/config.example.json"

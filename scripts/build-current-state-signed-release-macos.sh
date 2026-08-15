#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SIGNING_DIR="${ALPINE_RELEASE_SIGNING_DIR:-$HOME/Library/Application Support/Coreline AI/Alpine LLM Gateway/release-signing}"
export ALPINE_RELEASE_KEYSTORE="$SIGNING_DIR/alpine-upload.p12"
export ALPINE_RELEASE_KEY_ALIAS="alpine-ai-workspace-upload"
KEYCHAIN_SERVICE="dev.alpine.integrated.release-signing"
KEYCHAIN_ACCOUNT="store-password"

if [[ ! -f "$ALPINE_RELEASE_KEYSTORE" ]]; then
  echo "RELEASE_KEYSTORE_NOT_FOUND_RUN_CONFIGURE_FIRST" >&2
  exit 2
fi
export ALPINE_RELEASE_STORE_PASSWORD="$(
  security find-generic-password -w -s "$KEYCHAIN_SERVICE" -a "$KEYCHAIN_ACCOUNT"
)"
export ALPINE_RELEASE_KEY_PASSWORD="$ALPINE_RELEASE_STORE_PASSWORD"
cleanup() {
  unset ALPINE_RELEASE_STORE_PASSWORD ALPINE_RELEASE_KEY_PASSWORD
}
trap cleanup EXIT

"$SCRIPT_DIR/build-current-state-public-release.sh"

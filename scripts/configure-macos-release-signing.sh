#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]] || ! command -v security >/dev/null 2>&1; then
  echo "MACOS_KEYCHAIN_REQUIRED" >&2
  exit 2
fi

JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
KEYTOOL="$JAVA_HOME/bin/keytool"
OPENSSL_BIN="$(command -v openssl)"
SIGNING_DIR="${ALPINE_RELEASE_SIGNING_DIR:-$HOME/Library/Application Support/Coreline AI/Alpine LLM Gateway/release-signing}"
KEYSTORE="$SIGNING_DIR/alpine-upload.p12"
KEY_ALIAS="alpine-ai-workspace-upload"
KEYCHAIN_SERVICE="dev.alpine.integrated.release-signing"
KEYCHAIN_ACCOUNT="store-password"

if [[ ! -x "$KEYTOOL" ]] || [[ -z "$OPENSSL_BIN" ]]; then
  echo "RELEASE_SIGNING_TOOLCHAIN_MISSING" >&2
  exit 2
fi

KEYSTORE_PRESENT=false
SECRET_PRESENT=false
[[ -f "$KEYSTORE" ]] && KEYSTORE_PRESENT=true
if security find-generic-password \
  -s "$KEYCHAIN_SERVICE" -a "$KEYCHAIN_ACCOUNT" >/dev/null 2>&1; then
  SECRET_PRESENT=true
fi

if [[ "$KEYSTORE_PRESENT" != "$SECRET_PRESENT" ]]; then
  echo "RELEASE_SIGNING_STATE_INCONSISTENT" >&2
  exit 3
fi

if [[ "$KEYSTORE_PRESENT" == false ]]; then
  mkdir -p "$SIGNING_DIR"
  chmod 700 "$SIGNING_DIR"
  export ALPINE_BOOTSTRAP_SIGNING_SECRET="$($OPENSSL_BIN rand -hex 48)"
  cleanup_secret() {
    unset ALPINE_BOOTSTRAP_SIGNING_SECRET
  }
  trap cleanup_secret EXIT
  "$KEYTOOL" -genkeypair -noprompt \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA384withRSA \
    -validity 10000 \
    -dname "CN=Alpine AI Workspace Upload, OU=Release Engineering, O=Coreline AI, C=KR" \
    -storetype PKCS12 \
    -keystore "$KEYSTORE" \
    -storepass:env ALPINE_BOOTSTRAP_SIGNING_SECRET \
    -keypass:env ALPINE_BOOTSTRAP_SIGNING_SECRET >/dev/null 2>&1
  chmod 600 "$KEYSTORE"
  if ! security add-generic-password -U \
    -s "$KEYCHAIN_SERVICE" \
    -a "$KEYCHAIN_ACCOUNT" \
    -w "$ALPINE_BOOTSTRAP_SIGNING_SECRET" >/dev/null; then
    rm -f "$KEYSTORE"
    echo "RELEASE_SIGNING_KEYCHAIN_WRITE_FAILED" >&2
    exit 3
  fi
fi

export ALPINE_RELEASE_STORE_PASSWORD="$(
  security find-generic-password -w -s "$KEYCHAIN_SERVICE" -a "$KEYCHAIN_ACCOUNT"
)"
"$KEYTOOL" -list \
  -keystore "$KEYSTORE" \
  -storepass:env ALPINE_RELEASE_STORE_PASSWORD \
  -alias "$KEY_ALIAS" >/dev/null
CERT_FILE="$(mktemp)"
trap 'rm -f "$CERT_FILE"; unset ALPINE_RELEASE_STORE_PASSWORD ALPINE_BOOTSTRAP_SIGNING_SECRET' EXIT
"$KEYTOOL" -exportcert \
  -keystore "$KEYSTORE" \
  -storepass:env ALPINE_RELEASE_STORE_PASSWORD \
  -alias "$KEY_ALIAS" \
  -file "$CERT_FILE" >/dev/null 2>&1
CERT_SHA256="$(shasum -a 256 "$CERT_FILE" | awk '{print $1}')"

echo "release_signing=CONFIGURED"
echo "keystore=$KEYSTORE"
echo "alias=$KEY_ALIAS"
echo "certificate_sha256=$CERT_SHA256"

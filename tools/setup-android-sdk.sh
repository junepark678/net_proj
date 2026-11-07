#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
VERSION=16111833
BASE_URL=https://dl.google.com/android/repository
case "$(uname -s)" in
  Linux) ARCH=linux; SHA1=e025545c62a8e64c7559119566a569fb1dec5f60; SHA256=0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8 ;;
  Darwin) ARCH=mac_x86_64; SHA1=112cf9618794a997ff273537d55bee02c22abffe; SHA256=7e601c04e7173754b3d051a87bb4c3a8243c65a9d7bfa9356ed6499fb8953fa3 ;;
  *) echo "Unsupported host OS" >&2; exit 1 ;;
esac
ARCHIVE="commandlinetools-${ARCH}-${VERSION}_latest.zip"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$SDK_DIR/cmdline-tools"
if [[ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl -fsSL "$BASE_URL/$ARCHIVE" -o "$TMP/tools.zip"
  if command -v sha256sum >/dev/null; then
  printf '%s  %s\n' "$SHA1" "$TMP/tools.zip" | sha1sum -c -
  printf '%s  %s\n' "$SHA256" "$TMP/tools.zip" | sha256sum -c -
  else
    printf '%s  %s\n' "$SHA1" "$TMP/tools.zip" | shasum -a 1 -c -
    printf '%s  %s\n' "$SHA256" "$TMP/tools.zip" | shasum -a 256 -c -
  fi
  mkdir "$TMP/unpack"
  unzip -q "$TMP/tools.zip" -d "$TMP/unpack"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$TMP/unpack/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
fi
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
PACKAGES=("platform-tools" "platforms;android-35" "build-tools;35.0.0")
if [[ ! -d "$SDK_DIR/platform-tools" || ! -d "$SDK_DIR/platforms/android-35" || ! -d "$SDK_DIR/build-tools/35.0.0" ]]; then
  yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null || true
  "$SDKMANAGER" --sdk_root="$SDK_DIR" "${PACKAGES[@]}"
fi
printf 'sdk.dir=%s\n' "$SDK_DIR" > "$ROOT/local.properties"
echo "Android SDK ready at $SDK_DIR"

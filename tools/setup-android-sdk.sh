#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
VERSION=16111833
REVISION=23.0
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
# Require metadata as well as the files used by each package.
property_is() {
  [[ -f "$1" ]] && tr -d '\r' < "$1" | grep -Eq "^[[:space:]]*$2[[:space:]]*=[[:space:]]*$3[[:space:]]*$"
}
tools_valid() {
  local dir="$SDK_DIR/cmdline-tools/latest"
  property_is "$dir/source.properties" 'Pkg\.Revision' "${REVISION//./\\.}" &&
    [[ -x "$dir/bin/sdkmanager" && -x "$dir/bin/avdmanager" && -x "$dir/bin/android" && -s "$dir/lib/avdmanager-classpath.jar" ]]
}
if ! tools_valid; then
  echo "Downloading Android command-line tools revision $REVISION (archive $VERSION): $BASE_URL/$ARCHIVE"
  curl -fL --progress-bar "$BASE_URL/$ARCHIVE" -o "$TMP/tools.zip"
  if command -v sha256sum >/dev/null; then
  printf '%s  %s\n' "$SHA1" "$TMP/tools.zip" | sha1sum -c -
  printf '%s  %s\n' "$SHA256" "$TMP/tools.zip" | sha256sum -c -
  else
    printf '%s  %s\n' "$SHA1" "$TMP/tools.zip" | shasum -a 1 -c -
    printf '%s  %s\n' "$SHA256" "$TMP/tools.zip" | shasum -a 256 -c -
  fi
  mkdir "$TMP/unpack"
  unzip -q "$TMP/tools.zip" -d "$TMP/unpack"
  property_is "$TMP/unpack/cmdline-tools/source.properties" 'Pkg\.Revision' "${REVISION//./\\.}"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$TMP/unpack/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
fi
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
PACKAGES=()
if ! property_is "$SDK_DIR/platform-tools/source.properties" 'Pkg\.Revision' '[0-9]+(\.[0-9]+)*' ||
    [[ ! -x "$SDK_DIR/platform-tools/adb" ]]; then
  PACKAGES+=("platform-tools")
fi
if ! property_is "$SDK_DIR/platforms/android-35/source.properties" 'AndroidVersion\.ApiLevel' '35' ||
    [[ ! -s "$SDK_DIR/platforms/android-35/android.jar" ]]; then
  PACKAGES+=("platforms;android-35")
fi
if ! property_is "$SDK_DIR/build-tools/35.0.0/source.properties" 'Pkg\.Revision' '35\.0\.0' ||
    [[ ! -x "$SDK_DIR/build-tools/35.0.0/aapt2" ]]; then
  PACKAGES+=("build-tools;35.0.0")
fi
if (( ${#PACKAGES[@]} )); then
  # yes can exit with SIGPIPE when sdkmanager finishes; keep sdkmanager's status.
  set +e
  yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null
  license_status=${PIPESTATUS[1]}
  set -e
  if (( license_status != 0 )); then
    echo "sdkmanager license acceptance failed ($license_status)" >&2
    exit "$license_status"
  fi
  # sdkmanager skips packages whose metadata remains after file corruption.
  # Remove only invalid required packages so they can be provisioned again.
  for package in "${PACKAGES[@]}"; do
    rm -rf "${SDK_DIR:?}/${package//;/\/}"
  done
  "$SDKMANAGER" --sdk_root="$SDK_DIR" "${PACKAGES[@]}"
fi
printf 'sdk.dir=%s\n' "$SDK_DIR" > "$ROOT/local.properties"
echo "Android SDK ready at $SDK_DIR"

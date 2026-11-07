#!/usr/bin/env bash
set -euo pipefail
version=16.0
base="https://dist.torproject.org/torbrowser/${version}"
os=$(uname -s); arch=$(uname -m)
case "$os/$arch" in
  Linux/x86_64) platform=linux-x86_64 ;;
  Linux/aarch64|Linux/arm64) platform=linux-aarch64 ;;
  Darwin/x86_64) platform=macos-x86_64 ;;
  Darwin/arm64) platform=macos-aarch64 ;;
  MINGW64_NT-*/x86_64|MSYS_NT-*/x86_64) platform=windows-x86_64 ;;
  *) echo "Unsupported host: $os/$arch" >&2; exit 2 ;;
esac
case "$platform" in
 linux-x86_64) sha=cc950ebe9acb80188325c3a2cd8817def7dd7237d6fdcd861bb87b234a0cb2fd ;;
 linux-aarch64) sha=bed98744e093a0dc3a22be0eecfdce7a33cb36866d3f15fcf011a35bf3513d0d ;;
 macos-x86_64) sha=5b1860215c26f2d408392d1d79e798a2f81d65eb939510bb94c11f237bd0bfc3 ;;
 macos-aarch64) sha=b8da251eccbe9e82f3681ee3af3c97fb4291c4a4e153e5b824bde48a1117cd6a ;;
 windows-x86_64) sha=2ee123e9167318a3742778ff704e75f01d4780f05a14522d451fe011011077c4 ;;
esac
executable=tor
[[ "$platform" != windows-* ]] || executable=tor.exe
if command -v sha256sum >/dev/null 2>&1; then
  checksum=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  checksum=(shasum -a 256)
else
  echo 'SHA-256 verification requires sha256sum or shasum' >&2
  exit 1
fi
name="tor-expert-bundle-${platform}-${version}.tar.gz"
target="build/tor/${platform}"
marker="$target/.completed-sha256"
if [[ -x "$target/tor/$executable" && -d "$target/data" && -d "$target/docs" && -f "$marker" ]] &&
   [[ "$(cat "$marker")" == "$sha" ]]; then
  echo "Using cached Tor bundle: $target"
  exit 0
fi
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
curl -fL --retry 3 "$base/$name" -o "$tmp/$name"
printf '%s  %s\n' "$sha" "$tmp/$name" | "${checksum[@]}" -c -
tar -xzf "$tmp/$name" -C "$tmp"
root=$(find "$tmp" -type f -path "*/tor/$executable" -print -quit)
[[ -n "$root" ]] || { echo 'tor executable not found in archive' >&2; exit 1; }
bundle=${root%/tor/*}
[[ -d "$bundle/data" && -d "$bundle/docs" ]] || {
  echo 'data or docs directory not found in archive' >&2; exit 1;
}
mkdir -p "$target"
rm -f "$marker" "$target/.archive"
rm -rf "$target/tor" "$target/data" "$target/docs"
cp -a "$bundle/tor" "$bundle/data" "$bundle/docs" "$target/"
chmod +x "$target/tor/$executable"
printf '%s\n' "$sha" > "$marker"

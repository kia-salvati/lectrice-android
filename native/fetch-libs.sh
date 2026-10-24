#!/usr/bin/env bash
# Unpacks the pinned native libraries into native/lib/<set>/arm64-v8a/ (gitignored). Downloads only the archives
# missing from native/downloads/, and checks every archive against SHA256SUMS before unpacking.
set -euo pipefail

NATIVE="$(cd "$(dirname "$0")" && pwd)"
DOWNLOADS="$NATIVE/downloads"
LIB="$NATIVE/lib"
ABI="arm64-v8a"
SHERPA_RELEASES="https://github.com/k2-fsa/sherpa-onnx/releases/download"
PDFIUM_URL="https://github.com/bblanchon/pdfium-binaries/releases/download/chromium/7881/pdfium-android-arm64.tgz"

declare -A URLS=(
  [sherpa-onnx-v1.13.8-android.tar.bz2]="$SHERPA_RELEASES/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
  [sherpa-onnx-v2.0.0-android.tar.bz2]="$SHERPA_RELEASES/v2.0.0/sherpa-onnx-v2.0.0-android.tar.bz2"
  [pdfium-android-arm64.tgz]="$PDFIUM_URL"
)

mkdir -p "$DOWNLOADS"
for archive in "${!URLS[@]}"; do
  if [ ! -f "$DOWNLOADS/$archive" ]; then
    echo "Downloading $archive"
    curl -fL -o "$DOWNLOADS/$archive.part" "${URLS[$archive]}"
    mv "$DOWNLOADS/$archive.part" "$DOWNLOADS/$archive"
  fi
done
(cd "$DOWNLOADS" && sha256sum --quiet -c "$NATIVE/SHA256SUMS")

# Only the named members are extracted, each archive into its own empty folder.
unpack_sherpa() {
  local version="$1" set="$2" work
  work="$(mktemp -d)"
  tar -xjf "$DOWNLOADS/sherpa-onnx-v$version-android.tar.bz2" -C "$work" \
    "./jniLibs/$ABI/libsherpa-onnx-c-api.so" "./jniLibs/$ABI/libonnxruntime.so"
  rm -rf "${LIB:?}/$set" && mkdir -p "$LIB/$set/$ABI"
  mv "$work/jniLibs/$ABI/"*.so "$LIB/$set/$ABI/"
  rm -rf "$work"
}

unpack_sherpa 1.13.8 sherpa1
unpack_sherpa 2.0.0 sherpa2

work="$(mktemp -d)"
tar -xzf "$DOWNLOADS/pdfium-android-arm64.tgz" -C "$work" lib/libpdfium.so LICENSE
rm -rf "${LIB:?}/pdfium" && mkdir -p "$LIB/pdfium/$ABI"
mv "$work/lib/libpdfium.so" "$LIB/pdfium/$ABI/"
mv "$work/LICENSE" "$LIB/pdfium/LICENSE"
rm -rf "$work"

ls -l "$LIB"/*/"$ABI"/

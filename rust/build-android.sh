#!/usr/bin/env bash
# Builds liblectrice_ffi.so for arm64 against one sherpa-onnx set, copies it with its native libraries into a jniLibs
# folder, and generates the Kotlin bindings.
#   rust/build-android.sh <sherpa1|sherpa2> <jniLibs dir> <kotlin out dir> [cargo features]
# sherpa1 = the core in ../lectrice (sherpa-onnx 1.13.8, through .cargo/config.toml);
# sherpa2 = the core in $LECTRICE_CORE_SHERPA2 (default ../lectrice-sherpa2/backend/crates, sherpa-onnx 2.0.0).
set -euo pipefail

SET="$1"
JNI_LIBS="$2"
KOTLIN_OUT="$3"
FEATURES="${4:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABI="arm64-v8a"
TRIPLE="aarch64-linux-android"
MIN_SDK=29
CORE_URL="https://github.com/kia-salvati/lectrice"
SHERPA2_LOCK="$ROOT/rust/sherpa2.Cargo.lock"

cd "$ROOT"
[ -f "native/lib/$SET/$ABI/libsherpa-onnx-c-api.so" ] && [ -f "native/lib/pdfium/$ABI/libpdfium.so" ] || native/fetch-libs.sh >/dev/null

build_args=(build -p lectrice-ffi --lib --release)
# No /home/<user>/... paths in the library (Cargo's trim-paths isn't stable yet); rustc uses the last matching prefix.
cargo_home="${CARGO_HOME:-$HOME/.cargo}"
build_args+=(--config "build.rustflags=[\"--remap-path-prefix=$HOME=/home\", \"--remap-path-prefix=$cargo_home=/cargo\", \"--remap-path-prefix=$ROOT=/lectrice-android\"]")
[ -n "$FEATURES" ] && build_args+=(--features "$FEATURES")
target_dir="$ROOT/target"

ndk_build() {
  SHERPA_ONNX_LIB_DIR="$ROOT/native/lib/$SET/$ABI" CARGO_TARGET_DIR="$target_dir" \
    cargo ndk -t "$ABI" --platform "$MIN_SDK" "${build_args[@]}"
}

case "$SET" in
  sherpa1) ndk_build ;;
  sherpa2)
    core="$(cd "${LECTRICE_CORE_SHERPA2:-$ROOT/../lectrice-sherpa2/backend/crates}" && pwd)"
    for crate in lectrice-domain lectrice-app lectrice-infra; do
      build_args+=(--config "patch.\"$CORE_URL\".$crate.path=\"$core/$crate\"")
    done
    target_dir="$ROOT/target/sherpa2"
    # One Cargo.lock can't hold both sherpa-onnx versions, so the sherpa2 build swaps in its own.
    saved="$(mktemp)"
    cp Cargo.lock "$saved"
    [ -f "$SHERPA2_LOCK" ] && cp "$SHERPA2_LOCK" Cargo.lock
    status=0
    ndk_build || status=$?
    cp Cargo.lock "$SHERPA2_LOCK"
    cp "$saved" Cargo.lock
    rm -f "$saved"
    [ "$status" -eq 0 ] || exit "$status"
    ;;
  *) echo "unknown set: $SET (sherpa1 | sherpa2)" >&2; exit 2 ;;
esac

# Copied by name: cargo-ndk's `-o` would also copy pdfium-render's own cdylib, which nothing loads.
rm -rf "${JNI_LIBS:?}/$ABI" && mkdir -p "$JNI_LIBS/$ABI"
cp "$target_dir/$TRIPLE/release/liblectrice_ffi.so" "native/lib/$SET/$ABI/"*.so "native/lib/pdfium/$ABI/libpdfium.so" "$JNI_LIBS/$ABI/"

# The generator runs on the laptop and reads the API metadata from the Android library.
cargo run -q -p uniffi-bindgen -- generate \
  --library "$target_dir/$TRIPLE/release/liblectrice_ffi.so" --language kotlin --no-format --out-dir "$KOTLIN_OUT"

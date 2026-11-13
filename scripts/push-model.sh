#!/usr/bin/env bash
# Puts a voice model from ../lectrice/models/ into a debug build's files/models/<model id>/ (run-as), without the in-app
# download, and writes the `.installed` marker the app checks (ModelStore). Only what the English engine reads is pushed.
#   scripts/push-model.sh <serial> fast|full|small [package]
set -euo pipefail

SERIAL="$1"
case "$2" in
  fast) BUNDLE="kokoro-lectrice-q8-multi-lang-v1_0"; MODEL="model.int8.onnx"; ID="kokoro-lectrice-fast" ;;
  full) BUNDLE="kokoro-multi-lang-v1_0"; MODEL="model.onnx"; ID="kokoro-en-v1_0-fp32" ;;
  small) BUNDLE="kokoro-lectrice-w8-multi-lang-v1_0"; MODEL="model.int8.onnx"; ID="kokoro-lectrice-small" ;;
  *) echo "usage: $0 <serial> fast|full|small [package]" >&2; exit 2 ;;
esac
PACKAGE="${3:-app.lectrice.debug}"
SOURCE="$(cd "$(dirname "$0")/../.." && pwd)/lectrice/models/$BUNDLE"
FILES=(voices.bin tokens.txt lexicon-us-en.txt lexicon-gb-en.txt espeak-ng-data "$MODEL")
ADB=(adb -s "$SERIAL")
[ -d "$SOURCE" ] || { echo "missing $SOURCE" >&2; exit 1; }
"${ADB[@]}" shell pm path "$PACKAGE" >/dev/null || { echo "$PACKAGE is not installed" >&2; exit 1; }

STAGE="/data/local/tmp/lectrice-$BUNDLE"
"${ADB[@]}" shell "rm -rf $STAGE && mkdir -p $STAGE"
for file in "${FILES[@]}"; do
  "${ADB[@]}" push "$SOURCE/$file" "$STAGE/"
done
LOCAL_SHA="$(sha256sum "$SOURCE/$MODEL" | cut -d' ' -f1)"
PHONE_SHA="$("${ADB[@]}" shell sha256sum "$STAGE/$MODEL" | cut -d' ' -f1)"
[ "$LOCAL_SHA" = "$PHONE_SHA" ] || { echo "$MODEL damaged in transfer ($PHONE_SHA)" >&2; exit 1; }
"${ADB[@]}" shell "run-as $PACKAGE sh -c 'rm -rf files/models/$ID && mkdir -p files/models/$ID && cp -r $STAGE/. files/models/$ID/ && echo pushed > files/models/$ID/.installed'"
"${ADB[@]}" shell rm -rf "$STAGE"
echo "installed $BUNDLE as files/models/$ID ($MODEL sha256 checked); restart the app"

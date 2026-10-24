#!/usr/bin/env bash
# Pushes a Kokoro bundle from ../lectrice/models/ to an installed spike build.
#   spike/push-model.sh <serial> fp32|int8 [sherpa1|sherpa2]
# The bundle goes to the app's external files folder, which adb can write without run-as; the app moves it into
# files/models/ the next time "Engine" loads it. The sherpa2 build gets the bundle without espeak-ng-data (2.0 has no
# espeak). Fallback for phones where adb can't write Android/data: /data/local/tmp + run-as (debuggable builds only).
set -euo pipefail

SERIAL="$1"
case "$2" in
  fp32) BUNDLE="kokoro-multi-lang-v1_0" ;;
  int8) BUNDLE="kokoro-int8-multi-lang-v1_0" ;;
  *) echo "usage: $0 <serial> fp32|int8 [sherpa1|sherpa2]" >&2; exit 2 ;;
esac
SET="${3:-sherpa1}"
PACKAGE="app.lectrice.spike.$SET"
SOURCE="$(cd "$(dirname "$0")/../.." && pwd)/lectrice/models/$BUNDLE"
# Only what the English Kokoro engine reads.
FILES=(voices.bin tokens.txt lexicon-us-en.txt lexicon-gb-en.txt)
[ "$2" = fp32 ] && FILES+=(model.onnx) || FILES+=(model.int8.onnx)
[ "$SET" = sherpa1 ] && FILES+=(espeak-ng-data)

ADB=(adb -s "$SERIAL")
[ -d "$SOURCE" ] || { echo "missing $SOURCE" >&2; exit 1; }
"${ADB[@]}" shell pm path "$PACKAGE" >/dev/null || { echo "$PACKAGE is not installed" >&2; exit 1; }

STAGE="/data/local/tmp/lectrice-$BUNDLE"
"${ADB[@]}" shell rm -rf "$STAGE"
"${ADB[@]}" shell mkdir -p "$STAGE"
for file in "${FILES[@]}"; do
  "${ADB[@]}" push "$SOURCE/$file" "$STAGE/"
done

TARGET="/sdcard/Android/data/$PACKAGE/files/models/$BUNDLE"
if "${ADB[@]}" shell "mkdir -p $TARGET && cp -r $STAGE/. $TARGET/"; then
  echo "pushed to $TARGET; press Engine in the app to move it in"
else
  echo "external files folder not writable, trying run-as"
  "${ADB[@]}" shell "run-as $PACKAGE mkdir -p files/models/$BUNDLE && run-as $PACKAGE cp -r $STAGE/. files/models/$BUNDLE/"
  echo "copied into files/models/$BUNDLE"
fi
"${ADB[@]}" shell rm -rf "$STAGE"

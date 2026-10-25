#!/usr/bin/env bash
# Runs the cable part of the M1 spike on one phone and arms the unplugged soak:
#   spike/run-phone.sh <serial> <large.pdf> <medium.pdf> [thread list, default 2,4,8] [out dir]
# Needs the APK built with the models inside:
#   ./gradlew :spike:assembleSherpa1Release -Plectrice.bundleModels=true
# Steps: install, first launch (model copy), engine fp32/int8, import both PDFs, bench (+ dumpsys meminfo samples),
# accelerators, engine at the fastest combination, play 2 min, pull the results, then arm "soak when unplugged".
# The PDFs go to the phone as large.pdf / medium.pdf, so no file name reaches the app or the results.
# SKIP_INSTALL=1 reuses the installed app. Everything measured here is "plugged in".
set -euo pipefail

SERIAL="$1"
LARGE="$2"
MEDIUM="$3"
THREADS="${4:-2,4,8}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${5:-$ROOT/spike/build/phone-runs/$SERIAL-$(date +%Y%m%d-%H%M%S)}"
PACKAGE="app.lectrice.spike.sherpa1"
APK="$ROOT/spike/build/outputs/apk/sherpa1/release/spike-sherpa1-release.apk"
EXTERNAL="/sdcard/Android/data/$PACKAGE/files"
STEP_TIMEOUT_S=7200
POLL_S=5
MEMINFO_EVERY_S=20

ADB=(adb -s "$SERIAL")
mkdir -p "$OUT"

# Sends one step to the app and waits for its "<name> done" line; stops the run if it failed.
step() {
  local name="$1"; shift
  local since
  since="$("${ADB[@]}" shell date +%s.000 | tr -d '\r')"
  "${ADB[@]}" shell am start -n "$PACKAGE/app.lectrice.spike.MainActivity" "$@" >/dev/null 2>&1
  local started=$SECONDS lines
  while true; do
    lines="$("${ADB[@]}" logcat -d -v time -T "$since" -s LectriceSpike:I | grep LectriceSpike || true)"
    if grep -qE ": ($name done|$name failed|busy: wait)" <<<"$lines"; then break; fi
    (( SECONDS - started > STEP_TIMEOUT_S )) && { echo "timeout waiting for $name" >&2; exit 1; }
    sleep "$POLL_S"
  done
  sed 's/^.*LectriceSpike([ 0-9]*): /  /' <<<"$lines"
  grep -qE ": $name done" <<<"$lines" || { echo "$name did not finish" >&2; exit 1; }
}

meminfo_loop() {
  while [ ! -e "$OUT/.stop-meminfo" ]; do
    echo "$(date +%s) $("${ADB[@]}" shell dumpsys meminfo "$PACKAGE" | grep -E 'TOTAL PSS:' | tr -s ' ')" >>"$OUT/meminfo.txt"
    sleep "$MEMINFO_EVERY_S"
  done
}

pull_results() {
  "${ADB[@]}" pull "$EXTERNAL/results" "$OUT/" >/dev/null
}

# The fastest CPU combination in the newest session's result file (named by its UTC start), as "<bundle> <threads>".
fastest() {
  python3 -I - "$OUT/results" <<'EOF'
import json, pathlib, sys
for path in sorted(pathlib.Path(sys.argv[1]).glob("*.json"), reverse=True):
    benches = [r for r in json.loads(path.read_text())["runs"] if r["kind"] == "bench" and r["provider"] == "cpu"]
    if benches:
        best = min(benches, key=lambda r: r["medianRtf"])
        print(best["bundle"].lower(), best["threads"])
        break
EOF
}

echo "== $SERIAL: $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'), results in $OUT"
if [ "${SKIP_INSTALL:-0}" != 1 ]; then
  "${ADB[@]}" install -r "$APK" >/dev/null
  echo "installed $(stat -c %s "$APK") bytes"
fi
"${ADB[@]}" shell am force-stop "$PACKAGE"
"${ADB[@]}" logcat -c
step start
step engine --es step engine --es bundle fp32 --ei threads 4
step engine --es step engine --es bundle int8 --ei threads 4

"${ADB[@]}" push "$LARGE" /sdcard/Download/large.pdf >/dev/null
"${ADB[@]}" push "$MEDIUM" /sdcard/Download/medium.pdf >/dev/null
"${ADB[@]}" shell "mkdir -p $EXTERNAL/import && cp /sdcard/Download/large.pdf /sdcard/Download/medium.pdf $EXTERNAL/import/"
step import --es step import --es file large.pdf
step import --es step import --es file medium.pdf

rm -f "$OUT/.stop-meminfo"
trap 'touch "$OUT/.stop-meminfo"' EXIT
meminfo_loop &
step bench --es step bench --es threadList "$THREADS"
step accelerators --es step accelerators
pull_results
read -r BUNDLE BEST_THREADS < <(fastest)
step engine --es step engine --es bundle "$BUNDLE" --ei threads "$BEST_THREADS"
step play --es step play
touch "$OUT/.stop-meminfo"
wait
pull_results
echo "peak PSS: $(awk '{print $4}' "$OUT/meminfo.txt" | sort -n | tail -1) KB"

"${ADB[@]}" shell am start -n "$PACKAGE/app.lectrice.spike.MainActivity" --es step soakWhenUnplugged \
  --es bundle "$BUNDLE" --ei threads "$BEST_THREADS" --es provider cpu >/dev/null 2>&1
echo "armed: soak $BUNDLE ${BEST_THREADS}t cpu (then the earned battery runs). Unplug the phone and leave the app open."
echo "afterwards: adb -s $SERIAL pull $EXTERNAL/results $OUT/"

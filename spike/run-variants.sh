#!/usr/bin/env bash
# Benches model bundles × core policies on one phone, one combination per step, in the order given:
#   spike/run-variants.sh <serial> <threads> <bundle:cores> [<bundle:cores> ...]
#   e.g. spike/run-variants.sh R58M23PXZYM 4 fp32:all fp32:big small:big small:all
# Bundles: fp32, int8, small, fast (push small/fast first with push-model.sh). Cores: all, big or a CPU list.
# Samples `dumpsys meminfo` every MEMINFO_EVERY_S into the out dir, stops if the app leaves the foreground, and pulls the
# results at the end. OUT=<dir> picks the out dir; SKIP_START=1 keeps the running app session.
set -euo pipefail

SERIAL="$1"
THREADS="$2"
shift 2
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/spike/build/phone-runs/variants-$(date +%Y%m%d-%H%M%S)}"
PACKAGE="app.lectrice.spike.sherpa1"
ACTIVITY="$PACKAGE/app.lectrice.spike.MainActivity"
STEP_TIMEOUT_S=3600
POLL_S=5
MEMINFO_EVERY_S=15

ADB=(adb -s "$SERIAL")
mkdir -p "$OUT"

in_front() {
  "${ADB[@]}" shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | grep -q "$PACKAGE"
}

# Sends one step and waits for "<name> done"; stops the run on failure, a lost phone or the app leaving the front.
step() {
  local name="$1"; shift
  local since started=$SECONDS lines
  # Whole seconds only, so start the window at the next second: the last step's "done" line can't fall inside it.
  since="$(( $("${ADB[@]}" shell date +%s | tr -d '\r') + 1 )).000"
  sleep 1.2
  # "start" runs by itself when the activity is created.
  if [ "$name" = start ]; then
    "${ADB[@]}" shell am start -n "$ACTIVITY" >/dev/null 2>&1
  else
    "${ADB[@]}" shell am start -n "$ACTIVITY" --es step "$name" "$@" >/dev/null 2>&1
  fi
  while true; do
    lines="$("${ADB[@]}" logcat -d -v time -T "$since" -s LectriceSpike:I | grep LectriceSpike || true)"
    if grep -qE ": ($name done|$name failed|busy: wait)" <<<"$lines"; then break; fi
    (( SECONDS - started > STEP_TIMEOUT_S )) && { echo "timeout waiting for $name" >&2; exit 1; }
    in_front || { echo "the app left the foreground during $name" >&2; exit 1; }
    sleep "$POLL_S"
  done
  sed 's/^.*LectriceSpike([ 0-9]*): /  /' <<<"$lines"
  grep -qE ": $name done" <<<"$lines" || { echo "$name did not finish" >&2; exit 1; }
}

meminfo_loop() {
  while [ ! -e "$OUT/.stop-meminfo" ]; do
    echo "$(date +%s) $("${ADB[@]}" shell dumpsys meminfo "$PACKAGE" | grep -E 'TOTAL PSS:' | tr -s ' ')" >>"$OUT/meminfo.txt" || true
    sleep "$MEMINFO_EVERY_S"
  done
}

if [ "${SKIP_START:-0}" != 1 ]; then
  "${ADB[@]}" shell am force-stop "$PACKAGE"
  step start
fi
rm -f "$OUT/.stop-meminfo"
meminfo_loop &
trap 'touch "$OUT/.stop-meminfo"' EXIT

for combination in "$@"; do
  bundle="${combination%%:*}"
  cores="${combination#*:}"
  echo "$(date +%s) begin $bundle $cores $THREADS" >>"$OUT/steps.txt"
  echo "== $bundle cores=$cores threads=$THREADS"
  step bench --es bundles "$bundle" --es cores "$cores" --es threadList "$THREADS"
  echo "$(date +%s) end $bundle $cores $THREADS" >>"$OUT/steps.txt"
done

"${ADB[@]}" pull "/sdcard/Android/data/$PACKAGE/files/results" "$OUT/" >/dev/null
echo "results in $OUT"

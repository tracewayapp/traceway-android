#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (fieldtest.yml): the emulator is up, the gateway listens in
# capture mode on the runner's 127.0.0.1:18190. Plays what FM_PLAN_ARGS, FM_EVIDENCE_RUNS and FM_STARTUP ask for and
# leaves everything under $FM_OUT for the artifact. A failed run is recorded, not fatal: the job uploads what it has.
set -uo pipefail
cd "${GITHUB_WORKSPACE:-.}"
H=fieldtest/harness/fm.mjs
OUT=${FM_OUT:-out}
COMMON=(--out "$OUT" --ledger "$OUT/ledger" --apks "$OUT/apks")

node "$H" device-setup --size 720x1560 --density 280 --out "$OUT" || exit 1
node "$H" install --apks "$OUT/apks" || exit 1
node "$H" warmup || exit 1
adb shell settings put system show_touches 0

status=0
if [ -n "${FM_PLAN_ARGS:-}" ]; then
  # shellcheck disable=SC2086
  node "$H" plan $FM_PLAN_ARGS "${COMMON[@]}" || status=1
fi
for e in ${FM_EVIDENCE_RUNS//,/ }; do
  # Touches are drawn on the real screen in evidence runs only, so the reviewer sees each tap next to the replay's.
  adb shell settings put system show_touches 1
  node "$H" run --scenario "${e%%:*}" --variant "${e#*:}" --evidence "${COMMON[@]}" || status=1
  adb shell settings put system show_touches 0
done
if [ -n "${FM_STARTUP:-}" ]; then
  node "$H" startup --variants "$FM_STARTUP" --repeat "${FM_STARTUP_REPEAT:-10}" "${COMMON[@]}" || status=1
fi
adb logcat -d -v epoch -b main,system,crash > "$OUT/device-logcat-tail.txt" 2>/dev/null || true
exit $status

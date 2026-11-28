#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (minified.yml): installs the minified check app, starts it and
# waits until the stand-in collector on the runner (127.0.0.1:18199, through adb reverse) has OTLP logs, traces and
# metrics from it. Leaves logcat and the collector's ledger in OUT for the artifact.
set -uo pipefail
APK=$1
OUT=$2
PKG=com.tracewayapp.traceway.minified
mkdir -p "$OUT"
adb reverse tcp:18199 tcp:18199
adb install -r "$APK" || exit 1
adb logcat -c || true
adb shell am start -W -n "$PKG/.MainActivity" || exit 1
python3 scripts/ci/otlp_collector.py wait --ledger "$OUT/collector.jsonl" --timeout 150 \
  --paths /api/otel/v1/logs,/api/otel/v1/traces,/api/otel/v1/metrics
status=$?
adb logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null || true
if grep -q "Error calling getEntities" "$OUT/logcat.txt"; then
  echo "::error::OpenTelemetry's reflection failed in the minified app (Error calling getEntities on Resource)"
  grep "Error calling getEntities" "$OUT/logcat.txt" | head -3
  status=1
fi
grep -E " (D|I|W|E)/Traceway" "$OUT/logcat.txt" | tail -40 || true
exit $status

#!/usr/bin/env bash
# E2E-1.5 (design §7): drive the tia skill's wrapper end-to-end, agent-UI-bypassed.
# CI sets TIA_JAR to the built fat-jar; this asserts the skill produces a real report and
# fails loudly (non-zero + actionable message) when the CLI can't be resolved.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
RUN="$(dirname "$here")/scripts/run-tia.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail() { echo "FAIL: $*"; exit 1; }

cat > "$tmp/tw.json" <<'JSON'
{"tests":[{"uniformPath":"T#m","result":"PASSED","paths":[{"path":"com/x","files":[{"fileName":"A.java","coveredLines":"1-3,5"}]}]}]}
JSON

# 1) report via the skill wrapper (TIA_JAR provided by caller/CI)
bash "$RUN" report --testwise "$tmp/tw.json" --commit deadbeefcafe --out "$tmp/r.html" \
  --sut-name smoke-svc --scenarios - --flaky - --prod-files -
grep -q "TIA report · smoke-svc blackbox" "$tmp/r.html" || fail "report title/SUT"
grep -q "const D = {" "$tmp/r.html" || fail "data island missing"
grep -q "__DATA__" "$tmp/r.html" && fail "placeholder not replaced"
echo "PASS: skill report end-to-end"

# 2) precheck failure: no `tia` on PATH, no TIA_JAR, no local build → non-zero + message
javadir="$(dirname "$(command -v java)")"
if ( cd "$tmp" && env -u TIA_JAR PATH="$javadir:/usr/bin:/bin" \
       bash "$RUN" report --testwise "$tmp/tw.json" --commit x --out "$tmp/x.html" 2>"$tmp/err" ); then
  fail "expected non-zero exit when CLI unresolved"
fi
grep -q "tia CLI not found" "$tmp/err" || { cat "$tmp/err"; fail "missing actionable message"; }
echo "PASS: skill precheck failure is loud"

echo "ALL PASS"

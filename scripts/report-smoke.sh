#!/usr/bin/env bash
# 리포트 스모크: index → impact --format json → report --impact 를 실제 CLI로 돌려
# README "HTML 리포트 › CI에 적용"의 명령이 그대로 동작함을 확인한다 [RPT-REQ-002/003].
# 사용: TIA_JAR=tia-cli/build/libs/tia.jar bash scripts/report-smoke.sh [out-dir]
set -euo pipefail
JAR="${TIA_JAR:?TIA_JAR(=tia.jar 경로)를 지정하세요}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/build/report-smoke}"
fail() { echo "FAIL: $*" >&2; exit 1; }
tia() { java -jar "$JAR" "$@"; }

rm -rf "$OUT"; mkdir -p "$OUT"
cp "$ROOT/e2e/src/test/resources/spec-testwise.json" "$OUT/testwise.json"
cat > "$OUT/change.diff" <<'DIFF'
diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
--- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
+++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
@@ -8,1 +8,1 @@
-old
+new
DIFF

# 1) 베이스라인 인덱싱 (main 푸시에서 하는 일)
tia index --report "$OUT/testwise.json" --repo fixture --commit C0 --db "$OUT/tia.db"

# 2) diff 가 있는 실행 (PR·커밋): impact JSON → 변경 영향 페이지가 있는 리포트
tia impact --db "$OUT/tia.db" --commit C0 --diff-file "$OUT/change.diff" --format json > "$OUT/impact.json"
tia report --testwise "$OUT/testwise.json" --commit C0 --sut-name fixture \
  --impact "$OUT/impact.json" --out "$OUT/report.html"
grep -q '"changedFiles"' "$OUT/impact.json" || fail "impact.json 에 changedFiles 없음"
grep -q '"kind" : "modified"' "$OUT/impact.json" || fail "변경 파일이 modified 로 분류되지 않음"
grep -q '"impact":{' "$OUT/report.html" || fail "리포트 모델에 impact 가 실리지 않음"
grep -q 'PricingService.java' "$OUT/report.html" || fail "변경 파일이 리포트에 없음"

# 3) diff 가 없는 실행 (베이스라인만): 변경 영향 페이지 없음
tia report --testwise "$OUT/testwise.json" --commit C0 --sut-name fixture --out "$OUT/report-baseline.html"
grep -q '"impact":null' "$OUT/report-baseline.html" || fail "diff 없는 실행인데 impact 가 null 이 아님"

# 4) 자기완결형: 외부 URL 없음
if grep -qE 'https?://' "$OUT/report.html"; then fail "리포트에 외부 URL 이 있음"; fi

echo "PASS: report smoke → $OUT/report.html"

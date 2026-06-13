#!/usr/bin/env bash
set -euo pipefail
# 검증된 전체 E2E: fixture-app + teamscale 에이전트(TESTWISE) 수집 → convert -t → testwise JSON
#   → tia index → tia impact. 실제 에이전트 산출물 형식이 TestwiseReportParser와 일치함을 확인함(2026-06-13).
#
# 수집 신호 방식: 프로덕션 경로는 RestAssured 스위트 + TeamscaleTestwiseExtension(테스트별 /test/start·/test/end).
# 단, 일부 샌드박스에선 gradle 테스트 워커(포크 JVM)의 아웃바운드가 막혀 외부 에이전트(8123)에 못 닿는다
# (gradle 데몬·일반 JVM·curl·단위테스트는 정상 — 확장 코드 문제 아님). 그런 환경 호환을 위해 본 스크립트는
# 확장과 '동일한 HTTP 신호'를 curl로 직접 보낸다. 외부 레포 적용 시엔 런북(phase0-external-smoke.md)의
# RestAssured+확장 경로를 그대로 쓰면 된다.
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"   # 코드는 Java 17 바이트코드 — 17 강제(비-macOS는 JDK17 경로로 대체)
AGENT_JAR="$(find tools/teamscale -name 'teamscale-jacoco-agent.jar' -path '*/lib/*' -print -quit)"
[ -n "$AGENT_JAR" ] || { echo "에이전트 없음 — scripts/download-agent.sh 먼저"; exit 1; }
CONVERT="$(cd "$(dirname "$AGENT_JAR")/../bin" && pwd)/convert"
COMMIT="$(git rev-parse HEAD)"
SRC="fixture-app/src/main/java/io/tia/fixture/PricingService.java"
CLASSES="$PWD/fixture-app/build/classes/java/main"
OUTDIR="$PWD/poc-out/coverage"
rm -rf poc-out; mkdir -p "$OUTDIR"

./gradlew :fixture-app:bootJar :fixture-app:classes :tia-cli:installDist

# out= 는 .exec + test-execution.json 을 산출 (testwise JSON 아님 — convert로 변환). '*' glob 방지 위해 인용.
"$JAVA_HOME/bin/java" "-javaagent:$AGENT_JAR=mode=TESTWISE,includes=*io.tia.fixture.*,http-server-port=8123,class-dir=$CLASSES,out=$OUTDIR" \
  -jar fixture-app/build/libs/fixture-app-0.1.0.jar --server.port=8080 > poc-out/app.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true; [ -f "$SRC.bak" ] && mv "$SRC.bak" "$SRC" || true' EXIT
for i in $(seq 1 40); do curl -sf localhost:8080/greeting/x >/dev/null 2>&1 && break || sleep 1; done

# 응답 내부값 검증(상태코드뿐 아니라 본문값까지) — ApiSmokeTest와 동일 기대치.
g="$(curl -s localhost:8080/greeting/Alice)"; p="$(curl -s localhost:8080/price/ABC)"
[ "$g" = "hello alice" ] || { echo "❌ greeting 값 오류: '$g' (기대 'hello alice')"; exit 1; }
[ "$p" = "300" ]         || { echo "❌ price 값 오류: '$p' (기대 '300')"; exit 1; }
echo "✅ 엔드포인트 응답값 검증: greeting='hello alice', price=300"

# per-test 수집: 확장과 동일한 신호(/test/start → 엔드포인트 호출 → /test/end). 직렬.
signal_test() {  # $1=testId(raw)  $2=endpoint
  local enc="${1//\//%2F}"   # 슬래시 → %2F: teamscale {testId}는 단일 세그먼트 (raw=500, %2F=204)
  curl -s -m 8 -o /dev/null -X POST "localhost:8123/test/start/$enc"
  curl -s -m 8 -o /dev/null "localhost:8080/$2"
  curl -s -m 8 -o /dev/null -X POST "localhost:8123/test/end/$enc" \
    -H 'Content-Type: application/json' -d "{\"uniformPath\":\"$1\",\"durationMillis\":5,\"result\":\"PASSED\"}"
}
signal_test "io/tia/fixture/ApiSmokeTest/testGreeting" "greeting/Alice"
signal_test "io/tia/fixture/ApiSmokeTest/testPrice"    "price/ABC"
signal_test "io/tia/fixture/ApiSmokeTest/testFlaky"    "flaky"
kill $APP_PID 2>/dev/null || true; sleep 1   # .exec/test-execution flush 보장

# .exec + test-execution.json → testwise JSON (-t). 출력은 --split-after 때문에 '-N' 접미사가 붙음.
"$CONVERT" --class-dir "$CLASSES" --in "$OUTDIR" --testwise-coverage -o "$PWD/poc-out/testwise.json" 2>/dev/null
REPORT="$(find poc-out -name 'testwise*.json' -print -quit)"
echo "report=$REPORT  (tests: $(grep -o uniformPath "$REPORT" | wc -l | tr -d ' '))"

CLI="tia-cli/build/install/tia-cli/bin/tia-cli"
"$CLI" index --report "$REPORT" --repo fixture --commit "$COMMIT" --db poc-out/tia.db

# 실제 git diff (PricingService 한 줄) → 라인 번호 정확, old-side = HEAD(=인덱싱 커밋)이라 라인 공간 정렬.
perl -i.bak -pe 's/\* 100;/\* 200;/' "$SRC"
git diff --unified=0 -- "$SRC" > poc-out/sample.diff
mv "$SRC.bak" "$SRC"

echo "===== tia impact (PricingService 변경 → testPrice 선별 기대) ====="
OUT="$("$CLI" impact --db poc-out/tia.db --commit "$COMMIT" --diff-file poc-out/sample.diff)"
echo "$OUT"
# 프로그램적 검증: testPrice가 DETERMINISTIC으로 선별되고 testGreeting은 제외되어야 함(없으면 exit 1).
if echo "$OUT" | grep -qE "^DETERMINISTIC[[:space:]]+io/tia/fixture/ApiSmokeTest/testPrice$" \
   && ! echo "$OUT" | grep -q "testGreeting"; then
  echo "✅ E2E PASS: testPrice DETERMINISTIC 선별, testGreeting 제외"
else
  echo "❌ E2E FAIL: 기대한 선별 결과가 아님"; exit 1
fi

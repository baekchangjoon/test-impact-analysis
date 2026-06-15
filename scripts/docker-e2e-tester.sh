#!/usr/bin/env bash
# tester 컨테이너 엔트리포인트: sut(앱+에이전트)를 네트워크로 호출해 수집 → convert → index → impact → assert.
set -euo pipefail
cd /work
GW="./gradlew --no-daemon -Dorg.gradle.java.home=/opt/java/openjdk"   # 컨테이너 JDK로 override (호스트 gradle.properties 무시)

echo "=== SUT 대기 (sut:8080 앱 + sut:8123 에이전트) ==="
for i in $(seq 1 90); do
  (echo > /dev/tcp/sut/8080) 2>/dev/null && (echo > /dev/tcp/sut/8123) 2>/dev/null && break
  sleep 1
done
(echo > /dev/tcp/sut/8123) 2>/dev/null || { echo "❌ sut:8123 도달 불가"; exit 1; }
echo "sut 도달 가능"

echo "=== 1) RestAssured 수집 (확장 → sut:8123, RestAssured → sut:8080) — testGreeting·testPrice 엄격 통과 필수 ==="
$GW :fixture-app:test --rerun-tasks \
  --tests io.tia.fixture.ApiSmokeTest.testGreeting --tests io.tia.fixture.ApiSmokeTest.testPrice \
  -Dfixture.baseUrl=http://sut:8080 -Dtia.agent.url=http://sut:8123
echo "✅ RestAssured 통과: 확장이 sut:8123 도달 + 본문값(hello alice / 300) 검증 통과 — 컨테이너 간 동작"

echo "=== 2) convert: sut가 /cov에 쓴 .exec+test-execution.json → testwise JSON ==="
$GW :tia-cli:installDist
CONVERT="$(find tools/teamscale -name convert -path '*/bin/*' -print -quit)"
mkdir -p poc-out
JAVA_HOME=/opt/java/openjdk "$CONVERT" --class-dir /work/fixture-app/build/classes/java/main \
  --in /cov --testwise-coverage -o /work/poc-out/tw.json
REPORT="$(find poc-out -name 'tw*.json' -print -quit)"
echo "report=$REPORT (tests: $(grep -o uniformPath "$REPORT" | wc -l | tr -d ' '))"

echo "=== 3) tia index + impact (실제 수집 기반) ==="
CLI=tia-cli/build/install/tia/bin/tia   # D0: launcher 개명(tia-cli→tia)
JAVA_HOME=/opt/java/openjdk "$CLI" index --report "$REPORT" --repo fixture --commit DOCKER --db poc-out/tia.db
cat > poc-out/p.diff <<'EOF'
diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
--- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
+++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
@@ -9,1 +9,1 @@
-        return key.length() * 100;
+        return key.length() * 200;
EOF
OUT="$(JAVA_HOME=/opt/java/openjdk "$CLI" impact --db poc-out/tia.db --commit DOCKER --diff-file poc-out/p.diff)"
echo "$OUT"
if echo "$OUT" | grep -qE "^DETERMINISTIC[[:space:]]+io/tia/fixture/ApiSmokeTest/testPrice$" \
   && ! echo "$OUT" | grep -q "testGreeting"; then
  echo "✅ DOCKER E2E PASS: testPrice DETERMINISTIC 선별, testGreeting 제외"
else
  echo "❌ DOCKER E2E FAIL"; exit 1
fi

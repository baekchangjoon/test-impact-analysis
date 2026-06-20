#!/usr/bin/env bash
# tester 컨테이너 엔트리포인트: sut(앱+pjacoco 에이전트)를 네트워크로 호출해 수집 → convert → index → impact → assert.
set -euo pipefail
cd /work
GW="./gradlew --no-daemon -Dorg.gradle.java.home=/opt/java/openjdk"   # 컨테이너 JDK로 override (호스트 gradle.properties 무시)

echo "=== SUT 대기 (sut:8080 앱 + sut:6310 pjacoco 에이전트) ==="
for i in $(seq 1 90); do
  (echo > /dev/tcp/sut/8080) 2>/dev/null && (echo > /dev/tcp/sut/6310) 2>/dev/null && break
  sleep 1
done
(echo > /dev/tcp/sut/6310) 2>/dev/null || { echo "❌ sut:6310 도달 불가"; exit 1; }
echo "sut 도달 가능"

echo "=== 1) RestAssured 수집 (pjacoco 확장 → sut:6310, RestAssured → sut:8080) — parallel-tester 엄격 통과 필수 ==="
$GW :e2e:parallelTesterTest --rerun-tasks \
  -Dfixture.baseUrl=http://sut:8080 -Dpjacoco.control-url=http://sut:6310
echo "✅ RestAssured 통과: pjacoco 확장이 sut:6310 도달 + 본문값 검증 통과 — 컨테이너 간 동작"

echo "=== 2) convert: sut가 /cov에 쓴 .exec 파일들 → testwise JSON ==="
$GW :tia-cli:installDist
CLI=tia-cli/build/install/tia/bin/tia
CLASSES=/work/fixture-app/build/classes/java/main
mkdir -p /work/poc-out
JAVA_HOME=/opt/java/openjdk "$CLI" convert --allow-incomplete \
  --exec-dir /cov \
  --classes "$CLASSES" \
  --out /work/poc-out/testwise.json
REPORT=/work/poc-out/testwise.json
echo "report=$REPORT (tests: $(grep -o '"testId"' "$REPORT" | wc -l | tr -d ' '))"

echo "=== 3) tia index + impact (실제 수집 기반) ==="
JAVA_HOME=/opt/java/openjdk "$CLI" index --report "$REPORT" --repo fixture --commit DOCKER --db /work/poc-out/tia.db
cat > /work/poc-out/p.diff <<'EOF'
diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
--- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
+++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
@@ -9,1 +9,1 @@
-        return key.length() * 100;
+        return key.length() * 200;
EOF
OUT="$(JAVA_HOME=/opt/java/openjdk "$CLI" impact --db /work/poc-out/tia.db --commit DOCKER --diff-file /work/poc-out/p.diff)"
echo "$OUT"
if echo "$OUT" | grep -qE "DETERMINISTIC[[:space:]].*PriceTesterIT#" \
   && ! echo "$OUT" | grep -q "GreetingTesterIT"; then
  echo "✅ DOCKER E2E PASS: PriceTesterIT DETERMINISTIC 선별, GreetingTesterIT 제외"
else
  echo "❌ DOCKER E2E FAIL"; exit 1
fi

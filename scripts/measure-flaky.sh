#!/usr/bin/env bash
set -euo pipefail
# N회 스위트 실행 → 각 run-result JSON → tia flaky. 앱+에이전트를 자체 기동.
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"   # 17 강제 (비-macOS는 JDK17 경로로 대체)
N="${1:-10}"
AGENT_JAR="$(find tools/teamscale -name 'teamscale-jacoco-agent.jar' -path '*/lib/*' -print -quit)"
[ -n "$AGENT_JAR" ] || { echo "에이전트 없음 — scripts/download-agent.sh 먼저"; exit 1; }
CLASSES="$PWD/fixture-app/build/classes/java/main"
mkdir -p poc-out/flaky poc-out/flaky-cov

./gradlew :fixture-app:bootJar :fixture-app:classes :tia-cli:installDist
"$JAVA_HOME/bin/java" "-javaagent:$AGENT_JAR=mode=TESTWISE,includes=*io.tia.fixture.*,http-server-port=8123,class-dir=$CLASSES,out=$PWD/poc-out/flaky-cov" \
  -jar fixture-app/build/libs/fixture-app-0.1.0.jar --server.port=8080 > poc-out/flaky-app.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT
for i in $(seq 1 40); do curl -sf localhost:8080/greeting/x >/dev/null 2>&1 && break || sleep 1; done

RUNS=""
for i in $(seq 1 "$N"); do
  OUT="poc-out/flaky/run-$i.json"
  ./gradlew :fixture-app:test --tests io.tia.fixture.ApiSmokeTest --rerun-tasks \
    -Dfixture.baseUrl=http://localhost:8080 -Dtia.agent.url=http://localhost:8123 \
    -Dtia.run.out="$PWD/$OUT" || true
  RUNS="${RUNS:+$RUNS,}$OUT"
done
CLI="tia-cli/build/install/tia/bin/tia"
echo "===== tia flaky (N=$N) ====="
"$CLI" flaky --runs "$RUNS"

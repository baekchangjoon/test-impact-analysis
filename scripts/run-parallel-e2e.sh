#!/usr/bin/env bash
set -euo pipefail
# 단일 pjacoco SUT(fixture-app)에 대해 테스터를 직렬/forks/in-JVM 3모드로 구동, 각 모드 격리 destfile로 수집,
# tia convert로 testwise 생성, 마지막에 수용 비교 테스트(ParallelCollectionE2E) green 확인.

# Java 17 강제 — 코드는 Java 17 바이트코드 (jenv 기본값이 11일 수 있음)
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
JAVA_BIN="$JAVA_HOME/bin/java"

AGENT_JAR="$(bash scripts/setup-pjacoco.sh | sed -n 's/^PJACOCO_AGENT_JAR=//p')"
[ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 미해소" >&2; exit 1; }

CLASSES="$PWD/fixture-app/build/classes/java/main"
OUT="$PWD/build/parallel-e2e"; rm -rf "$OUT"; mkdir -p "$OUT"
CTRL=6310; PORT=8080

./gradlew --no-daemon :fixture-app:bootJar :fixture-app:classes :tia-cli:installDist
CLI="$PWD/tia-cli/build/install/tia/bin/tia"
JAR="$(find fixture-app/build/libs -name 'fixture-app*.jar' -print -quit)"
[ -n "$JAR" ] || { echo "❌ fixture-app bootJar 없음" >&2; exit 1; }

# SUT를 기동하고 pid를 파일에 저장 — 서브셸 간 공유를 위해 파일 사용
SUT_PID_FILE="$OUT/sut.pid"

kill_sut() {
  if [ -f "$SUT_PID_FILE" ]; then
    local pid
    pid="$(cat "$SUT_PID_FILE")"
    kill "$pid" 2>/dev/null || true
    rm -f "$SUT_PID_FILE"
    # 포트 해제 대기
    for i in $(seq 1 10); do
      curl -sf "localhost:$PORT/price/F" >/dev/null 2>&1 || break
      sleep 1
    done
  fi
}

trap 'kill_sut' EXIT

run_mode() {  # $1 = serial|forks|injvm
  local mode="$1"
  local cov="$OUT/cov-$mode"
  rm -rf "$cov"; mkdir -p "$cov"           # 실행별 격리 디렉터리 [REQ-009]

  # 스케줄러 노이즈 끔(@Profile '!e2e' 비활성 — Task 6에서 NoiseScheduler를 프로필 게이트)
  "$JAVA_BIN" \
      "-javaagent:$AGENT_JAR=destfile=$cov,port=$CTRL,aggregate=false,includes=io.tia.fixture.*" \
      -Dspring.profiles.active=e2e \
      -jar "$JAR" --server.port=$PORT > "$OUT/sut-$mode.log" 2>&1 &
  local pid=$!
  echo "$pid" > "$SUT_PID_FILE"

  # SUT 헬스체크 — 최대 40초
  echo "  [${mode}] SUT 기동 대기 (PID=$pid)..." >&2
  local ready=0
  for i in $(seq 1 40); do
    if curl -sf "localhost:$PORT/price/F" >/dev/null 2>&1; then
      ready=1; break
    fi
    sleep 1
  done
  if [ $ready -eq 0 ]; then
    echo "❌ [${mode}] SUT가 40초 내 기동 실패 — 로그: $OUT/sut-$mode.log" >&2
    kill "$pid" 2>/dev/null || true
    rm -f "$SUT_PID_FILE"
    return 1
  fi
  echo "  [${mode}] SUT 준비 완료" >&2

  local t0
  t0=$(python3 -c "import time; print(int(time.time()*1000))")
  # --rerun 으로 Gradle 캐시 무효화 — 모드별로 SUT가 다르므로 항상 재실행 필요
  ./gradlew --no-daemon :e2e:parallelTesterTest --rerun -Pparallel.mode="$mode" \
      "-Dfixture.baseUrl=http://localhost:$PORT" \
      "-Dpjacoco.control-url=http://127.0.0.1:$CTRL" >&2
  local t1
  t1=$(python3 -c "import time; print(int(time.time()*1000))")

  kill "$pid" 2>/dev/null || true
  rm -f "$SUT_PID_FILE"
  sleep 1   # .exec flush 보장 + 포트 해제

  "$CLI" convert --exec-dir "$cov" --classes "$CLASSES" --out "$OUT/testwise_$mode.json" >&2

  # 타이밍만 stdout으로 출력 (캡처 대상)
  echo "$mode:$((t1 - t0))"
}

echo "=== parallel-e2e: 3모드 수집 시작 ===" >&2
declare -a T
T+=("$(run_mode serial)")
T+=("$(run_mode forks)")
T+=("$(run_mode injvm)")

# timings.json [REQ-004]
{
  echo "{"
  first=1
  for kv in "${T[@]}"; do
    k="${kv%%:*}"; v="${kv##*:}"
    [ $first -eq 1 ] && first=0 || echo ","
    printf '  "%s": %s' "$k" "$v"
  done
  echo
  echo "}"
} > "$OUT/timings.json"

echo "=== timings.json ==="
cat "$OUT/timings.json"

# 수용 비교 green 확인 [REQ-001~004/009]
echo "=== ParallelCollectionE2E 수용 비교 실행 ==="
./gradlew --no-daemon :e2e:parallelCollectionE2ETest \
    "-Dtia.parallel.artifacts=$OUT"

echo "✅ parallel-e2e PASS"

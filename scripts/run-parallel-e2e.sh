#!/usr/bin/env bash
set -euo pipefail
# 단일 pjacoco SUT(fixture-app)에 대해 테스터를 직렬/forks/in-JVM 3모드로 구동, 각 모드 격리 destfile로 수집,
# tia convert로 testwise 생성, 마지막에 수용 비교 테스트(ParallelCollectionE2E) green 확인.

# Java 17 강제 — 코드는 Java 17 바이트코드 (macOS/Linux 이식 가능)
# 우선순위:
#   1) 이미 유효한 JAVA_HOME이 설정되어 있고 Java 17+ 이면 그대로 사용
#   2) macOS: /usr/libexec/java_home -v 17 로 Java 17 선택
#   3) 그 외: PATH의 java에서 JAVA_HOME 추론
_need_java_home=1
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  _cur_ver="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')"
  if [ "${_cur_ver:-0}" -ge 17 ] 2>/dev/null; then
    _need_java_home=0
  fi
fi
if [ "$_need_java_home" -eq 1 ]; then
  if [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  else
    _java_bin="$(command -v java 2>/dev/null)"
    [ -n "$_java_bin" ] || { echo "❌ java가 PATH에 없습니다" >&2; exit 1; }
    # bin/java → bin → JAVA_HOME
    JAVA_HOME="$(cd "$(dirname "$_java_bin")/.." && pwd)"
  fi
fi
export JAVA_HOME
JAVA_BIN="$JAVA_HOME/bin/java"
# Java 버전 확인 — 17 이상이어야 함
_java_ver="$("$JAVA_BIN" -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')"
if [ "${_java_ver:-0}" -lt 17 ] 2>/dev/null; then
  echo "❌ Java 17 이상 필요 (현재: $("$JAVA_BIN" -version 2>&1 | head -1))" >&2
  exit 1
fi

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
    local pid waited
    pid="$(cat "$SUT_PID_FILE")"
    kill "$pid" 2>/dev/null || true
    rm -f "$SUT_PID_FILE"
    # 프로세스가 실제로 종료될 때까지 최대 10초 폴링
    waited=0
    while kill -0 "$pid" 2>/dev/null && [ $waited -lt 10 ]; do
      sleep 1
      waited=$(( waited + 1 ))
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
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
  t0=$(( $(date +%s) * 1000 ))
  # --rerun 으로 Gradle 캐시 무효화 — 모드별로 SUT가 다르므로 항상 재실행 필요
  ./gradlew --no-daemon :e2e:parallelTesterTest --rerun -Pparallel.mode="$mode" \
      "-Dfixture.baseUrl=http://localhost:$PORT" \
      "-Dpjacoco.control-url=http://127.0.0.1:$CTRL" >&2
  local t1
  t1=$(( $(date +%s) * 1000 ))

  # SUT 동시성 프로브 — 테스터 종료 후, SUT 종료 전 [REQ-010]
  local max_concurrent
  max_concurrent="$(curl -sf "http://localhost:$PORT/__concurrency__/max" 2>/dev/null || { >&2 echo "⚠️ concurrency probe 실패 (SUT 미응답?) — 0으로 기록"; echo "0"; })"

  kill "$pid" 2>/dev/null || true
  rm -f "$SUT_PID_FILE"
  # 프로세스가 실제로 종료될 때까지 최대 10초 대기 (JaCoCo .exec 플러시 포함)
  local waited=0
  while kill -0 "$pid" 2>/dev/null && [ $waited -lt 10 ]; do
    sleep 1
    waited=$(( waited + 1 ))
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null || true
  fi
  sleep 1   # 소켓 해제 + .exec 최종 플러시 안정화

  "$CLI" convert --allow-incomplete --exec-dir "$cov" --classes "$CLASSES" --out "$OUT/testwise_$mode.json" >&2

  # stdout: timing + concurrency (캡처 대상)
  echo "$mode:$((t1 - t0)):$max_concurrent"
}

echo "=== parallel-e2e: 3모드 수집 시작 ===" >&2

# 각 모드별 출력을 직접 캡처 (bash 3.x 호환 — declare -A 불가)
KV_serial="$(run_mode serial)"
KV_forks="$(run_mode forks)"
KV_injvm="$(run_mode injvm)"

# "mode:ms:maxconcurrent" 파싱 (IFS=: + 위치 분리)
_parse_ms()  { local kv="$1"; local rest="${kv#*:}"; echo "${rest%%:*}"; }
_parse_mc()  { local kv="$1"; echo "${kv##*:}"; }

T_serial_ms="$(_parse_ms "$KV_serial")"
T_forks_ms="$(_parse_ms "$KV_forks")"
T_injvm_ms="$(_parse_ms "$KV_injvm")"
T_serial_mc="$(_parse_mc "$KV_serial")"
T_forks_mc="$(_parse_mc "$KV_forks")"
T_injvm_mc="$(_parse_mc "$KV_injvm")"

# timings.json [REQ-004]
{
  printf '{\n'
  printf '  "serial": %s,\n' "$T_serial_ms"
  printf '  "forks": %s,\n'  "$T_forks_ms"
  printf '  "injvm": %s\n'   "$T_injvm_ms"
  printf '}\n'
} > "$OUT/timings.json"

# concurrency.json [REQ-010]
{
  printf '{\n'
  printf '  "serial": %s,\n' "$T_serial_mc"
  printf '  "forks": %s,\n'  "$T_forks_mc"
  printf '  "injvm": %s\n'   "$T_injvm_mc"
  printf '}\n'
} > "$OUT/concurrency.json"

echo "=== timings.json ==="
cat "$OUT/timings.json"
echo "=== concurrency.json ==="
cat "$OUT/concurrency.json"

# 수용 비교 green 확인 [REQ-001~004/009]
echo "=== ParallelCollectionE2E 수용 비교 실행 ==="
./gradlew --no-daemon :e2e:parallelCollectionE2ETest \
    "-Dtia.parallel.artifacts=$OUT"

echo "✅ parallel-e2e PASS"

#!/usr/bin/env bash
set -euo pipefail
# tia demo 전용 경량 수집 스크립트 — serial 단일 모드 실수집 + convert만 수행한다(design spec §4).
# 기존 run-inprocess-e2e.sh는 3모드(serial/forks/injvm) 풀빌드 CI 회귀 스크립트(분 단위·installDist
# 재빌드 5회)라 온보딩용으로 부적합하다. 이 스크립트는 그 serial 경로만 추출하고, installDist는
# 재빌드하지 않는다 — demo는 이미 설치된 tia 바이너리로 실행 중이므로 실행 중 자기 교체를 피한다
# (이미 설치돼 있는 tia-cli/build/install/tia/bin/tia 를 convert에 그대로 재사용한다).
#
# 계약: $1(우선) 또는 DEMO_OUT 환경변수로 출력 디렉터리를 받아 <out>/testwise_serial.json 을 쓴다.
# (스텁도 동일 계약 — E2E는 이 스크립트 자체를 스텁으로 대체해 3~6단계만 실구동으로 검증한다.)

OUT="${1:-${DEMO_OUT:-}}"
[ -n "$OUT" ] || { echo "❌ 출력 디렉터리가 필요합니다: demo-collect.sh <out-dir> (또는 DEMO_OUT 환경변수)" >&2; exit 1; }
mkdir -p "$OUT"

# JAVA_HOME 이식성: 이미 설정·유효하면 사용, 아니면 macOS java_home, 아니면 PATH java.
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q 'version "17'; then
  if [ -x /usr/libexec/java_home ]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  elif command -v java >/dev/null 2>&1; then
    export JAVA_HOME="$(dirname "$(dirname "$(command -v java)")")"
  else
    echo "❌ Java 17 미발견 (JAVA_HOME/java_home/PATH 모두 실패)" >&2; exit 1
  fi
fi
export JAVA_HOME
echo "Using JAVA_HOME=$JAVA_HOME" >&2

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENT_JAR="$REPO_ROOT/tools/pjacoco/jacocoagent-parallel.jar"
[ -f "$AGENT_JAR" ] || {
  echo "❌ pjacoco 에이전트 jar 없음: $AGENT_JAR (scripts/setup-pjacoco.sh 먼저 실행하세요)" >&2
  exit 1
}

CLASSES="$REPO_ROOT/fixture-app/build/classes/java/main"
CLI="$REPO_ROOT/tia-cli/build/install/tia/bin/tia"
[ -x "$CLI" ] || {
  echo "❌ tia CLI가 설치돼 있지 않습니다: $CLI (./gradlew :tia-cli:installDist 먼저 실행하세요)" >&2
  exit 1
}

COV="$OUT/cov-serial"
rm -rf "$COV"; mkdir -p "$COV"

echo "=== fixture-app:classes (installDist 재빌드 없음) ===" >&2
"$REPO_ROOT/gradlew" --no-daemon :fixture-app:classes >&2

echo "=== serial 단일 모드 실수집 ===" >&2
"$REPO_ROOT/gradlew" --no-daemon --no-build-cache :e2e:inProcessTesterTest \
    -Pinprocess.mode=serial \
    -Pinprocess.agentJar="$AGENT_JAR" \
    -Pinprocess.covDir="$COV" >&2

exec_count=$(find "$COV" -name '*.exec' 2>/dev/null | wc -l | tr -d ' ')
[ "$exec_count" -gt 0 ] || { echo "❌ .exec 파일 0 — 에이전트 미부착 의심" >&2; exit 1; }

echo "--- convert ($exec_count exec files) ---" >&2
"$CLI" convert --allow-incomplete --exec-dir "$COV" --classes "$CLASSES" --out "$OUT/testwise_serial.json" >&2

echo "✅ demo-collect PASS -> $OUT/testwise_serial.json" >&2

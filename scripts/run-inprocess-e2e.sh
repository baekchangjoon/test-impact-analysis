#!/usr/bin/env bash
set -euo pipefail
# in-JVM per-test 수집을 직렬/forks/in-JVM 3모드로 구동(테스터 JVM에 pjacoco 에이전트 -javaagent),
# 모드별 격리 디렉터리로 .exec 수집 → tia convert → 수용 비교(InProcessCollectionE2E) green.
# JAVA_HOME 이식성: 이미 설정·유효하면 사용, 아니면 macOS java_home, 아니면 PATH java.
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q 'version "17'; then
  if [ -x /usr/libexec/java_home ]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  elif command -v java >/dev/null 2>&1; then
    # Linux/CI: derive JAVA_HOME from java on PATH (two levels up from the symlink)
    export JAVA_HOME="$(dirname "$(dirname "$(command -v java)")")"
  else
    echo "❌ Java 17 미발견 (JAVA_HOME/java_home/PATH 모두 실패)" >&2; exit 1
  fi
fi
export JAVA_HOME
echo "Using JAVA_HOME=$JAVA_HOME" >&2
"${JAVA_HOME}/bin/java" -version >&2

AGENT_JAR="$(bash "$(dirname "$0")/setup-pjacoco.sh" | sed -n 's/^PJACOCO_AGENT_JAR=//p')"
[ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 미해소" >&2; exit 1; }
echo "AGENT_JAR=$AGENT_JAR" >&2

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$REPO_ROOT/fixture-app/build/classes/java/main"
OUT="$REPO_ROOT/build/inprocess-e2e"; rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== fixture-app:classes + tia-cli:installDist ===" >&2
"$REPO_ROOT/gradlew" --no-daemon :fixture-app:classes :tia-cli:installDist >&2
CLI="$REPO_ROOT/tia-cli/build/install/tia/bin/tia"

run_mode() {  # $1 = serial|forks|injvm
  local mode
  mode="$1"
  local cov="$OUT/cov-$mode"
  local ov="$OUT/overlap-$mode.csv"
  rm -rf "$cov"; mkdir -p "$cov"; rm -f "$ov"
  echo "=== run_mode: $mode ===" >&2
  "$REPO_ROOT/gradlew" --no-daemon --no-build-cache :e2e:inProcessTesterTest \
      -Pinprocess.mode="$mode" \
      -Pinprocess.agentJar="$AGENT_JAR" \
      -Pinprocess.covDir="$cov" \
      "-Dtia.inprocess.overlapFile=$ov" >&2
  # 에이전트가 .exec를 생성했는지 검증 — 0이면 에이전트 미부착으로 즉시 실패
  local exec_count
  exec_count=$(find "$cov" -name '*.exec' 2>/dev/null | wc -l | tr -d ' ')
  [ "$exec_count" -gt 0 ] || { echo "❌ $mode: .exec 파일 0 — 에이전트 미부착 의심" >&2; exit 1; }
  echo "--- convert $mode ($exec_count exec files) ---" >&2
  "$CLI" convert --exec-dir "$cov" --classes "$CLASSES" --out "$OUT/testwise_$mode.json" >&2
  # 수용 테스트가 기대하는 파일명으로 정리(overlap_<mode>.csv, underscore)
  if [ -f "$ov" ]; then
    cp "$ov" "$OUT/overlap_$mode.csv"
  else
    if [ "$mode" = "forks" ]; then
      echo "⚠️  overlap 파일 없음($ov) — forks는 허용(테스트에서 읽지 않음)" >&2
      touch "$OUT/overlap_$mode.csv"
    else
      echo "❌ $mode: overlap 파일 없음 — overlapFile 프로퍼티 전달 실패 의심" >&2
      exit 1
    fi
  fi
  echo "--- $mode done ---" >&2
}

run_mode serial
run_mode forks
run_mode injvm

echo "=== InProcessCollectionE2E ===" >&2
"$REPO_ROOT/gradlew" --no-daemon :e2e:inProcessE2ETest \
    "-Dtia.inprocess.artifacts=$OUT" >&2

echo "✅ inprocess-e2e PASS"

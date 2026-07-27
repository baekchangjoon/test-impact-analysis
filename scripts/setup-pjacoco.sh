#!/usr/bin/env bash
set -euo pipefail
# Do not source this script; always run as: bash scripts/setup-pjacoco.sh
# pjacoco 해소 — 두 가지를 보장한다:
#   (1) 에이전트 jar → tools/pjacoco/jacocoagent-parallel.jar
#   (2) testkit(io.pjacoco:pjacoco-testkit-*)의 mavenLocal 게시 (e2e 의존 해소)
#
# v2 전략: 다운로드 우선, 소스 빌드 폴백.
#   1) 에이전트 jar는 pjacoco GitHub 릴리스 에셋에서 무인증 다운로드(수 초).
#   2) testkit은 아직 Maven 저장소/릴리스 에셋에 게시되지 않으므로,
#      mavenLocal(~/.m2)에 이미 있으면 스킵하고, 없을 때만 clone+빌드한다.
#      (pjacoco 릴리스가 testkit 에셋/저장소 게시를 시작하면 이 폴백도 다운로드로 대체 예정)
#   3) 다운로드 실패(오프라인 등) 시에는 기존과 동일하게 소스 빌드로 폴백한다.
# 해소 실패 시 비0 종료 — 호출측(E2E/CI)이 skip 아닌 fail로 다룬다.
#
# 환경변수:
#   PJACOCO_VERSION  에이전트 릴리스 버전 (기본 1.4.0; 태그 v$PJACOCO_VERSION의 에셋 사용)
#   TESTKIT_VERSION  e2e가 핀한 io.pjacoco:* 버전 (기본 1.3.0 — e2e/build.gradle과 일치해야 함)
#   PJACOCO_FORCE=1  이미 있어도 에이전트 jar를 다시 받는다
#   PJACOCO_SRC / PJACOCO_REPO / PJACOCO_REF  소스 빌드 폴백용 (기존과 동일)
#
# 주의: PJACOCO_SRC 디렉터리가 이미 존재하면 clone/fetch를 건너뛴다.
# 다른 ref가 필요한 경우 PJACOCO_SRC를 삭제하거나 직접 체크아웃 후 재실행하라.
PJACOCO_VERSION="${PJACOCO_VERSION:-1.4.0}"
TESTKIT_VERSION="${TESTKIT_VERSION:-1.3.0}"
PJACOCO_SRC="${PJACOCO_SRC:-$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage}"
PJACOCO_REPO="${PJACOCO_REPO:-https://github.com/baekchangjoon/parallel-per-test-coverage.git}"
PJACOCO_REF="${PJACOCO_REF:-main}"
# 스크립트 위치 기준으로 repo 루트를 고정 — CWD 무관하게 올바른 경로에 jar를 배치한다.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_DEST="$REPO_ROOT/tools/pjacoco/jacocoagent-parallel.jar"
RELEASE_BASE="${PJACOCO_REPO%.git}/releases/download"

# ---- (1) 에이전트 jar: 릴리스 에셋 다운로드 우선 ----
agent_ok=0
if [ -f "$AGENT_DEST" ] && [ "${PJACOCO_FORCE:-0}" != "1" ]; then
  echo "에이전트 jar 이미 존재 → 재사용: $AGENT_DEST" >&2
  agent_ok=1
else
  url="$RELEASE_BASE/v${PJACOCO_VERSION}/pjacoco-agent-${PJACOCO_VERSION}.jar"
  mkdir -p "$REPO_ROOT/tools/pjacoco"
  echo "에이전트 jar 다운로드 시도: $url" >&2
  if curl -fsSL --retry 2 -o "$AGENT_DEST.tmp" "$url"; then
    mv "$AGENT_DEST.tmp" "$AGENT_DEST"
    echo "에이전트 jar 다운로드 완료 (v$PJACOCO_VERSION)" >&2
    agent_ok=1
  else
    rm -f "$AGENT_DEST.tmp"
    echo "다운로드 실패 → 소스 빌드로 폴백" >&2
  fi
fi

# ---- (2) testkit: mavenLocal에 있으면 스킵 ----
m2_testkit() {
  local a="$1"
  [ -f "$HOME/.m2/repository/io/pjacoco/$a/$TESTKIT_VERSION/$a-$TESTKIT_VERSION.jar" ]
}
testkit_ok=0
if m2_testkit pjacoco-testkit-junit5 && m2_testkit pjacoco-testkit-restassured; then
  echo "testkit $TESTKIT_VERSION 이미 mavenLocal에 있음 → 빌드 스킵" >&2
  testkit_ok=1
fi

# ---- (3) 폴백: 소스 빌드 (에이전트 또는 testkit이 미해소일 때만) ----
if [ "$agent_ok" != "1" ] || [ "$testkit_ok" != "1" ]; then
  if [ ! -d "$PJACOCO_SRC" ]; then
    echo "pjacoco 소스 없음 → clone: $PJACOCO_REPO@$PJACOCO_REF" >&2
    git clone --depth 1 --branch "$PJACOCO_REF" "$PJACOCO_REPO" "$PJACOCO_SRC" >&2
  fi
  ( cd "$PJACOCO_SRC" && ./gradlew --no-daemon :agent:shadowJar publishToMavenLocal ) >&2
  if [ "$agent_ok" != "1" ]; then
    AGENT_JAR="$(find "$PJACOCO_SRC/agent/build/libs" -name 'pjacoco-agent*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
    [ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 jar 빌드 실패" >&2; exit 1; }
    mkdir -p "$REPO_ROOT/tools/pjacoco"
    cp "$AGENT_JAR" "$AGENT_DEST"
  fi
  if ! m2_testkit pjacoco-testkit-junit5 || ! m2_testkit pjacoco-testkit-restassured; then
    echo "❌ testkit $TESTKIT_VERSION 이 mavenLocal에 게시되지 않음 (소스 버전과 TESTKIT_VERSION 불일치 가능)" >&2
    exit 1
  fi
fi

[ -f "$AGENT_DEST" ] || { echo "❌ 에이전트 jar 확보 실패" >&2; exit 1; }
echo "PJACOCO_AGENT_JAR=$AGENT_DEST"

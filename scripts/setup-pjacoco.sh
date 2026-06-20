#!/usr/bin/env bash
set -euo pipefail
# Do not source this script; always run as: bash scripts/setup-pjacoco.sh
# pjacoco(미게시)를 소스에서 빌드해 (1) 에이전트 jar 확보 (2) testkit/plugin을 mavenLocal에 게시.
# 해소 실패 시 비0 종료 — 호출측(E2E/CI)이 skip 아닌 fail로 다룬다.
#
# 주의: PJACOCO_SRC 디렉터리가 이미 존재하면 clone/fetch를 건너뛴다.
# 다른 ref가 필요한 경우 PJACOCO_SRC를 삭제하거나 직접 체크아웃 후 재실행하라.
PJACOCO_SRC="${PJACOCO_SRC:-$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage}"
PJACOCO_REPO="${PJACOCO_REPO:-https://github.com/baekchangjoon/parallel-per-test-coverage.git}"
PJACOCO_REF="${PJACOCO_REF:-main}"
# 스크립트 위치 기준으로 repo 루트를 고정 — CWD 무관하게 올바른 경로에 jar를 배치한다.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ ! -d "$PJACOCO_SRC" ]; then
  echo "pjacoco 소스 없음 → clone: $PJACOCO_REPO@$PJACOCO_REF" >&2
  git clone --depth 1 --branch "$PJACOCO_REF" "$PJACOCO_REPO" "$PJACOCO_SRC" >&2
fi

( cd "$PJACOCO_SRC" && ./gradlew --no-daemon :agent:shadowJar publishToMavenLocal ) >&2

AGENT_JAR="$(find "$PJACOCO_SRC/agent/build/libs" -name 'pjacoco-agent*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
[ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 jar 빌드 실패" >&2; exit 1; }
mkdir -p "$REPO_ROOT/tools/pjacoco"
cp "$AGENT_JAR" "$REPO_ROOT/tools/pjacoco/jacocoagent-parallel.jar"
[ -f "$REPO_ROOT/tools/pjacoco/jacocoagent-parallel.jar" ] || { echo "❌ jar 복사 실패" >&2; exit 1; }
echo "PJACOCO_AGENT_JAR=$REPO_ROOT/tools/pjacoco/jacocoagent-parallel.jar"

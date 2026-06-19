#!/usr/bin/env bash
set -euo pipefail
# pjacoco(미게시)를 소스에서 빌드해 (1) 에이전트 jar 확보 (2) testkit/plugin을 mavenLocal에 게시.
# 해소 실패 시 비0 종료 — 호출측(E2E/CI)이 skip 아닌 fail로 다룬다.
PJACOCO_SRC="${PJACOCO_SRC:-$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage}"
PJACOCO_REPO="${PJACOCO_REPO:-https://github.com/baekchangjoon/parallel-per-test-coverage.git}"
PJACOCO_REF="${PJACOCO_REF:-main}"

if [ ! -d "$PJACOCO_SRC" ]; then
  echo "pjacoco 소스 없음 → clone: $PJACOCO_REPO@$PJACOCO_REF"
  git clone --depth 1 --branch "$PJACOCO_REF" "$PJACOCO_REPO" "$PJACOCO_SRC"
fi

( cd "$PJACOCO_SRC" && ./gradlew --no-daemon :agent:shadowJar publishToMavenLocal )

AGENT_JAR="$(find "$PJACOCO_SRC/agent/build/libs" -name 'jacocoagent-parallel*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
[ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 jar 빌드 실패"; exit 1; }
mkdir -p tools/pjacoco
cp "$AGENT_JAR" tools/pjacoco/jacocoagent-parallel.jar
echo "PJACOCO_AGENT_JAR=$PWD/tools/pjacoco/jacocoagent-parallel.jar"

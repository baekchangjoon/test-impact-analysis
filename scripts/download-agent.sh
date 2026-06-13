#!/usr/bin/env bash
set -euo pipefail
# 검증 2026-06-13: 레포가 cqse/teamscale-jacoco-agent → cqse/teamscale-java-profiler 로 이전됨.
# 자산명은 버전 없는 teamscale-jacoco-agent.zip, jar는 .../teamscale-jacoco-agent/lib/teamscale-jacoco-agent.jar
VERSION="${TEAMSCALE_AGENT_VERSION:-36.5.2}"
DEST="tools"
mkdir -p "$DEST"
ZIP="$DEST/teamscale-jacoco-agent.zip"
if [ ! -f "$ZIP" ]; then
  curl -fsSL -o "$ZIP" \
    "https://github.com/cqse/teamscale-java-profiler/releases/download/v${VERSION}/teamscale-jacoco-agent.zip"
fi
unzip -oq "$ZIP" -d "$DEST/teamscale"
AGENT_JAR="$(find "$DEST/teamscale" -name 'teamscale-jacoco-agent.jar' -path '*/lib/*' -print -quit)"
echo "AGENT_JAR=$AGENT_JAR"

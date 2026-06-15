#!/usr/bin/env bash
# tia Agent Skill — resolve the `tia` CLI and forward all args.
# Resolution: `tia` on PATH  >  $TIA_JAR  >  local build (tia-cli/build/libs/tia.jar).
# Prechecks JDK and CLI availability; on failure exits non-zero with an actionable message
# (never returns an empty/success result when the tool is missing).
set -euo pipefail

err() { echo "tia-skill: $*" >&2; }

# 1) JDK present?
if ! command -v java >/dev/null 2>&1; then
  err "Java not found. Install JDK 17+ (e.g. https://adoptium.net) and retry."
  exit 3
fi

# 2) `tia` on PATH wins
if command -v tia >/dev/null 2>&1; then
  exec tia "$@"
fi

# 3) $TIA_JAR, else a local fat-jar build
JAR="${TIA_JAR:-}"
if [ -z "$JAR" ]; then
  for cand in "tia-cli/build/libs/tia.jar" "./tia.jar"; do
    if [ -f "$cand" ]; then JAR="$cand"; break; fi
  done
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  err "tia CLI not found. Put 'tia' on PATH, or set TIA_JAR=/path/to/tia.jar"
  err "(build it: ./gradlew :tia-cli:shadowJar  →  tia-cli/build/libs/tia.jar)."
  exit 4
fi

exec java -jar "$JAR" "$@"

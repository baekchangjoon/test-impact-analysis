#!/usr/bin/env bash
set -euo pipefail
# Do not source this script; always run as: bash scripts/setup-pjacoco.sh
# pjacoco 해소 — 두 가지를 보장한다:
#   (1) 에이전트 jar → tools/pjacoco/jacocoagent-parallel.jar
#   (2) testkit(io.pjacoco:pjacoco-testkit{,-junit5,-restassured})의 mavenLocal 존재 (e2e 의존 해소)
#
# v3 전략: 릴리스 에셋 완전 다운로드, 소스 빌드는 오프라인 폴백.
#   1) 에이전트·testkit 3종을 릴리스(v$PJACOCO_VERSION) 에셋에서 무인증 다운로드하고 sha256 검증.
#   2) testkit jar는 최소 POM(junit5/restassured → core 의존만; 외부 dep은 전부 compileOnly라 불필요)과
#      함께 ~/.m2에 설치한다. mavenLocal에 이미 해당 버전이 있으면 스킵.
#   3) 다운로드 실패(오프라인 등) 시 태그(v$PJACOCO_VERSION) 클론 + -PreleaseVersion 스탬프 빌드로 폴백
#      (스탬프 없이는 소스 기본 버전으로 게시돼 e2e 핀과 어긋난다).
# 해소 실패 시 비0 종료 — 호출측(E2E/CI)이 skip 아닌 fail로 다룬다.
#
# 환경변수:
#   PJACOCO_VERSION  agent·testkit 릴리스 버전 (기본 1.4.0 — e2e/build.gradle의 io.pjacoco:* 핀과 일치해야 함)
#   PJACOCO_FORCE=1  이미 있어도 에이전트 jar를 다시 받는다
#   PJACOCO_SRC / PJACOCO_REPO / PJACOCO_REF  소스 빌드 폴백용 (REF 기본값은 v$PJACOCO_VERSION)
PJACOCO_VERSION="${PJACOCO_VERSION:-1.4.0}"
PJACOCO_SRC="${PJACOCO_SRC:-$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage}"
PJACOCO_REPO="${PJACOCO_REPO:-https://github.com/baekchangjoon/parallel-per-test-coverage.git}"
PJACOCO_REF="${PJACOCO_REF:-v$PJACOCO_VERSION}"
# 스크립트 위치 기준으로 repo 루트를 고정 — CWD 무관하게 올바른 경로에 jar를 배치한다.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_DEST="$REPO_ROOT/tools/pjacoco/jacocoagent-parallel.jar"
RELEASE_BASE="${PJACOCO_REPO%.git}/releases/download/v${PJACOCO_VERSION}"
M2_PJACOCO="$HOME/.m2/repository/io/pjacoco"
TESTKIT_ARTIFACTS=(pjacoco-testkit pjacoco-testkit-junit5 pjacoco-testkit-restassured)

# asset을 받아 sha256까지 검증한다. 실패 시 비0 (호출측이 폴백 결정).
fetch_verified() {   # $1=asset명 $2=저장경로
  local asset="$1" dest="$2" sum expect
  curl -fsSL --retry 2 -o "$dest.tmp" "$RELEASE_BASE/$asset" || { rm -f "$dest.tmp"; return 1; }
  expect="$(curl -fsSL --retry 2 "$RELEASE_BASE/$asset.sha256" | awk '{print $1}')" || { rm -f "$dest.tmp"; return 1; }
  sum="$(shasum -a 256 "$dest.tmp" | awk '{print $1}')"
  [ -n "$expect" ] && [ "$sum" = "$expect" ] || { echo "❌ sha256 불일치: $asset" >&2; rm -f "$dest.tmp"; return 1; }
  mv "$dest.tmp" "$dest"
}

# 최소 POM 작성 — junit5/restassured는 core(pjacoco-testkit) 의존만 선언(그 외 외부 dep은 compileOnly).
write_pom() {   # $1=artifactId $2=pom경로
  local a="$1" pom="$2" deps=""
  if [ "$a" != "pjacoco-testkit" ]; then
    deps="  <dependencies><dependency><groupId>io.pjacoco</groupId><artifactId>pjacoco-testkit</artifactId><version>$PJACOCO_VERSION</version></dependency></dependencies>"
  fi
  cat > "$pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.pjacoco</groupId>
  <artifactId>$a</artifactId>
  <version>$PJACOCO_VERSION</version>
  <packaging>jar</packaging>
$deps
</project>
POM
}

need_source_build=0

# ---- (1) 에이전트 jar: 릴리스 에셋 다운로드 우선 ----
if [ -f "$AGENT_DEST" ] && [ "${PJACOCO_FORCE:-0}" != "1" ]; then
  echo "에이전트 jar 이미 존재 → 재사용: $AGENT_DEST" >&2
else
  mkdir -p "$REPO_ROOT/tools/pjacoco"
  echo "에이전트 jar 다운로드: $RELEASE_BASE/jacocoagent-parallel-${PJACOCO_VERSION}.jar" >&2
  if fetch_verified "jacocoagent-parallel-${PJACOCO_VERSION}.jar" "$AGENT_DEST"; then
    echo "에이전트 jar 다운로드·검증 완료 (v$PJACOCO_VERSION)" >&2
  else
    echo "다운로드 실패 → 소스 빌드로 폴백" >&2
    need_source_build=1
  fi
fi

# ---- (2) testkit 3종: mavenLocal에 없으면 다운로드+최소 POM 설치 ----
for a in "${TESTKIT_ARTIFACTS[@]}"; do
  dir="$M2_PJACOCO/$a/$PJACOCO_VERSION"
  jar="$dir/$a-$PJACOCO_VERSION.jar"
  if [ -f "$jar" ]; then
    echo "testkit $a:$PJACOCO_VERSION 이미 mavenLocal에 있음" >&2
    continue
  fi
  mkdir -p "$dir"
  echo "testkit 다운로드: $a-$PJACOCO_VERSION.jar" >&2
  if fetch_verified "$a-$PJACOCO_VERSION.jar" "$jar"; then
    write_pom "$a" "$dir/$a-$PJACOCO_VERSION.pom"
  else
    echo "다운로드 실패($a) → 소스 빌드로 폴백" >&2
    need_source_build=1
    break
  fi
done

# ---- (3) 폴백: 태그 클론 + 버전 스탬프 빌드 (다운로드가 하나라도 실패했을 때만) ----
if [ "$need_source_build" = "1" ]; then
  if [ ! -d "$PJACOCO_SRC" ]; then
    echo "pjacoco 소스 없음 → clone: $PJACOCO_REPO@$PJACOCO_REF" >&2
    git clone --depth 1 --branch "$PJACOCO_REF" "$PJACOCO_REPO" "$PJACOCO_SRC" >&2
  fi
  # -PreleaseVersion 스탬프: 소스 기본 버전과 무관하게 e2e 핀($PJACOCO_VERSION)과 일치시킨다.
  ( cd "$PJACOCO_SRC" && ./gradlew --no-daemon "-PreleaseVersion=$PJACOCO_VERSION" \
      :agent:shadowJar publishToMavenLocal ) >&2
  if [ ! -f "$AGENT_DEST" ] || [ "${PJACOCO_FORCE:-0}" = "1" ]; then
    AGENT_JAR="$(find "$PJACOCO_SRC/agent/build/libs" -name 'pjacoco-agent*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
    [ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 jar 빌드 실패" >&2; exit 1; }
    mkdir -p "$REPO_ROOT/tools/pjacoco"
    cp "$AGENT_JAR" "$AGENT_DEST"
  fi
fi

# ---- 최종 검증: 계약 위반 시 비0 종료 ----
for a in "${TESTKIT_ARTIFACTS[@]}"; do
  [ -f "$M2_PJACOCO/$a/$PJACOCO_VERSION/$a-$PJACOCO_VERSION.jar" ] || {
    echo "❌ testkit $a:$PJACOCO_VERSION 해소 실패 (mavenLocal에 없음)" >&2; exit 1; }
done
[ -f "$AGENT_DEST" ] || { echo "❌ 에이전트 jar 확보 실패" >&2; exit 1; }
echo "PJACOCO_AGENT_JAR=$AGENT_DEST"

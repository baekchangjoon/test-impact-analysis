#!/usr/bin/env bash
set -euo pipefail
# Do not source this script; always run as: bash scripts/setup-pjacoco.sh
# pjacoco 에이전트 jar 해소 — tools/pjacoco/pjacoco-agent.jar 를 보장한다.
# testkit(io.github.beltian.pjacoco:pjacoco-testkit{,-junit5,-restassured})은 Maven Central에
# 실좌표로 게시돼 있어 e2e/build.gradle의 의존성 선언만으로 컴파일 타임에 자동 해소된다 — 이 스크립트가
# 다룰 필요가 없다(v1.x 시절의 합성 POM m2 설치 로직은 그래서 삭제).
#
# v4 전략(pjacoco 2.0.0 Maven Central 이전 반영):
#   1) 에이전트 jar를 Maven Central(repo1)에서 무인증 다운로드하고 .sha256 검증.
#   2) 다운로드 실패(오프라인 등) 시 태그(v$PJACOCO_VERSION) 클론 + -PreleaseVersion 스탬프 빌드로
#      폴백한다. 이 빌드는 :agent:shadowJar 로 에이전트 jar를 얻는 동시에 publishToMavenLocal 로
#      testkit도 로컬 m2에 게시한다 — 오프라인일 때 e2e testkit 의존성을 해소할 유일한 경로이므로
#      "에이전트만 빌드"로 축소하지 않는다(그러면 오프라인에서 :e2e:test 자체가 깨진다).
# 해소 실패 시 비0 종료 — 호출측(E2E/CI)이 skip 아닌 fail로 다룬다.
#
# 환경변수:
#   PJACOCO_VERSION       agent 릴리스 버전 (기본 2.0.0 — e2e/build.gradle의
#                          io.github.beltian.pjacoco:* 핀과 일치해야 함)
#   PJACOCO_FORCE=1        이미 있어도 에이전트 jar를 다시 받는다
#   PJACOCO_RELEASE_BASE   Central(repo1) 베이스 URL 오버라이드 — 정상 경로는 쓸 일 없고, 폴백(소스
#                          빌드) 경로를 강제 검증할 때만 무효 URL로 덮어써서 쓴다
#   PJACOCO_SRC / PJACOCO_REPO / PJACOCO_REF  소스 빌드 폴백용 (REF 기본값은 v$PJACOCO_VERSION)
PJACOCO_VERSION="${PJACOCO_VERSION:-2.0.0}"
PJACOCO_SRC="${PJACOCO_SRC:-$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage}"
PJACOCO_REPO="${PJACOCO_REPO:-https://github.com/beltian/parallel-per-test-coverage.git}"
PJACOCO_REF="${PJACOCO_REF:-v$PJACOCO_VERSION}"
# 스크립트 위치 기준으로 repo 루트를 고정 — CWD 무관하게 올바른 경로에 jar를 배치한다.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_DEST="$REPO_ROOT/tools/pjacoco/pjacoco-agent.jar"
RELEASE_BASE="${PJACOCO_RELEASE_BASE:-https://repo1.maven.org/maven2/io/github/beltian/pjacoco/pjacoco-agent/$PJACOCO_VERSION}"

# asset을 받아 sha256까지 검증한다. 실패 시 비0 (호출측이 폴백 결정).
fetch_verified() {   # $1=asset명 $2=저장경로
  local asset="$1" dest="$2" sum expect
  curl -fsSL --retry 2 -o "$dest.tmp" "$RELEASE_BASE/$asset" || { rm -f "$dest.tmp"; return 1; }
  expect="$(curl -fsSL --retry 2 "$RELEASE_BASE/$asset.sha256" | awk '{print $1}')" || { rm -f "$dest.tmp"; return 1; }
  sum="$(shasum -a 256 "$dest.tmp" | awk '{print $1}')"
  [ -n "$expect" ] && [ "$sum" = "$expect" ] || { echo "❌ sha256 불일치: $asset" >&2; rm -f "$dest.tmp"; return 1; }
  mv "$dest.tmp" "$dest"
}

need_source_build=0

# ---- 에이전트 jar: Maven Central(repo1) 다운로드 우선 ----
if [ -f "$AGENT_DEST" ] && [ "${PJACOCO_FORCE:-0}" != "1" ]; then
  echo "에이전트 jar 이미 존재 → 재사용: $AGENT_DEST" >&2
else
  mkdir -p "$REPO_ROOT/tools/pjacoco"
  echo "에이전트 jar 다운로드: $RELEASE_BASE/pjacoco-agent-${PJACOCO_VERSION}.jar" >&2
  if fetch_verified "pjacoco-agent-${PJACOCO_VERSION}.jar" "$AGENT_DEST"; then
    echo "에이전트 jar 다운로드·검증 완료 (v$PJACOCO_VERSION)" >&2
  else
    echo "다운로드 실패 → 소스 빌드로 폴백" >&2
    need_source_build=1
  fi
fi

# ---- 폴백: 태그 클론 + 버전 스탬프 빌드(다운로드가 실패했을 때만) ----
if [ "$need_source_build" = "1" ]; then
  if [ ! -d "$PJACOCO_SRC" ]; then
    echo "pjacoco 소스 없음 → clone: $PJACOCO_REPO@$PJACOCO_REF" >&2
    git clone --depth 1 --branch "$PJACOCO_REF" "$PJACOCO_REPO" "$PJACOCO_SRC" >&2
  fi
  # -PreleaseVersion 스탬프: 소스 기본 버전과 무관하게 e2e 핀($PJACOCO_VERSION)과 일치시킨다.
  # publishToMavenLocal: 오프라인 시 e2e testkit 의존성 해소의 유일한 경로 — 반드시 유지한다.
  ( cd "$PJACOCO_SRC" && ./gradlew --no-daemon "-PreleaseVersion=$PJACOCO_VERSION" \
      :agent:shadowJar publishToMavenLocal ) >&2
  AGENT_JAR="$(find "$PJACOCO_SRC/agent/build/libs" -name 'pjacoco-agent*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
  [ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 jar 빌드 실패" >&2; exit 1; }
  mkdir -p "$REPO_ROOT/tools/pjacoco"
  cp "$AGENT_JAR" "$AGENT_DEST"
fi

# ---- 최종 검증: 계약 위반 시 비0 종료 ----
[ -f "$AGENT_DEST" ] || { echo "❌ 에이전트 jar 확보 실패" >&2; exit 1; }
echo "PJACOCO_AGENT_JAR=$AGENT_DEST"

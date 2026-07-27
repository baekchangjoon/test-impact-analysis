#!/usr/bin/env bash
set -euo pipefail
# PR 코멘트 게시 (SP5). env: BODY_FILE(필수) PR_NUMBER REPO GITHUB_TOKEN DRY_RUN
# 소프트 실패 원칙: 게시는 부가 기능 — PR 미컨텍스트·gh 부재·API 실패는 ::warning + exit 0.
MAX=65536; TRUNC_AT=60000

[ -f "${BODY_FILE:-}" ] || { echo "ERROR: BODY_FILE not found: ${BODY_FILE:-}" >&2; exit 1; }
if [ -z "${PR_NUMBER:-}" ]; then
  echo "::warning::PR 컨텍스트가 아님 — 코멘트 게시 스킵"; exit 0
fi

# 크기 절단 [SP5-REQ-006]: 요약 테이블(첫 <details> 이전) 유지 → 그래도 크면 하드캡.
# wc -c는 바이트 기준(UTF-8 한글은 과잉 보수적 절단 — 안전 방향, 요구는 바이트 근사 허용).
# 주의: 절단 시 <details> 이후의 '### 경고' 섹션은 유실될 수 있음(베스트 에포트 — REQ-006 명시).
size=$(wc -c < "$BODY_FILE")
if [ "$size" -gt "$TRUNC_AT" ]; then
  awk '/<details>/{exit} {print}' "$BODY_FILE" > "$BODY_FILE.trunc"
  printf '\n_상세 목록이 길어 생략했습니다(%s바이트). 전체는 job summary 또는 TIA 리포트를 참조하세요._\n' \
    "$size" >> "$BODY_FILE.trunc"
  if [ "$(wc -c < "$BODY_FILE.trunc")" -ge "$MAX" ]; then
    head -c "$TRUNC_AT" "$BODY_FILE.trunc" > "$BODY_FILE.cap"   # 하드캡 — <details> 부재/후행에도 보장
    printf '\n\n_…(하드캡 절단)_\n' >> "$BODY_FILE.cap"
    mv "$BODY_FILE.cap" "$BODY_FILE.trunc"
  fi
  BODY_FILE="$BODY_FILE.trunc"
fi

if [ "${DRY_RUN:-0}" = "1" ]; then
  echo "DRY_RUN: POST repos/${REPO:-}/issues/$PR_NUMBER/comments"
  cat "$BODY_FILE"
  exit 0
fi

command -v gh >/dev/null 2>&1 || { echo "::warning::gh CLI 없음(셀프호스티드?) — 코멘트 게시 스킵"; exit 0; }
export GH_TOKEN="${GITHUB_TOKEN:-}"
if ! gh api "repos/$REPO/issues/$PR_NUMBER/comments" -F body=@"$BODY_FILE"; then
  echo "::warning::코멘트 게시 실패 — 소비 워크플로에 'permissions: pull-requests: write'가 필요합니다(포크 PR은 기본 토큰이 read-only)."
  exit 0
fi

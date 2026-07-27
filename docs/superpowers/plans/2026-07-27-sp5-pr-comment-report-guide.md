# SP5 구현 계획 — Action PR 코멘트 + HTML 해석 가이드

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `tia impact --format markdown`을 PR 코멘트로 게시하는 opt-in Action 경로 + HTML 리포트 탭 가이드/빈 상태 내장.

**Architecture:** 게시 로직은 `scripts/pr-comment.sh`(단독 테스트 가능)로 분리하고 action.yml은 배선만. HTML은 `report-template.html` 단독 수정(자바 무변경).

**Tech Stack:** bash + gh CLI, GitHub composite action, HTML/JS 템플릿, JUnit(ProcessBuilder 셸-아웃 — e2e 모듈 신규 패턴).

**참조:** spec `docs/superpowers/specs/2026-07-27-sp5-pr-comment-report-guide-design.md`, 요구명세 `docs/superpowers/requirements/2026-07-27-sp5-requirements.md` (SP5-REQ-001..010; 매트릭스 상태 직접 갱신할 것)

## Global Constraints

- 기존 `impact` 스텝의 실행·파싱 로직 무변경(TIA_ARGS export 3줄 추가만 허용) [SP5-REQ-001]
- composite 번들 파일 참조는 반드시 `$GITHUB_ACTION_PATH` 기준 [SP5-REQ-007]
- `gh api … -F body=@file` (**-f 금지** — 리터럴이 됨) [SP5-REQ-004]
- 게시 실패·PR 미컨텍스트·gh 부재 = `::warning` + exit 0; BODY_FILE 부재만 exit 1 [SP5-REQ-003/005]
- 본문 60,000자 초과 시 절단(최종 <65,536자) [SP5-REQ-006]
- 가이드/빈 상태 문구는 일반 산문만(fixture 형태 리터럴 금지) — 전체 스위트로 충돌 검증 [SP5-REQ-008]
- 구현은 워크트리 브랜치 `feat/sp5-pr-comment-report-guide`(origin/main 기준)에서. 커밋에 `[SP5-REQ-…]` 표기 + 세션 트레일러.

---

### Task 1: scripts/pr-comment.sh + 블랙박스 E2E

**REQ-IDs:** SP5-REQ-002, SP5-REQ-003, SP5-REQ-004, SP5-REQ-005, SP5-REQ-006

**Files:**
- Create: `scripts/pr-comment.sh` (755)
- Test: `e2e/src/test/java/io/tia/e2e/action/PrCommentScriptE2ETest.java`

**Interfaces:**
- Consumes: 없음(독립 스크립트)
- Produces: env 계약 — `BODY_FILE`(필수) `PR_NUMBER` `REPO` `GITHUB_TOKEN` `DRY_RUN`. Task 2의 action.yml이 이 계약으로 호출.

- [ ] **Step 1: 실패하는 E2E 작성** — `PrCommentScriptE2ETest` (`@Execution(SAME_THREAD)`). 스크립트 경로는 cwd에서 상향 탐색(`scripts/pr-comment.sh`가 나올 때까지 부모로). 헬퍼:

```java
    record Exec(int code, String out) {}
    Exec run(Map<String, String> env, Path... pathPrepend) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
        pb.redirectErrorStream(true);
        pb.environment().putAll(env);
        if (pathPrepend.length > 0)
            pb.environment().put("PATH", pathPrepend[0] + ":" + System.getenv("PATH"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        p.waitFor();
        return new Exec(p.exitValue(), out);
    }
```

케이스 6개(각 `@DisplayName("SP5-REQ-0NN: …")`):
`dryRunPrintsApiPathAndBody`(REQ-002: DRY_RUN=1, PATH에 가짜 gh 없이도 exit 0 + `repos/o/r/issues/7/comments`+본문 출력), `emptyPrNumberWarnsExitZero`·`missingBodyFileFails`(REQ-003), `stubGhReceivesFileBody`(REQ-004: @TempDir에 기록형 스텁 `gh` 작성 — `#!/bin/bash\nprintf '%s\n' "$@" > "$GH_ARGS_OUT"; exit 0` — PATH 선두 주입 후 인자에 `-F`·`body=@` 존재 + 참조 파일 내용이 원본과 일치), `ghFailureWarnsWithPermissionHint`(REQ-005: `exit 1` 스텁 → 출력에 `::warning`+`pull-requests: write` 포함, exit 0), `oversizedBodyTruncated`(REQ-006: 70,000자 본문 → DRY_RUN 출력 본문 <65,536자 + 절단 안내 + 첫 테이블 행 보존).

- [ ] **Step 2: red 확인** — `./gradlew :e2e:test --tests 'io.tia.e2e.action.*'` → 스크립트 부재로 전부 FAIL.
- [ ] **Step 3: 구현** — `scripts/pr-comment.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
# PR 코멘트 게시 (SP5). env: BODY_FILE(필수) PR_NUMBER REPO GITHUB_TOKEN DRY_RUN
# 소프트 실패 원칙: 게시는 부가 기능 — PR 미컨텍스트·gh 부재·API 실패는 ::warning + exit 0.
MAX=65536; TRUNC_AT=60000

[ -f "${BODY_FILE:-}" ] || { echo "ERROR: BODY_FILE not found: ${BODY_FILE:-}" >&2; exit 1; }
if [ -z "${PR_NUMBER:-}" ]; then
  echo "::warning::PR 컨텍스트가 아님 — 코멘트 게시 스킵"; exit 0
fi

# 크기 절단: 요약 테이블(첫 <details> 이전)은 유지, 상세는 안내로 대체 [SP5-REQ-006]
size=$(wc -c < "$BODY_FILE")
if [ "$size" -gt "$TRUNC_AT" ]; then
  head_part="$(awk '/<details>/{exit} {print}' "$BODY_FILE")"
  printf '%s\n\n_상세 목록이 길어 생략했습니다(%s자). 전체는 job summary 또는 TIA 리포트를 참조하세요._\n' \
    "$head_part" "$size" > "$BODY_FILE.trunc"
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
```

- [ ] **Step 4: green 확인** — 6/6 PASS + `chmod +x` 확인(`git ls-files -s scripts/pr-comment.sh` → 100755).
- [ ] **Step 5: Commit** — `feat(action): pr-comment.sh — DRY_RUN·스텁 검증 가능한 PR 코멘트 게시 [SP5-REQ-002..006]`
- [ ] **Step 6: 매트릭스 갱신** (REQ-002..006 🟢)

---

### Task 2: action.yml 배선 + 소비자 문서

**REQ-IDs:** SP5-REQ-001, SP5-REQ-007, SP5-REQ-010

**Files:**
- Modify: `action.yml`
- Modify: `docker/README.md`, `README.md`, `GETTING-STARTED.md` (각 1블록/1줄)

**Interfaces:**
- Consumes: Task 1의 스크립트 env 계약, 기존 `steps.impact.outputs.run-all`

- [ ] **Step 1: action.yml 수정.**
  - inputs에 `pr-comment`(default 'false')·`github-token`(default `${{ github.token }}`) 추가.
  - 기존 `impact` 스텝 **끝에만** 3줄 추가(기존 로직 라인 무수정):

```bash
        # SP5: 코멘트 스텝이 동일 인자를 재사용하도록 export (개행-연결)
        { echo "TIA_ARGS<<__ARGS__"; printf '%s\n' "${args[@]}"; echo "__ARGS__"; } >> "$GITHUB_ENV"
```

  - 신규 스텝(요지 — 실제 작성 시 그대로):

```yaml
    - name: PR comment (opt-in)
      if: inputs.pr-comment == 'true'
      shell: bash
      env:
        IN_IMAGE: ${{ inputs.image }}
        RUN_ALL: ${{ steps.impact.outputs.run-all }}
        PR_NUMBER: ${{ github.event.pull_request.number }}
        REPO: ${{ github.repository }}
        GITHUB_TOKEN: ${{ inputs.github-token }}
      run: |
        set -euo pipefail
        if [ -z "$PR_NUMBER" ]; then echo "::warning::PR 이벤트가 아님 — 코멘트 스킵"; exit 0; fi
        BODY_FILE=$(mktemp "$RUNNER_TEMP"/tia-comment.XXXXXX.md)
        { echo '<!-- tia-impact-comment -->'; echo '### TIA — 영향 테스트'; echo ''; } > "$BODY_FILE"
        if [ "$RUN_ALL" = "true" ]; then
          echo '_TIA 베이스라인 없음 → 전체 실행 권장 (보수적, 누락 위험 0)._' >> "$BODY_FILE"
        else
          mapfile -t args <<< "$TIA_ARGS"
          docker run --rm -v "$PWD:/work" -w /work "$IN_IMAGE" "${args[@]}" --format markdown >> "$BODY_FILE"
        fi
        BODY_FILE="$BODY_FILE" "$GITHUB_ACTION_PATH/scripts/pr-comment.sh"
```

- [ ] **Step 2: 정적 검토(REQ-007 수용):** `$GITHUB_ACTION_PATH` 사용·상대경로 부재·env 4종 매핑·run-all 게이트·TIA_ARGS 재사용을 diff에서 확인하고 결과를 커밋 메시지/리포트에 기록. 기존 스텝 로직 라인 무변경(REQ-001) 확인.
- [ ] **Step 3: 문서.** docker/README 예시에 두 입력 + 전제(`permissions: pull-requests: write`, 포크 PR read-only) 명시. README·GETTING-STARTED에 "리포트에 인라인 탭 가이드 내장 + PR 코멘트 옵션" 1줄.
- [ ] **Step 4: 전체 스위트 green 확인 후 Commit** — `feat(action): opt-in PR 코멘트 스텝 — ACTION_PATH·TIA_ARGS 재사용·run-all 게이트 [SP5-REQ-001/007/010]`
- [ ] **Step 5: 매트릭스 갱신** (REQ-001/007/010 🟢 — 검증 방법 명시대로)

---

### Task 3: report-template.html 탭 가이드 + 빈 상태

**REQ-IDs:** SP5-REQ-008, SP5-REQ-009

**Files:**
- Modify: `tia-core/src/main/resources/report-template.html`
- Test: `tia-core/src/test/java/io/tia/core/report/ReportBuilderTest.java` (케이스 2개 추가)

- [ ] **Step 1: 실패 테스트 작성** — `tabGuidesRenderedFiveTimes`(렌더 HTML에서 `class="tab-guide"` 정확히 5회 + 문구 "이 탭 읽는 법" 존재), `emptyStateHintsForSparseTabs`(테스트 0건 testwise + 빈 prod로 렌더 → 탭 1·2·5 빈 상태 문구 존재). red 확인.
- [ ] **Step 2: 템플릿 수정.** 5개 `<section>`의 `<h2>` 직후에 `<details class="tab-guide"><summary>이 탭 읽는 법</summary><p>…</p></details>`(REPORT-GUIDE 해당 탭 3~5문장 요약, 일반 산문만 — 경로/테스트명 모양 금지). 탭 1·2·5의 렌더 JS에 빈 배열 가드 추가(예: `if(!D.perTest.length){el.innerHTML='<p class="hint">per-test 데이터가 없습니다 — testwise.json에 테스트가 없거나 필터로 모두 제외되었습니다.</p>'}` 스타일 — 정확 문구는 구현 시 확정하되 fixture 리터럴 금지).
- [ ] **Step 3: green + 전체 스위트** — `./gradlew test` 전부 green(부재 단언 테스트 포함 — 충돌 검증)이어야 REQ-008 충족.
- [ ] **Step 4: Commit** — `feat(report): 탭 가이드 5종 + 탭1/2/5 빈 상태 내장 [SP5-REQ-008/009]`
- [ ] **Step 5: 매트릭스 갱신** (REQ-008/009 🟢) + Coverage 라인 10/10.

## 완료 정의

매트릭스 10/10(수동 게이트 2건은 명시된 검증 방법으로) + 전체 스위트 green + 문서 갱신 포함. 실PR 스모크는 머지 후 이 레포 CI에서 확인(후속 체크).

# SP5 요구사항명세 — Action PR 코멘트 + HTML 해석 가이드
> 출처(design spec): docs/superpowers/specs/2026-07-27-sp5-pr-comment-report-guide-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 요구사항 목록

### SP5-REQ-001 — pr-comment 옵트인과 하위호환
- 유형: Functional
- 우선순위: Must
- 설명: `pr-comment` 입력 기본값 'false'. 미지정 시 action의 기존 `impact` 스텝 로직·출력(`selected`/`run-all`)이 무변경이다(TIA_ARGS export 추가만 허용).
- 수용기준:
  - Given `pr-comment` 미지정, When action.yml diff 검토, Then 기존 스텝의 실행·파싱 로직이 구조적으로 무변경이고 신규 스텝은 `if:` 가드로 스킵된다.
- 검증 레벨: diff 검토 + 전체 스위트 green (문서화된 수동 게이트)

### SP5-REQ-002 — pr-comment.sh DRY_RUN 계약
- 유형: Functional
- 우선순위: Must
- 설명: `DRY_RUN=1`이면 gh를 호출하지 않고 API 경로와 본문을 stdout으로 출력하고 exit 0.
- 수용기준:
  - Given 본문 파일과 `PR_NUMBER=7`·`REPO=o/r`, PATH 선두에 **호출 시 고유 마커를 출력하고 exit 99로 종료하는 포이즌 필 gh 스텁**을 prepend한 환경, When `DRY_RUN=1`로 실행, Then stdout에 `repos/o/r/issues/7/comments`와 본문 내용이 있고 포이즌 마커는 없으며 exit 0이다(prepend는 실제 gh의 설치 위치—macOS Homebrew `/opt/homebrew/bin`이든 GitHub-hosted ubuntu-latest `/usr/bin`이든—와 무관하게 항상 실제 gh를 shadow하므로, 러너 환경에 의존하지 않고 DRY_RUN 분기의 gh 비호출을 결정적으로 증명한다).
- 검증 레벨: E2E black-box (ProcessBuilder 셸-아웃)
- 순서 제약: 스크립트 체크 순서는 BODY_FILE → PR_NUMBER → 절단 → **DRY_RUN(조기 종료, gh 불요)** → gh 존재 체크로 고정한다(이 순서가 본 요구의 전제).

### SP5-REQ-003 — 소프트 스킵(경고 필수, 실패 금지)
- 유형: Functional
- 우선순위: Must
- 설명: `PR_NUMBER` 빈 값 또는 `gh` 부재 시 `::warning` 출력 후 exit 0. `BODY_FILE` 부재는 exit 1(배선 버그).
- 수용기준:
  - Given `PR_NUMBER` 빈 값, When 실행, Then stdout/stderr에 `::warning` 포함 + exit 0.
  - Given `BODY_FILE` 부재, When 실행, Then exit 1.
- 검증 레벨: E2E black-box

### SP5-REQ-004 — 실제 파일 본문 전달 (-F 계약)
- 유형: Functional
- 우선순위: Must
- 설명: 게시 호출은 `gh api … -F body=@<file>` 형태로 파일 **내용**이 전달된다(-f 리터럴 금지).
- 수용기준:
  - Given PATH 선두의 기록형 스텁 gh, When DRY_RUN 없이 실행, Then 스텁이 수신한 인자에 `-F`와 `body=@<BODY_FILE>`가 있고, 그 파일 내용이 본문 마크다운과 일치한다.
- 검증 레벨: E2E black-box (스텁 gh)

### SP5-REQ-005 — 게시 실패 내성 + 권한 안내
- 유형: Functional
- 우선순위: Must
- 설명: `gh api` 비0 종료 시 스크립트는 `::warning`(권한 안내: `permissions: pull-requests: write` 필요, 포크 PR은 read-only) 후 exit 0.
- 수용기준:
  - Given 항상 실패하는 스텁 gh, When 실행, Then `::warning`에 `pull-requests: write` 문구 포함 + exit 0.
- 검증 레벨: E2E black-box (스텁 gh)

### SP5-REQ-006 — 65,536자 한도 절단 (하드캡 포함)
- 유형: Functional
- 우선순위: Must
- 설명: 본문 60,000바이트 초과 시 요약 테이블은 유지하고 상세를 절단 안내로 대체하며, 그래도 한도 이상이면 **하드캡(head -c)** 으로 최종 본문 <65,536바이트를 알고리즘 구조와 무관하게 보장한다. 크기는 바이트 기준(UTF-8 과잉 보수 절단 허용). 절단 시 `<details>` 이후의 경고 섹션은 유실될 수 있다(베스트 에포트 — 명시적 수용).
- 수용기준:
  - Given 실제 markdown 형태(요약 테이블+`<details>`)의 70,000바이트 본문과 **유효한 PR_NUMBER·REPO**, When 실행(DRY_RUN=1), Then 출력 본문 <65,536바이트 + 절단 안내 + 요약 테이블 첫 행 보존.
  - Given `<details>`가 없는 70,000바이트 본문, When 실행(DRY_RUN=1), Then 하드캡으로 출력 본문 <65,536바이트.
- 검증 레벨: E2E black-box

### SP5-REQ-007 — 액션 스텝 배선 (ACTION_PATH·ENV 재사용·no-baseline)
- 유형: Functional
- 우선순위: Must
- 설명: 신규 스텝은 `$GITHUB_ACTION_PATH/scripts/pr-comment.sh`를 호출하고, `TIA_ARGS`($GITHUB_ENV 경유, 랜덤 델리미터)를 재사용해 `--format markdown`을 덧붙이며, `steps.impact.outputs.run-all == 'true'`면 재실행 없이 고정 문구 본문을 쓴다. env 매핑(BODY_FILE·PR_NUMBER·REPO·GITHUB_TOKEN) 완비. **PR-컨텍스트 조기 스킵이 docker run 이전**에 있고, docker 재생성 실패도 `::warning`+exit 0(잡 실패 금지).
- 수용기준:
  - Given action.yml, When 정적 검토, Then 위 배선 5항목(ACTION_PATH·env 매핑·run-all 게이트·TIA_ARGS 재사용·조기 스킵+재생성 소프트 스킵)이 모두 존재하고 상대경로 스크립트 참조가 없다.
- 검증 레벨: diff/정적 검토 (문서화된 수동 게이트; 실배선은 머지 후 실PR 스모크)

### SP5-REQ-008 — 탭 가이드 내장
- 유형: Functional
- 우선순위: Must
- 설명: 렌더된 HTML에 5개 탭 각각 `<h2>` 직후·기존 힌트 앞 위치로 `tab-guide` 블록이 있고, 내용은 일반 산문(fixture 형태 리터럴 금지)이다.
- 수용기준:
  - Given 표준 입력으로 렌더, When HTML 검사, Then `tab-guide` 마커 정확히 5회 + 대표 가이드 문구 1개 존재.
  - Given 전체 테스트 스위트, When 실행, Then 부재 단언 테스트 포함 전부 green(문구 충돌 없음).
- 검증 레벨: unit (ReportBuilderTest) + 전체 스위트

### SP5-REQ-009 — 탭 1·2·5 빈 상태 안내 (탭5는 성공/결측 구분)
- 유형: Functional
- 우선순위: Must
- 설명: per-test·역인덱스 탭이 빈 데이터일 때 "왜 비었는지+무엇을 주면 채워지는지" 문구를 표시한다. blind spots 탭은 **`nProd === 0`(입력 없음)일 때만** 결측 안내를, `nProd>0 && blind==0`이면 "전체 커버 — 사각지대 없음" 긍정 메시지를 표시한다(성공 상태를 결측으로 오표시 금지). flaky·scenarios는 기존 문구 유지.
- 수용기준:
  - Given 테스트 0건 testwise·빈 prod, When 렌더, Then 탭 1·2·5의 빈 상태 문구 존재.
  - Given prod 있음·blind 0건, When 렌더, Then 탭 5에 긍정 문구(결측 안내 아님) 존재.
- 검증 레벨: unit (ReportBuilderTest — `render()`가 유일한 렌더 경로라 unit으로 충분, e2e 중복 단언 불요)

### SP5-REQ-010 — 소비자 문서 갱신
- 유형: Non-functional (문서)
- 우선순위: Must
- 설명: docker/README.md 예시에 `pr-comment`/`github-token`과 전제조건(`permissions: pull-requests: write`, 포크 PR read-only) 명시. README·GETTING-STARTED에 인라인 가이드 1줄 언급.
- 수용기준:
  - Given 갱신된 문서, When 검토, Then 위 항목이 모두 기재.
- 검증 레벨: build/docs 게이트

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| SP5-REQ-001 | 옵트인·하위호환 | action.yml diff 검토 + 전체 스위트 | manual+suite | 🟢 green |
| SP5-REQ-002 | DRY_RUN 계약 | PrCommentScriptE2ETest#dryRunPrintsApiPathAndBody | E2E | 🟢 green |
| SP5-REQ-003 | 소프트 스킵 | PrCommentScriptE2ETest#emptyPrNumberWarnsExitZero / #missingBodyFileFails | E2E | 🟢 green |
| SP5-REQ-004 | -F 파일 본문 전달 | PrCommentScriptE2ETest#stubGhReceivesFileBody | E2E | 🟢 green |
| SP5-REQ-005 | 실패 내성+권한 안내 | PrCommentScriptE2ETest#ghFailureWarnsWithPermissionHint | E2E | 🟢 green |
| SP5-REQ-006 | 65,536자 절단+하드캡 | PrCommentScriptE2ETest#oversizedBodyTruncated / #oversizedHeadHardCapped | E2E | 🟢 green |
| SP5-REQ-007 | 액션 스텝 배선 | action.yml 정적 검토 (구현 task 산출물 대조) | manual | 🟢 green |
| SP5-REQ-008 | 탭 가이드 | ReportBuilderTest#tabGuidesRenderedFiveTimes + 전체 스위트 | unit | 🔴 planned |
| SP5-REQ-009 | 빈 상태 1·2·5 (탭5 구분) | ReportBuilderTest#emptyStateHintsForSparseTabs / #fullCoverageBlindTabShowsPositiveMessage | unit | 🔴 planned |
| SP5-REQ-010 | 소비자 문서 | PR 전 docs 게이트 점검 | build | 🟡 partial |

Coverage: 7/10 green (70%) — target 100% (대상: Must 10 = 10; SP5-REQ-001/007은 수동 게이트로 검증 방법 명시).
SP5-REQ-010 🟡 note: docker/README.md의 `pr-comment`/`github-token`+전제조건(permissions/포크 read-only)과
GETTING-STARTED 입력 열거 문장 갱신은 Task 2에서 완료. 수용기준의 "README·GETTING-STARTED 인라인 가이드
1줄 언급"은 인라인 **탭** 가이드(SP5-REQ-008 구현 산출물)를 가리키므로 Task 3(REQ-008 구현 후)에서 이어서
완료한다 — Task 2 브리프에서 명시적으로 범위 제외.

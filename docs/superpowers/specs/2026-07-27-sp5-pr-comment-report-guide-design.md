# SP5 설계 — Action PR 코멘트 게시 + HTML 리포트 해석 가이드 내장

- 날짜: 2026-07-27
- 상태: 3-벤더 리뷰(Sonnet ×3 — Gemini·Cursor 슬롯 대체) 소견 반영 완료
- 상위 맥락: 사용성 개선 5-SP 분해(SP1 spec §0)의 다섯 번째. SP1이 만든 `--format markdown` 출력을 PR에서 소비하게 하고, HTML 리포트를 문서 없이 읽을 수 있게 한다.
- 사용자 확정 결정: PR 코멘트는 **매번 새 코멘트**(스티키 아님).

## 1. 목표·비범위

**목표**

1. GitHub Action(`action.yml`)이 옵션으로 `tia impact --format markdown` 결과를 **PR 코멘트로 게시**한다(매 실행 새 코멘트).
2. HTML 리포트(`report-template.html`)의 각 탭에 **"이 탭 읽는 법" 가이드**를 내장하고, 빈 상태 안내가 없는 탭(1·2·5)에 빈 상태 문구를 추가한다.

**비범위**

- 스티키(업데이트형) 코멘트 — 마커만 심어 여지 확보
- flaky 결과의 PR 코멘트 게시 — impact만
- REPORT-GUIDE.md 삭제 — 상세판으로 유지(HTML에는 요약만). README·GETTING-STARTED에는 "리포트에 인라인 가이드 내장" 1줄 언급 추가

## 2. Action PR 코멘트

**설계 원칙: 기존 계약 무변경.** 현행 `id: impact` 스텝의 text 실행·`selected`/`run-all` 출력 파싱은 **무수정**으로 두고, 코멘트는 추가 스텝으로 붙인다(하위호환 검증은 diff 리뷰 + 이 레포 자체 소비 스모크 — 바이트 해시 게이트는 정당한 수정도 막는 브리틀 게이트라 채택하지 않음).

**action.yml 변경**

```yaml
inputs:
  pr-comment:      # 'true'면 markdown 결과를 PR 코멘트로 게시 (기본 'false' — 완전 하위호환)
  github-token:    # 코멘트 게시용 토큰 (기본 ${{ github.token }})
```

**스텝 간 데이터 흐름** (composite 스텝은 셸 프로세스가 분리됨):

- 기존 `impact` 스텝이 조립한 인자 배열을 개행-연결 문자열 `TIA_ARGS`로 `$GITHUB_ENV`에 export
  한다(기존 로직 뒤에 3줄 추가 — 출력·파싱 경로는 불변). 새 스텝은 `TIA_ARGS`를 그대로 읽어
  `--format markdown`만 덧붙인다 → 인자 조립 로직 중복·드리프트 없음.
- no-baseline 분기는 재파싱하지 않고 **`steps.impact.outputs.run-all == 'true'`** 로 판정한다.

**신규 스텝 (pr-comment='true'일 때만, `if:` 가드)**

1. PR 이벤트가 아니면(`github.event.pull_request.number` 빈 값) `::warning` 후 스킵(1차 게이트).
2. `run-all == 'true'`면 본문은 고정 문구("TIA 베이스라인 없음 → 전체 실행 권장"). 아니면
   `docker run … "$IN_IMAGE" $TIA_ARGS --format markdown` 재실행으로 본문 생성
   (비용: 컨테이너+JVM 기동 1회 추가, 통상 수 초 — opt-in 기능으로 수용).
3. 본문 파일: `BODY_FILE=$(mktemp "$RUNNER_TEMP"/tia-comment.XXXXXX.md)` (호스티드 러너는
   임시 디렉터리라 별도 정리 불요). 앞에 마커 `<!-- tia-impact-comment -->` + `### TIA — 영향 테스트`.
4. 게시: `"$GITHUB_ACTION_PATH/scripts/pr-comment.sh"` 호출 (**composite 액션의 번들 파일은
   반드시 `$GITHUB_ACTION_PATH` 기준** — 상대경로는 소비자 워크스페이스 기준이라 외부 소비자에서 깨짐).
   env 매핑 전부 명시: `BODY_FILE`, `PR_NUMBER=github.event.pull_request.number`,
   `REPO=github.repository`, `GITHUB_TOKEN=inputs.github-token`.

**`scripts/pr-comment.sh` 계약** (755, 테스트 가능성의 핵심)

- 입력(env): `BODY_FILE` `PR_NUMBER` `REPO` `GITHUB_TOKEN`, 옵션 `DRY_RUN`
- 프리플라이트: `command -v gh` 없으면(셀프호스티드) `::warning` + exit 0.
  `PR_NUMBER` 빈 값이면 `::warning` + exit 0(2차 백스톱 — 1차는 action의 `if:` 가드).
  `BODY_FILE` 부재는 exit 1(내부 배선 버그이므로 명확히 실패).
- **크기 제한**: GitHub 코멘트 한도 65,536자. 본문이 60,000자 초과 시 요약 테이블은 유지하고
  `<details>` 상세를 "…목록이 길어 생략 — 전체는 job summary/리포트 참조" 안내로 대체(절단 명시).
- 게시: `export GH_TOKEN="$GITHUB_TOKEN"` 후
  `gh api "repos/$REPO/issues/$PR_NUMBER/comments" -F body=@"$BODY_FILE"`
  (**`-F`가 파일 참조(@) 지원 — `-f`는 리터럴 문자열이라 오답**).
- **실패 내성은 스크립트 안에서**: `gh api` 비0이면 `::warning`(권한 안내 포함: "소비 워크플로에
  `permissions: pull-requests: write` 필요, 포크 PR은 기본 토큰이 read-only") + exit 0.
- `DRY_RUN=1`이면 gh를 **절대 호출하지 않고** 실행할 API 경로와 본문을 stdout으로 출력.

**소비자 문서(범위 포함)**: `docker/README.md`의 action 예시에 `pr-comment`/`github-token` 추가 +
전제조건 명시 — ① 소비 워크플로 `permissions: pull-requests: write` 필요(기본 GITHUB_TOKEN이
read-only인 조직/신규 레포 多) ② **포크 발 `pull_request` 런은 토큰이 강제 read-only라 경고 후
스킵됨**(필요 시 `pull_request_target`+체크아웃 주의 또는 PAT). README·GETTING-STARTED에도 1줄 링크.

## 3. HTML 리포트 해석 가이드 내장

`tia-core/src/main/resources/report-template.html`에만 손댄다(모델/자바 코드 무변경).

- **탭별 가이드**: 5개 탭 각각, **`<h2>` 직후·기존 `<p class="hint">`/동적 힌트보다 앞**(단일 규칙)에
  접힌 `<details class="tab-guide"><summary>이 탭 읽는 법</summary>…</details>` 추가. 내용은
  REPORT-GUIDE.md 해당 탭 해설의 3~5문장 요약(한국어): 무엇을 보여주는지·어떻게 읽는지·행동 1줄.
- **빈 상태 안내 — 재범위**: flaky(212행)·scenarios(192행) 탭은 **이미** "왜 비었는지+무엇을 주면
  채워지는지"를 갖추고 있어 무변경(문구 검토만). 실제 갭인 **탭 1(per-test)·2(역인덱스)·5(blind
  spots)** 에 빈 배열 시 빈 상태 문구를 추가한다.
- **충돌 금지 규칙**: 가이드·빈 상태 문구는 **fixture 형태 리터럴(테스트명·파일 경로 모양) 금지** —
  일반 산문만. 근거: 렌더 HTML에 부재 단언을 거는 테스트가 다수(`ReportFilterE2ETest`,
  `ReportBuilderTest`의 excluded-경로 3단언, `FlakyFilterE2ETest`, `FilterE2ETest`). 검증은 해당
  테스트만이 아니라 **전체 스위트**로 한다.

## 4. 에러 처리

- `pr-comment` 기본 'false' → 미사용자는 어떤 변화도 없음.
- 게시 실패(권한·네트워크·크기 외 오류): 스크립트가 `::warning`(권한 안내 포함) + exit 0 —
  선별 출력이 본질, 코멘트는 부가(침묵 스킵 금지, 경고 필수).
- PR 이벤트 아님 / gh 부재: `::warning` + 스킵.

## 5. 테스트 전략과 E2E/수용 명세

GitHub Actions 실행 환경은 로컬 재현 불가 → 실현 가능한 최고 레벨은 **스크립트 블랙박스**(스텁 gh
포함) + **템플릿 렌더 검증**. Action YAML 배선은 diff 검토 + 머지 후 이 레포 CI 실PR 스모크.

**신규 테스트 하네스 주의**: e2e 모듈에 셸-아웃 테스트가 처음 생긴다(기존은 전부 인프로세스
picocli). 스크립트 경로는 e2e 모듈 작업 디렉터리에서 레포 루트로 **상향 탐색**해 해석하고,
DRY_RUN 케이스는 gh 미설치 환경에서도 돌아야 한다(DRY_RUN은 gh를 호출하지 않음을 단언).

1. **pr-comment.sh 블랙박스** (`PrCommentScriptE2ETest`, ProcessBuilder):
   - DRY_RUN=1 → stdout에 `repos/o/r/issues/7/comments`와 본문 포함, exit 0, gh 미호출
   - PR_NUMBER 빈 값 → 경고 + exit 0
   - BODY_FILE 부재 → exit 1
   - **스텁 gh**(PATH 선두에 실패하는 `gh` 스크립트) → `::warning`(권한 안내 문구 포함) + exit 0
   - **스텁 gh(성공 기록형)** → 스텁이 받은 `-F body=@…` 인자로 **실제 파일 내용이 전달**됨을 검증
     (DRY_RUN 문자열 검사만으로는 -f/-F 오류를 못 잡는다는 리뷰 소견 반영)
   - 60,000자 초과 본문 → 게시 본문에 절단 안내 존재 + 65,536자 미만
2. **markdown 본문**: SP1 `FormatE2ETest#impactMarkdownTableAndDetails`가 커버(신규 불요).
3. **템플릿 가이드 렌더** (`ReportBuilderTest` 케이스 추가): `tab-guide` 마커 5회, 대표 가이드
   문구 1개, 탭 1·2·5 빈 상태 문구(빈 입력 렌더 시), 기존 테스트 무약화.
4. **하위호환**: `pr-comment` 미지정 시 기존 `impact` 스텝 로직 무변경(diff 검토) + 전체 스위트 green.

**완료 정의**: §5.1 블랙박스 6케이스 + §5.3 렌더 케이스 green, 전체 스위트 green, docker/README·
README·GETTING-STARTED 갱신 포함. 실PR 스모크는 머지 후 이 레포 CI에서 확인(후속 체크 항목).

## 6. 리스크와 반론

- **코멘트 노이즈**: 매번 새 코멘트는 푸시가 잦은 PR에서 쌓임 — 사용자가 의도 선택(이력 추적).
  마커로 스티키 전환 여지 확보.
- **Action 배선 검증 한계**: composite 실배선은 로컬 불가 — 스텁 gh 블랙박스 + 실PR 스모크로 보완.
  act 도입은 유지비 대비 과함(기각).
- **이중 실행 비용**: 컨테이너+JVM 기동 1회 추가(수 초) — opt-in 부가 기능으로 수용.
- **포크 PR 무동작**: GitHub 구조적 제약(기본 토큰 read-only) — 문서화 + 경고 문구로 안내(§2).

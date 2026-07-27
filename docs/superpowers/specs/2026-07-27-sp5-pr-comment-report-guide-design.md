# SP5 설계 — Action PR 코멘트 게시 + HTML 리포트 해석 가이드 내장

- 날짜: 2026-07-27
- 상위 맥락: 사용성 개선 5-SP 분해(SP1 spec §0)의 다섯 번째. SP1이 만든 `--format markdown` 출력을 PR에서 소비하게 하고, HTML 리포트를 문서 없이 읽을 수 있게 한다.
- 사용자 확정 결정: PR 코멘트는 **매번 새 코멘트**(스티키 아님).

## 1. 목표·비범위

**목표**

1. GitHub Action(`action.yml`)이 옵션으로 `tia impact --format markdown` 결과를 **PR 코멘트로 게시**한다(매 실행 새 코멘트).
2. HTML 리포트(`report-template.html`)의 각 탭에 **"이 탭 읽는 법" 가이드·빈 상태 안내**를 내장한다(REPORT-GUIDE.md 핵심의 이식).

**비범위**

- 스티키(업데이트형) 코멘트, 코멘트 이원화(요약/상세 분리) — 이후 필요 시
- flaky 결과의 PR 코멘트 게시 — impact만(요구 시 확장)
- REPORT-GUIDE.md 삭제 — 문서는 상세판으로 유지, HTML에는 요약 가이드만

## 2. Action PR 코멘트

**설계 원칙: 기존 계약 무변경.** 현행 text 실행·`selected`/`run-all` 출력 파싱 경로는 그대로 두고, 코멘트는 **추가 옵션 경로**로 붙인다.

**action.yml 변경**

```yaml
inputs:
  pr-comment:      # 'true'면 markdown 결과를 PR 코멘트로 게시 (기본 'false' — 완전 하위호환)
  github-token:    # 코멘트 게시용 토큰 (기본 ${{ github.token }})
```

**동작 (pr-comment='true'일 때, 기존 스텝에 이어 추가 스텝)**

1. 같은 인자에 `--format markdown`만 바꿔 CLI를 한 번 더 실행해 markdown 본문을 얻는다
   (SQLite 조회라 비용 미미; text 파싱 경로를 건드리지 않는 것이 우선).
   no-baseline이면 markdown 대신 "베이스라인 없음 → 전체 실행 권장" 고정 문구를 본문으로 쓴다.
2. 본문 앞에 마커 헤더를 붙인다: `<!-- tia-impact-comment -->` + `### TIA — 영향 테스트` (마커는
   이후 스티키 전환·중복 식별용 훅).
3. 게시는 **`scripts/pr-comment.sh`** 로 분리한다(테스트 가능성):
   - 입력: `BODY_FILE`(본문 파일), `PR_NUMBER`, `REPO`(owner/name), `GITHUB_TOKEN`
   - `gh api repos/$REPO/issues/$PR_NUMBER/comments -f body=@…` 로 새 코멘트 생성
   - `DRY_RUN=1`이면 API를 호출하지 않고 실행할 명령과 본문을 stdout으로 출력(수용 테스트 훅)
   - PR 컨텍스트가 아니면(`PR_NUMBER` 빈 값) 경고 후 exit 0 (게이트 아님 — 게시는 부가 기능)
4. Action 스텝은 `github.event.pull_request.number`를 `PR_NUMBER`로 전달하고, PR 이벤트가 아니면
   `::warning` 후 스킵한다. 게시 실패는 `::warning`으로 알리되 **스텝은 실패시키지 않는다**
   (선별 출력이 본질, 코멘트는 부가 — 침묵 스킵은 금지, 경고는 남긴다).

## 3. HTML 리포트 해석 가이드 내장

`tia-core/src/main/resources/report-template.html`에만 손댄다(모델/자바 코드 무변경).

- **탭별 가이드**: 5개 탭(per-test 영향범위·역인덱스·tia impact·flaky·blind spots) 각각의 상단에
  접힌 `<details class="tab-guide"><summary>이 탭 읽는 법</summary>…</details>` 블록을 추가.
  내용은 REPORT-GUIDE.md의 각 탭 해설을 3~5문장으로 요약(한국어): 무엇을 보여주는지, 어떻게
  읽는지, 무엇을 하면 되는지(행동 1줄).
- **빈 상태 안내**: 데이터가 빈 탭(예: flaky 미제공, scenarios 미제공)에 이미 graceful degrade가
  있으므로, 빈 상태 문구를 "왜 비었는지 + 채우려면 어떤 입력을 주면 되는지"로 보강한다
  (예: "flaky.json 미제공 — `tia flaky` 출력 또는 '-' 전달 시 이 탭은 비어 있습니다").
- **주의**: 가이드 텍스트는 정적 문자열이며 테스트가 부재를 단언하는 동적 값(testId·파일 경로)과
  겹치지 않아야 한다(기존 `ReportFilterE2ETest`의 절대-부재 단언 보존).

## 4. 에러 처리

- `pr-comment` 기본 'false' → 미사용자는 어떤 변화도 없음(action 하위호환).
- 코멘트 게시 실패(토큰 권한·네트워크): `::warning` + exit 0. `selected`/`run-all` 출력은 영향 없음.
- `pr-comment='true'`인데 PR 이벤트가 아님: `::warning` + 스킵.

## 5. 테스트 전략과 E2E/수용 명세

GitHub Actions 실행 환경은 로컬에서 재현 불가하므로, 실현 가능한 최고 레벨은
**스크립트 블랙박스 검증**(DRY_RUN) + **템플릿 렌더 검증**이다. Action YAML 자체의 배선은
수동 검토 + 실제 PR에서의 스모크(머지 후 이 레포 CI가 곧 소비자)로 본다 — 이 한계를 명시한다.

1. **pr-comment.sh 블랙박스** (e2e 모듈 Java 테스트, ProcessBuilder):
   - DRY_RUN=1 + 본문/PR번호 → stdout에 API 경로(`repos/o/r/issues/7/comments`)와 본문 포함,
     exit 0, 실제 네트워크 호출 없음
   - PR_NUMBER 빈 값 → 경고 + exit 0
   - 본문 파일 부재 → exit 1 (명확한 에러)
2. **markdown 본문 생성**: SP1의 `FormatE2ETest#impactMarkdownTableAndDetails`가 이미 커버
   (신규 테스트 불요 — 명시).
3. **템플릿 가이드 렌더** (`ReportBuilderTest` 케이스 추가): 렌더된 HTML에 5개 탭 가이드 마커
   (`tab-guide`)가 5회 존재, 대표 가이드 문구 1개 포함, 기존 테스트 무약화.
4. **하위호환**: `pr-comment` 미지정 시 action.yml의 기존 스텝 텍스트가 구조적으로 무변경
   (diff 검토로 확인; 기존 `selected`/`run-all` 소비 워크플로 무영향).

## 6. 리스크와 반론

- **코멘트 노이즈**: 매번 새 코멘트는 푸시가 잦은 PR에서 쌓인다 — 사용자가 의도적으로 선택
  (이력 추적 우선). 마커를 심어 두어 스티키 전환 여지는 남김.
- **Action 검증 한계**: composite step의 실배선은 로컬 검증 불가 — DRY_RUN 스크립트 검증 +
  머지 후 실PR 스모크로 보완(§5 명시). 반론: act 등 로컬 러너 도입은 유지비 대비 과함.
- **이중 실행 비용**: markdown용 CLI 재실행은 SQLite 조회 1회 추가 — 무시 가능. 단일 실행
  json 파생안은 text 파싱 경로 개조가 필요해 회귀 위험이 더 큼(기각 근거).

# TIA 사용성 개선 — 기반(SP1): tia.yml 설정 + 소비 필터 + 출력 포맷 설계

- 날짜: 2026-07-26
- 상태: 사용자 승인된 브레인스토밍 설계의 문서화 (3-벤더 리뷰 대상)
- 상위 맥락: 사용성 개선 전체는 5개 서브프로젝트로 분해되며, 본 문서는 그 첫 번째(SP1)의 설계다.

## 0. 배경과 전체 분해

TIA는 이미 CLI·Docker+GitHub Action·Gradle 플러그인·Agent Skill·인터랙티브 HTML 리포트라는
표면을 갖고 있으나, 사용자 피드백은 "진입(온보딩)과 소비(리포트 읽기·PR에서 보기)가 어렵고,
대상 코드·테스트를 거를 방법이 없다"는 것이다. 브레인스토밍에서 아래 5개 서브프로젝트로
분해하고 기반-우선 순서로 진행하기로 확정했다.

| SP | 내용 | 의존 |
|---|---|---|
| **SP1 (본 문서)** | `tia.yml` 공유 설정 + 소비 단계 include/exclude 필터 + `--format` 출력 계층(summary/json/markdown) | — |
| SP2 | 수집 단계 필터 전파 (Gradle 플러그인·pjacoco 에이전트 ← tia.yml) | SP1 |
| SP3 | 온보딩 명령 `tia init`/`tia doctor`/`tia demo` + GETTING-STARTED 재구성 | SP1 |
| SP4 | 에이전트 표면: 스킬 강화 + MCP 서버 (SP1의 JSON 출력을 소비) | SP1 |
| SP5 | HTML 리포트 내 해석 가이드 내장 + Action PR 코멘트(SP1의 Markdown 출력 게시) | SP1 |

SP1이 기반인 이유: MCP·스킬은 기계 판독 출력(JSON)을, PR 코멘트는 Markdown을, init은
tia.yml 스키마를 전제한다. 이 셋을 먼저 확정해야 나머지가 재작업 없이 쌓인다.

## 1. SP1 목표·비범위

**목표**

1. 레포 루트의 `tia.yml` 하나로 필터·기본값을 선언하고, 모든 소비 표면(CLI, 이후 스킬·MCP·Action)이 같은 로더로 읽는다.
2. 소비 시점(query-time)에 대상 코드·테스트를 include/exclude 필터링한다.
3. `impact`·`flaky`에 `--format text|summary|json|markdown`을 추가한다.

**비범위 (이후 SP)**

- 수집 단계 필터(에이전트 includes/excludes, Gradle DSL 전파) — SP2
- `init`/`doctor`/`demo`, 문서 재구성 — SP3
- 스킬·MCP 서버 변경 — SP4 (단, 본 설계의 JSON 스키마가 그 계약이 된다)
- HTML 리포트 신규 탭·해석 가이드, Action PR 코멘트 게시 — SP5

## 2. `tia.yml` 스키마와 해석 규칙

```yaml
# tia.yml — 레포 루트
version: 1                  # 필수. 미지원 값이면 exit 1
sut-name: my-service        # report --sut-name 기본값 (선택)
db: .tia/tia.db             # --db 기본값 (선택; 기존 내장 기본값보다 우선)
filters:                    # 전체 선택
  code:                     # 프로덕션 코드: 저장소-상대 파일 경로 글로브
    include: ["src/main/java/com/acme/**"]
    exclude: ["**/generated/**", "**/*Dto.java"]
  test:                     # 테스트: testId 글로브 (예: io/tia/fixture/ApiSmokeTest/testPrice)
    include: []             # 비면 전체
    exclude: ["**/*Slow*"]
```

**해석 규칙**

- **탐색:** `--config <path>` 명시가 최우선. 없으면 cwd에서 git 루트까지 상향 탐색으로
  `tia.yml`을 찾는다. 못 찾으면 "설정 없음" — 필터 없이 기존 기본값으로 동작한다
  (**완전 하위호환**: tia.yml이 없는 기존 사용자는 아무 변화도 겪지 않는다).
- **우선순위:** CLI 플래그 > `tia.yml` > 내장 기본값. 새 플래그
  `--include-code/--exclude-code/--include-test/--exclude-test`(반복 가능)는 tia.yml의
  해당 필터 목록을 **대체**한다(병합 아님 — 예측 가능성 우선. 예: 플래그로
  `--exclude-code`만 주면 code.exclude만 대체되고 code.include는 tia.yml 값 유지).
- **검증:** YAML 파싱 실패, 미지원 `version`, 알 수 없는 최상위 키, 글로브 문법 오류는
  모두 **즉시 exit 1** + 파일·위치·원인 메시지. 침묵 무시 금지.
- **구현 위치:** 로더·글로브 매칭은 `tia-core`(순수, TDD)에 두고 CLI가 사용한다. SP2의
  Gradle 플러그인, SP4의 MCP도 같은 로더를 재사용한다.
- 글로브 문법은 Java `FileSystem#getPathMatcher`의 `glob:` 문법(`**`, `*`, `?`)을 따른다.
  code 글로브는 저장소-상대 경로에, test 글로브는 `pkg/Class/method` 형태의 testId에 매칭한다.

## 3. 필터 의미론 (소비 단계, query-time)

**적용 지점: 조회 시점.** `index`는 지금처럼 전체를 저장하고, `impact`·`report`가 읽을 때
필터를 적용한다. 근거: tia.yml만 수정하면 재수집·재인덱싱 없이 결과가 바뀐다(소비 단계
필터의 취지). index에서 거르면 필터 변경마다 재인덱스가 필요해진다.

| 규칙 | 내용 |
|---|---|
| 기본 | `include` 비면 전체 포함. `exclude`가 `include`보다 우선 |
| code 필터 — impact | diff의 변경 파일에 적용. 제외 경로의 변경 라인은 판정에서 제거하고, 제거된 파일마다 stderr에 `# WARN: excluded change ignored: <path>` 1줄 출력 |
| code 필터 — report | 파일 축(역인덱스·blind spot 등)에 적용 |
| test 필터 — impact | 선별 결과에 적용. 제외 테스트는 DETERMINISTIC이어도 출력하지 않고, CONSERVATIVE 전체 선택 집합에서도 제외 |
| test 필터 — report·flaky | 테스트 축에 적용 |
| CONSERVATIVE 상호작용 | **포함된** 경로의 매핑 불가 변경(신규 파일·설정 등) → 기존대로 보수적 전체 선택(단, test 필터 적용 후 집합). 변경이 **전부 제외 경로**뿐이면 → 선별 0건 + WARN. 침묵으로 0건을 내지 않는다(거짓 "영향 없음" 면죄부 방지) |

**명시적 리스크 (문서·출력 양쪽에 고지):** `exclude`는 "이 경로/테스트는 TIA 판정 범위
밖"이라는 사용자 선언이다. 제외 경로의 회귀는 TIA가 잡아주지 않는다. 그래서 제외로 인해
변경이 무시될 때마다 WARN을 출력하고, summary·markdown 뷰에도 무시된 변경 파일 수를
표기한다.

## 4. 출력 포맷 계층

`impact`·`flaky`에 `--format <text|summary|json|markdown>` 옵션을 추가한다. 기본값
`text`는 **기존 출력과 바이트 단위로 동일**하게 유지한다(기존 스크립트·E2E가 파싱하므로).
exit code 의미는 포맷과 무관하게 동일하다.

- **`summary`** — 사람용 터미널 뷰. 내용: 선별/전체 테스트 카운트, DETERMINISTIC·CONSERVATIVE
  구분 집계, 변경 파일→선별 테스트 매핑 상위 목록, blind spot(선별 0건인 포함-경로 변경
  파일) 경고, 필터로 무시된 변경 파일 수, "다음에 할 일" 안내 1줄. TTY면 ANSI 색 사용,
  파이프면 자동 무색(`NO_COLOR` 존중).
- **`json`** — 버전드 기계 판독 스키마. SP4(MCP·스킬)의 소비 계약이므로 필드 제거·의미
  변경은 `schemaVersion` 증가로만 한다.

```json
{
  "schemaVersion": 1,
  "command": "impact",
  "commit": "<sha>",
  "appliedFilters": { "code": {"include": [], "exclude": []}, "test": {"include": [], "exclude": []} },
  "tests": [ { "id": "io/tia/fixture/ApiSmokeTest/testPrice", "confidence": "DETERMINISTIC", "reason": "covered-line intersects diff" } ],
  "ignoredChangedFiles": [ "gen/Foo.java" ],
  "warnings": [ "excluded change ignored: gen/Foo.java" ]
}
```

- **`markdown`** — PR 코멘트용. 요약 테이블(선별 수·확정/보수·무시된 변경) + `<details>`
  접힘 상세 목록. SP5의 Action이 이 출력을 그대로 게시한다.
- `flaky`의 json/markdown/summary도 동일 envelope(`command: "flaky"`, `tests[]`에
  flaky ratio 필드)로 통일한다.

## 5. 에러 처리

- tia.yml 오류(파싱·version·알 수 없는 키·글로브 문법)는 즉시 exit 1 + 원인/위치.
- `--format` 미지원 값은 picocli 표준 에러.
- 필터 결과가 공집합이어도 에러가 아니다 — 단 §3의 WARN 규칙으로 침묵을 금지한다.
- 경고는 전부 stderr, 데이터 출력은 stdout (json/markdown 파이프 소비를 깨지 않기 위해).

## 6. 테스트 전략과 E2E/수용 테스트 명세

**단위 (tia-core, 인너 루프 TDD):** 설정 로더(탐색·우선순위·검증 에러), 글로브 매칭
(code 경로·test id), 필터 교차 판정(exclude 우선·CONSERVATIVE 상호작용), 포맷터
(summary/json/markdown 골든 파일).

**CLI 수용 테스트:** `--format`별 골든 파일 비교, `text` 기본 출력 무변경 검증,
stderr/stdout 분리 검증, tia.yml 오류 exit 1 검증.

**E2E (아우터 루프 — 구현 전에 먼저 작성하고 red 확인):**

1. **필터 E2E:** fixture 레포에 `tia.yml`(test exclude로 `testGreeting` 제외)을 두고
   기존 in-process E2E 파이프라인 실행 → `impact` 결과에 exclude된 테스트가 절대
   나타나지 않음을 검증.
2. **JSON 계약 E2E:** `tia impact --format json` 출력을 `jq`로 파싱해 `schemaVersion`,
   `tests[].id`, `tests[].confidence` 필드 존재·값 검증.
3. **제외-변경 WARN E2E:** 제외 경로의 파일만 바꾼 diff로 `impact` 실행 → 선별 0건 +
   stderr WARN 존재 + exit 0 검증.
4. **하위호환 E2E:** tia.yml 없는 기존 E2E 스위트(`run-inprocess-e2e.sh`, 컨테이너 E2E)
   전부 무변경 green — 이것이 하위호환의 증거다.

완료 정의: 위 E2E 전부 green + 이후 작성될 요구사항명세 추적 매트릭스의 대상 REQ 100% green.

## 7. 리스크와 반론

- **exclude 오남용:** 넓은 exclude는 회귀 누출로 직결된다. WARN·문서 고지로 완화하지만
  원천 차단은 불가 — 사용자 선언을 신뢰하는 설계다(반대 관점: exclude 자체를 안 주는 게
  더 안전하나, 생성 코드·DTO 노이즈 때문에 필터 요구가 실재한다).
- **플래그 "대체" 의미론:** 병합을 기대하는 사용자가 있을 수 있다. `--help`와
  GETTING-STARTED에 대체 규칙을 명시한다.
- **JSON 스키마 조기 고정:** SP4 요구가 드러나며 스키마가 흔들릴 수 있다 —
  `schemaVersion`으로 흡수하고, SP1에서는 최소 필드만 확정한다.
- **text 출력 동결:** 기본 출력을 동결하면 text 뷰 개선이 막힌다 — 개선은 `summary`
  뷰가 담당하는 것으로 역할을 분리했다.

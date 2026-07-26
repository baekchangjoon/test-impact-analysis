# TIA 사용성 개선 — 기반(SP1): tia.yml 설정 + 소비 필터 + 출력 포맷 설계

- 날짜: 2026-07-26
- 상태: 브레인스토밍 승인 → 3-벤더 설계 리뷰(Claude Sonnet ×2 + Gemini) 소견 반영 완료
- 상위 맥락: 사용성 개선 전체는 5개 서브프로젝트로 분해되며, 본 문서는 그 첫 번째(SP1)의 설계다.

## 0. 배경과 전체 분해

TIA는 이미 CLI·Docker+GitHub Action·Gradle 플러그인·Agent Skill·인터랙티브 HTML 리포트라는
표면을 갖고 있으나, 사용자 피드백은 "진입(온보딩)과 소비(리포트 읽기·PR에서 보기)가 어렵고,
대상 코드·테스트를 거를 방법이 없다"는 것이다. 브레인스토밍에서 아래 5개 서브프로젝트로
분해하고 기반-우선 순서로 진행하기로 확정했다.

| SP | 내용 | 의존 |
|---|---|---|
| **SP1 (본 문서)** | `tia.yml` 공유 설정 + 소비 단계 include/exclude 필터 + `--format` 출력 계층(summary/json/markdown) | — |
| SP2 | 수집 단계 필터 전파 + Gradle 플러그인의 tia.yml 소비(`db` 기본값 주입 포함) | SP1 |
| SP3 | 온보딩 명령 `tia init`/`tia doctor`/`tia demo` + GETTING-STARTED 재구성 | SP1 |
| SP4 | 에이전트 표면: 스킬 강화 + MCP 서버 (SP1의 JSON 출력을 소비) | SP1 |
| SP5 | HTML 리포트 내 해석 가이드 내장 + Action PR 코멘트(SP1의 Markdown 출력 게시) | SP1 |

SP1이 기반인 이유: MCP·스킬은 기계 판독 출력(JSON)을, PR 코멘트는 Markdown을, init은
tia.yml 스키마를 전제한다. 이 셋을 먼저 확정해야 나머지가 재작업 없이 쌓인다.

**SP1↔SP2 경계 주의:** Gradle 플러그인(`TiaPlugin`)은 현재 `db` 속성이 없으면
`GradleException`을 던진다. tia.yml의 `db` 값을 플러그인이 읽어 기본값으로 주입하는 것은
**SP2 범위**다 — SP1의 tia.yml `db`는 **CLI에만** 적용된다(플러그인 사용자는 SP2 전까지
기존대로 build.gradle에 선언).

## 1. SP1 목표·비범위

**목표**

1. 레포 루트의 `tia.yml` 하나로 필터·기본값을 선언하고, CLI가 읽는다(이후 SP에서 스킬·MCP·Action·플러그인이 같은 계약을 소비).
2. 소비 시점(query-time)에 대상 코드·테스트를 include/exclude 필터링한다.
3. `impact`·`flaky`에 `--format text|summary|json|markdown`을 추가한다.

**비범위 (이후 SP)**

- 수집 단계 필터(에이전트 includes/excludes), Gradle 플러그인의 tia.yml 소비 — SP2
- `init`/`doctor`/`demo`, 문서 재구성 — SP3
- 스킬·MCP 서버 변경 — SP4 (단, 본 설계의 JSON 스키마가 그 계약이 된다)
- HTML 리포트 신규 탭·해석 가이드, Action PR 코멘트 게시 — SP5

## 2. `tia.yml` 스키마와 해석 규칙

```yaml
# tia.yml — 레포 루트
version: 1                  # 필수. 미지원 값이면 exit 1
sut-name: my-service        # report --sut-name 기본값 (선택)
# db: /shared/tia.db        # --db 기본값 (선택). 미선언 시 기존 git-common-dir 기본값
                            # (DbPaths.resolveDefault) 사용 — 워크트리-상대 경로는 워크트리 간
                            # DB 분열을 일으키므로 권장하지 않음 (GETTING-STARTED의 공유 DB 지침 참조)
filters:                    # 전체 선택
  code:                     # 프로덕션 코드: PathNormalizer.canonical 정규화 후의
    include: ["com/acme/**"]        # package-relative 경로 글로브 (src/main/java/ 접두어 없음!)
    exclude: ["**/generated/**", "**/*Dto.java"]
  test:                     # 테스트: 정규화된 testId 글로브 (아래 참조)
    include: []             # 비면 전체
    exclude: ["**/*Slow*"]
```

**글로브가 매칭되는 문자열 공간 (중요)**

- **code 글로브**는 저장소-상대 소스 경로가 아니라 **`PathNormalizer.canonical` 정규화 후의
  package-relative 경로**(예: `com/acme/pricing/PricingService.java`)에 매칭한다.
  diff 파싱(`GitDiffParser`)·인덱스·리포트의 파일 키가 모두 이 공간이기 때문이다.
  `src/main/java/com/acme/**` 같은 글로브는 아무것도 매칭하지 않는다 — 문서·`init`(SP3)에서
  이 함정을 명시한다.
- **test 글로브**는 매칭 직전에 testId를 정규화한다: `#`를 `/`로 치환한 문자열에 매칭.
  저장소가 산출하는 두 형태 모두를 커버하기 위함이다 —
  in-process는 `io/tia/fixture/ApiSmokeTest/testPrice`(패키지 포함),
  out-of-process(petclinic 스타일)는 `AuthApiBlackBoxIT#loginWithInvalidCredentialsReturns400`
  (패키지 없음, `#` 구분) → 정규화 후 `AuthApiBlackBoxIT/loginWithInvalidCredentialsReturns400`.
  글로브 작성 시 out-of-process id에는 패키지 세그먼트가 없다는 점을 문서에 명시한다.
- 글로브 문법은 `**`/`*`/`?`를 지원하는 glob 문법이되, **OS와 무관하게 `/` 구분자 기준으로
  매칭**한다(플랫폼 기본 FileSystem의 separator에 의존하지 않도록 Unix-style 문법으로
  matcher를 고정 생성). 이 어휘 밖의 문법(짝 안 맞는 `[`, `{a,b}` 브레이스 확장 등)은
  "글로브 문법 오류"로 보고 fail-fast 대상이다(§2 검증 규칙).

**해석 규칙**

- **탐색:** `--config <path>` 명시가 최우선이며, 이때 **상향 탐색은 완전히 우회**한다
  (상위 디렉터리의 깨진 tia.yml이 있어도 파싱·검증하지 않는다). `--config`가 없으면
  cwd에서 git 루트까지 상향 탐색으로 `tia.yml`을 찾는다. 못 찾으면 "설정 없음" — 필터
  없이 기존 기본값으로 동작한다(**완전 하위호환**: tia.yml이 없는 기존 사용자는 아무
  변화도 겪지 않는다).
- **탐색 시작점 시임(테스트 가능성):** 로더는 탐색 시작 디렉터리를 **명시적 파라미터**로
  받는다(프로덕션에서만 실제 cwd가 기본값). 기본 `:e2e:test`는 직렬이지만 별도 태그
  태스크는 JUnit 병렬을 켜므로, JVM 전역 cwd에 의존하지 않는 시임이 안전하다(기존
  `runGitDiff(ref, workingDir)` 패턴과 동일한 접근). 상대 `--config` 경로도 cwd가 아닌
  탐색 시작점 기준으로 해석한다.
- **상대 경로 해석:** tia.yml 안의 상대 경로(`db` 등)는 cwd가 아니라 **tia.yml이 있는
  디렉터리 기준**으로 해석한다(하위 디렉터리에서 실행해도 같은 파일을 가리키도록).
- **우선순위:** CLI 플래그 > `tia.yml` > 내장 기본값. 새 플래그
  `--include-code/--exclude-code/--include-test/--exclude-test`(반복 가능)는 tia.yml의
  해당 필터 목록을 **대체**한다(병합 아님 — 예측 가능성 우선. 예: 플래그로
  `--exclude-code`만 주면 code.exclude만 대체되고 code.include는 tia.yml 값 유지).
- **검증:** YAML 파싱 실패, 미지원 `version`, 알 수 없는 최상위 키, 글로브 문법 오류는
  모두 **즉시 exit 1** + 파일·위치·원인 메시지. 침묵 무시 금지.
  (리뷰 반론 검토: "알 수 없는 키는 경고로 완화" 제안은 기각 — 같은 version에서 알 수 없는
  키는 거의 항상 오타이며, 스키마 확장은 `version` 증가로 흡수한다.)
- **YAML 파서:** `jackson-dataformat-yaml`을 `tia-core`에 추가한다(이미 jackson-databind를
  쓰므로 같은 계열). 의존성 추가에 따라 `THIRD-PARTY-NOTICES.md`·`licenses/`·SBOM 반영을
  구현 작업에 포함한다.
- **구현 위치:** 로더·글로브 매칭은 `tia-core`(순수, TDD)에 두고 CLI가 사용한다. SP2의
  Gradle 플러그인 등 **JVM 표면**이 이 로더를 재사용한다. SP4의 MCP 서버는 로더를 직접
  쓰지 않고 **CLI를 서브프로세스로 실행해 `--format json` 출력을 소비**하는 것을 계약으로
  한다(비-JVM 구현 가능성 확보).

**CLI 옵션 배선 (커맨드 × 신규 옵션)**

| 옵션 | impact | flaky | report | index | convert |
|---|---|---|---|---|---|
| `--config <path>` | ✔ | ✔ | ✔ | ✔ (db 기본값만 소비) | — (tia.yml에서 소비할 값이 없음) |
| `--include-code/--exclude-code` | ✔ | — | ✔ | — | — |
| `--include-test/--exclude-test` | ✔ | ✔ | ✔ | — | — |
| `--format` | ✔ | ✔ | — (HTML 전용) | — | — |

공통 옵션은 picocli `@Mixin`으로 선언해 커맨드별 중복을 피하되, `@Mixin`은 옵션 선택
배제가 불가하므로 **축별 3개 믹스인**(Config: `--config`/`--search-root` · CodeFilter ·
TestFilter)으로 분할하고 커맨드별로 위 표에 맞게 조합한다(index는 Config만, flaky는
Config+TestFilter).

## 3. 필터 의미론 (소비 단계, query-time)

**적용 지점: 조회 시점.** `index`는 지금처럼 전체를 저장하고, `impact`·`report`·`flaky`가
읽을 때 필터를 적용한다. 근거: tia.yml만 수정하면 재수집·재인덱싱 없이 결과가 바뀐다(소비
단계 필터의 취지). index에서 거르면 필터 변경마다 재인덱스가 필요해진다.

| 규칙 | 내용 |
|---|---|
| 기본 | `include` 비면 전체 포함. `exclude`가 `include`보다 우선 |
| include의 적용 범위 | `code.include`는 **매핑 가능한 코드 경로**(`changedOldLinesByJavaFile`·`additionOnlyJavaFiles`의 `.java` 키)에만 적용한다. **`unmappableFiles`(build.gradle·설정 등 비-Java)는 include 판정을 우회**하며 명시적 `exclude` 매칭으로만 제거된다 — include가 좁다고 빌드/설정 변경이 침묵 무시되어 CONSERVATIVE 안전망이 깨지는 것을 막기 위함 |
| code 필터 — impact | **`DiffSummary`의 세 필드 모두**(`changedOldLinesByJavaFile`·`additionOnlyJavaFiles`·`unmappableFiles`)에서 제외 항목을 제거한 뒤 `ImpactAnalyzer.select()`를 호출한다(단 include의 적용 범위는 위 행). 이래야 "전부 제외 → 0건" 규칙이 성립한다(신규파일·매핑불가 필드가 남으면 CONSERVATIVE가 잘못 발동). 제거된 파일마다 stderr에 `# WARN: excluded change ignored: <path>` 1줄 출력. 역으로, **필터가 존재하되 매칭되지 않는** unmappable 변경은 필터 없음과 동일하게 CONSERVATIVE 전체 선택을 발동한다 |
| code 필터 — report | 파일 축(역인덱스·blind spot 등)에 적용 |
| test 필터 — impact | 선별 결과에 적용. 제외 테스트는 **Confidence 값과 무관하게**(DETERMINISTIC·LOW_CONFIDENCE·CONSERVATIVE 일괄) 출력하지 않고, CONSERVATIVE 전체 선택 집합에서도 제외 |
| test 필터 — flaky | **집계 전에** 제외 테스트를 run-result에서 제거한다 — 전체 ratio의 분모(totalTests)·분자(flakyTests) 모두 필터 적용 후 집합으로 계산. 필터 후 집합이 비면 ratio는 `0.0`으로 정의하고 stderr에 경고를 낸다(0-나눗셈/NaN 금지) |
| test 필터 — report | 테스트 축에 적용 |
| CONSERVATIVE 상호작용 | **포함된** 경로의 매핑 불가 변경(신규 파일·설정 등) → 기존대로 보수적 전체 선택(단, test 필터 적용 후 집합). 변경이 **전부 제외 경로**뿐이면 → 선별 0건 + WARN. 침묵으로 0건을 내지 않는다(거짓 "영향 없음" 면죄부 방지) |

**`ReportCommand`의 필터 적용 방식:** report는 DB 조회가 아니라 파일 입력
(`--testwise`/`--scenarios`/`--flaky`/`--prod-files`)을 소비한다. 따라서 report의 필터는
**파싱된 testwise·prod-files 데이터를 렌더링 직전에 인프로세스로 거르는** 방식으로
적용한다(입력 파일은 무변경). 테스트 행 제거뿐 아니라 살아남은 테스트의 파일 목록·역인덱스
축에도 code 필터를 적용해야 제외 파일이 HTML에 남지 않는다. **flaky 탭은 SP1 필터 대상에서
제외**한다 — `--flaky` 입력이 스키마 없는 opaque 구조로 파싱되기 때문(명시적 descope).
이를 위해 report에도 `--config`·필터 옵션을 추가한다(§2 표).

**명시적 리스크 (문서·출력 양쪽에 고지):** `exclude`는 "이 경로/테스트는 TIA 판정 범위
밖"이라는 사용자 선언이다. 제외 경로의 회귀는 TIA가 잡아주지 않는다. 그래서 제외로 인해
변경이 무시될 때마다 WARN을 출력하고, summary·markdown 뷰에도 무시된 변경 파일 수를
표기한다.

## 4. 출력 포맷 계층

`impact`·`flaky`에 `--format <text|summary|json|markdown>` 옵션을 추가한다. 기본값
`text`는 **기존 출력과 바이트 단위로 동일**하게 유지한다(기존 스크립트·E2E가 파싱하므로).
exit code 의미는 포맷과 무관하게 동일하다.

- **`summary`** — 사람용 터미널 뷰. 내용: 선별/전체 테스트 카운트, Confidence별 구분 집계,
  변경 파일→선별 테스트 매핑 상위 목록, blind spot(선별 0건인 포함-경로 변경 파일) 경고,
  필터로 무시된 변경 파일 수, "다음에 할 일" 안내 1줄. TTY면 ANSI 색 사용, 파이프면 자동
  무색(`NO_COLOR` 존중).
- **`json`** — 버전드 기계 판독 스키마. SP4(MCP·스킬)의 소비 계약이므로 필드 제거·의미
  변경은 `schemaVersion` 증가로만 한다. `tests[].reason`은 Confidence→고정 문자열
  매핑으로 채운다(현 코어 모델에 per-test 사유가 없으므로 — DETERMINISTIC→
  "covered-line intersects diff", CONSERVATIVE→"conservative select-all",
  LOW_CONFIDENCE→"low-confidence").

impact:

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

flaky — **현행 `FlakyReport` 모델(집계 ratio + boolean 분류)만 사용**한다. per-test
flip-rate는 현 모델에 없으므로 SP1 범위에서 제외하고(코어 확장 필요 시 별도 SP), `commit`
필드는 flaky에 개념이 없으므로 생략한다:

```json
{
  "schemaVersion": 1,
  "command": "flaky",
  "appliedFilters": { "test": {"include": [], "exclude": []} },
  "ratio": 0.333,
  "totalTests": 3,
  "flakyTests": [ "io/tia/fixture/ApiSmokeTest/testFlaky" ],
  "warnings": []
}
```

- **`markdown`** — PR 코멘트용. 요약 테이블(선별 수·Confidence별·무시된 변경) + `<details>`
  접힘 상세 목록. SP5의 Action이 이 출력을 그대로 게시한다.

## 5. 에러 처리·출력 스트림 규약

- tia.yml 오류(파싱·version·알 수 없는 키·글로브 문법)는 즉시 exit 1 + 원인/위치.
- `--format` 미지원 값은 picocli 표준 에러.
- 필터 결과가 공집합이어도 에러가 아니다 — 단 §3의 WARN 규칙으로 침묵을 금지한다.
- **스트림 규약:** 신규 경고(WARN)는 전부 stderr, 데이터 출력은 stdout(json/markdown 파이프
  소비를 깨지 않기 위해). 단, **기존 `# 주의:` 라인(CONSERVATIVE 사유)과 `# tia:no-baseline`
  마커는 동결된 text 포맷 계약의 일부이므로 stdout에 그대로 남는다** — 구채널(text 계약)과
  신채널(필터 WARN)을 의도적으로 분리한 것이며 통일하지 않는다.

## 6. 테스트 전략과 E2E/수용 테스트 명세

**단위 (tia-core, 인너 루프 TDD):** 설정 로더(탐색·우선순위·검증 에러), 글로브 매칭
(code 정규화 경로·testId `#` 정규화·두 id 형태), 필터 교차 판정(exclude 우선·
`DiffSummary` 3필드 필터링·"제외 신규 파일만 있는 diff"·"제외 비-.java 파일만 있는 diff"·
CONSERVATIVE 상호작용), 포맷터(summary/json/markdown 골든 파일).

**CLI 수용 테스트:** `--format`별 골든 파일 비교, `text` 기본 출력 무변경 검증,
stderr/stdout 분리 검증, tia.yml 오류 exit 1 검증.

**E2E (아우터 루프 — 구현 전에 먼저 작성하고 red 확인):** 신규 E2E는 impact/flaky의 text
계약을 실제로 파싱하는 기존 `e2e/src/test/java/io/tia/e2e/SpecAcceptanceE2ETest.java`와
같은 수준(인프로세스 picocli 구동)에 둔다.

1. **필터 E2E (in-process id):** fixture에 `tia.yml`(test exclude로 `testGreeting` 제외)을
   두고 파이프라인 실행 → `impact` 결과에 제외 테스트가 절대 나타나지 않음을 검증.
2. **필터 E2E (out-of-process id 형태):** `Class#method` 형태 testId를 담은 testwise
   픽스처(petclinic-demo 형태)로 test 글로브가 `#` 정규화를 거쳐 매칭됨을 검증.
3. **JSON 계약 E2E:** `tia impact --format json` 출력을 파싱해 `schemaVersion`,
   `tests[].id`, `tests[].confidence` 필드 존재·값 검증.
4. **제외-변경 WARN E2E:** 제외 경로의 파일만 바꾼 diff로 `impact` 실행 → 선별 0건 +
   stderr WARN 존재 + exit 0 검증.
5. **하위호환 E2E:** tia.yml 없이 — ① `SpecAcceptanceE2ETest`(impact/flaky text 출력을
   구조적으로 파싱하는 실질 가드) 무변경 green ② 수집 경로 스크립트
   (`scripts/run-inprocess-e2e.sh`, 컨테이너 E2E) 무변경 green.

완료 정의: 위 E2E 전부 green + 이후 작성될 요구사항명세 추적 매트릭스의 대상 REQ 100% green.

## 7. 리스크와 반론

- **exclude 오남용:** 넓은 exclude는 회귀 누출로 직결된다. WARN·문서 고지로 완화하지만
  원천 차단은 불가 — 사용자 선언을 신뢰하는 설계다(반대 관점: exclude 자체를 안 주는 게
  더 안전하나, 생성 코드·DTO 노이즈 때문에 필터 요구가 실재한다).
- **글로브 공간의 학습 비용:** package-relative 매칭은 소스 경로 직관과 다르다 — 문서·
  SP3 `init`이 생성하는 주석으로 완화하고, "매칭 0건 + diff에 변경 존재" 상황에서 경로
  공간 힌트를 stderr로 안내하는 것을 구현 시 고려한다.
- **플래그 "대체" 의미론:** 병합을 기대하는 사용자가 있을 수 있다. `--help`와
  GETTING-STARTED에 대체 규칙을 명시한다.
- **JSON 스키마 조기 고정:** SP4 요구가 드러나며 스키마가 흔들릴 수 있다 —
  `schemaVersion`으로 흡수하고, SP1에서는 최소 필드만 확정한다.
- **text 출력 동결:** 기본 출력을 동결하면 text 뷰 개선이 막힌다 — 개선은 `summary`
  뷰가 담당하는 것으로 역할을 분리했다.

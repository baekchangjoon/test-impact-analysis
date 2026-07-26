# TIA 사용성 개선 기반(SP1) 요구사항명세
> 출처(design spec): docs/superpowers/specs/2026-07-26-usability-foundation-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 요구사항 목록

### REQ-001 — tia.yml 탐색과 부재 시 무변화
- 유형: Functional
- 우선순위: Must
- 설명: `--config <path>`가 최우선이며 이때 상향 탐색을 완전히 우회한다(상위의 깨진 tia.yml도 파싱하지 않음). 없으면 탐색 시작 디렉터리에서 git 루트까지 상향 탐색한다. 못 찾으면 필터 없이 기존 기본값으로 동작한다. 로더는 탐색 시작 디렉터리를 명시적 파라미터로 받는다(프로덕션만 실제 cwd 기본값 — e2e 병렬 실행에서 JVM cwd 의존 없이 테스트하기 위한 시임).
- 수용기준:
  - Given `--config`로 명시한 파일과 탐색 경로 상의 **깨진** `tia.yml`이 공존할 때, When `tia impact --config <path>` 실행, Then 명시한 파일만 적용되고 깨진 파일은 파싱·검증되지 않는다(에러 없음).
  - Given 하위 디렉터리(탐색 시작점 파라미터)와 git 루트의 `tia.yml`, When `--config` 없이 실행, Then 루트의 `tia.yml`이 발견·적용된다.
  - Given `tia.yml`이 어디에도 없음, When 기존 명령 실행, Then 출력·exit code가 도입 전과 동일하다.
- 검증 레벨: E2E black-box (인프로세스 picocli 구동, `SpecAcceptanceE2ETest` 수준)

### REQ-002 — CLI 플래그의 목록 단위 대체 우선순위
- 유형: Functional
- 우선순위: Must
- 설명: `--include-code/--exclude-code/--include-test/--exclude-test`(반복 가능)는 tia.yml의 해당 필터 목록을 병합이 아니라 **대체**한다. 주지 않은 목록은 tia.yml 값이 유지된다.
- 수용기준:
  - Given tia.yml에 code.include·code.exclude가 모두 선언됨, When `--exclude-code` 플래그만 주고 실행, Then code.exclude는 플래그 값으로 대체되고 code.include는 tia.yml 값이 유지된다.
- 검증 레벨: E2E black-box

### REQ-003 — tia.yml 검증 실패 시 fail-fast
- 유형: Functional
- 우선순위: Must
- 설명: YAML 파싱 실패, 미지원 `version`, 알 수 없는 최상위 키, 글로브 문법 오류는 즉시 exit 1과 파일·위치·원인 메시지를 낸다.
- 수용기준:
  - Given 깨진 YAML/`version: 99`/오타 키(`filtres:`)가 든 tia.yml 각각, When 아무 소비 명령 실행, Then exit 1이고 stderr에 파일 경로와 원인이 포함된다.
  - Given 지원 어휘(`**`/`*`/`?`) 밖의 글로브(예: 짝 안 맞는 `[`)가 든 tia.yml, When 소비 명령 실행, Then exit 1이고 stderr에 문제의 글로브가 명시된다.
- 검증 레벨: E2E black-box

### REQ-004 — code 글로브는 정규화(package-relative) 경로에 매칭
- 유형: Functional
- 우선순위: Must
- 설명: code 필터 글로브는 `PathNormalizer.canonical` 정규화 후 경로(예: `com/acme/**`)에 매칭한다. `src/main/java/` 접두어 글로브는 매칭되지 않는다.
- 수용기준:
  - Given `com/acme/**` exclude와 `src/main/java/com/acme/Foo.java`를 바꾼 diff, When `tia impact` 실행, Then 그 파일 변경은 제외 처리된다(정규화 키 `com/acme/Foo.java`에 매칭).
- 검증 레벨: E2E black-box

### REQ-005 — test 글로브의 testId `#` 정규화 (두 id 형태 지원)
- 유형: Functional
- 우선순위: Must
- 설명: test 글로브는 매칭 직전 testId의 `#`를 `/`로 치환한 문자열에 매칭한다. in-process(`pkg/Class/method`)와 out-of-process(`Class#method`) 두 형태 모두 동작한다.
- 수용기준:
  - Given `AuthApiBlackBoxIT/**` exclude와 `AuthApiBlackBoxIT#login...` 형태 testId가 든 인덱스, When `tia impact` 실행, Then 해당 테스트가 제외된다.
- 검증 레벨: E2E black-box

### REQ-006 — include/exclude 기본 의미론
- 유형: Functional
- 우선순위: Must
- 설명: `include`가 비면 전체 포함이고, `exclude`는 `include`보다 우선한다.
- 수용기준:
  - Given 같은 항목이 include와 exclude 양쪽에 매칭됨, When 필터 평가, Then 그 항목은 제외된다.
  - Given include·exclude 모두 빈 목록, When 필터 평가, Then 모든 항목이 포함된다.
- 검증 레벨: unit (tia-core; E2E들의 전제로도 간접 검증)

### REQ-007 — impact code 필터의 DiffSummary 전면 적용 + WARN + CONSERVATIVE 보존
- 유형: Functional
- 우선순위: Must
- 설명: code 필터는 `DiffSummary`의 `changedOldLinesByJavaFile`·`additionOnlyJavaFiles`·`unmappableFiles` 세 필드 모두에서 제외 항목을 제거한 뒤 판정하며, 제거된 파일마다 stderr에 `# WARN: excluded change ignored: <path>` 1줄을 낸다. 단 `code.include`는 매핑 가능한 `.java` 경로에만 적용되고 `unmappableFiles`(build.gradle 등 비-Java)는 include 판정을 우회한다(명시적 exclude로만 제거). 필터가 존재해도 매칭되지 않는 변경의 CONSERVATIVE 발동은 필터 없음과 동일하다.
- 수용기준:
  - Given 제외 경로의 **신규 파일만** 있는 diff, When `tia impact`, Then CONSERVATIVE 전체 선택이 발동하지 않는다.
  - Given 제외 경로 파일이 섞인 diff, When `tia impact`, Then 제거된 파일 수만큼 WARN 라인이 stderr에 나온다.
  - Given 좁은 `code.include`(예: `com/acme/**`)만 설정된 tia.yml과 `build.gradle`을 바꾼 diff, When `tia impact`, Then include 미매칭에도 불구하고 그 변경은 무시되지 않고 CONSERVATIVE 전체 선택이 발동한다.
  - Given 어떤 변경에도 매칭되지 않는 exclude가 있는 tia.yml과 unmappable 변경 diff, When `tia impact`, Then 필터 없음과 동일하게 CONSERVATIVE 전체 선택이 발동한다(test 필터 적용 후 집합).
- 검증 레벨: E2E black-box + unit(3필드 필터링: `DiffSummaryFilterTest#filtersAllThreeFields`)

### REQ-008 — 전부-제외 diff는 0건 + WARN + exit 0
- 유형: Functional
- 우선순위: Must
- 설명: 변경이 전부 제외 경로뿐이면 선별 0건과 WARN을 내고 exit 0으로 끝난다(침묵 0건 금지).
- 수용기준:
  - Given 제외 경로의 파일만 바꾼 diff, When `tia impact`, Then 선별 결과 0건, stderr에 WARN ≥1줄, exit 0.
- 검증 레벨: E2E black-box

### REQ-009 — impact test 필터는 모든 Confidence에 일괄 적용
- 유형: Functional
- 우선순위: Must
- 설명: 제외된 테스트는 DETERMINISTIC·LOW_CONFIDENCE·CONSERVATIVE 어느 값이든 출력되지 않으며, CONSERVATIVE 전체 선택 집합에서도 빠진다.
- 수용기준:
  - Given 제외 글로브에 걸리는 테스트가 DETERMINISTIC으로 선별될 diff, When `tia impact`, Then 그 테스트는 출력에 없다.
  - Given CONSERVATIVE 전체 선택을 유발하는 diff, When `tia impact`, Then 전체 선택 목록에도 제외 테스트가 없다.
- 검증 레벨: E2E black-box

### REQ-010 — flaky test 필터의 집계 전 적용
- 유형: Functional
- 우선순위: Must
- 설명: flaky는 제외 테스트를 run-result 집계 **전에** 제거하고, 전체 ratio의 분모·분자를 필터 후 집합으로 계산한다. 필터 후 집합이 비면 ratio는 `0.0`이고 stderr 경고를 낸다(0-나눗셈/NaN 금지).
- 수용기준:
  - Given flaky한 테스트 1개가 제외 글로브에 걸리는 run-result들, When `tia flaky`, Then 그 테스트는 목록에 없고 ratio·totalTests가 필터 후 집합 기준으로 계산된다.
  - Given 모든 테스트가 제외되는 필터, When `tia flaky`, Then ratio `0.0`·totalTests `0`이 출력되고 stderr에 경고가 있으며 예외가 발생하지 않는다.
- 검증 레벨: E2E black-box

### REQ-011 — report의 인프로세스 필터링
- 유형: Functional
- 우선순위: Must
- 설명: report는 파일 입력(testwise·prod-files)을 파싱한 뒤 렌더링 직전에 code/test 필터를 적용한다(입력 파일 무변경). 필터는 테스트 행뿐 아니라 **살아남은 테스트의 파일 목록과 역인덱스 축**에도 적용된다. **flaky 탭은 SP1 필터 대상에서 제외** — `--flaky` 입력이 스키마 없는 opaque 구조로 파싱되기 때문(명시적 descope).
- 수용기준:
  - Given 제외 테스트·제외 코드 경로가 든 testwise/prod-files, When `tia report` 실행, Then 생성된 HTML에 제외 테스트가 나타나지 않고, 제외 파일이 역인덱스·per-test 파일 목록에도 나타나지 않으며, 입력 파일은 바뀌지 않는다.
- 검증 레벨: E2E black-box (HTML 내용 검사)

### REQ-012 — 기본 text 출력 동결 (byte-identical)
- 유형: Non-functional (하위호환 계약)
- 우선순위: Must
- 설명: `--format` 기본값 text의 출력은 기존과 바이트 단위로 동일하다. 기존 `# 주의:`·`# tia:no-baseline` stdout 라인도 그대로다.
- 수용기준:
  - Given tia.yml·신규 플래그 없이 기존 시나리오, When impact/flaky 실행, Then 도입 전 출력과 바이트 동일(기존 `SpecAcceptanceE2ETest`의 구조 파싱이 무변경 통과).
- 검증 레벨: E2E black-box (기존 `SpecAcceptanceE2ETest` 무변경 green)

### REQ-013 — impact `--format json` 계약
- 유형: Functional
- 우선순위: Must
- 설명: impact의 json 출력은 `schemaVersion`·`command`·`commit`·`appliedFilters`·`tests[]{id,confidence,reason}`·`ignoredChangedFiles`·`warnings` 필드를 가진 버전드 스키마다. `reason`은 Confidence→고정 문자열 매핑(DETERMINISTIC→"covered-line intersects diff" 등)으로 채운다(코어 모델 무변경).
- 수용기준:
  - Given 인덱싱된 db와 diff, When `tia impact --format json`, Then stdout이 유효한 JSON이고 위 필드가 존재하며 `schemaVersion == 1`.
- 검증 레벨: E2E black-box

### REQ-014 — flaky `--format json` 계약
- 유형: Functional
- 우선순위: Must
- 설명: flaky의 json 출력은 `schemaVersion`·`command`·`appliedFilters`·`ratio`·`totalTests`·`flakyTests[]`·`warnings`를 가진다. per-test ratio와 `commit`은 없다.
- 수용기준:
  - Given run-result 파일들, When `tia flaky --format json`, Then 위 필드가 존재하고 `commit` 키가 없다.
- 검증 레벨: E2E black-box

### REQ-015 — summary 터미널 뷰
- 유형: Functional
- 우선순위: Must
- 설명: `--format summary`는 선별/전체 카운트, Confidence별 집계, 파일→테스트 매핑 상위 목록, blind spot 경고, 무시된 변경 파일 수, 다음 행동 1줄을 사람용으로 출력한다. TTY에서만 ANSI 색, `NO_COLOR` 존중.
- 수용기준:
  - Given 선별·blind spot·필터 무시가 모두 존재하는 diff, When `tia impact --format summary`(파이프), Then 카운트·매핑·다음 행동 문구·blind spot 경고·무시된 변경 파일 수가 모두 있고 ANSI 이스케이프가 없다.
- 검증 레벨: E2E black-box

### REQ-016 — markdown 뷰 (PR 코멘트 계약)
- 유형: Functional
- 우선순위: Must
- 설명: `--format markdown`은 요약 테이블(선별 수·Confidence별·무시된 변경)과 `<details>` 접힘 상세를 출력한다.
- 수용기준:
  - Given 선별·필터 무시가 존재하는 diff, When `tia impact --format markdown`, Then Markdown 테이블 행(선별 수·Confidence별·무시된 변경 수 포함)과 `<details>` 블록이 stdout에 있다.
- 검증 레벨: E2E black-box

### REQ-017 — 스트림 규약 (신규 경고 stderr / 데이터 stdout)
- 유형: Non-functional (계약)
- 우선순위: Must
- 설명: 신규 WARN은 전부 stderr, 포맷 데이터 출력은 전부 stdout이다(파이프 소비 보장).
- 수용기준:
  - Given WARN이 발생하는 필터 시나리오, When `tia impact --format json 2>/dev/null`, Then stdout만으로 유효한 JSON 파싱이 된다.
  - Given 같은 시나리오, When 기본 `--format text`로 실행, Then 신규 WARN은 stderr에만 있고 기존 `# 주의:`·`# tia:no-baseline` stdout 라인은 영향받지 않는다(REQ-012와 정합).
- 검증 레벨: E2E black-box

### REQ-018 — exit code의 포맷 독립성
- 유형: Non-functional (계약)
- 우선순위: Must
- 설명: 같은 시나리오라면 `--format` 값과 무관하게 exit code가 동일하다. `impact`·`flaky` 두 커맨드 모두에 적용된다.
- 수용기준:
  - Given 동일 입력, When `impact`를 text/summary/json/markdown 각각으로 실행, Then 네 실행의 exit code가 같다.
  - Given 동일 입력, When `flaky`를 네 포맷 각각으로 실행, Then 네 실행의 exit code가 같다.
- 검증 레벨: E2E black-box

### REQ-019 — 기존 스위트 무변경 하위호환
- 유형: Non-functional (회귀)
- 우선순위: Must
- 설명: tia.yml 없는 기존 사용 경로는 아무 변화가 없다 — 기존 E2E 스위트가 무변경으로 green이다.
- 수용기준:
  - Given SP1 구현 완료 상태, When `SpecAcceptanceE2ETest`·`scripts/run-inprocess-e2e.sh`·컨테이너 E2E 실행, Then 전부 무변경 green.
- 검증 레벨: E2E black-box (기존 스위트)

### REQ-020 — YAML 의존성의 라이선스 고지 동기화
- 유형: Non-functional (컴플라이언스)
- 우선순위: Must
- 설명: `jackson-dataformat-yaml`(및 전이 의존 snakeyaml) 추가 시 `THIRD-PARTY-NOTICES.md`·`licenses/`·SBOM에 반영한다.
- 수용기준:
  - Given SP1 구현 완료 상태, When THIRD-PARTY-NOTICES.md·SBOM 산출 확인, Then 신규 의존성과 라이선스가 기재되어 있다.
- 검증 레벨: build/docs 게이트 (PR 전 점검; 자동 테스트 아님)

### REQ-021 — 글로브 매칭의 OS 독립성
- 유형: Non-functional
- 우선순위: Must
- 설명: 글로브 매칭은 플랫폼 FileSystem separator에 의존하지 않고 `/` 구분 문자열 기준으로 동작한다.
- 수용기준:
  - Given `/` 구분 경로·testId 문자열, When matcher 생성·매칭, Then 기본 FileSystem이 아닌 고정 Unix-style 문법으로 일관 매칭된다(단위 테스트로 고정).
- 검증 레벨: unit (tia-core)

### REQ-022 — CLI 옵션 배선 (커맨드 × 옵션 표 준수)
- 유형: Functional
- 우선순위: Should — 신규 행위가 아닌 횡단 일관성 점검이라 Should로 두되, 연기하지 않고 분모에 포함한다
- 설명: design spec §2 표대로 각 커맨드에 `--config`·필터·`--format` 옵션이 배선되고(@Mixin), 표에 없는 조합은 노출되지 않는다. `convert`에는 `--config`를 배선하지 않는다(tia.yml에서 소비할 값이 없음).
- 수용기준:
  - Given 각 서브커맨드, When `--help` 출력 확인, Then 표에 명시된 옵션이 존재하고 표 밖 조합이 없다(예: report에 `--format` 없음, convert에 `--config` 없음).
- 검증 레벨: CLI acceptance (picocli usage 검사)

### REQ-023 — tia.yml `db` 기본값의 CLI 적용
- 유형: Functional
- 우선순위: Must
- 설명: tia.yml의 `db`는 `index`/`impact`의 `--db` 기본값으로 적용된다(플래그 명시가 항상 우선). 상대 경로는 tia.yml이 있는 디렉터리 기준으로 해석한다. `db` 미선언 시 기존 git-common-dir 기본값(`DbPaths.resolveDefault`)이 유지된다. Gradle 플러그인 주입은 SP2 범위.
- 수용기준:
  - Given tia.yml에 상대 경로 `db`가 선언됨, When 하위 디렉터리에서 `--db` 없이 `tia index`/`tia impact` 실행, Then tia.yml 디렉터리 기준으로 해석된 같은 db 파일이 사용된다.
  - Given `--db` 플래그와 tia.yml `db`가 둘 다 있음, When 실행, Then 플래그 값이 이긴다.
  - Given tia.yml에 `db` 미선언, When 실행, Then 기존 git-common-dir 기본값이 사용된다.
- 검증 레벨: E2E black-box

### REQ-024 — tia.yml `sut-name` 기본값의 report 적용
- 유형: Functional
- 우선순위: Must
- 설명: tia.yml의 `sut-name`은 report의 `--sut-name` 기본값으로 적용된다(플래그 명시가 항상 우선).
- 수용기준:
  - Given tia.yml에 `sut-name`이 선언됨, When `--sut-name` 없이 `tia report` 실행, Then 그 값이 리포트에 반영된다.
  - Given 플래그와 tia.yml이 둘 다 있음, When 실행, Then 플래그 값이 이긴다.
- 검증 레벨: E2E black-box

### REQ-025 — 사용자 문서의 필터 규칙 반영 (docs 게이트)
- 유형: Non-functional (문서)
- 우선순위: Must
- 설명: GETTING-STARTED(및 관련 `--help` 텍스트)에 ① 플래그의 "목록 단위 대체(병합 아님)" 규칙 ② code 글로브가 package-relative 정규화 공간에 매칭된다는 함정 ③ exclude는 "TIA 범위 밖 선언"이라는 리스크를 명시한다(design spec §7 완화책의 이행).
- 수용기준:
  - Given SP1 구현 완료 상태, When GETTING-STARTED와 신규 옵션 `--help` 텍스트 확인, Then 위 세 항목이 모두 기재되어 있다.
- 검증 레벨: build/docs 게이트 (PR 전 점검; 자동 테스트 아님)

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | tia.yml 탐색·부재 시 무변화 | ConfigE2ETest#configFlagPrecedence / #upwardDiscovery / #absentYmlUnchanged | E2E | 🟡 red(테스트 작성 완료, Task 5 배선 대기) |
| REQ-002 | 플래그의 목록 단위 대체 | ConfigE2ETest#flagReplacesListNotMerge | E2E | 🟡 red(테스트 작성 완료, Task 5 배선 대기) |
| REQ-003 | 검증 실패 fail-fast | ConfigE2ETest#invalidYmlFailsFast | E2E | 🟡 red(테스트 작성 완료, Task 5 배선 대기) |
| REQ-004 | code 글로브 정규화 공간 | FilterE2ETest#codeGlobMatchesCanonicalPath | E2E | 🟡 red(테스트 작성 완료, Task 6 배선 대기) |
| REQ-005 | testId `#` 정규화 | FilterE2ETest#hashTestIdNormalization | E2E | 🟡 red(테스트 작성 완료, Task 6 배선 대기) |
| REQ-006 | include/exclude 의미론 | GlobFilterTest#excludeWins / #emptyIncludeMeansAll | unit | 🔴 planned |
| REQ-007 | DiffSummary 전면 필터 + WARN + CONSERVATIVE 보존 | FilterE2ETest#excludedNewFileNoConservative / #warnPerIgnoredFile / #unmappableBypassesInclude / #nonMatchingFilterKeepsConservative + DiffSummaryFilterTest#filtersAllThreeFields | E2E+unit | 🟡 red(E2E 작성 완료·unit은 기존 green, Task 6 배선 대기) |
| REQ-008 | 전부-제외 → 0건+WARN+exit0 | FilterE2ETest#allExcludedDiffZeroSelection | E2E | 🟡 red(테스트 작성 완료, Task 6 배선 대기) |
| REQ-009 | test 필터 Confidence 일괄 | FilterE2ETest#excludedTestNeverOutput / #excludedFromConservativeSet | E2E | 🟡 red(테스트 작성 완료, Task 6 배선 대기) |
| REQ-010 | flaky 집계 전 필터 | FlakyFilterE2ETest#excludedBeforeAggregation / #allExcludedRatioZero | E2E | 🟡 red(테스트 작성 완료, Task 7 배선 대기) |
| REQ-011 | report 인프로세스 필터 | ReportFilterE2ETest#filteredAxesNotRendered | E2E | 🟡 red(테스트 작성 완료, Task 8 배선 대기) |
| REQ-012 | text 출력 동결 | SpecAcceptanceE2ETest (기존, 무변경) | E2E | 🔴 planned |
| REQ-013 | impact json 계약 | FormatE2ETest#impactJsonSchema | E2E | 🟡 red(테스트 작성 완료, Task 8 배선 대기) |
| REQ-014 | flaky json 계약 | FormatE2ETest#flakyJsonSchema | E2E | 🟡 red(테스트 작성 완료, Task 8 배선 대기) |
| REQ-015 | summary 뷰 | FormatE2ETest#impactSummaryPipedNoAnsi | E2E | 🟡 red(테스트 작성 완료, Task 8 배선 대기) |
| REQ-016 | markdown 뷰 | FormatE2ETest#impactMarkdownTableAndDetails | E2E | 🟡 red(테스트 작성 완료, Task 8 배선 대기) |
| REQ-017 | 스트림 규약 | FormatE2ETest#stderrWarnStdoutData | E2E | 🟡 red(테스트 작성 완료, Task 8 배선 대기) |
| REQ-018 | exit code 포맷 독립 | FormatE2ETest#exitCodeFormatIndependent_impact / _flaky | E2E | 🟡 red(테스트 작성 완료, Task 8 배선 대기) |
| REQ-019 | 기존 스위트 무변경 green | SpecAcceptanceE2ETest + scripts/run-inprocess-e2e.sh + 컨테이너 E2E | E2E | 🔴 planned |
| REQ-020 | 라이선스 고지 동기화 | PR 전 build/docs 게이트 점검 (NOTICES·SBOM 대조) | build | 🔴 planned |
| REQ-021 | 글로브 OS 독립 | GlobFilterTest#unixSyntaxFixedMatcher | unit | 🔴 planned |
| REQ-022 | CLI 옵션 배선 | CliWiringTest#optionsForImpact / #optionsForFlaky / #optionsForReport / #optionsForIndex / #optionsForConvert | CLI | 🟡 red(convert만 처음부터 green, 나머지 Task 5~8 배선 대기) |
| REQ-023 | tia.yml `db` 기본값 적용 | ConfigE2ETest#ymlDbDefaultRelativeToYml / #dbFlagBeatsYml / #noDbKeepsCommonDirDefault | E2E | 🟡 red(테스트 작성 완료, Task 5 배선 대기) |
| REQ-024 | tia.yml `sut-name` 기본값 적용 | ConfigE2ETest#ymlSutNameDefault / #sutNameFlagBeatsYml | E2E | 🟡 red(테스트 작성 완료, Task 8 배선 대기) |
| REQ-025 | 사용자 문서의 필터 규칙 반영 | PR 전 build/docs 게이트 점검 (GETTING-STARTED·--help 대조) | build | 🔴 planned |

Coverage: 0/25 green (0%) — target 100% (대상: Must 24 + 미연기 Should 1 = 25)
(Task 4: 19개 REQ의 수용 테스트가 작성되어 red 상태로 전환됨 — 🟡 표시. green 전환은 Task 5~8의 CLI 배선 완료 후.)

## design spec E2E 항목 ↔ REQ 매핑

design spec §6의 번호 붙은 E2E 시나리오는 다음 REQ가 커버한다: ①(in-process id 필터)
→ REQ-009, ②(out-of-process `#` id 형태) → REQ-005, ③(JSON 계약) → REQ-013,
④(제외-변경 WARN) → REQ-008, ⑤(하위호환) → REQ-012·REQ-019.

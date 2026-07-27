# SP2 설계 — 수집 단계 필터 전파 + Gradle 플러그인의 tia.yml 소비

- 날짜: 2026-07-27
- 상위 맥락: 사용성 개선 5-SP 분해(SP1 spec §0)의 두 번째. 사용자 확정: **TIA 쪽 wiring만**(pjacoco 에이전트가 이미 지원하는 옵션 범위 내 — `AgentOptions`는 `includes`(기본 `*`)·`excludes`(기본 빈)를 클래스 패턴으로 받음을 소스로 확인).

## 1. 목표·비범위

**목표**

1. `io.tia` Gradle 플러그인이 **tia.yml을 읽어** `db`·`sut-name` 기본값을 주입한다(SP1의 알려진 갭: tia.yml에 db가 있어도 플러그인은 `GradleException`을 던짐 — 해소).
2. `attachCoverageAgent`가 tia.yml의 `filters.code`를 **pjacoco 에이전트 includes/excludes로 전파**한다(수집 자체를 좁혀 오버헤드 감소). 경로 글로브(`com/acme/**`) → 클래스 패턴(`com.acme.*`) 변환 규칙 포함.

**비범위**

- pjacoco 에이전트 자체 기능 추가(별도 레포 소관 — 기존 옵션만 사용).
- test 필터의 수집 전파(에이전트는 코드 계측 필터만 지원 — test 필터는 소비 시점 전용으로 유지, 문서 명시).
- 플러그인의 Plugin Portal/Maven 게시(기존과 동일하게 in-repo 사용; tia-core 의존 추가에 따른 게시 영향은 게시 시점에 처리).

## 2. tia.yml 소비 (플러그인)

- **의존**: `tia-gradle-plugin`에 `implementation project(':tia-core')` 추가 — SP1 로더(`TiaConfigLoader`) 재사용(JVM 표면 재사용 원칙, SP1 §2).
- **탐색**: `apply` 시 `TiaConfigLoader.load(null, project.getProjectDir().toPath())` — cwd가 아니라 **프로젝트 디렉터리** 기준 상향 탐색(멀티모듈 서브프로젝트에서도 루트 tia.yml 발견).
- **우선순위**: DSL 명시(`tia { db = … }`) > tia.yml > 기존 convention. 구현은 Gradle convention 체인으로: `ext.getDb().convention(<yml db>)`, `ext.getSutName().convention(<yml sut-name> ?: project.getName())`. tia.yml의 `db`는 SP1 로더가 이미 절대경로로 해석해 줌.
- **오류 의미론**: tia.yml이 존재하지만 깨진 경우(`TiaConfigException`) → **apply 시점에 `GradleException`으로 빌드 실패**(SP1 fail-fast 계약과 정합 — 플러그인도 소비자다). 파일이 없으면 무변화(완전 하위호환).
- `req()` 메시지 갱신: db 미해결 시 "tia { db = … } 또는 레포 루트 tia.yml의 db" 두 경로를 안내.

## 3. 수집 필터 전파 (attachCoverageAgent)

- **변환 규칙** (`GlobToClassPattern` — 플러그인 내 순수 유틸, 단위 테스트):
  경로 글로브(SP1 code 필터, `/` 구분·package-relative) → 에이전트 클래스 패턴(`.` 구분, `*` 와일드카드).
  - `.java` 접미 제거 → `/`를 `.`로 → `**`를 `*`로 → 연속 `*.*`·`.*.`는 자연 축약 없이 그대로(`*`가 dot 경계를 넘는 것은 JaCoCo 와일드카드 의미론상 허용).
  - 예: `com/acme/**` → `com.acme.*` · `**/gen/**` → `*.gen.*` · `**/*Dto.java` → `*.*Dto` · `com/acme/PricingService.java` → `com.acme.PricingService`
  - `?`는 그대로 전달. 변환 불능 패턴은 없음(어휘가 `**`/`*`/`?`뿐 — SP1 로더가 이미 fail-fast).
- **API**: 기존 5-인자 `attachCoverageAgent(test, jar, destDir, port, includes)`는 **불변**(명시 includes가 항상 우선 — 하위호환). 신규 4-인자 오버로드 `attachCoverageAgent(project, test, jar, destDir, port)`… 대신 명확하게: `attachCoverageAgentFromConfig(Project, Test, File agentJar, File destDir, int controlPort)` — 프로젝트의 tia.yml `filters.code`에서 includes/excludes를 도출해 부착. include 비면 `includes` 옵션 생략(에이전트 기본 `*`), exclude 비면 `excludes` 생략.
- **TiaArgs 확장**: `coverageAgentJvmArg(jar, destDir, port, includes, excludes)` 오버로드 추가 — `excludes=` 옵션 방출(기존 4-인자 시그니처는 위임 유지, 기존 테스트 무수정).
- **주의 문서화**: 수집 필터는 소비 필터(SP1)와 **의미가 겹치지만 독립**이다 — 수집을 좁히면 그 밖 코드는 커버리지 자체가 없어 CONSERVATIVE로도 못 잡는다(exclude와 동일한 "범위 밖 선언" 리스크, tia.yml 한 곳에서 관리되므로 일관성은 유지됨). GETTING-STARTED tia.yml 절에 1문단.

## 4. 테스트 전략과 수용 명세

플러그인의 기존 최고 실현 레벨 = **ProjectBuilder 단위/통합 테스트**(`TiaPluginTest` 패턴 — GradleRunner TestKit은 신규 인프라라 비례성상 도입하지 않음, 한계 명시).

1. **GlobToClassPattern 단위**: 변환 예 4종 + `?` 통과.
2. **tia.yml 소비**: @TempDir에 tia.yml(db·sut-name) + ProjectBuilder(projectDir=그 하위) → `ext.getDb()`/`getSutName()` convention 반영; DSL 명시가 이기는지; 깨진 tia.yml → apply가 GradleException; 파일 없음 → 기존 동작(변화 없음, tiaIndex의 req 에러 메시지에 tia.yml 안내 포함).
3. **필터 전파**: tia.yml filters.code 有 → `attachCoverageAgentFromConfig`가 부착한 jvmArgs에 변환된 `includes=`/`excludes=` 존재; filters 無 → 두 옵션 모두 생략; 기존 5-인자 API 결과 무변(기존 테스트 그대로 green).
4. **회귀**: 전체 스위트 green(플러그인 외 무변경).

완료 정의: 요구 매트릭스 100% + 전체 스위트 green + GETTING-STARTED 1문단 + tia-gradle-plugin/README 갱신.

## 5. 리스크와 반론

- **수집 필터로 인한 사각**: 수집을 좁힌 코드는 영영 안 보인다 — §3 리스크 문단으로 고지(소비 exclude와 동일 부류). 반론: 그래서 기본은 "필터 없음=전체"이고 opt-in이다.
- **apply-시점 fail-fast**: 깨진 tia.yml이 플러그인 적용만으로 빌드를 깨뜨림 — 의도(조기 발견). CLI와 동일 의미론이라 놀라움 최소.
- **클래스 패턴 변환의 근사**: 경로 글로브와 JaCoCo 와일드카드는 1:1이 아니다(`*.gen.*`는 `gen` 최상위 패키지도 매칭 등 — 과포함 방향). 수집 필터는 성능 최적화이므로 과포함은 안전, 과소포함이 위험 — 변환은 항상 과포함 쪽으로 근사함을 단위 테스트로 고정·문서화.

# SP2 요구사항명세 — 수집 필터 전파 + 플러그인 tia.yml 소비
> 출처(design spec): docs/superpowers/specs/2026-07-27-sp2-collection-filter-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 요구사항 목록

### SP2-REQ-001 — 플러그인의 tia.yml 기본값 주입 (db·sut-name)
- 유형: Functional
- 우선순위: Must
- 설명: apply 시 프로젝트 디렉터리 기준 상향 탐색으로 tia.yml을 읽어 `db`·`sut-name` convention을 주입한다. 우선순위: DSL 명시 > tia.yml > 기존 기본값(project.name 등). yml convention 호출은 기존 기본값 convention 이후여야 한다.
- 수용기준:
  - Given tia.yml(db·sut-name)이 있는 @TempDir(.git 마커 포함) 하위 ProjectBuilder 프로젝트, When plugin apply, Then `ext.getDb()`/`getSutName()`이 yml 값이다(특히 sut-name이 project.name 기본값을 이긴다).
  - Given DSL로 `tia { db = … }` 명시, When 평가, Then DSL 값이 yml 값을 이긴다.
  - Given tia.yml 없음, When apply + tiaIndex 인자 평가, Then 기존 GradleException 메시지에 tia.yml 경로 안내가 포함된다.
- 검증 레벨: ProjectBuilder 통합 (플러그인 최고 실현 레벨 — TestKit 미도입 명시)

### SP2-REQ-002 — 깨진 tia.yml의 apply-시점 fail-fast
- 유형: Functional
- 우선순위: Must
- 설명: tia.yml이 존재하지만 파싱/검증 실패면 apply가 `GradleException`으로 실패한다(SP1 fail-fast 정합; 구성 단계 전체에 파급됨은 README에 명시).
- 수용기준:
  - Given 깨진 tia.yml, When plugin apply, Then GradleException + 원인 메시지.
- 검증 레벨: ProjectBuilder 통합

### SP2-REQ-003 — GlobToClassPattern 변환 (과소포함 금지)
- 유형: Functional
- 우선순위: Must
- 설명: 경로 글로브 → JaCoCo 클래스 패턴 변환: `.java` 제거·`/`→`.`·`**`→`*`; 선두 `**/`는 유니온(`X:*.X`)으로 0-세그먼트(기본 패키지) 포함; 정확-파일은 `*` 접미(내부/람다 클래스 포함); 다중 패턴은 `:` 결합. 과소포함 없음(과포함 방향 근사).
- 수용기준(전부 **실제 JaCoCo `WildcardMatcher` 매칭**으로 단언):
  - Given `com/acme/**`, Then `com.acme.*`가 `com.acme.Svc`·`com.acme.sub.X` 매칭.
  - Given `**/gen/**`, Then 결과 유니온이 `gen.Foo`(기본 패키지 인접)와 `a.gen.B` 모두 매칭.
  - Given `**/*Dto.java`, Then 유니온이 `FooDto`(기본 패키지)와 `a.BDto` 모두 매칭.
  - Given `com/acme/PricingService.java`, Then 결과가 `com.acme.PricingService`·`…$Builder`·`…$1` 매칭.
  - Given 다중 exclude 2건, Then 단일 `:`-결합 문자열로 방출.
- 검증 레벨: unit (실매처 기반)

### SP2-REQ-004 — attachCoverageAgentFromConfig의 필터 전파
- 유형: Functional
- 우선순위: Must
- 설명: 신규 `attachCoverageAgentFromConfig(Project, Test, File agentJar, File destDir, int controlPort)`가 tia.yml(단순 재로드)의 filters.code를 변환해 에이전트 `includes=`/`excludes=`로 부착한다. include 비면 includes 생략(에이전트 기본 `*`), exclude 비면 excludes 생략. 기존 5-인자 API는 불변(명시 includes 우선·excludes 미전파 — 마이그레이션 노트로 문서화).
- 수용기준:
  - Given filters.code(include 1·exclude 2) tia.yml, When FromConfig 부착, Then jvmArgs에 변환·`:`-결합된 `includes=`·`excludes=` 존재.
  - Given filters 없음, When 부착, Then 두 옵션 모두 부재.
  - Given 기존 5-인자 호출, Then 산출 jvmArg 기존과 동일(기존 테스트 무수정 green).
- 검증 레벨: ProjectBuilder 통합 + unit(TiaArgs 오버로드)

### SP2-REQ-005 — 플러그인 클래스패스 위생
- 유형: Non-functional (계약)
- 우선순위: Must
- 설명: tia-core 의존은 `org.jacoco`/`org.xerial`/`org.roaringbitmap` 그룹을 제외해 플러그인 런타임 클래스패스에 유입시키지 않는다(내장 jacoco 플러그인 충돌 방지). jackson 잔존은 명시 리스크.
- 수용기준:
  - Given 구현 완료, When `./gradlew :tia-gradle-plugin:dependencies --configuration runtimeClasspath` 검사, Then 위 세 그룹 부재 + jackson 존재(리포트 기록).
- 검증 레벨: build 게이트 (의존성 리포트 대조)

### SP2-REQ-006 — 문서·계약 주석 동기화
- 유형: Non-functional (문서)
- 우선순위: Must
- 설명: tia-gradle-plugin/README(§: yml 기본값+fail-fast 의미론·FromConfig 예·5-인자 마이그레이션 노트), TiaArgs 계약 주석(excludes 반영), GETTING-STARTED 1문단(수집 필터="범위 밖 선언" 리스크 + 멀티 서브프로젝트 `--parallel` 금지 1줄).
- 수용기준:
  - Given 갱신 문서/주석, When 검토, Then 위 항목 전부 기재.
- 검증 레벨: build/docs 게이트

### SP2-REQ-007 — 하위호환·구성 캐시
- 유형: Non-functional (회귀)
- 우선순위: Must
- 설명: tia.yml 없는 기존 사용 경로 무변화(전체 스위트 green) + `./gradlew help --configuration-cache`(tia.yml 존재) 스모크 통과.
- 수용기준:
  - Given 구현 완료, When 전체 스위트 + CC 스모크 실행, Then 전부 성공(결과 기록).
- 검증 레벨: 전체 스위트 + 스모크

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| SP2-REQ-001 | yml 기본값 주입 | TiaPluginTest#ymlDbSutNameConventions / #dslBeatsYml / #reqMessageMentionsYml | integ | 🔴 planned |
| SP2-REQ-002 | apply fail-fast | TiaPluginTest#brokenYmlFailsApply | integ | 🔴 planned |
| SP2-REQ-003 | 글로브→클래스 패턴 | GlobToClassPatternTest#(5케이스, 실매처) | unit | 🔴 planned |
| SP2-REQ-004 | FromConfig 전파 | TiaPluginTest#fromConfigAttachesIncludesExcludes / #noFiltersOmitsOptions / 기존 5-인자 테스트 무수정 | integ | 🔴 planned |
| SP2-REQ-005 | 클래스패스 위생 | 의존성 리포트 대조 (build 게이트) | build | 🔴 planned |
| SP2-REQ-006 | 문서·주석 동기화 | PR 전 docs 게이트 점검 | build | 🔴 planned |
| SP2-REQ-007 | 하위호환·CC | 전체 스위트 + help --configuration-cache 스모크 | suite | 🔴 planned |

Coverage: 0/7 green (0%) — target 100% (대상: Must 7 = 7)

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
  - Given DSL로 `tia { sutName = … }` 명시 + yml sut-name 존재, When 평가, Then DSL 값이 이긴다(db와 대칭 검증).
  - Given tia.yml 없음, When apply + tiaIndex 인자 평가, Then 기존 GradleException 메시지가 tia.yml을 **대안 설정 소스로 언급**한다(해석된 파일 경로 출력 요구 아님).
- 검증 레벨: ProjectBuilder 통합 (플러그인 최고 실현 레벨 — TestKit 미도입 명시)

### SP2-REQ-002 — 깨진 tia.yml의 apply-시점 fail-fast
- 유형: Functional
- 우선순위: Must
- 설명: tia.yml이 존재하지만 파싱/검증 실패면 apply가 `GradleException`으로 실패한다(SP1 fail-fast 정합; 구성 단계 전체에 파급됨은 README에 명시).
- 수용기준:
  - Given 깨진 tia.yml, When plugin apply, Then GradleException + 원인 메시지.
- 검증 레벨: ProjectBuilder 통합

### SP2-REQ-003 — GlobToClassPattern 변환 (일괄 과포함 규칙 — 과소포함 금지)
- 유형: Functional
- 우선순위: Must
- 설명: 경로 글로브 → JaCoCo 클래스 패턴 변환은 5단계 일괄 규칙(spec §3): ①`.java` 제거 ②`**/`→`*` ③잔여 `/`→`.` ④잔여 `**`→`*` ⑤끝이 `*`가 아니면 `*` 접미. 각 단계가 매칭 집합을 확장만 하므로 과소포함이 없다(기본 패키지·중간 `**`·중간 `?`·내부 클래스 반례 계열 해소). 다중 패턴은 `:` 결합.
- 수용기준(전부 **실제 JaCoCo `WildcardMatcher` 매칭**으로 단언 — 문자열 비교 금지):
  - Given `com/acme/**`, Then 결과가 `com.acme.Svc`·`com.acme.sub.X` 매칭.
  - Given `**/gen/**`, Then 결과가 `gen.Foo`(기본 패키지)와 `a.gen.B` 모두 매칭.
  - Given `**/*Dto.java`, Then 결과가 `FooDto`(기본 패키지)와 `a.BDto` 모두 매칭.
  - Given `com/acme/**/dto/**`(중간 `**`), Then 결과가 `com.acme.dto.X`(0-세그먼트)와 `com.acme.a.dto.X` 모두 매칭.
  - Given `com/acme/PricingService.java`, Then 결과가 `com.acme.PricingService`·`…$Builder`·`…$1` 매칭.
  - Given `com/acme/Prici?gService.java`(중간 `?`), Then 결과가 `com.acme.PricingService$Builder`까지 매칭(`*` 접미 보편 적용).
  - Given `com/acme/*.java`(단일 `*`), Then 결과가 `com.acme.X` 매칭(하위 패키지 과포함은 허용·명시).
  - Given 다중 exclude 2건(A·B), Then `:`-결합 문자열을 **결합 상태 그대로** `WildcardMatcher`에 넣어 A·B 각각의 대응 FQN이 매칭된다.
- 검증 레벨: unit (실매처 기반)

### SP2-REQ-004 — attachCoverageAgentFromConfig의 필터 전파
- 유형: Functional
- 우선순위: Must
- 설명: 신규 `attachCoverageAgentFromConfig(Project, Test, File agentJar, File destDir, int controlPort)`가 tia.yml(단순 재로드)의 filters.code를 변환해 에이전트 `includes=`/`excludes=`로 부착한다. include 비면 includes 생략(에이전트 기본 `*`), exclude 비면 excludes 생략. 기존 5-인자 API는 불변(명시 includes 우선·excludes 미전파 — 마이그레이션 노트로 문서화).
- 수용기준:
  - Given filters.code(include 1·exclude 2) tia.yml, When FromConfig 부착, Then jvmArgs에 변환·`:`-결합된 `includes=`·`excludes=` 존재.
  - Given filters 섹션 없음, When 부착, Then 두 옵션 모두 부재.
  - Given **tia.yml 파일 자체 부재**, When 부착, Then 두 옵션 모두 부재(에러 없음).
  - Given 기존 5-인자 호출, Then 산출 jvmArg 기존과 동일(기존 테스트 무수정 green).
  - Given TiaArgs 5-인자 오버로드 단독 호출(includes+excludes / excludes만), Then `excludes=` 방출이 정확하다(unit).
- 검증 레벨: ProjectBuilder 통합 + unit(TiaArgs 오버로드)

### SP2-REQ-005 — 플러그인 클래스패스 위생
- 유형: Non-functional (계약)
- 우선순위: Must
- 설명: tia-core 의존은 `org.jacoco`/`org.xerial`/`org.roaringbitmap` 그룹을 제외해 플러그인 런타임 클래스패스에 유입시키지 않는다(내장 jacoco 플러그인 충돌 방지 — 이 레포 루트 빌드가 실제 jacoco 적용). jackson 잔존은 명시 리스크. 검증은 **`check`에 연결된 자동 태스크**(runtimeClasspath 순회로 세 그룹 부재 단언)로 상시 게이트화한다.
- 수용기준:
  - Given 구현 완료, When `./gradlew :tia-gradle-plugin:check`, Then 클래스패스 검증 태스크가 세 그룹 부재를 단언하며 통과(향후 의존 변경 시 자동 회귀 검출).
- 검증 레벨: build 자동 게이트 (check 연결)

### SP2-REQ-006 — 문서·계약 주석 동기화
- 유형: Non-functional (문서)
- 우선순위: Must
- 설명: tia-gradle-plugin/README(§: yml 기본값+fail-fast 의미론·FromConfig 예·5-인자 마이그레이션 노트), TiaArgs 계약 주석(excludes 반영), GETTING-STARTED 1문단(수집 필터="범위 밖 선언" 리스크 + 멀티 서브프로젝트 `--parallel` 금지 1줄). README에는 file-watcher 지연 주의사항 포함(tia.yml 변경 직후 동일 데몬 즉시 재빌드 시 워처 지연 가능성).
- 수용기준:
  - Given 갱신 문서/주석, When 검토, Then 위 항목 전부 기재.
- 검증 레벨: build/docs 게이트

### SP2-REQ-007 — 하위호환·구성 캐시 정합
- 유형: Non-functional (회귀)
- 우선순위: Must
- 설명: tia.yml 없는 기존 사용 경로 무변화(전체 스위트 green). CC 정합은 **GradleRunner 기능 스모크 1건(3단계)** 으로 검증: 플러그인이 실제 apply된 @TempDir 소비 프로젝트에서 (1단계: tia.yml 부재 상태) `--configuration-cache`로 빌드 → (2단계: tia.yml A 생성) 재빌드 → (3단계: tia.yml을 B로 변경) 재빌드 → **각 단계에서 새 값 반영 확인**(캐시 staleness 부재 — 스모크 무오류만으로는 불충분; 레포 루트 tia.yml 임시 생성 방식은 플러그인 미적용이라 거짓 green이므로 금지).
- 수용기준:
  - Given @TempDir 소비 프로젝트(플러그인 실적용), When CC 빌드 (1단계: tia.yml 부재) → (2단계: tia.yml A 생성·재빌드) → (3단계: tia.yml B로 변경·재빌드), Then 각 단계의 빌드가 해당 상태의 값을 정확히 사용한다(태스크 출력으로 관측) + 전체 스위트 green.
- 검증 레벨: GradleRunner 기능 스모크(정확히 1건 — 전면 TestKit 스위트는 비도입) + 전체 스위트

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| SP2-REQ-001 | yml 기본값 주입 | TiaPluginTest#ymlDbSutNameConventions / #dslBeatsYml(db·sutName) / #reqMessageMentionsYml | integ | 🟢 green |
| SP2-REQ-002 | apply fail-fast | TiaPluginTest#brokenYmlFailsApply | integ | 🟢 green |
| SP2-REQ-003 | 글로브→클래스 패턴 | GlobToClassPatternTest#(8케이스, 실매처 — 중간 `**`/`?`·단일 `*`·기본 패키지·결합 포함) | unit | 🟢 green |
| SP2-REQ-004 | FromConfig 전파 | TiaPluginTest#fromConfigAttachesIncludesExcludes / #noFiltersOmitsOptions / #absentYmlOmitsOptions / #coverageAgentJvmArgWithExcludes(unit) / 기존 5-인자 테스트 무수정 | integ+unit | 🟢 green |
| SP2-REQ-005 | 클래스패스 위생 | check 연결 verifyPluginClasspath 태스크 (자동 게이트) | build | 🟢 green |
| SP2-REQ-006 | 문서·주석 동기화 | README(tia.yml 기본값+fail-fast+stale 주의·FromConfig 예·마이그레이션 노트)·GETTING-STARTED(수집 필터 리스크+`--parallel` 금지)·TiaArgs 계약 주석(excludes) 갱신 확인 | build | 🟢 green |
| SP2-REQ-007 | 하위호환·CC 정합 | PluginCcSmokeFunctionalTest#ymlChangeReflectedUnderConfigurationCache + 전체 스위트 | functional | 🟢 green |

Coverage: 7/7 green (100%) — target 100% (대상: Must 7 = 7).

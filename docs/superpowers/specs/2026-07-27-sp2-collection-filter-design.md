# SP2 설계 — 수집 단계 필터 전파 + Gradle 플러그인의 tia.yml 소비

- 날짜: 2026-07-27
- 상태: 3-벤더 리뷰(Sonnet ×3 대체 슬롯) 소견 반영 완료
- 상위 맥락: 사용성 개선 5-SP 분해(SP1 spec §0)의 두 번째. 사용자 확정: **TIA 쪽 wiring만**(pjacoco 에이전트가 이미 지원하는 옵션 범위 내). `excludes` 지원 근거: 리뷰에서 pjacoco-agent 1.4.0 jar의 `AgentOptions`를 디컴파일해 `includes`(기본 `*`)·`excludes`(기본 빈)와 콜론(`:`) 구분 `WildcardMatcher` 의미론을 확인 — **TiaArgs.java의 낡은 계약 주석(includes만 언급)과 플러그인 README도 SP2에서 함께 갱신**한다.

## 1. 목표·비범위

**목표**

1. `io.tia` Gradle 플러그인이 **tia.yml을 읽어** `db`·`sut-name` 기본값을 주입한다(SP1의 알려진 갭: tia.yml에 db가 있어도 플러그인은 `GradleException`을 던짐 — 해소).
2. **신규 `attachCoverageAgentFromConfig`** 가 tia.yml의 `filters.code`를 pjacoco 에이전트 includes/excludes로 전파한다(수집 자체를 좁혀 오버헤드 감소). 경로 글로브 → 클래스 패턴 변환 규칙 포함. (기존 5-인자 `attachCoverageAgent`는 불변 — §3.)

**비범위**

- pjacoco 에이전트 자체 기능 추가(별도 레포 소관 — 기존 옵션만 사용).
- test 필터의 수집 전파(에이전트는 코드 계측 필터만 지원 — test 필터는 소비 시점 전용으로 유지, 문서 명시).
- 필터의 DSL 오버라이드(`tia { filters… }`) — SP2에선 tia.yml이 유일한 필터 소스(의도적 비대칭 — db/sut-name과 달리 필터는 "설정파일 단일 소스"가 SP1 취지. 필요가 실증되면 후속).
- 플러그인의 Plugin Portal/Maven 게시(기존과 동일하게 in-repo 사용; 게시 영향은 게시 시점에 처리 — 그 시점의 대안으로 경량 `tia-config` 모듈 분리 검토를 백로그에 둔다).

## 2. tia.yml 소비 (플러그인)

- **의존**: `tia-gradle-plugin`에 tia-core를 추가하되 **전이 의존을 명시적으로 제외**해 플러그인 클래스패스 오염을 막는다(플러그인 클래스패스는 소비 빌드와 공유되는 잘 알려진 충돌 표면 — 특히 `org.jacoco.core`는 Gradle 내장 jacoco 플러그인과 충돌 소지, 이 레포 루트 빌드가 실제 사용):

```groovy
implementation(project(':tia-core')) {
    exclude group: 'org.jacoco'          // 내장 jacoco 플러그인과 충돌 방지
    exclude group: 'org.xerial'          // sqlite — 로더 경로에서 불필요
    exclude group: 'org.roaringbitmap'   // 비트맵 — 로더 경로에서 불필요
}
```

  로더 경로(`TiaConfigLoader`/`TiaConfig`/`GlobMatcher`)는 jackson(-yaml)만 필요하다. jackson 자체의 버전 충돌 가능성은 잔존 리스크로 §5에 명시(현실화 시 셰이딩/`tia-config` 모듈 분리 후속). 구현 시 `./gradlew :tia-gradle-plugin:dependencies`로 제외 결과를 확인해 리포트에 기록한다.
- **탐색**: `apply` 시 `TiaConfigLoader.load(null, project.getProjectDir().toPath())` — cwd가 아니라 **프로젝트 디렉터리** 기준 상향 탐색(멀티모듈 서브프로젝트에서도 루트 tia.yml 발견). apply-시점 파일 IO는 Gradle 8.x configuration cache가 파일 시스템 계측으로 자동 추적하는 입력으로 기대되며, 수용 확인으로 `./gradlew help --configuration-cache` 스모크를 포함한다.
- **우선순위**: DSL 명시(`tia { db = … }`) > tia.yml > 기존 convention. 구현은 Gradle convention 체인: yml 값이 있으면 `ext.getDb().convention(<yml db>)`·`ext.getSutName().convention(<yml sut-name>)` — **기존 `convention(project.getName())` 호출(TiaPlugin.java:26) 이후에 호출해야 yml이 기본값을 이긴다**(convention은 마지막 호출 승리; 순서 명시). yml `db`는 SP1 로더가 이미 절대경로로 해석.
- **오류 의미론**: tia.yml이 존재하지만 깨진 경우(`TiaConfigException`) → **apply 시점에 `GradleException`으로 빌드 실패**(SP1 fail-fast 계약과 정합 — 플러그인도 소비자다). 주의: apply-시점 실패는 CLI보다 파급이 넓다(`gradle clean`·IDE 싱크 포함 구성 단계 전체) — 조기 발견을 위한 의도적 선택임을 README에 명시. 파일이 없으면 무변화(완전 하위호환).
- **재로드 의미론**: `attachCoverageAgentFromConfig`(§3)는 tia.yml을 **단순 재호출로 다시 로드**한다(로더가 순수·저비용; 캐시 상태 없음 — 명시적 채택).
- `req()` 메시지 갱신: db 미해결 시 "tia { db = … } 또는 레포 루트 tia.yml의 db" 두 경로를 안내.

## 3. 수집 필터 전파 (attachCoverageAgent)

- **변환 규칙** (`GlobToClassPattern` — 플러그인 내 순수 유틸, 단위 테스트는 **실제 JaCoCo `WildcardMatcher`로 매칭 검증**(`testImplementation org.jacoco:org.jacoco.core` 추가; `WildcardMatcher`는 콜론-분리 결합 문자열을 자체 처리 — 결합 상태로도 실매처 단언)):
  경로 글로브(SP1 code 필터, `/` 구분·package-relative) → 에이전트 클래스 패턴(`.` 구분, JaCoCo `*`(dot 경계 무제한)/`?` 와일드카드, **다중 패턴은 콜론(`:`) 결합** — `-javaagent` 옵션 파서가 쉼표를 쓰므로 쉼표 금지).
  **일괄 과포함 규칙** — 각 단계가 매칭 집합을 확장만 하므로(치환 대상이 더 넓은 와일드카드로만 바뀜) 원본 글로브가 매칭하던 소스는 결과 패턴이 반드시 매칭한다(과소포함 반례 계열 — 기본 패키지·중간 `**`·중간 `?`·내부/람다 클래스 — 일괄 해소):
  1. `.java` 접미 제거
  2. `**/` → `*` (0-세그먼트 포함 — JaCoCo `*`는 빈 문자열도 매칭)
  3. 잔여 `/` → `.`
  4. 잔여 `**` → `*`
  5. 결과가 `*`로 끝나지 않으면 `*` 접미(내부/익명/람다 `$` 클래스 포함; "정확-파일" 특수 판정 불요 — 규칙이 보편 적용)
  - 예: `com/acme/**` → `com.acme.*` · `**/gen/**` → `*gen.*`(기본 패키지 `gen.Foo` 매칭) · `**/*Dto.java` → `*Dto*` · `com/acme/PricingService.java` → `com.acme.PricingService*` · `com/acme/**/dto/**` → `com.acme.*dto.*`(`com.acme.dto.X` 매칭) · `com/acme/Prici?gService.java` → `com.acme.Prici?gService*`.
  - 과포함 예(허용·명시): `*gen.*`는 `mygen.Foo`도, `*Dto*`는 `DtoFactory`도 매칭 — 수집 필터는 성능 최적화이므로 안전 방향.
  - 변환 불능 패턴 없음(어휘 `**`/`*`/`?`뿐 — SP1 로더가 fail-fast).
  - 다중 엔트리: 각 변환 결과를 `:`로 결합해 단일 `includes=`/`excludes=` 값으로 방출.
- **API**: 기존 5-인자 `attachCoverageAgent(test, jar, destDir, port, includes)`는 **불변**(명시 includes가 항상 우선 — 하위호환). 신규 4-인자 오버로드 `attachCoverageAgent(project, test, jar, destDir, port)`… 대신 명확하게: `attachCoverageAgentFromConfig(Project, Test, File agentJar, File destDir, int controlPort)` — 프로젝트의 tia.yml `filters.code`에서 includes/excludes를 도출해 부착. include 비면 `includes` 옵션 생략(에이전트 기본 `*`), exclude 비면 `excludes` 생략.
- **TiaArgs 확장**: `coverageAgentJvmArg(jar, destDir, port, includes, excludes)` 오버로드 추가 — `excludes=` 옵션 방출(기존 4-인자 시그니처는 위임 유지, 기존 테스트 무수정).
- **주의 문서화**: 수집 필터는 소비 필터(SP1)와 **의미가 겹치지만 독립**이다 — 수집을 좁히면 그 밖 코드는 커버리지 자체가 없어 CONSERVATIVE로도 못 잡는다(exclude와 동일한 "범위 밖 선언" 리스크, tia.yml 한 곳에서 관리되므로 일관성은 유지됨). GETTING-STARTED tia.yml 절에 1문단.

## 4. 테스트 전략과 수용 명세

플러그인의 주 실현 레벨 = **ProjectBuilder 단위/통합 테스트**(`TiaPluginTest` 패턴). 예외 1건: apply-시점 파일 IO의 **configuration-cache 정합**과 실제 `gradle` 프로세스 적용은 ProjectBuilder로 검증 불가하므로, **GradleRunner(TestKit) 기능 스모크를 정확히 1건만 도입**한다(`java-gradle-plugin` + `gradleTestKit()` 의존 + `gradlePlugin.testSourceSets` 배선 — 전면적 TestKit 스위트는 여전히 비례성상 배제). @TempDir 트리에는 `.git` 마커 디렉터리를 두어 로더의 상향 탐색이 임시 트리 밖으로 새지 않게 한다.

1. **GlobToClassPattern 단위**: §3 예 전종(중간 `**`·중간 `?`·단일 `*`·기본 패키지 포함) + **다중 엔트리 콜론 결합(결합 문자열째로 실매처 단언)** — 전부 **실제 `WildcardMatcher` 매칭으로 단언**(문자열 비교 아님).
2. **tia.yml 소비**: tia.yml(db·sut-name) → convention 반영(**yml이 project.name 기본값을 이김**); DSL 명시가 yml을 이김(**db·sut-name 양쪽**); 깨진 tia.yml → apply GradleException; 파일 없음 → 기존 동작 + tiaIndex req 메시지가 tia.yml을 **대안 소스로 언급**(해석된 파일 경로 출력이 아님).
3. **필터 전파**: filters.code 有 → `attachCoverageAgentFromConfig` jvmArgs에 변환·결합된 `includes=`/`excludes=` 존재(다중 exclude 포함); filters 섹션 無 **및 tia.yml 파일 자체 부재** → 두 옵션 생략; 기존 5-인자 API 결과 무변(기존 테스트 그대로 green); TiaArgs 5-인자 오버로드 단독 unit(includes+excludes·excludes만).
4. **클래스패스 위생 자동 게이트**: `check`에 연결된 검증 태스크가 `runtimeClasspath`에서 `org.jacoco`/`org.xerial`/`org.roaringbitmap` 그룹 부재를 단언(1회 수동 대조가 아닌 회귀 게이트).
5. **CC 스모크(GradleRunner 1건, 3단계)**: @TempDir 소비 프로젝트에 플러그인 실적용 → (1단계: tia.yml 부재 상태) `--configuration-cache` 빌드 → (2단계: tia.yml A 생성) 재빌드 → (3단계: tia.yml을 B로 변경) 재빌드 → **각 단계에서 새 값 반영 확인**(스모크 무오류만이 아니라 캐시 staleness 부재까지).
6. **회귀**: 전체 스위트 green.

완료 정의: 요구 매트릭스 100%(SP2 요구사항명세 별도 문서) + 전체 스위트 green + 문서 갱신 — GETTING-STARTED 1문단(수집 필터 리스크) / **tia-gradle-plugin/README**(① tia.yml 기반 db·sut-name 기본값+apply-시점 fail-fast 의미론 ② `attachCoverageAgentFromConfig` 사용 예 ③ 기존 5-인자 호출자는 excludes 전파를 받으려면 신규 메서드로 이행해야 한다는 마이그레이션 노트 ④ TiaArgs 계약 주석의 excludes 갱신).

## 5. 리스크와 반론

- **수집 필터로 인한 사각**: 수집을 좁힌 코드는 영영 안 보인다 — §3 리스크 문단으로 고지(소비 exclude와 동일 부류). 반론: 그래서 기본은 "필터 없음=전체"이고 opt-in이다.
- **apply-시점 fail-fast**: 깨진 tia.yml이 플러그인 적용만으로 빌드를 깨뜨림 — 의도(조기 발견). CLI와 동일 의미론이라 놀라움 최소.
- **클래스 패턴 변환의 근사**: 경로 글로브와 JaCoCo 와일드카드는 1:1이 아니다. 수집 필터는 성능 최적화이므로 **과포함은 안전, 과소포함이 위험** — §3의 유니온(선두 `**/`)·`*` 접미(정확-파일) 규칙이 과소포함 반례(기본 패키지·내부 클래스)를 제거하며, 이를 실제 매처 기반 단위 테스트로 고정한다.
- **플러그인 클래스패스**: tia-core 의존은 제외 지정으로 jacoco/sqlite/RoaringBitmap 유입을 차단했지만 jackson은 남는다 — 소비 빌드의 다른 플러그인과 jackson 충돌이 현실화되면 셰이딩 또는 경량 `tia-config` 모듈 분리로 전환(백로그).
- **멀티 서브프로젝트 공유 db**: yml `db` convention으로 여러 서브프로젝트가 같은 SQLite 파일을 자동 공유하게 된다 — `--parallel`로 tia 태스크를 동시 실행하지 말 것을 문서에 1줄 고지(CoverageStore는 WAL/busy_timeout 미설정 — 하드닝은 후속).

# `io.tia` — TIA Gradle 플러그인 (D3)

배포 설계 §4 Phase D3. `tia` CLI를 빌드에 통합한다 — JVM 팀이 빌드 네이티브로 TIA를 도입.

```gradle
plugins { id 'io.tia' version '<ver>' }

tia {
    db = 'tia.db'                 // baseline 인덱스
    commit = '<baseline-sha>'
    testwise = 'testwise.json'    // index/report 입력
    reportOut = 'build/tia/report.html'
    sutName = project.name        // 기본값
    // gitRef = '<ref>'           // impact diff 베이스(기본 = commit; two-dot git diff)
    // diffFile = 'change.diff'   // impact: precomputed diff(CI 오버라이드)
    // strict = true              // 베이스라인 없으면 실패(기본: 전체 실행 신호)
    // testSrcRoot, jacocoDir, prefixStrip, cliCoordinates 도 설정 가능
}
```

## 태스크 (CLI 래핑; `javaexec`로 `tiaCli` 클래스패스 실행)

| 태스크 | 동작 |
|---|---|
| `tiaIndex` | `tia index` — testwise.json → SQLite 스냅샷 |
| `tiaImpact` | `tia impact` — diff로 영향 테스트 선별(`gitRef`/`strict` 반영) |
| `tiaReport` | `tia report` — 인터랙티브 HTML 리포트 |

CLI는 `tiaCli` configuration에서 해소된다(기본 `io.tia:tia-cli:<project.version>`, `tia.cliCoordinates`로 오버라이드). PATH의 `tia`에 의존하지 않는다.

## tia.yml 소비 — db·sut-name 기본값 (SP2)

플러그인 `apply` 시점에 **프로젝트 디렉터리 기준 상향 탐색**으로 `tia.yml`을 읽어(CLI와 같은
`TiaConfigLoader`) `db`·`sutName`의 기본값(convention)을 채운다. 우선순위는 **DSL 명시
(`tia { db = ... }`) > tia.yml > 플러그인 내장 기본값**(`sutName`은 기본 `project.name`)이다.
`tia.yml`이 아예 없으면 무변화 — 기존 사용자는 이 절을 몰라도 지금까지와 동일하게 동작한다.

```gradle
plugins { id 'io.tia' version '<ver>' }
// 레포 루트(또는 상위 디렉터리)의 tia.yml에 db/sut-name이 있으면 아래 DSL은 생략 가능
tia {
    commit = '<baseline-sha>'
    testwise = 'testwise.json'
    // db, sutName은 tia.yml 값이 자동 주입됨 — DSL로 명시하면 그 값이 tia.yml보다 우선
}
```

**apply-시점 fail-fast.** `tia.yml`이 존재하지만 파싱/검증에 실패하면(예: 미지원 `version`, 잘못된
글로브 문법) `apply` 시점에 곧바로 `GradleException`으로 빌드를 실패시킨다 — CLI의 fail-fast
계약과 동일한 의미론이다. **주의:** apply-시점 실패는 태스크 실행 시점 실패보다 파급 범위가 훨씬
넓다 — `gradle clean`, `gradle tasks`, IntelliJ 등 IDE의 Gradle 싱크까지, 그 프로젝트를
**구성(configure)만 해도** 전부 실패한다. 이는 의도적 선택이다 — 깨진 설정을 태스크 실행까지
기다리지 않고 구성 단계에서 가능한 한 빨리 드러내기 위함이다.

> **같은 데몬에서 즉시 재빌드 시 stale 주의.** `tia.yml`을 고치자마자 같은 Gradle 데몬으로 바로
> 재빌드하면, 파일시스템 워처(VFS)가 변경을 아직 못 잡아 이전 값으로 평가될 수 있다(configuration
> cache와는 별개로 데몬 자체의 워처 지연). 값이 안 바뀐 것 같으면 `--no-watch-fs`로 재실행하거나
> 잠시 후 다시 시도한다.

## D3.1 커버리지 에이전트 와이어링 — 두 모델

per-test 수집은 에이전트마다 모델이 다르다. 플러그인은 각각의 attach 헬퍼를 제공한다(에이전트 jar은 §5.3대로 사용자 제공).

### (a) out-of-process — parallel-per-test-coverage (baggage)

**권장 — pjacoco 네이티브 플러그인 + 테스트킷.** pjacoco가 자체 Gradle 플러그인(`io.github.beltian.pjacoco`)과
테스트킷(`pjacoco-testkit-junit5`·`pjacoco-testkit-restassured`)을 제공한다. 에이전트 attach,
control-url 주입, 테스트별 start/stop, 요청의 `baggage: test.id` 전파를 플러그인+테스트킷이 모두 처리하므로
TIA는 산출물(per-test `.exec`)을 `tia convert`로 받기만 하면 된다.

```gradle
plugins { id 'io.github.beltian.pjacoco' version '2.0.0' }   // ※ Gradle Plugin Portal 미게시 — 로컬 게시 필요(아래 캐비앗)
pjacoco {
    includes.set(['com.acme.*'])
    attachTo.set(['integrationTest'])
    aggregate.set(false)            // TIA는 per-test만 소비 → 전체-실행 aggregate.exec 끔
}
dependencies {
    testImplementation 'io.github.beltian.pjacoco:pjacoco-testkit-junit5:2.0.0'
    testImplementation 'io.github.beltian.pjacoco:pjacoco-testkit-restassured:2.0.0'
}
// 이후: tia convert --exec-dir <pjacoco 출력 dir> --classes ... → testwise.json → tiaIndex
```

> **현재 상태:** 테스트킷(`pjacoco-testkit-*`)과 에이전트(`pjacoco-agent`)는 **Maven Central에 실좌표로
> 게시돼 있어**(`io.github.beltian.pjacoco:*:2.0.0`) 위 `dependencies` 블록은 그대로 resolve된다. **Gradle
> 플러그인만** Gradle Plugin Portal에 아직 게시되지 않았다 — `plugins { id 'io.github.beltian.pjacoco' ... }`
> 를 쓰려면 소스를 클론해 `:gradle-plugin:publishToMavenLocal`로 로컬 게시하고 소비 측
> `pluginManagement { repositories { mavenLocal() } }`가 필요하다. 플러그인만 쓸 수 없는 동안에는 아래
> TIA 내장 헬퍼로 같은 계약을 직접 와이어한다. Gradle 플러그인이 Plugin Portal에 올라오면 위 블록만으로
> 충분해지고 TIA 내장 헬퍼는 제거한다.

**대안 — TIA 내장 헬퍼 (공개 배포 전 임시).** 이 헬퍼는 에이전트를 **Test JVM**에 붙이는
**직렬·in-JVM 부착 브리지**다 — 에이전트와 테스터가 같은 JVM에 있으므로 `maxParallelForks=1`이어야 한다.
진짜 out-of-process **병렬** 수집은 pjacoco 에이전트를 **단일 SUT**에 붙이고 테스터를 병렬화하는
위 권장 토폴로지를 사용한다.

계약은 `io.pjacoco.agent.AgentOptions` 확인값:
`destfile=<dir>`·`port=<ctrl 고정>`·`aggregate=false`(per-test만 소비 → 전체-실행 `aggregate.exec` 비활성)·`includes`.
고정 포트·Test JVM 부착 시 `maxParallelForks=1`. 요청별 `baggage: test.id` 전파는 테스트 하니스가 담당.

```gradle
io.tia.gradle.TiaPlugin.attachCoverageAgent(
    t, file('libs/pjacoco-agent.jar'), file("$buildDir/tia/cov"), 6310, 'com.acme.*')
// 이후: tia convert --exec-dir build/tia/cov --classes ... → testwise.json
```

**`attachCoverageAgentFromConfig` — tia.yml 기반 수집 필터 (SP2).** `includes`를 직접 문자열로
넘기는 대신, 같은 `tia.yml`의 `filters.code`(SP1 소비 필터와 동일 설정)를 읽어 에이전트
`includes=`/`excludes=`로 그대로 전파한다(경로 글로브 → JaCoCo 클래스 패턴 변환은
`GlobToClassPattern` — 일괄 과포함 규칙이라 원본 글로브가 잡던 소스는 항상 매칭된다). `tia.yml`을
매 호출마다 다시 읽으므로(순수·저비용 재로드) 캐시 무효화를 신경 쓸 필요가 없다.

```gradle
io.tia.gradle.TiaPlugin.attachCoverageAgentFromConfig(
    project, t, file('libs/pjacoco-agent.jar'), file("$buildDir/tia/cov"), 6310)
// tia.yml의 filters.code.include/exclude를 변환해 includes=/excludes= 로 부착한다.
// filters.code가 비어 있거나 tia.yml 자체가 없으면 두 옵션 모두 생략(에이전트 기본값 사용, 에러 없음).
```

> **5-인자 → FromConfig 마이그레이션 노트.** 기존 5-인자 `attachCoverageAgent(test, jar, destDir,
> port, includes)`는 시그니처·동작 모두 그대로다(명시한 `includes`가 항상 우선하며, **`excludes`는
> 전파하지 않는다**). `tia.yml`의 `filters.code.exclude`를 에이전트 수집에도 반영하려면
> `attachCoverageAgentFromConfig`로 옮겨야 한다 — 5-인자 메서드는 어떤 경우에도 `excludes=` 옵션을
> 방출하지 않는다.

### (b) in-process — pjacoco in-process (권장)

**Test JVM**에 pjacoco 에이전트(`-javaagent`)를 붙이고, `PjacocoInProcessExtension`이 테스트마다
start/stop 신호. `aggregate=false`·`port=0`(OS 할당)으로 설정하며, 서비스는 테스트에서 직접 호출한다.
per-test `.exec` → `tia convert` → `testwise.json` → `tia index`.

에이전트 옵션 요약: `aggregate=false` (per-test `.exec`만 수집; 전체-실행 `aggregate.exec` 비활성),
`port=0` (고정 포트 없으므로 병렬 JVM 충돌 없음), `includes=<프로덕션 패키지>`.

```gradle
// build.gradle.kts (또는 Groovy 동등)
dependencies {
    testImplementation("io.github.beltian.pjacoco:pjacoco-testkit-junit5:<ver>")  // PjacocoInProcessExtension
    testRuntimeOnly("io.github.beltian.pjacoco:pjacoco-agent:<ver>")  // 아래 find가 testRuntimeClasspath에서 에이전트 jar를 찾으려면 필요(testkit은 agent를 전이 의존하지 않음)
}

tasks.withType<Test>().configureEach {
    jvmArgs("-javaagent:${configurations.testRuntimeClasspath.find { it.name.contains("pjacoco-agent") }}=aggregate=false,port=0,includes=com.acme.*")
}
// 이후: tia convert --exec-dir build/tia/cov --classes ... → testwise.json → tia index
```

> **기존 레포(테스트 클래스 다수)에 적용할 때** `@ExtendWith`를 일일이 추가하는 대신 JUnit 5 자동
> 등록으로 전역 적용한다 — 절차와 함정(`test.classpath` 재할당 금지 → `testRuntimeOnly` 사용,
> `includes`는 프로덕션 패키지만)은
> [GETTING-STARTED §1.1](../GETTING-STARTED.md#11-기존-레포에-적용할-때-in-process) 참고.

## 검증 / 범위

- 단위(ProjectBuilder + 순수 인자 빌더): 태스크/익스텐션/`tiaCli` 등록, 두 attach 헬퍼의 jvmArg 계약·`pjacoco.control-url`·`maxParallelForks=1` 검증. 전체 회귀 GREEN.
- **out-of-process 실증(E2E-R):** pjacoco parallel 에이전트로 petclinic 데모 end-to-end — per-test `.exec` 35개 → `tia convert` 35 tests/27 커버리지 → index→impact→flaky→report 통과.
- **in-process 실증(E2E):** pjacoco in-process 에이전트 + `PjacocoInProcessExtension`으로 `scripts/run-inprocess-e2e.sh` — per-test `.exec` 수집 → testwise → index → `tia impact` → **`DETERMINISTIC testPrice` 선별, testGreeting 제외(E2E PASS)**.

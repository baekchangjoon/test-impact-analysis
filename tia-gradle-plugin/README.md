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

## D3.1 커버리지 에이전트 와이어링 — 두 모델

per-test 수집은 에이전트마다 모델이 다르다. 플러그인은 각각의 attach 헬퍼를 제공한다(에이전트 jar은 §5.3대로 사용자 제공).

### (a) out-of-process — parallel-per-test-coverage (baggage)

**권장 — pjacoco 네이티브 플러그인 + 테스트킷.** pjacoco가 자체 Gradle 플러그인(`io.pjacoco.gradle`)과
테스트킷(`pjacoco-testkit-junit5`·`pjacoco-testkit-restassured`)을 제공한다. 에이전트 attach,
control-url 주입, 테스트별 start/stop, 요청의 `baggage: test.id` 전파를 플러그인+테스트킷이 모두 처리하므로
TIA는 산출물(per-test `.exec`)을 `tia convert`로 받기만 하면 된다.

```gradle
plugins { id 'io.pjacoco.gradle' version '1.1.0' }   // ※ 공개 배포(Maven Central/Plugin Portal) 후 사용 가능
pjacoco {
    includes.set(['com.acme.*'])
    attachTo.set(['integrationTest'])
    aggregate.set(false)            // TIA는 per-test만 소비 → 전체-실행 aggregate.exec 끔
}
dependencies {
    testImplementation 'io.pjacoco:pjacoco-testkit-junit5:1.1.0'
    testImplementation 'io.pjacoco:pjacoco-testkit-restassured:1.1.0'
}
// 이후: tia convert --exec-dir <pjacoco 출력 dir> --classes ... → testwise.json → tiaIndex
```

> **현재 상태:** pjacoco 플러그인/테스트킷은 아직 공개 저장소에 배포되지 않았다(`mavenLocal`로만 검증 가능).
> 공개 배포 전까지는 아래 TIA 내장 헬퍼로 같은 계약을 직접 와이어한다. pjacoco가 Maven Central /
> Gradle Plugin Portal에 올라오면 위 블록으로 전환하고 TIA 내장 헬퍼는 제거한다.

**대안 — TIA 내장 헬퍼 (공개 배포 전 임시).** SUT 프로세스(HTTP 블랙박스)에 에이전트를 직접 붙이고 요청의
test.id baggage로 per-test 귀속. 계약은 `io.pjacoco.agent.AgentOptions` 확인값:
`destfile=<dir>`·`port=<ctrl 고정>`·`aggregate=false`(per-test만 소비 → 전체-실행 `aggregate.exec` 비활성)·`includes`.
고정 포트라 `maxParallelForks=1`. 요청별 `baggage: test.id` 전파는 테스트 하니스가 담당.

```gradle
io.tia.gradle.TiaPlugin.attachCoverageAgent(
    t, file('libs/jacocoagent-parallel.jar'), file("$buildDir/tia/cov"), 6310, 'com.acme.*')
// 이후: tia convert --exec-dir build/tia/cov --classes ... → testwise.json
```

### (b) in-process — teamscale-jacoco-agent (TESTWISE)
**Test JVM**에 에이전트를 붙이고, `tia-junit-extension`(테스트 클래스패스)이 테스트마다 `/test/start|end`를 HTTP control 서버로 신호. 계약(scripts/run-poc.sh·docker-compose.e2e.yml): `mode=TESTWISE,includes=<p>,http-server-port=<port>,class-dir=<classes>,out=<dir>`. 플러그인이 `tia.agent.url` 시스템 프로퍼티 + `maxParallelForks=1`을 설정.

```gradle
io.tia.gradle.TiaPlugin.attachTeamscaleAgent(
    t, file('tools/teamscale/.../teamscale-jacoco-agent.jar'),
    file("$buildDir/tia/cov"), 8123, file("$buildDir/classes/java/main"), 'io.acme.*')
// 테스트는 @ExtendWith(io.tia.junit.TeamscaleTestwiseExtension); 이후 teamscale convert → testwise.json → tia index
```

> 기존 레포(테스트 클래스 다수)는 `@ExtendWith`를 일일이 추가하는 대신 **확장을 전역 등록**한다 —
> 절차와 함정(`test.classpath` 재할당 금지 → `testRuntimeOnly` 사용, `includes`는 프로덕션
> 패키지만)은 [GETTING-STARTED §1.1](../GETTING-STARTED.md#11-기존-레포에-적용할-때-in-process) 참고.

## 검증 / 범위

- 단위(ProjectBuilder + 순수 인자 빌더): 태스크/익스텐션/`tiaCli` 등록, 두 attach 헬퍼의 jvmArg 계약·`tia.agent.url`/`pjacoco.control-url`·`maxParallelForks=1` 검증. 전체 회귀 GREEN.
- **out-of-process 실증(E2E-R):** parallel 에이전트로 petclinic 데모 end-to-end — per-test `.exec` 35개 → `tia convert` 35 tests/27 커버리지 → index→impact→flaky→report 통과.
- **in-process 실증(E2E):** teamscale 에이전트(TESTWISE)로 `scripts/run-poc.sh` — per-test 수집 3 tests → testwise → index → `tia impact` → **`DETERMINISTIC testPrice` 선별, testGreeting 제외(E2E PASS)**. 플러그인 attach 헬퍼는 이 스크립트와 동일 계약을 와이어(단위로 잠금); 플러그인-드리븐 전체 실행은 에이전트가 있는 샘플에서 동일 인자로 재현된다.

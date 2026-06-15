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

## D3.1 커버리지 에이전트 와이어링

per-test 커버리지 수집을 위해 `Test` 태스크에 parallel-per-test-coverage 에이전트를 주입한다. 실제 에이전트 계약(`io.pjacoco.agent.AgentOptions` 확인)에 맞춰 `destfile=<dir>`(per-test `.exec` 출력 디렉터리)·`port=<ctrl>`(control 엔드포인트)·`includes=...`를 넘기고, per-test 드라이버를 `-Dpjacoco.control-url`로 그 포트에 연결한다. **control 포트는 고정**(ephemeral 아님)이라 `attachCoverageAgent`가 `maxParallelForks = 1`로 고정해 fork 간 포트 충돌을 막는다.

```gradle
tasks.named('test', Test) { t ->
    io.tia.gradle.TiaPlugin.attachCoverageAgent(
        t, file('libs/jacocoagent-parallel.jar'), file("$buildDir/tia/cov"), 6310, 'com.acme.*')
}
// 이후: tia convert --exec-dir build/tia/cov --classes ... → testwise.json
```

에이전트 jar 자체는 TIA가 번들하지 않는다(§5.3) — 사용자가 제공(parallel-per-test-coverage / teamscale).

## 검증 / 범위

- 단위(ProjectBuilder + 순수 인자 빌더): 태스크/익스텐션/`tiaCli` 등록, 인자 벡터, 에이전트 jvmArg(`destfile`/`port`/`includes`)·control-url·`maxParallelForks=1` 검증. 전체 회귀 GREEN.
- **수집 파이프라인 실증(E2E-R/§7):** parallel-per-test-coverage 에이전트로 petclinic 데모를 end-to-end 실행 — 에이전트가 **per-test `.exec` 35개 수집** → `tia convert`가 **35 tests / 27 커버리지** testwise 산출 → index→impact→flaky→`tia report` 통과. 즉 에이전트 계약·수집→CLI 경로가 실데이터로 검증됨.
- **남은 부분:** 위는 **out-of-process(SUT-attach)** 모델. **in-process(Test JVM attach + JUnit 익스텐션)** 전체 흐름은 그 익스텐션을 함께 와이어한 샘플 프로젝트에서 추가 검증 필요(에이전트 옵션 계약·`maxParallelForks=1`은 단위로 잠금).

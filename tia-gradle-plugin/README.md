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

per-test 커버리지 수집을 위해 `Test` 태스크 fork에 에이전트를 주입한다. 포트는 `port=0`으로 둔다 — fork마다 free port가 되려면 **에이전트가 `port=0`을 프로세스별 ephemeral 바인딩으로 해석**해야 한다(그럴 때 `maxParallelForks > 1`에서 `BindException` 회피). 이는 사용자가 제공하는 에이전트의 런타임 동작에 달려 있다(O4에서 검증).

```gradle
tasks.named('test', Test) { t ->
    io.tia.gradle.TiaPlugin.attachCoverageAgent(t, file('libs/jacocoagent-parallel.jar'), 'com.acme.*')
}
```

에이전트 jar 자체는 TIA가 번들하지 않는다(§5.3) — 사용자가 제공(teamscale / parallel-per-test-coverage).

## 검증 / 범위

- 단위(ProjectBuilder + 순수 인자 빌더): 태스크/익스텐션/`tiaCli` 등록, 인자 벡터, 에이전트 jvmArg(`port=0`) 검증. 전체 회귀 GREEN.
- **E2E-4(설계 §7) 부분 의존(O4):** `tiaImpact`/`tiaReport`(기존 인덱스 질의)는 게시된 `tia-cli`로 즉시 동작. **수집(에이전트 와이어링)을 통한 전체 파이프라인**은 parallel-per-test-coverage 에이전트 가용성에 의존 — 그때 완전 검증.

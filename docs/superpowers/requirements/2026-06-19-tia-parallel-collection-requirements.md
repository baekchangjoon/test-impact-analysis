# 병렬 테스트 수집 지원 요구사항명세
> 출처(design spec): docs/superpowers/specs/2026-06-19-tia-parallel-collection-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 범위 메모
- 대상: out-of-process(pjacoco) per-test 커버리지 **수집의 병렬화**. 토폴로지 = pjacoco 에이전트를
  단일 SUT에 부착 + 별도 병렬 테스터.
- in-process를 pjacoco로 전환, cross-service 전파(OTel substrate)는 **다음 phase** → 본 명세 밖.
- `TestwiseConverter` 순차 처리 병렬화는 수집이 아닌 convert 최적화 → 본 명세 밖.

## 요구사항 목록

### REQ-001 — 병렬(프로세스 포크) 수집이 직렬과 동일한 per-test 커버리지를 낸다
- 유형: Functional
- 우선순위: Must
- 설명: 동일 SUT(동일 `--classes`)에서 테스터를 Gradle `maxParallelForks>1`로 병렬 실행해 수집한
  testwise가, 직렬(forks=1) 수집 testwise와 per-test 단위로 동일해야 한다(교차 오염 0).
- 수용기준:
  - Given pjacoco 에이전트가 붙은 fixture-app SUT와 결정적 테스트 케이스(≥8, `/flaky` 제외),
  - When 직렬(forks=1)과 병렬(`maxParallelForks>1`)로 각각 수집해 `tia convert`로 testwise를 만들면,
  - Then 두 testwise는 `Map<uniformPath, Map<fileName, Set<Integer>>>` 정규화 형태로 equals다
    (테스트 집합 동일 + 테스트별 (파일, 라인) 집합 동일).
- 검증 레벨: E2E black-box

### REQ-002 — 병렬(JUnit in-JVM 스레드) 수집이 직렬과 동일한 per-test 커버리지를 낸다
- 유형: Functional
- 우선순위: Must
- 설명: 한 테스터 JVM 안 JUnit 5 in-JVM 병렬(`junit.jupiter.execution.parallel.enabled=true`,
  `mode.default=concurrent`)로 수집한 testwise가 직렬 수집과 per-test 단위로 동일해야 한다(동기 HTTP
  호출 기준; 자식 스레드/스레드풀 위임은 한계로 REQ-006).
- 수용기준:
  - Given pjacoco SUT와 결정적 케이스(≥8, `/flaky` 제외), JUnit in-JVM 병렬이 실제로 발생,
  - When in-JVM 병렬로 수집해 `tia convert`로 testwise를 만들면,
  - Then 직렬 testwise와 위 정규화 형태로 equals다.
- 검증 레벨: E2E black-box

### REQ-003 — 병렬 수집에서 각 테스트의 커버리지가 비어 있지 않다 (거짓 green 방지)
- 유형: Functional
- 우선순위: Must
- 설명: baggage 전파가 조용히 실패해 빈 `.exec`가 생기는데 동등 비교가 우연히 성립하는 거짓 green을
  막기 위해, 병렬 수집 산출물의 각 테스트가 비어 있지 않은 커버리지(≥1 커버 라인)를 가져야 한다.
- 수용기준:
  - Given REQ-001/REQ-002의 병렬 수집 testwise,
  - When 각 테스트 항목의 커버 라인 수를 확인하면,
  - Then 모든 테스트가 ≥1 커버 라인을 가진다(빈 커버리지 0건).
- 검증 레벨: E2E black-box

### REQ-004 — 병렬 수집의 벽시계 시간을 측정·기록한다
- 유형: Non-functional
- 우선순위: Must
- 설명: 직렬/병렬(forks, in-JVM) 세 실행의 벽시계 시간을 측정해 출력(로그/리포트)한다. speedup 배수
  자체는 환경 의존이라 하드 게이트로 단언하지 않는다(머지 플레이크 방지).
- 수용기준:
  - Given 세 가지 수집 실행,
  - When E2E가 각 실행의 수집 소요 시간을 측정하면,
  - Then 세 시간이 산출물/로그에 기록된다(정보용). "병렬 < 직렬"의 하드 단언은 하지 않는다.
- 검증 레벨: E2E black-box

### REQ-005 — 내장 헬퍼는 직렬 안전을 유지하고 병렬은 pjacoco 토폴로지로 안내한다
- 유형: Functional
- 우선순위: Must
- 설명: `TiaPlugin.attachCoverageAgent`(에이전트가 Test JVM에 부착)는 `maxParallelForks=1`을 계속
  강제해 forks>1 포트 충돌을 방지한다. 병렬 경로는 pjacoco 에이전트=단일 SUT 토폴로지임을 코드 계약과
  문서가 일치시켜 안내한다.
- 수용기준:
  - Given `attachCoverageAgent`로 와이어된 Test 태스크,
  - When 플러그인 단위 테스트로 검증하면,
  - Then `maxParallelForks==1`이 유지되고 기존 jvmArg/control-url 계약 단언이 통과한다.
- 검증 레벨: integration (Gradle ProjectBuilder 단위)

### REQ-006 — 동기-호출 한계를 문서화한다
- 유형: Non-functional
- 우선순위: Must
- 설명: 테스트가 작업을 자식 스레드/스레드풀/비동기로 위임하면 그 커버리지는 test.id로 귀속되지 않을
  수 있음(pjacoco phase-2 한계)을 사용자 문서에 명시한다.
- 수용기준:
  - Given 사용자 문서(GETTING-STARTED/plugin README),
  - When 병렬 수집 절을 읽으면,
  - Then 동기 HTTP 호출 기준 지원과 비동기/스레드풀 위임 한계가 명시돼 있다.
- 검증 레벨: 문서 점검 (PR 문서 게이트)

### REQ-007 — 병렬 수집 와이어링·전제를 사용자 문서로 제공한다
- 유형: Non-functional
- 우선순위: Must
- 설명: out-of-process 병렬 토폴로지(에이전트=단일 SUT, 병렬 테스터), 두 메커니즘(forks/in-JVM),
  `aggregate=false`(플러그인 DSL `pjacoco { aggregate.set(false) }` vs 내장 헬퍼 jvmarg `aggregate=false`),
  testId 키 형식 혼용 금지, 내장 헬퍼 문단 토폴로지 교정을 GETTING-STARTED/plugin README에 반영한다.
- 수용기준:
  - Given 변경된 사용자 문서,
  - When 병렬 수집 절차를 따르면,
  - Then 토폴로지·두 메커니즘·aggregate 비활성 두 경로·키 형식 주의가 빠짐없이 안내된다.
- 검증 레벨: 문서 점검 (PR 문서 게이트)

### REQ-008 — pjacoco 미게시 의존을 CI에서 결정적으로 해소한다 (상시 skip 금지)
- 유형: Non-functional
- 우선순위: Must
- 설명: CI에서 pjacoco 에이전트 jar(release 버전 핀 다운로드)과 testkit/plugin(소스 빌드 →
  `publishToMavenLocal` 또는 `includeBuild`)을 해소해 병렬 E2E(REQ-001~004)를 실제로 실행한다.
  해소 실패는 **skip이 아니라 fail**로 처리한다(거짓 green 방지). pjacoco를 끝내 해소 못 하는 로컬
  등에서만 명시적 메시지와 함께 skip하고 그 사실을 보고한다.
- 수용기준:
  - Given CI 파이프라인,
  - When 병렬 E2E job이 실행되면,
  - Then pjacoco 의존이 해소되어 E2E가 실제로 수행되며, 해소 실패 시 job이 fail한다(조용한 skip 아님).
- 검증 레벨: CI 파이프라인 (E2E job)

### REQ-009 — E2E는 실행 간 격리된 출력으로 결정적으로 비교한다
- 유형: Non-functional
- 우선순위: Should
- 설명: 직렬/forks/in-JVM 세 수집은 각각 별도 exec 디렉터리에 쓰고 각 수집 전 디렉터리를 비워, 이전
  `.exec` 잔존이 집합 비교를 오염시키지 않게 한다. 세 실행은 동일 `--classes`를 사용한다.
- 수용기준:
  - Given 세 수집 실행,
  - When 각 실행이 수집을 시작하면,
  - Then 각자 비워진 전용 디렉터리에 수집하고 동일 classesDir로 convert해, 비교가 잔존물·빌드 차이에
    영향받지 않는다.
- 검증 레벨: E2E black-box

## 추적 매트릭스
| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | 병렬(forks) == 직렬 | ParallelCollectionE2E#forksParallelMatchesSerial | E2E | 🔴 planned |
| REQ-002 | 병렬(in-JVM) == 직렬 | ParallelCollectionE2E#inJvmParallelMatchesSerial | E2E | 🔴 planned |
| REQ-003 | per-test 커버리지 non-empty | ParallelCollectionE2E#everyTestHasNonEmptyCoverage | E2E | 🔴 planned |
| REQ-004 | 벽시계 시간 측정·기록 | ParallelCollectionE2E#recordsWallClockPerMode | E2E | 🔴 planned |
| REQ-005 | 내장 헬퍼 직렬 유지 | TiaPluginTest#coverageHelperPinsSingleFork | integration | 🔴 planned |
| REQ-006 | 동기-호출 한계 문서화 | docs gate (GETTING-STARTED/README) | 문서 | 🔴 planned |
| REQ-007 | 병렬 와이어링 문서 | docs gate (GETTING-STARTED/README) | 문서 | 🔴 planned |
| REQ-008 | CI 의존 해소(fail-not-skip) | ci.yml parallel-e2e job | CI | 🔴 planned |
| REQ-009 | 격리 출력·동일 classes | ParallelCollectionE2E (디렉터리 격리/동일 classesDir) | E2E | 🔴 planned |

Coverage: 0/9 green (0%) — target 100% (대상: Must 8 + 미연기 Should 1[REQ-009]). 연기/Won't/제외 없음.

# 설계: 병렬 테스트 수집 지원 (out-of-process / pjacoco)

상태: 리뷰용 초안
작성일: 2026-06-19
브랜치: `feat/parallel-collection`
관련 피드백: graph-rag 적용기 P2-3 (고정 포트 → 직렬 강제)

## 1. 배경 & 문제

TIA의 per-test 커버리지 수집은 현재 **직렬만** 지원한다. Gradle 플러그인의 두 attach 헬퍼가
`maxParallelForks=1`을 하드코딩한다:

- `TiaPlugin.attachCoverageAgent` (out-of-process, pjacoco) — `TiaPlugin.java:87`
- `TiaPlugin.attachTeamscaleAgent` (in-process, teamscale) — `TiaPlugin.java:103`
- 단위 테스트가 이 계약을 잠근다 — `TiaPluginTest.java:93,106`

직렬 강제의 원래 근거는 "에이전트 control 포트가 고정이라 포크가 여러 개면 충돌"이다. 그러나
**out-of-process 모델에서는 SUT가 단일 프로세스**이고 pjacoco 에이전트도 그 한 프로세스에만 붙으므로,
테스터가 포크를 여러 개 띄워도 모두 동일한 단일 control 포트로 향한다 — 충돌이 발생하지 않는다.
즉 out-of-process에서 `maxParallelForks=1` 핀은 **과보수적 제약**이며, 이것이 유일한 병목이다.

대형 멀티모듈에서 직렬 수집은 비싸다(graph-rag-builder 통합테스트 직렬 8m30s). 병렬 수집을 열면
수집 구간의 벽시계 시간을 크게 줄일 수 있다.

## 2. 목표 & 불변식

- **목표**: out-of-process(pjacoco) per-test 수집을 **병렬로** 허용한다. 테스터 측 병렬은 두 형태를
  모두 지원한다 — (1) Gradle `maxParallelForks>1`(프로세스 분리), (2) JUnit 5 in-JVM 병렬(스레드).
- **핵심 불변식 (하드 게이트)**: 병렬로 수집한 testwise 산출물이 직렬과 **per-test 단위로 동일**하다.
  즉 각 테스트의 (커버 파일, 커버 라인) 집합이 직렬 실행과 같아야 하며, 동시 실행된 다른 테스트의
  커버리지가 섞이지 않는다(교차 오염 0).
- **부가 목표**: 대표 스위트에서 직렬 대비 병렬의 벽시계 **speedup을 실측·기록**한다.
- **비목표(이 spec 밖, 다음 phase)**: in-process per-test 수집을 teamscale 대신
  pjacoco(`PjacocoInProcessExtension`)로 전환하는 작업. 별도 spec으로 다룬다.

## 3. 근거 — 병렬 기계 장치는 pjacoco가 이미 소유·검증

이 작업은 새 수집/격리 메커니즘을 만들지 않는다. pjacoco(parallel-per-test-coverage)가 이미 제공한다.
근거(`~/github_parallel-per-test-coverage`):

- **격리**: pjacoco는 ByteBuddy advice로 jacoco 프로브에 additive 기록을 끼워, 현재 스레드에 활성화된
  testId 스토어에 복제한다. 컨텍스트는 ThreadLocal `TestStore` → **병렬 워커 스레드 간 교차 오염 0**
  (pjacoco README "동작 원리").
- **routing**: 인입 요청의 OpenTelemetry Baggage(`baggage: test.id=...`)로 testId가 들어오고,
  제어 엔드포인트(`POST /__coverage__/test/start|stop?testId=...`)가 flush 경계를 정의한다.
- **출력**: testId별 `<FQN>#<method>.exec`(바닐라 JaCoCo 바이트 호환) + 사이드카 `.json`.
- **실증**: petclinic 데모가 **parallelism=8**에서 24개 블랙박스 테스트의 per-test 귀속을 정확히
  수행함을 이미 보였다(`petclinic-demo/00-SUMMARY.md:14,72`).
- **TIA 소비 경로 준비됨**: `tia convert --exec-dir <dir> --classes <classes> --out testwise.json`이
  `<testId>.exec` 디렉터리를 testwise로 변환한다(`ConvertCommand.java`, `TestwiseConverter`). pjacoco
  출력 형식과 호환.

또한 TIA의 기존 배포 설계가 이미 이 방향을 명시한다 — `tia-gradle-plugin/README.md` §(a)는
**"권장 — pjacoco 네이티브 플러그인 + 테스트킷"**, 내장 헬퍼는 **"공개 배포 전 임시"**라고 적는다.
따라서 본 작업은 새 방향이 아니라 **의도된 pjacoco 경로의 활성화 + 직렬 핀 제거 + E2E 잠금**이다.

### pjacoco가 제공하는 productized 표면 (소비 대상)

- `io.pjacoco.gradle` Gradle 플러그인: 지정 태스크에 에이전트 + `-Dpjacoco.control-url` 주입,
  JUnit 5 자동등록(autodetection) 활성화. **forks를 강제하지 않음** — 병렬은 사용자 설정에 맡긴다.
- `pjacoco-testkit-junit5` (`PjacocoExtension`): 테스트별 start/stop 신호.
- `pjacoco-testkit-restassured` (`PjacocoRestAssured`): 요청에 `baggage: test.id` 주입(스레드 스코프).
- 산출물: `build/pjacoco/<FQN>#<method>.exec` (+ `.json`) + 전체 `aggregate.exec`.

## 4. 아키텍처 & 데이터 흐름

```
[테스터: 직렬(forks=1) 또는 병렬(forks>1 / JUnit in-JVM)]
        │  요청마다  baggage: test.id=<FQN>#<method>
        ▼
[SUT JVM  +  -javaagent: pjacoco agent  (단일 control 포트)]
        │  testId별 .exec  (ThreadLocal 격리 → 병렬에도 교차 오염 0)
        ▼
   tia convert --exec-dir build/pjacoco --classes <main classes> --out testwise.json
        ▼
   tia index → tia.db → tia impact
```

SUT가 단일 프로세스이므로 control 포트는 공유되며, 병렬에도 충돌하지 않는다. 직렬과 병렬의 유일한
차이는 테스터 측 동시성뿐이고, pjacoco가 testId로 분리하므로 산출물은 동일해야 한다.

## 5. 변경 컴포넌트 (TIA 레포)

1. **`tia-gradle-plugin`**
   - out-of-process 권장 경로를 pjacoco 플러그인(`io.pjacoco.gradle`) + testkit으로 전환(문서·예제).
   - 내장 `attachCoverageAgent` 헬퍼에서 `setMaxParallelForks(1)` **제거**(직렬 강제 해제). 헬퍼는
     "pjacoco 미게시 전 임시 브리지"로 격하하되, 더 이상 병렬을 막지 않는다. (포크 수 자체는
     사용자/Gradle 설정에 맡긴다 — 헬퍼가 강제하지 않는다.)
   - `attachTeamscaleAgent`(in-process)의 직렬 핀은 **이 spec에서 건드리지 않는다**(다음 phase).
   - 단위 테스트 갱신: `TiaPluginTest`의 `maxParallelForks==1` 단언을 "헬퍼가 forks를 강제하지
     않는다"로 변경.

2. **문서**
   - `GETTING-STARTED.md` §1 out-of-process, `tia-gradle-plugin/README.md` §(a)를 갱신: pjacoco
     플러그인+testkit 경로가 병렬 OK임을 명시, 직렬 핀 제거 반영. pjacoco 미게시 동안의 의존 해소
     (에이전트 jar = GitHub release 고정 버전, testkit/plugin = mavenLocal/소스빌드)를 기록.

3. **E2E 모듈 (`e2e/`)**
   - 아래 §7의 수용 테스트 추가.

## 6. 에러 처리 / 전제

- **pjacoco 의존 해소(미게시 1.1.0)**: 에이전트 jar은 GitHub release의 고정 버전, testkit/plugin은
  mavenLocal(또는 소스빌드)로 해소한다.
- **해소 불가 시**: E2E는 **명시적 skip 메시지와 함께 중단**한다(예: "pjacoco agent/testkit 미해소 —
  release 다운로드 또는 publishToMavenLocal 필요"). **절대 silent green이 아니다** — 레포의 기존
  패턴(`run-poc.sh` 환경 제약 시 명시적 대체, 컨테이너 E2E)과 일치.
- **포트/리소스**: 단일 SUT control 포트는 공유 안전. SUT 기동 실패·포트 점유 시 명확한 오류로 중단.

## 7. 테스트 & 수용 기준 (E2E 먼저 작성)

CLAUDE.md의 double-loop 원칙에 따라 아래 수용 E2E를 **먼저** 작성한다(구현 전 red).

### 7.1 수용 E2E — out-of-process 병렬==직렬 (in-repo, fixture-app SUT)

- **대상**: `fixture-app`을 out-of-process SUT로 기동하고 pjacoco 에이전트를 attach. 테스트
  하니스는 RestAssured + baggage `test.id`(이미 `fixture-app`의 `ApiSmokeTest`에 와이어됨;
  필요한 만큼 테스트 케이스 보강).
- **절차**:
  1. 동일 SUT(또는 동일 빌드/커밋)에 대해 스위트를 **직렬**(forks=1)로 1회 실행 → pjacoco 출력 →
     `tia convert` → `testwise_serial.json`.
  2. 동일 스위트를 **병렬**로 실행한다. 사용자가 둘 다 지원을 요구했으므로 **두 병렬 메커니즘을 각각**
     검증한다 — (2a) Gradle `maxParallelForks>1`(프로세스 분리), (2b) JUnit 5 in-JVM 병렬(스레드,
     `junit.jupiter.execution.parallel.enabled=true`). 각각 `tia convert` →
     `testwise_parallel_forks.json`, `testwise_parallel_injvm.json`.
  3. 두 병렬 산출물을 각각 직렬과 비교.
- **수용 단언(하드 게이트)**: 병렬 산출물(2a)·(2b)이 **각각** 직렬과 — 테스트 집합이 같고, 각 테스트의
  (커버 파일, 커버 라인) 집합이 **동일**하다(정렬·순서 정규화 후 equals). 이로써 두 메커니즘 모두에서
  교차 오염 0과 직렬 동등을 회귀로 잠근다. (in-JVM 경로는 ThreadLocal `test.id` 스코프가 스레드 간
  오염을 막는지를 직접 검증하는 더 까다로운 케이스다.)
- **speedup 기록**: 두 실행의 벽시계 시간을 측정·출력하고, 병렬이 직렬보다 느리지 않음을 확인한다
  (speedup 배수는 환경 의존이라 정보로 기록; 하드 단언은 "병렬 ≤ 직렬 시간"의 느슨한 하한만).

> in-JVM 병렬을 의미 있게 만들려면 fixture-app 테스트 케이스 수를 충분히(예: 다수의 독립 엔드포인트
> 호출) 두어 동시성이 실제로 발생하도록 한다. 케이스 보강은 구현 단계에서 정한다.

### 7.2 단위 테스트

- `TiaPluginTest`: out-of-process 헬퍼가 더 이상 `maxParallelForks=1`을 강제하지 않음을 단언.
- 기존 jvmArg 계약(에이전트 옵션, control-url) 단언은 유지.

### 7.3 회귀

- 전체 스위트(unit + 기존 E2E) green. pjacoco 미해소 환경에서는 7.1을 명시적 skip하되 그 사실을
  보고한다.

### 완료 정의

- 7.1 수용 E2E가 green: 두 병렬 메커니즘(forks, in-JVM) 산출물이 **각각** 직렬과 동일(정확성 단언
  통과) + speedup 기록.
- 7.2 단위 단언 통과, 7.3 회귀 green(또는 명시적 skip 보고).
- 관련 문서(§5.2) 갱신 포함.

## 8. 다음 phase (이 spec 밖)

in-process per-test 수집을 pjacoco로 전환: `PjacocoInProcessExtension`(JUnit 5 자동등록)으로
teamscale 확장을 대체. `attachTeamscaleAgent`의 직렬 핀도 그때 함께 다룬다. 별도
brainstorming → spec → plan.

## 9. 미해결 질문

- 없음(설계 수준). 의존 해소 세부(release 버전 고정 값, mavenLocal 빌드 트리거)는 plan 단계에서 확정.

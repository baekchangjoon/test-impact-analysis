# 설계: 병렬 테스트 수집 지원 (out-of-process / pjacoco)

상태: 리뷰용 초안 (다중 벤더 리뷰 반영 rev2)
작성일: 2026-06-19
브랜치: `feat/parallel-collection`
관련 피드백: graph-rag 적용기 P2-3 (고정 포트 → 직렬 강제)

## 1. 배경 & 문제

TIA의 per-test 커버리지 수집은 현재 **직렬만** 지원한다. 두 가지가 맞물려 있다:

- TIA Gradle 플러그인의 내장 헬퍼가 에이전트를 **Test 태스크 JVM에 부착**하고 `maxParallelForks=1`을
  하드코딩한다 — `TiaPlugin.attachCoverageAgent`(`TiaPlugin.java:85,87`),
  `attachTeamscaleAgent`(`TiaPlugin.java:103`). 단위 테스트가 이 계약을 잠근다(`TiaPluginTest.java:93,106`).
- 에이전트가 Test JVM에 붙는 모델에서는 `maxParallelForks>1`로 포크를 여러 개 띄우면 각 포크 JVM이
  **동일한 고정 control 포트에 바인드**하려다 충돌한다(`BindException`). 즉 이 토폴로지에서 직렬 핀은
  단순한 과보수가 아니라 **충돌 방지 장치**다.

그러나 진짜 out-of-process 토폴로지 — pjacoco 에이전트를 **단일 SUT 프로세스**에 붙이고, **별도의**
테스터가 그 SUT에 요청을 보내는 구조 — 에서는 control 포트가 SUT 하나에만 있으므로 테스터를 여러
포크/스레드로 병렬화해도 충돌하지 않는다. petclinic 데모가 정확히 이 구조로 parallelism=8을 달성했다.

문제의 본질: **TIA에는 이 병렬-가능 토폴로지가 와이어·문서·테스트되어 있지 않다.** 유일하게 정비된
경로(내장 헬퍼)는 에이전트를 Test JVM에 붙여 직렬에 묶인다. 대형 멀티모듈 직렬 수집은 비싸다
(graph-rag-builder 통합테스트 직렬 8m30s).

## 2. 목표 & 불변식

- **목표**: out-of-process(pjacoco) per-test 수집을 **병렬로** 가능하게 한다 — pjacoco 에이전트를
  단일 SUT에 붙이고 테스터를 병렬화. 테스터 병렬은 두 형태를 지원한다:
  - **(2a) Gradle `maxParallelForks>1`** — 테스터 JVM 프로세스 분리.
  - **(2b) JUnit 5 in-JVM 병렬** — 한 테스터 JVM 안 스레드 병렬
    (`junit.jupiter.execution.parallel.enabled=true`). 동기 HTTP 호출(RestAssured) 기준으로 지원한다.
- **핵심 불변식 (하드 게이트)**: 병렬로 수집한 testwise가 직렬과 **per-test 단위로 동일**하다 — 각
  테스트의 (커버 파일, 커버 라인) 집합이 직렬과 같고, 교차 오염 0.
- **부가 목표**: 대표 스위트에서 직렬 대비 병렬 **speedup 실측·기록**.
- **명시적 한계 (비목표, pjacoco phase-2와 일치)**: 테스트가 작업을 **자식 스레드·스레드풀로 위임**하면
  그 작업의 커버리지는 해당 testId로 귀속되지 않을 수 있다(컨텍스트 전파는 동기 호출 스레드에 한함).
  이 경우는 본 spec에서 풀지 않고 문서화한다.
- **비목표(다음 phase)**: in-process per-test 수집을 teamscale 대신
  pjacoco(`PjacocoInProcessExtension`)로 전환. 별도 spec.

## 3. 근거 — 병렬 기계 장치는 pjacoco가 이미 소유·검증

이 작업은 새 수집/격리 메커니즘을 만들지 않는다. pjacoco(parallel-per-test-coverage)가 제공한다
(`~/github_parallel-per-test-coverage`):

- **격리(SUT 측)**: ByteBuddy advice가 jacoco 프로브에 additive 기록을 끼워, **SUT의 요청 처리
  스레드에 활성화된 testId 스토어**(`CoverageContext`, `ThreadLocal<TestStore>`)에 복제한다 → 동시
  요청이 서로 다른 SUT 스레드에서 처리되어 교차 오염 0.
- **두 협력 경로**: ① 제어 엔드포인트 `POST /__coverage__/test/start|stop?testId=...`가 `TestStore`를
  등록/flush하고, ② 인입 요청의 Baggage(`baggage: test.id=...`)가 그 요청을 처리하는 SUT 스레드의
  `CoverageContext`에 해당 `TestStore`를 set한다. 둘 다 있어야 프로브 기록이 testId에 도달한다.
- **테스터 측 식별자**: 테스트킷이 현재 testId를 보관하고(`Pjacoco.currentTestId()`) 동기 요청에
  baggage 헤더로 싣는다(`PjacocoRestAssured`). 동기 호출이므로 (2b) in-JVM 병렬에서도 각 워커
  스레드가 자기 testId를 정확히 싣는다.
- **출력**: testId별 `<testId>.exec`(바닐라 JaCoCo 바이트 호환) + 사이드카 `.json`.
- **실증**: petclinic 데모가 parallelism=8에서 24개 블랙박스 테스트의 per-test 귀속을 정확히 수행
  (`petclinic-demo/00-SUMMARY.md:14-16`).
- **TIA 소비 경로 준비됨**: `tia convert --exec-dir <dir> --classes <classes> --out testwise.json`이
  `<testId>.exec` 디렉터리를 testwise로 변환한다(`ConvertCommand.java`, `TestwiseConverter`).

또한 TIA 기존 배포 설계가 이미 이 방향을 명시한다 — `tia-gradle-plugin/README.md` §(a)는
**"권장 — pjacoco 네이티브 플러그인 + 테스트킷"**, 내장 헬퍼는 **"공개 배포 전 임시"**. 본 작업은
**의도된 pjacoco 경로의 활성화 + 병렬 토폴로지 확립 + E2E 잠금**이다.

> 참고(범위 밖): `TestwiseConverter.convert`는 `.exec`를 단일 스레드로 순차 분석한다. 수백 개 테스트
> 규모에서는 convert가 수집 speedup을 상쇄할 수 있으나, 이는 수집 병렬화와 별개의 최적화로 후속에서
> 다룬다(본 spec의 목표 아님).

## 4. 아키텍처 & 데이터 흐름 (병렬 토폴로지)

```
[테스터 (별도 JVM/들)]                         [SUT: 단일 프로세스]
  직렬(forks=1) 또는                            fixture-app + -javaagent: pjacoco agent
  병렬 (2a forks>1  /  2b in-JVM 스레드)         (control 포트 1개 — 공유, 충돌 없음)
        │  PjacocoExtension: 테스트별 start/stop ──► /__coverage__/test/start|stop
        │  PjacocoRestAssured: 요청에 baggage test.id ──► 인입 요청 → SUT 스레드 CoverageContext
        ▼                                              │ testId별 .exec  (SUT 스레드 ThreadLocal 격리)
                                                       ▼
        tia convert --exec-dir <pjacoco out> --classes <main classes> --out testwise.json
                                                       ▼
                                          tia index → tia.db → tia impact
```

핵심: **에이전트는 단일 SUT에만** 붙는다. 테스터가 몇 개 포크/스레드든 모두 같은 SUT control 포트로
향하므로 충돌이 없고, pjacoco가 testId로 분리하므로 산출물은 직렬과 동일해야 한다. (반대로 에이전트를
테스터 Test JVM에 붙이는 내장 헬퍼 모델은 forks>1에서 포트 충돌 → §5.1 참조.)

## 5. 변경 컴포넌트 (TIA 레포)

### 5.1 `tia-gradle-plugin`
- **병렬 경로 = pjacoco 플러그인(`io.pjacoco.gradle`)을 SUT 태스크에 적용.** pjacoco 플러그인은
  forks를 강제하지 않고 에이전트+control-url을 주입한다. 권장 경로 문서·예제를 이 토폴로지로 정비.
- **내장 `attachCoverageAgent`의 `maxParallelForks=1` 핀은 유지한다.** 이 헬퍼는 에이전트를 Test
  JVM에 붙이므로 forks>1이면 고정 포트 충돌이 난다 — 핀 제거는 안전하지 않다. 헬퍼는 "pjacoco
  미게시 전 임시·직렬 브리지"로 문서화하고, 병렬이 필요하면 pjacoco 플러그인 토폴로지(§4)를 쓰도록
  안내한다. (`TiaPluginTest`의 `maxParallelForks==1` 단언은 그대로 유효 → 변경 없음.)
- **Javadoc/주석 교정**: `TiaPlugin.attachCoverageAgent`의 Javadoc은 현재 "(in-process model)"로
  오기되어 있고(`TiaPlugin.java:77`) "고정 포트라 단일 fork" 근거를 단다. 모델 표기를
  out-of-process(임시 직렬 브리지)로 바로잡고, 병렬은 pjacoco 플러그인 경로임을 명시한다.
  `TiaArgs.java:46-54`의 동일 취지 주석도 정합화한다.
- `attachTeamscaleAgent`(in-process)는 이 spec에서 건드리지 않는다(다음 phase).

### 5.2 문서
- `GETTING-STARTED.md` §1 out-of-process, `tia-gradle-plugin/README.md` §(a)를 갱신: 병렬 토폴로지
  (에이전트=단일 SUT, 테스터=병렬), 두 메커니즘(2a/2b), 동기-호출 한계(§2).
- **aggregate 비활성 두 경로 명시**: pjacoco 플러그인 경로는 `pjacoco { aggregate.set(false) }`,
  내장 헬퍼 경로는 jvmarg `aggregate=false`. 둘 다 빠지면 `aggregate.exec`가 생성되며,
  `TestwiseConverter`가 이를 방어적으로 스킵하는 것은 이중 안전망임을 기록한다.
- **testId 키 형식 차이 주의**: pjacoco `ClassName#method` vs teamscale `FQN/method`. 한 인덱스 안에서
  두 형식을 섞지 말 것(섞으면 `tia impact` 조인이 어긋남). 본 E2E는 양쪽 실행 모두 pjacoco 키를
  쓰므로 동등 비교가 잘 정의된다.
- **내장 헬퍼 문단 교정**: `tia-gradle-plugin/README.md` §(a)의 "대안 — TIA 내장 헬퍼"
  문단(현재 README.md:60-67)은 "SUT 프로세스에 에이전트를 직접 붙이고"라고 하면서 예제는
  `attachCoverageAgent(t, ...)`를 **Test 태스크 `t`** 에 호출한다 — 토폴로지 불일치. 이 헬퍼는
  에이전트가 Test JVM에 붙는 **직렬·in-JVM 부착 브리지**임을 명확히 하고, 진짜 out-of-process
  병렬은 §4 토폴로지(에이전트=단일 SUT)임을 적는다.
- pjacoco 미게시 동안 의존 해소 절차(§6)를 문서화.

### 5.3 E2E 모듈 (`e2e/`)
- §7의 수용 테스트(신규 pjacoco 테스터)를 추가. fixture-app은 SUT(서버)로 기동, 테스터는 e2e 모듈에
  새로 작성(기존 `ApiSmokeTest`/teamscale 확장은 그대로 두고 건드리지 않는다).
- **신규 의존/태스크(현재 `e2e/build.gradle`엔 JUnit 5만 있음 → 추가 필요)**: RestAssured,
  pjacoco testkit(`pjacoco-testkit-junit5`, `pjacoco-testkit-restassured`), fixture-app `bootJar`
  산출물 참조, SUT 외부 프로세스 기동/종료 수단(JavaExec/ProcessBuilder). 구체 와이어링은 plan에서 확정.

## 6. 에러 처리 / 전제

- **aggregate=false** 를 수집 구성에 명시(기본 ON). 누락 시 backstop은 `TestwiseConverter`의
  `aggregate.exec` 스킵.
- **결정성 전제**: 직렬/병렬 두 실행은 **동일한 `--classes` 디렉터리(동일 빌드/커밋)** 를 쓴다.
  `tia convert`는 동일 classesDir에 대해 결정적(동일 라인 범위)이다. 클래스파일이 다르면(다른 JDK/경로
  컴파일) 라인 범위가 달라질 수 있다.
- **CI 의존 해소(미게시 pjacoco 1.1.0)**: 에이전트 jar은 GitHub release 고정 버전 다운로드, testkit/
  gradle-plugin은 **CI 단계에서 pjacoco 소스를 빌드해 `publishToMavenLocal`**(또는 `includeBuild`)로
  해소한다. **단순 "있으면 실행, 없으면 skip"에 의존하지 않는다** — 그러면 병렬 E2E가 CI에서 상시
  skip(거짓 green)이 되어 가치가 없다.
  현재 `.github/workflows/ci.yml`에는 pjacoco 빌드/게시 단계가 없고 pjacoco 에이전트 다운로드
  스크립트도 없다(teamscale만 `scripts/download-agent.sh`로 받음). 따라서 plan에서: ① pjacoco
  에이전트 jar 다운로드 스크립트(버전 핀) 추가, ② pjacoco 소스 체크아웃·빌드·`publishToMavenLocal`
  CI 스텝 추가, ③ §7.1 병렬 E2E용 CI job 추가, ④ 해소 실패 시 **skip이 아니라 fail** 처리 — 를
  명시적으로 정의한다.
- **불가피한 skip**: 로컬 등 pjacoco를 끝내 해소 못 하는 환경에서는 **명시적 메시지와 함께 skip**하고
  그 사실을 보고한다(silent green 금지). pjacoco 공개 게시 후 조건부 skip을 제거하는 시점도 문서화.
- **동시 제어 호출 안전성((2a))**: forks가 여럿이면 여러 테스터가 단일 SUT control 포트에 동시
  start/stop을 보낸다. pjacoco의 제어 서버/`TestStoreRegistry`가 동시 호출을 안전 처리함을 plan에서
  코드로 확인(이상 시 직렬화 또는 보고).
- **SUT 기동/종료**: fixture-app을 bootJar로 빌드 후 외부 프로세스로 기동(`-javaagent` attach),
  테스터 실행 후 종료. 구체 와이어링(태스크/lifecycle)은 plan에서 정의.
- **SUT 백그라운드 활동**: fixture-app의 `NoiseScheduler`(fixedRate 백그라운드 호출)는 testId
  컨텍스트가 없는 스케줄러 스레드에서 돌아 per-test에 귀속되지 않는다(설계상 안전). 그래도 직렬/병렬
  간 잡음 차이를 배제하기 위해 E2E에서는 스케줄링을 끈다(profile/property). plan에서 확정.

## 7. 테스트 & 수용 기준 (E2E 먼저 작성)

수용 E2E를 **먼저(red)** 작성하고 구현으로 green을 만든다(테스트 우선; 레포의 기존 E2E 패턴과 동일
계층 — 컨테이너/out-of-process 블랙박스).

### 7.1 수용 E2E — out-of-process 병렬==직렬 (in-repo, fixture-app SUT)
- **토폴로지**: `fixture-app`을 SUT 서버로 기동하고 pjacoco 에이전트 attach(aggregate=false). 테스터는
  **e2e 모듈에 신규 작성** — `@ExtendWith(PjacocoExtension.class)` + `PjacocoRestAssured.enable()`로
  baggage 전파(기존 teamscale 기반 ApiSmokeTest는 사용하지 않는다).
  - 이 E2E는 **§4 토폴로지(에이전트=단일 SUT + 병렬 테스터)** 자체를 명시적 와이어링으로 증명한다.
    pjacoco Gradle 플러그인 DSL 경로(`io.pjacoco.gradle` on SUT 태스크)는 문서 산출물(§5.2)로 다루고
    최소 스모크로 확인한다(플러그인 경로 전체 E2E는 별도). — 리뷰 I9 반영.
- **테스트 케이스**: 동시성이 실제로 발생하도록 **서로 독립인 결정적 케이스 ≥8개**를 e2e 테스터에
  새로 둔다(petclinic parallelism=8에 준함). fixture-app의 **비결정 엔드포인트(`/flaky`)는 커버리지
  E2E에서 제외**한다.
- **격리된 출력 디렉터리**: 세 실행은 각각 별도 exec 디렉터리(`build/tia/cov-serial`, `cov-forks`,
  `cov-injvm`)에 쓰고, 각 수집 전 디렉터리를 비운다(이전 `.exec` 잔존이 집합 비교를 오염시키지 않게).
- **(2b) JUnit 병렬 설정**: `junit-platform.properties`(또는 `test{ systemProperty }`)에
  `junit.jupiter.execution.parallel.enabled=true`, `...mode.default=concurrent`, 고정 스레드 수
  지정. **병렬이 실제로 일어났는지**도 확인한다(동시 실행 로깅/프로브). 정확한 키·스레드 수는 plan에서.
- **절차**: 동일 SUT(동일 `--classes`)에 대해
  1. **직렬**(forks=1) → `tia convert` → `testwise_serial.json`
  2. **병렬 (2a)** Gradle `maxParallelForks>1` → `tia convert` → `testwise_forks.json`
  3. **병렬 (2b)** JUnit in-JVM 병렬 → `tia convert` → `testwise_injvm.json`
- **비교기(명시)**: 각 testwise를 `Map<uniformPath, Map<fileName, Set<Integer>>>` 로 정규화한다 —
  커버 라인 범위는 `LineRangeParser`로 정수 집합으로 펼치고, 키는 정렬해 비교. 공유 테스트 유틸로
  구현(엔지니어 간 비교 구현 차이 방지). uniformPath는 양쪽 모두 pjacoco `ClassName#method` 키.
- **수용 단언(하드 게이트)**:
  - (a) 병렬 산출물 (2a)·(2b)이 **각각** 직렬과 위 정규화 형태로 equals(테스트 집합 + 테스트별 파일·
    라인 집합 동일).
  - (b) **각 테스트의 커버리지가 비어 있지 않다**(테스트별 .exec에 커버 라인 존재) — baggage 전파가
    조용히 실패해 빈 .exec가 나오는데 equality가 우연히 성립하는 거짓 green을 막는 안전망.
- **speedup(정보용, 게이트 아님)**: 세 실행의 벽시계 시간을 측정·기록한다. 공유 CI 러너에서 벽시계는
  비결정적이므로 **"병렬이 직렬보다 빠름"을 하드 단언하지 않는다**(머지 플레이크 방지). 게이트는
  정확성(a/b)만. speedup 단언은 로컬/벤치마크 프로파일에서 여유 마진으로만 선택 수행. — 리뷰 I10 반영.

### 7.2 단위 테스트
- `TiaPluginTest`: 내장 헬퍼의 기존 jvmArg/`maxParallelForks==1` 계약 유지 단언(헬퍼는 직렬 브리지로
  남으므로). Javadoc 교정은 동작 영향 없음.

### 7.3 회귀
- 전체 스위트(unit + 기존 E2E) green. pjacoco 미해소 시 7.1은 §6대로 처리(CI는 빌드·해소, 불가 환경만
  명시적 skip 보고).

### 완료 정의
- 7.1 green: 두 병렬 메커니즘(2a/2b) 산출물이 각각 직렬과 동일(정확성) + 각 테스트 커버리지 non-empty
  + speedup 기록.
- 7.2 단위 통과, 7.3 회귀 green(또는 명시적 skip 보고).
- 관련 문서(§5.2) 갱신 포함.

## 8. 다음 phase (이 spec 밖)
- in-process per-test 수집을 pjacoco로 전환: `PjacocoInProcessExtension`(JUnit5 자동등록)으로 teamscale
  확장 대체. `attachTeamscaleAgent` 직렬 핀도 그때 함께. 별도 brainstorming → spec → plan.
- **전파 substrate 재검토(cross-service per-test 귀속 시)**: 현재 pjacoco 전파는 동기 HTTP 헤더 명시
  stamp(서블릿 인입 전제)라 자식 스레드/스레드풀/비동기/Kafka·다운스트림 서비스로는 test.id가
  따라가지 않는다(pjacoco phase-2 한계). TIA 로드맵의 "크로스 레포/API 경계 매핑·Kafka 귀속"을 할
  때는 **OpenTelemetry 기반 컨텍스트 전파(스레드/비동기/메시징/네트워크 경계 자동 전파) 위에 pjacoco
  커버리지 라우팅을 얹는 하이브리드**를 재검토한다. 단일 SUT·동기 호출인 본 spec 범위에서는 불필요.

## 9. 다중 벤더 리뷰 반영 요약

리뷰어: Claude Sonnet ×2(주 리뷰 + GPT 슬롯 폴백), Gemini 3.5 Flash(High), Cursor(auto).

rev1 반영(3-벤더):
- (수용) 내장 헬퍼 핀 제거는 포트 충돌 위험 → **핀 유지**, 병렬은 pjacoco-플러그인-온-SUT 토폴로지로
  (Gemini I1).
- (수용) E2E 테스터를 teamscale 확장이 아닌 **신규 pjacoco 테스터**로 (3-벤더 공통).
- (수용) **aggregate=false** 두 경로 명시 + converter backstop (Claude×2).
- (수용) in-JVM 격리 설명 교정(격리는 SUT-측 ThreadLocal; 테스터는 동기로 baggage만 싣음) + **커버리지
  non-empty 안전망**(Claude). 동기-호출 한계 명문화.
- (수용) **testId 키 형식 차이**, **동일 classesDir 결정성**, Javadoc 오기 교정, 데이터흐름 두 경로,
  제어 동시성, **CI 의존 해소(상시 skip 금지)** (Claude×2 / Gemini).

rev2 반영(Cursor auto):
- (수용) 내장 헬퍼 README 문단(README.md:60-67) 토폴로지 불일치 교정 §5.2 (I1/I2).
- (수용) "CLAUDE.md double-loop" → 레포 내 표현(테스트 우선)으로 (I3).
- (수용) **비교기 명시**(`Map<uniformPath,Map<fileName,Set<Integer>>>`, 라인 펼침, 공유 유틸) (I4).
- (수용) **JUnit 병렬 설정 명시 + 병렬 실증** (I5).
- (수용) **실행별 격리 exec 디렉터리 + 정리** (I6).
- (수용) **결정적 케이스 ≥8 + `/flaky` 제외** (I7).
- (수용) **CI 워크플로 변경 명시(빌드/게시/다운로드/E2E job, skip 아닌 fail)** (I8).
- (수용) **§5.1 vs §7.1 토폴로지 일관화** — E2E는 명시 와이어링으로 토폴로지 증명, 플러그인 DSL은
  문서+스모크 (I9).
- (수용) **speedup 하드 게이트 제거, 정보용 기록만** (I10).
- (수용) e2e 모듈 신규 의존/태스크 명시 §5.3 (I11).
- (수용) **NoiseScheduler** E2E에서 비활성 §6 (I12).

보류·범위 밖:
- `TestwiseConverter` 순차 처리 병렬화 — 수집이 아닌 convert 최적화, 후속(Gemini I4 / §3 참고).
  근거: 본 spec 목표는 수집 병렬화. convert 성능은 별개 최적화로 분리.

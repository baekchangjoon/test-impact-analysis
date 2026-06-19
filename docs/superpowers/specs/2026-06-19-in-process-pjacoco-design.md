# 설계: in-process per-test 수집을 pjacoco로 전환 + teamscale 은퇴

상태: 리뷰용 초안
작성일: 2026-06-19
브랜치: `feat/in-process-pjacoco`
선행: 2026-06-19 병렬 수집(out-of-process/pjacoco) — out-of-process는 이미 pjacoco로 통일됨.

## 1. 배경 & 문제

지난 phase에서 **out-of-process** per-test 수집을 pjacoco로 통일했다. 남은 것은 **in-process(in-JVM)**
경로다. 현재 repo의 "in-process" 수집은 teamscale-jacoco-agent(`mode=TESTWISE`) + `TeamscaleTestwiseExtension`
+ `attachTeamscaleAgent`로 구성되며, 에이전트의 **HTTP control 서버**(`http-server-port`)에 테스트
start/end를 신호한다. 이 구조의 한계:

- HTTP control 포트가 고정이라 `maxParallelForks=1`(직렬) 강제.
- 두 종류의 에이전트(teamscale + pjacoco)가 공존 — 유지보수·문서·배포 표면이 둘로 갈림.
- 실제로 repo에서 teamscale는 **out-of-process 컨테이너 E2E**(`docker-compose.e2e.yml`)에 쓰여 왔는데,
  그 검증은 이제 pjacoco out-of-process(parallel-e2e)로 대체 가능하다.

pjacoco는 **순수 in-JVM per-test 수집**(서블릿/HTTP 경계 없이, SUT를 테스트 스레드에서 직접 호출)을
위한 `PjacocoInProcessExtension` + 에이전트 `CoverageControl` API를 이미 제공한다. HTTP 포트가
필요 없어 **병렬(forks·in-JVM 스레드)이 자유롭다**.

## 2. 목표 & 불변식

- **목표**: in-JVM per-test 커버리지 수집을 pjacoco `PjacocoInProcessExtension`로 제공하고, **teamscale를
  완전히 은퇴**시킨다(에이전트·확장·헬퍼·다운로드·POC·컨테이너 E2E 모두 pjacoco로 대체/이전).
- **핵심 불변식(하드 게이트)**: in-JVM 병렬(forks·in-JVM 스레드) 수집 testwise가 직렬과 **per-test
  단위로 동일**(각 테스트의 (파일, 라인) 집합 동일, 교차 오염 0).
- **non-empty 안전망**: 병렬 산출의 각 테스트가 ≥1 커버 라인(거짓 green 방지).
- **동시성 실증**: 병렬 모드가 실제로 동시 실행됐음을 프로브로 증명(§5).
- **회귀 무손상**: teamscale 제거 후 전체 빌드·테스트·CI green, 컨테이너 out-of-process E2E는 pjacoco로
  green.
- **비목표(다음 phase)**: cross-service 전파(OTel substrate), `TestwiseConverter` 순차 처리 병렬화.

## 3. 근거 — pjacoco in-process는 이미 구현·검증됨

`~/github_parallel-per-test-coverage`:
- `PjacocoInProcessExtension`(testkit-junit5): 각 테스트 전후로 `InProcessBridge.activate/deactivate`
  → 에이전트 `CoverageControl.activate/deactivate`(리플렉션)로 **테스트 스레드의 ThreadLocal
  `CoverageContext`** 설정. 순수 in-JVM 테스트(SUT 직접 호출, 서블릿 없음)가 per-test `.exec`를 받는다.
  testId = **FQN#method**.
- `CoverageControl`(agent.api): 안정 계약(리플렉션 호출). activate/본문/deactivate는 **같은 스레드**에서.
  HTTP/포트 불필요.
- JUnit 5 자동등록: pjacoco gradle 플러그인이 `junit.jupiter.extensions.autodetection.enabled=true`를
  켜고 `PjacocoInProcessExtension`이 유일한 service-registered 확장 → 애너테이션 불요.
- 출력 형식은 out-of-process와 동일(`<testId>.exec`) → `tia convert`가 그대로 소비.

fixture-app에 in-JVM 대상이 있다: `GreetingService.greet`, `PricingService.priceOf`, `TextUtil.normalize`
— HTTP 없이 직접 호출 가능.

## 4. 아키텍처 & 데이터 흐름

```
[테스터 JVM(들) — 직렬 / forks(별 JVM) / in-JVM 스레드]
  PjacocoInProcessExtension: 각 테스트 전후 CoverageControl.activate(testId)/deactivate (테스트 스레드)
  └ 테스트 본문이 fixture-app 서비스를 in-JVM 직접 호출
        │  pjacoco 에이전트가 그 스레드의 ThreadLocal 컨텍스트로 프로브를 testId 스토어에 복제
        ▼  (HTTP/포트 없음 → 포트 충돌 없음, 병렬 자유)
   <testId>.exec  →  tia convert --exec-dir <dir> --classes <main classes> --out testwise.json
        ▼
   직렬 testwise와 per-test 비교(정규화 equals)
```

forks: 각 fork는 별 JVM = 자체 pjacoco 에이전트 인스턴스가 자기 destfile 디렉터리에 per-test `.exec`를
쓴다(JVM 격리). in-JVM 스레드: 한 JVM 안에서 `CoverageContext`(ThreadLocal)로 테스트별 분리.

## 5. in-process 동시성 실증 (out-of-process와 다른 점)

out-of-process는 **단일 SUT**의 maxConcurrent를 프로브했다. in-process는 **forks가 별개 JVM**이라 공유
SUT 카운터가 없다. 따라서 **공유 파일 오버랩 프로브**를 쓴다:

- 각 테스트가 본문에서 공유 append-only 파일(경로 = 시스템 프로퍼티, 모든 fork/스레드 공통)에
  `testId,START,<epochMillis>`와 `testId,END,<epochMillis>`를 기록한다. 동시성이 관측되도록 본문에
  작은 고정 지연(예: 150ms)을 둔다(커버 라인 불변 — 타이밍만).
- 사후 분석: 기록된 [START,END] 구간 중 **≥2개가 겹치면 동시 실행 증명**. forks(파일 공유)·in-JVM(동시
  append) 모두 일관 동작. POSIX append(O_APPEND)는 작은 쓰기에 원자적.
- 수용: forks·in-JVM 각 모드에서 겹치는 구간 ≥1쌍; 직렬 모드는 겹침 0.

## 6. 변경 컴포넌트 (TIA 레포)

### 6.1 신규 (in-process 수집·검증)
- `e2e` 모듈: in-JVM 테스터(서비스 직접 호출, `@ExtendWith(PjacocoInProcessExtension)` 또는 자동등록),
  공유 파일 오버랩 프로브 유틸 + 사후 분석, in-process 오케스트레이터
  (`scripts/run-inprocess-e2e.sh`: 직렬/forks/in-JVM 수집 → `tia convert` → 수용 비교), 수용 테스트.
- in-process 테스터도 pjacoco testkit에 의존하므로, 지난 phase에서 채택한 방식을 그대로 따른다: e2e
  모듈에 testkit 의존을 두고, `Tests + Coverage` CI 잡이 컴파일 전에 `setup-pjacoco.sh`로 mavenLocal에
  해소(이미 main에 적용됨). 새 소스셋은 만들지 않는다(불필요한 복잡도 회피).

### 6.2 제거 (teamscale)
- `tia-junit-extension`: `TeamscaleTestwiseExtension`, `HttpAgentClient`(+ 각 테스트). (모듈이 비면
  모듈 자체 제거 검토 — 단, `currentTestId()`를 쓰는 곳이 남았는지 확인 후.)
- `tia-gradle-plugin`: `attachTeamscaleAgent`, `TiaArgs.teamscaleAgentJvmArg`(+ 관련 단위 테스트).
- `scripts/download-agent.sh`, `scripts/run-poc.sh`(teamscale POC).
- `fixture-app/.../ApiSmokeTest.java`(teamscale 기반) — pjacoco 경로로 대체하거나 제거.

### 6.3 마이그레이션 (out-of-process 컨테이너 E2E → pjacoco)
- `docker-compose.e2e.yml` + `scripts/docker-e2e-tester.sh` + ci `docker-e2e` job: sut에 **pjacoco
  에이전트**(서블릿 경로) 부착, tester는 `PjacocoExtension` + `PjacocoRestAssured`로 baggage 전파.
  네트워크 격리 out-of-process 검증을 pjacoco로 유지.
- `TestwiseConverter`/`LineRangeParser`의 teamscale 주석 정리(코드 동작 무관, 포맷은 동일).
- 문서: `README.md`·`GETTING-STARTED.md`·`THIRD-PARTY-NOTICES.md`의 teamscale 언급을 pjacoco로 갱신.

## 7. 에러 처리 / 전제
- pjacoco 미게시 → 소스 빌드→mavenLocal(`scripts/setup-pjacoco.sh`, 지난 phase 산출물). 에이전트 jar도
  거기서. CI 해소 실패는 **fail**(skip 아님).
- 결정성: 직렬/병렬 비교는 동일 `--classes`(fixture-app main classes)로 convert.
- testId 키: in-process는 pjacoco **FQN#method**. 한 인덱스에 형식 혼용 금지.
- 동시성 프로브 지연은 e2e 전용(커버 라인 불변). 공유 파일 경로는 모드별 격리 디렉터리 안.

## 8. 테스트 & 수용 기준 (E2E 먼저 작성)
수용 E2E를 먼저(red) 작성하고 구현으로 green.

### 8.1 in-process 수용 E2E (in-JVM, fixture-app 서비스)
- in-JVM 테스터(서비스 직접 호출, ≥8 결정적 케이스)를 직렬/forks/in-JVM 3모드로 수집(모드별 격리 exec
  디렉터리, 동일 `--classes`) → `tia convert` → testwise_{serial,forks,injvm}.json + overlap 로그.
- 수용 단언:
  - (a) forks·in-JVM 산출물이 **각각** 직렬과 per-test 동일(정규화 equals).
  - (b) 각 테스트 커버리지 non-empty.
  - (c) forks·in-JVM 각 모드에서 오버랩 구간 ≥1쌍(동시성 실증), 직렬은 겹침 0.

### 8.2 out-of-process 컨테이너 E2E (pjacoco 마이그레이션)
- 마이그레이션된 컨테이너 E2E(pjacoco 서블릿 경로)가 per-test 수집→index→impact green.

### 8.3 teamscale 부재 + 회귀
- 코드/스크립트/CI/문서에 teamscale 잔존 0(grep 게이트). 전체 회귀 `./gradlew test` green. 기존
  parallel-e2e(out-of-process) 여전히 green.

### 완료 정의
- 8.1 green(정확성 a + non-empty b + 동시성 c), 8.2 green, 8.3 green(teamscale 잔존 0 + 회귀).
- 관련 문서 갱신 포함.

## 9. 미해결 질문
- 없음(설계 수준). in-JVM 테스터의 정확한 케이스 구성, 오버랩 지연 값, 컨테이너 마이그레이션 세부는
  plan에서 확정.

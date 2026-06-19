# 설계: in-process per-test 수집을 pjacoco로 전환 + teamscale 은퇴

상태: 리뷰용 초안 (다중 벤더 리뷰 반영 rev1)
작성일: 2026-06-19
브랜치: `feat/in-process-pjacoco`
선행: 2026-06-19 병렬 수집(out-of-process/pjacoco) — out-of-process는 이미 pjacoco로 통일됨.

## 1. 배경 & 문제

지난 phase에서 **out-of-process** per-test 수집을 pjacoco로 통일했다. 남은 것은 **in-process(in-JVM)**
경로다. 현재 repo의 "in-process" 수집은 teamscale-jacoco-agent(`mode=TESTWISE`) + `TeamscaleTestwiseExtension`
+ `attachTeamscaleAgent`로 구성되며, 에이전트의 **HTTP control 서버**(`http-server-port`)에 테스트
start/end를 신호한다. 한계: HTTP control 포트가 고정이라 `maxParallelForks=1`(직렬) 강제; teamscale와
pjacoco 두 에이전트가 공존; 실제로 teamscale는 out-of-process 컨테이너 E2E에 쓰여 와 이제 pjacoco로
대체 가능하다.

pjacoco는 **순수 in-JVM per-test 수집**(서블릿/HTTP 경계 없이, SUT를 테스트 스레드에서 직접 호출)을
위한 `PjacocoInProcessExtension` + 에이전트 `CoverageControl` API를 이미 제공한다. HTTP 포트가
필요 없어 **병렬(forks·in-JVM 스레드)이 자유롭다**.

## 2. 목표 & 불변식

- **목표**: in-JVM per-test 수집을 pjacoco `PjacocoInProcessExtension`로 제공하고, **teamscale를 완전히
  은퇴**시킨다(에이전트·확장·헬퍼·다운로드·POC·컨테이너 E2E 모두 pjacoco로 대체/이전).
- **핵심 불변식(하드 게이트)**: in-JVM 병렬(forks·in-JVM 스레드) 수집 testwise가 직렬과 **per-test
  단위로 동일**(각 테스트의 (파일, 라인) 집합 동일, 교차 오염 0).
- **non-empty 안전망**: 병렬 산출의 각 테스트가 ≥1 커버 라인(거짓 green 방지).
- **동시성 실증**: 병렬이 실제 동시 실행됐음을 프로브로 증명(§5). 하드 게이트는 **in-JVM 스레드 모드**
  (결정적); forks 동시성은 정보용 기록(CI 코어 수에 의존해 플랩 가능 — §5/§8.1).
- **회귀 무손상**: teamscale 제거 후 전체 빌드·테스트·CI green, 컨테이너 out-of-process E2E는 pjacoco로
  green.
- **비목표(다음 phase)**: cross-service 전파(OTel substrate), `TestwiseConverter` 순차 처리 병렬화.

## 3. 근거 — pjacoco in-process는 이미 구현·검증됨

pjacoco 소스 `~/github_parallel-per-test-coverage/parallel-per-test-coverage`
(= `setup-pjacoco.sh`의 `PJACOCO_SRC` 기본값):
- `PjacocoInProcessExtension`(testkit-junit5): 각 테스트 전후로 `InProcessBridge.activate/deactivate`
  → 에이전트 `CoverageControl.activate/deactivate`(리플렉션)로 **테스트 스레드의 ThreadLocal
  `CoverageContext`** 설정. 순수 in-JVM 테스트(SUT 직접 호출, 서블릿 없음)가 per-test `.exec`를 받는다.
  testId = **FQN#method**.
- **전제(중요)**: `CoverageControl.bindRegistry()`는 에이전트 `Bootstrap.premain`에서만 호출된다 →
  **에이전트가 그 JVM에 `-javaagent`로 부착돼 있어야** activate/deactivate가 동작하고, 없으면
  best-effort no-op(커버리지 0). in-process에서는 **테스터 JVM이 곧 SUT**이므로, 에이전트를 그
  테스터 JVM에 붙여야 한다(§6.1).
- 출력 형식은 out-of-process와 동일(`<testId>.exec`) → `tia convert`가 그대로 소비.
- 확장 등록: `PjacocoInProcessExtension`을 **명시적 `@ExtendWith`** 로 단다(자동등록은 클래스패스의
  다른 testkit Extension까지 끌어와 충돌 위험 — §6.1).

fixture-app에 in-JVM 대상이 있다: `GreetingService.greet`, `PricingService.priceOf`, `TextUtil.normalize`
— HTTP 없이 직접 인스턴스화·호출 가능.

## 4. 아키텍처 & 데이터 흐름

```
[테스터 JVM(들) — 직렬 / forks(별 JVM) / in-JVM 스레드]  ← 각 JVM에 -javaagent: pjacoco agent 부착
  @ExtendWith(PjacocoInProcessExtension): 각 테스트 전후 CoverageControl.activate(testId)/deactivate (테스트 스레드)
  └ 테스트 본문이 fixture-app 서비스를 in-JVM 직접 호출(new GreetingService() 등)
        │  pjacoco 에이전트가 그 스레드의 ThreadLocal 컨텍스트로 프로브를 testId 스토어에 복제
        ▼  (HTTP control 미사용 → in-process 경로는 포트 불요; 병렬 자유)
   <testId>.exec  →  tia convert --exec-dir <dir> --classes <main classes> --out testwise.json
        ▼  직렬 testwise와 per-test 비교(TestwiseNormalizer 정규화 equals)
```

- forks: 각 fork는 별 JVM = 자체 에이전트 인스턴스. **동일** destfile 디렉터리에 testId가 다른 `.exec`를
  독립 기록한다(파일명=testId; 클래스 단위 fork 배분이라 충돌 없음).
- in-JVM 스레드: 한 JVM 안에서 `CoverageContext`(ThreadLocal)로 테스트별 분리.
- 포트 노트: 에이전트는 in-process에서도 best-effort로 control endpoint 바인드를 시도하므로, forks에서
  2번째 이후 fork가 같은 포트에 BindException→warn(무해, in-process는 control을 안 씀). jvmArg에
  `port=0`(임의 포트)을 줘 로그 노이즈를 없앤다(§7).

## 5. in-process 동시성 실증 (out-of-process와 다른 점)

out-of-process는 **단일 SUT**의 maxConcurrent를 프로브했다. in-process는 **forks가 별개 JVM**이라 공유
SUT 카운터가 없다. 따라서 **공유 파일 오버랩 프로브**를 쓴다:

- 각 테스트가 본문에서 공유 append 파일(경로=시스템 프로퍼티, 모드별 격리 디렉터리 안, 모든 fork/스레드
  공통)에 `testId,START,<epochMillis>`·`testId,END,<epochMillis>`를 기록한다. 동시성 관측을 위해 본문에
  작은 고정 지연(예: 150ms)을 둔다(커버 라인 불변 — 타이밍만).
- **원자적 기록(중요)**: 한 줄이 쪼개지면 거짓 양성/음성이 난다. 기록은
  `Files.write(path, (line+"\n").getBytes(UTF_8), CREATE, APPEND)`(단일 write syscall)로 하고,
  in-JVM 스레드 동시 기록은 추가로 `synchronized`(또는 단일 기록 락)로 보호한다. POSIX(Linux/macOS)
  전용 — CI는 ubuntu-latest이므로 무방.
- 사후 분석: `OverlapProbeAnalyzer`(e2e 신규)가 파일을 파싱해 [START,END] 구간을 만들고 **겹치는 구간
  쌍 수**를 반환한다. 각 줄이 완전한 CSV(파싱 가능)임도 단언한다.
- 수용(§8.1 c): **in-JVM 모드 겹침 ≥1쌍(하드 게이트)**, 직렬 모드 겹침 0. forks 모드 겹침 수는 기록만
  (CI 코어 수 의존 — 플랩 방지). in-JVM은 한 JVM 내 스레드+지연이라 코어 1개여도 시분할로 구간이
  겹쳐 결정적이다.

## 6. 변경 컴포넌트 (TIA 레포)

### 6.1 신규 (in-process 수집·검증)
- **e2e 모듈 의존 추가**: `testImplementation project(':fixture-app')` — 서비스(`GreetingService` 등)를
  테스터 클래스패스에 둔다. 테스터는 Spring 컨텍스트 없이 `new`로 직접 인스턴스화해 호출(자동 부팅 회피).
  in-process는 `pjacoco-testkit-junit5`만 필요(restassured 불요).
- **in-JVM 테스터**(신규, ≥8 결정적 케이스): `@ExtendWith(PjacocoInProcessExtension.class)`로 각
  테스트를 감싸고 서비스 직접 호출 + 오버랩 프로브 기록. `/flaky` 같은 비결정 대상 없음.
- **에이전트 부착(핵심)**: in-process 테스트 태스크는 **테스터 JVM에 pjacoco 에이전트를 `-javaagent`로**
  단다 — `-javaagent:tools/pjacoco/jacocoagent-parallel.jar=destfile=<cov-mode-dir>,aggregate=false,includes=io.tia.fixture.*,port=0`.
  agent jar 경로는 `setup-pjacoco.sh`가 배치한 `tools/pjacoco/jacocoagent-parallel.jar`. 모드별 destfile
  디렉터리 격리(cov-serial/forks/injvm). (지난 phase와 다른 점: 에이전트가 외부 SUT가 아니라 **테스터
  JVM**에 붙는다.)
- **오케스트레이터** `scripts/run-inprocess-e2e.sh`: setup-pjacoco → 모드별(직렬/forks/in-JVM) 격리
  디렉터리로 in-JVM 테스터 수집(태스크에 위 jvmArg + 모드별 fork/병렬 구성) → `tia convert`(동일
  `--classes`) → 수용 비교 테스트 실행.
- **수용 비교**: 지난 phase의 `TestwiseNormalizer` 재사용 + `OverlapProbeAnalyzer`.
- pjacoco testkit 의존 컴파일은 지난 phase 방식 유지: `Tests + Coverage` CI 잡이 컴파일 전
  `setup-pjacoco.sh`로 mavenLocal 해소(이미 main 적용). 새 소스셋은 만들지 않는다.

### 6.2 제거 (teamscale) — 결정(검토 아님)
- `tia-junit-extension` **모듈 전체 제거**: `TeamscaleTestwiseExtension`·`HttpAgentClient`·`AgentClient`
  + 테스트(`TeamscaleTestwiseExtensionTest`·`HttpAgentClientTest`). `currentTestId()`의 유일 사용처는
  `fixture-app/ApiSmokeTest`(제거됨)뿐임을 확인 후 제거.
  - 연쇄: `settings.gradle`의 `include 'tia-junit-extension'` 제거; `coverage/build.gradle`의
    `jacocoAggregation project(':tia-junit-extension')` 제거; `fixture-app/build.gradle`의
    `testImplementation project(':tia-junit-extension')` 제거.
- `tia-gradle-plugin`: `attachTeamscaleAgent`, `TiaArgs.teamscaleAgentJvmArg`(+ 관련 단위 테스트),
  `tia-gradle-plugin/README.md`의 teamscale(in-process) 섹션.
- `fixture-app`: `ApiSmokeTest.java`(teamscale 기반) 및 그 전용 `RunResultWriter`(teamscale 비의존이나
  ApiSmokeTest 전용 → 함께 제거 또는 pjacoco 테스터로 흡수).
- `scripts`: `download-agent.sh`, `run-poc.sh`(teamscale POC), `measure-flaky.sh`(teamscale 에이전트
  직접 사용 — 제거; flaky 측정 재구현은 다음 phase로 보류, 보류 사실 명시).

### 6.3 마이그레이션 (out-of-process 컨테이너 E2E → pjacoco)
- `docker-compose.e2e.yml`: sut 볼륨 `./tools/teamscale/...` → `./tools/pjacoco/jacocoagent-parallel.jar`,
  에이전트 옵션 teamscale `mode=TESTWISE,...` → pjacoco `destfile=/cov,port=6310,aggregate=false,includes=io.tia.fixture.*`(서블릿 경로).
- `scripts/docker-e2e-tester.sh`: teamscale `convert` 호출 제거 → `tia convert --exec-dir /cov --classes ... --out testwise.json`; 실행 대상 테스트를 **신규 pjacoco 블랙박스 테스터**(예: e2e의 기존 `CoverageTesterIT` 패턴 = `@ExtendWith(PjacocoExtension)` + `PjacocoRestAssured`)로 교체; impact assertion testId를 pjacoco `ClassName#method`로 갱신.
- `.github/workflows/ci.yml` `docker-e2e` job: `download-agent.sh` 단계 → `setup-pjacoco.sh`; job/단계
  이름 "teamscale" → "pjacoco" 갱신.
- `TestwiseConverter`/`LineRangeParser`의 teamscale 주석 정리(동작 무관, 포맷 동일).
- 문서: `README.md`·`GETTING-STARTED.md`·`THIRD-PARTY-NOTICES.md`(teamscale 런타임 의존 제거 → 해당
  고지 섹션 정리)·`tia-gradle-plugin/README.md`.

## 7. 에러 처리 / 전제
- pjacoco 미게시 → 소스 빌드→mavenLocal(`scripts/setup-pjacoco.sh`). 에이전트 jar도 거기서. CI 해소
  실패는 **fail**(skip 아님).
- 결정성: 직렬/병렬 비교는 동일 `--classes`(fixture-app main classes)로 convert.
- in-process jvmArg에 **`aggregate=false`** 필수(기본 ON이면 `aggregate.exec` 생성 → converter 방어
  스킵에 의존하게 됨). **`port=0`** 으로 forks의 control 포트 BindException 경고 제거.
- testId 키: in-process는 pjacoco **FQN#method**. 한 인덱스에 형식 혼용 금지.
- 오버랩 프로브: 원자적 줄 기록(§5), 지연은 e2e 전용(커버 라인 불변), 분석은 테스트 완료 후.

## 8. 테스트 & 수용 기준 (E2E 먼저 작성)
수용 E2E를 먼저(red) 작성하고 구현으로 green.

### 8.1 in-process 수용 E2E (in-JVM, fixture-app 서비스)
- **전제**: 테스터 JVM에 pjacoco 에이전트 `-javaagent`(aggregate=false, port=0) 부착(§6.1) — 없으면
  CoverageControl no-op로 거짓 green이므로, non-empty 단언(b)이 이를 방어.
- in-JVM 테스터(서비스 직접 호출, ≥8 결정적 케이스)를 직렬/forks/in-JVM 3모드로 수집(모드별 격리 exec
  디렉터리, 동일 `--classes`) → `tia convert` → testwise_{serial,forks,injvm}.json + overlap 로그.
- **비교기**: `TestwiseNormalizer.normalize` → `Map<testId, Map<fileKey, RoaringBitmap>>` equals(지난
  phase 재사용).
- 수용 단언:
  - (a) forks·in-JVM 산출물이 **각각** 직렬과 per-test 동일.
  - (b) 각 테스트 커버리지 non-empty(≥1 라인).
  - (c) `OverlapProbeAnalyzer`: **in-JVM 모드 겹침 ≥1쌍(하드 게이트)**, 직렬 겹침 0; forks 겹침 수는
    기록만(비게이트). 오버랩 파일 각 줄이 파싱 가능.

### 8.2 out-of-process 컨테이너 E2E (pjacoco 마이그레이션)
- 마이그레이션된 컨테이너 E2E(pjacoco 서블릿 경로)가 per-test 수집→`tia convert`→index→impact green.

### 8.3 teamscale 부재 + 회귀
- **grep 게이트(범위 한정)**: `git grep -li teamscale -- '*.java' '*.gradle' '*.yml' '*.sh' README.md
  GETTING-STARTED.md THIRD-PARTY-NOTICES.md tia-gradle-plugin/README.md` 결과 **0**.
  **제외**: `docs/superpowers/`(과거 설계·계획 기록 — 역사 보존). THIRD-PARTY-NOTICES.md는 teamscale가
  더 이상 런타임 의존이 아니므로 해당 고지 섹션 제거.
- 전체 회귀 `./gradlew test` green; 기존 parallel-e2e(out-of-process) 여전히 green.

### 완료 정의
- 8.1 green(정확성 a + non-empty b + in-JVM 동시성 c), 8.2 green, 8.3 green(teamscale 잔존 0 + 회귀).
- 관련 문서 갱신 포함.

## 9. 다중 벤더 리뷰 반영 요약
리뷰어: Claude Sonnet ×2(주 + Gemini 슬롯 폴백 — agy 빈 출력), Cursor(auto).
- (critical, 수용) in-process 에이전트는 **테스터 JVM에 `-javaagent`** 부착 + `aggregate=false` 명시(§6.1/§3/§8.1) — 누락 시 no-op 거짓 green.
- (수용) e2e에 **fixture-app 의존** + 서비스 직접 인스턴스화, **명시 `@ExtendWith`**(자동등록 충돌 회피).
- (수용) teamscale 제거 목록 완비: measure-flaky.sh, settings/coverage/fixture-app build.gradle, 모듈 자체 제거, docker-e2e-tester(convert→tia convert), compose 마운트, ci job 이름, plugin README, AgentClient/RunResultWriter(§6.2/6.3).
- (수용) 오버랩 프로브 **원자적 줄 기록**(Files.write APPEND + synchronized) + 파싱 가능 단언; CI 플랩 방지로 **in-JVM을 하드 게이트, forks는 정보용**(§5/§8.1).
- (수용) forks **port=0**으로 control 바인드 경고 제거; §4 fork destfile 공유+파일명 격리 명확화.
- (수용) **grep 게이트 범위 한정**(docs/superpowers 제외) + THIRD-PARTY-NOTICES 처리(§8.3); §3 경로 정정.

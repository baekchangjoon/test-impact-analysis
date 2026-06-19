# in-process pjacoco 전환 + teamscale 은퇴 요구사항명세
> 출처(design spec): docs/superpowers/specs/2026-06-19-in-process-pjacoco-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 범위 메모
- 대상: in-JVM per-test 커버리지 수집을 pjacoco `PjacocoInProcessExtension`로 제공 + teamscale 완전 은퇴
  + out-of-process 컨테이너 E2E를 pjacoco로 마이그레이션.
- 다음 phase(밖): cross-service 전파(OTel), `TestwiseConverter` 순차 처리 병렬화.

## 요구사항 목록

### REQ-001 — in-JVM per-test 커버리지를 pjacoco in-process로 수집한다
- 유형: Functional
- 우선순위: Must
- 설명: 테스터 JVM(=SUT)에 pjacoco 에이전트를 `-javaagent`(aggregate=false, port=0)로 부착하고,
  `@ExtendWith(PjacocoInProcessExtension)`로 감싼 in-JVM 테스트가 fixture-app 서비스를 직접 호출하면
  테스트별 `.exec`가 생성되고 `tia convert`로 testwise가 만들어진다.
- 수용기준:
  - Given pjacoco 에이전트가 붙은 테스터 JVM + 서비스 직접 호출 결정적 케이스(≥8),
  - When 직렬로 수집해 `tia convert`로 testwise를 만들면,
  - Then 각 테스트가 자신이 호출한 프로덕션 라인만 커버한 per-test 항목으로 산출된다.
- 검증 레벨: E2E black-box(in-JVM)

### REQ-002 — 병렬(forks) 수집이 직렬과 per-test 동일하다
- 유형: Functional
- 우선순위: Must
- 설명: Gradle `maxParallelForks>1`(별 JVM, 각자 에이전트)로 수집한 testwise가 직렬과 per-test 단위로
  동일해야 한다(교차 오염 0).
- 수용기준:
  - Given 동일 SUT(동일 `--classes`)·동일 테스터, 모드별 격리 exec 디렉터리,
  - When forks로 수집해 convert하면,
  - Then `Map<testId, Map<fileKey, RoaringBitmap>>` 정규화로 직렬과 equals.
- 검증 레벨: E2E black-box(in-JVM)

### REQ-003 — 병렬(in-JVM 스레드) 수집이 직렬과 per-test 동일하다
- 유형: Functional
- 우선순위: Must
- 설명: 한 테스터 JVM 안 JUnit 5 in-JVM 병렬(스레드, ThreadLocal `CoverageContext`)로 수집한 testwise가
  직렬과 per-test 단위로 동일해야 한다.
- 수용기준:
  - Given 동일 SUT·테스터, in-JVM 병렬 실행,
  - When in-JVM 병렬로 수집해 convert하면,
  - Then 직렬 testwise와 정규화 equals.
- 검증 레벨: E2E black-box(in-JVM)

### REQ-004 — 병렬 수집의 각 테스트 커버리지가 non-empty이다 (거짓 green 방지)
- 유형: Functional
- 우선순위: Must
- 설명: 에이전트 미부착/컨텍스트 미설정으로 빈 `.exec`가 나오는데 동등이 우연히 성립하는 거짓 green을
  막기 위해, 병렬 산출의 각 테스트가 ≥1 커버 라인을 가져야 한다.
- 수용기준:
  - Given REQ-002/003의 병렬 수집 testwise,
  - When 각 테스트의 커버 라인 수를 확인하면,
  - Then 모든 테스트가 ≥1 커버 라인(빈 커버리지 0건).
- 검증 레벨: E2E black-box(in-JVM)

### REQ-005 — in-JVM 병렬이 실제로 동시 실행됨을 프로브로 증명한다
- 유형: Functional
- 우선순위: Must
- 설명: 공유 파일 오버랩 프로브(원자적 줄 기록)로, in-JVM 스레드 모드가 실제 동시 실행됐음을 증명한다.
  forks 동시성은 CI 코어 수 의존으로 기록만(비게이트). 직렬은 겹침 0.
- 수용기준:
  - Given 각 테스트가 START/END epochMillis를 공유 파일에 원자적으로 기록(본문에 작은 지연),
  - When `OverlapProbeAnalyzer`로 겹치는 구간 쌍을 세면,
  - Then in-JVM 모드 겹침 ≥1쌍, 직렬 겹침 0; 오버랩 파일의 각 줄이 파싱 가능. (forks 겹침 수는 기록만.)
- 검증 레벨: E2E black-box(in-JVM)

### REQ-006 — teamscale를 완전히 은퇴시킨다
- 유형: Functional
- 우선순위: Must
- 설명: teamscale 에이전트·확장·헬퍼·다운로드·POC·flaky 스크립트를 제거한다 —
  `tia-junit-extension` 모듈(+settings/coverage/fixture-app 연쇄), `attachTeamscaleAgent`/`teamscaleAgentJvmArg`,
  `ApiSmokeTest`/`RunResultWriter`, `download-agent.sh`/`run-poc.sh`/`measure-flaky.sh`.
- 수용기준:
  - Given 활성 산출물(`*.java`/`*.gradle`/`*.yml`/`*.sh`, README/GETTING-STARTED/THIRD-PARTY-NOTICES/plugin README; `docs/superpowers/` 제외),
  - When `git grep -li teamscale`로 검사하면,
  - Then 결과 0이고, 전체 빌드·테스트가 green이다.
- 검증 레벨: integration(빌드/회귀) + grep 게이트

### REQ-007 — out-of-process 컨테이너 E2E를 pjacoco로 마이그레이션한다
- 유형: Functional
- 우선순위: Must
- 설명: `docker-compose.e2e.yml`(pjacoco 에이전트 마운트/옵션), `docker-e2e-tester.sh`(teamscale convert →
  `tia convert`, pjacoco 테스터), ci `docker-e2e` job(`download-agent`→`setup-pjacoco`, 이름 갱신)을
  pjacoco 서블릿 경로로 전환한다.
- 수용기준:
  - Given 마이그레이션된 컨테이너 E2E,
  - When 컨테이너 sut(pjacoco 에이전트) ↔ tester로 per-test 수집→`tia convert`→index→impact를 돌리면,
  - Then green이고 teamscale 참조가 없다.
- 검증 레벨: E2E black-box(컨테이너 out-of-process)

### REQ-008 — 사용자 문서가 pjacoco in-process 경로를 반영한다
- 유형: Non-functional
- 우선순위: Must
- 설명: README/GETTING-STARTED/plugin README/THIRD-PARTY-NOTICES에서 teamscale in-process 서술을 pjacoco
  in-process(에이전트 부착, `@ExtendWith(PjacocoInProcessExtension)`, aggregate=false, port=0)로 갱신/정리.
- 수용기준:
  - Given 변경된 사용자 문서,
  - When in-process 수집 절차를 읽으면,
  - Then pjacoco 경로가 정확히 안내되고 teamscale 런타임 의존 서술이 없다.
- 검증 레벨: 문서 점검 (PR 문서 게이트)

### REQ-009 — 기존 out-of-process(parallel-e2e) 회귀가 유지된다
- 유형: Non-functional
- 우선순위: Should
- 설명: 지난 phase의 out-of-process parallel-e2e(REQ-001~010)가 본 변경 후에도 green이어야 한다(회귀 무손상).
- 수용기준:
  - Given teamscale 제거 + in-process 추가 변경,
  - When `scripts/run-parallel-e2e.sh`와 전체 회귀를 돌리면,
  - Then 여전히 green이다.
- 검증 레벨: E2E black-box(out-of-process) + 회귀

### REQ-010 — 빌드 캐시가 agent 수집을 조용히 무력화하지 않는다 (graph-rag 피드백 P2-5)
- 유형: Functional
- 우선순위: Must
- 설명: agent `.exec`는 Gradle 태스크 출력이 아니라 `-javaagent` side-effect라, 테스트 태스크가 빌드
  캐시(FROM-CACHE)/up-to-date로 복원되면 실제로 실행되지 않아 빈 커버리지가 조용히 생긴다. in-process
  수집은 항상 실제 실행돼야 하며, `.exec`가 0개면 명시적 실패로 처리한다.
- 수용기준:
  - Given in-process 수집 오케스트레이터,
  - When 수집을 실행하면,
  - Then 테스트 태스크가 캐시/up-to-date로 스킵되지 않고 실제 실행되며(`--no-build-cache` +
    `outputs.upToDateWhen{false}`), 모드별 `.exec`가 0개면 즉시 비0 종료한다(빈 testwise 조용한 통과 금지).
- 검증 레벨: E2E black-box(in-JVM) + 스크립트 가드

### REQ-011 — 인덱스(tia.db) 저장 위치 가이드를 문서화한다 (graph-rag 피드백 P3-4)
- 유형: Non-functional
- 우선순위: Should
- 설명: `tia.db`를 git에 커밋하지 말고 공유 비추적 위치(예: git common dir 또는 `$XDG_CACHE_HOME`)에 두라는
  가이드를 문서화한다(멀티 worktree/CI 공유; 인덱스는 commit 단위 스냅샷). CLI `--db` 자동 기본값은 별도
  코드 작업으로 보류(본 REQ는 문서만).
- 수용기준:
  - Given GETTING-STARTED 문서,
  - When 인덱스 저장 위치 절을 읽으면,
  - Then "db git 커밋 금지 + 공유 비추적 위치 권장"이 안내된다.
- 검증 레벨: 문서 점검 (PR 문서 게이트)

## 추적 매트릭스
| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | in-JVM per-test 수집 | InProcessCollectionE2E#serialPerTestAttribution | E2E | 🔴 planned |
| REQ-002 | forks == 직렬 | InProcessCollectionE2E#forksMatchesSerial | E2E | 🔴 planned |
| REQ-003 | in-JVM == 직렬 | InProcessCollectionE2E#inJvmMatchesSerial | E2E | 🔴 planned |
| REQ-004 | per-test non-empty | InProcessCollectionE2E#everyTestHasNonEmptyCoverage | E2E | 🔴 planned |
| REQ-005 | in-JVM 동시성 실증 | InProcessCollectionE2E#inJvmRunsConcurrently | E2E | 🔴 planned |
| REQ-006 | teamscale 완전 은퇴 | grep 게이트 + `./gradlew test` | integration | 🔴 planned |
| REQ-007 | 컨테이너 E2E pjacoco 마이그레이션 | ci docker-e2e (pjacoco) | E2E | 🔴 planned |
| REQ-008 | 문서 pjacoco 반영 | docs gate | 문서 | 🔴 planned |
| REQ-009 | out-of-process 회귀 유지 | run-parallel-e2e.sh + ./gradlew test | E2E+회귀 | 🔴 planned |
| REQ-010 | 빌드캐시 무력화 방지(--no-build-cache + exec0 fail) | run-inprocess-e2e.sh 가드 | E2E+스크립트 | 🔴 planned |
| REQ-011 | 인덱스 저장 위치 가이드(db 커밋 금지) | docs gate (GETTING-STARTED) | 문서 | 🔴 planned |

Coverage: 0/11 green (0%) — target 100% (대상: Must 9 + 미연기 Should 2[REQ-009, REQ-011]). 연기/Won't/제외 없음.

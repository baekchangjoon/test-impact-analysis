# SP3 요구사항명세 — 온보딩 명령(init·doctor·demo) + 문서 재구성
> 출처(design spec): docs/superpowers/specs/2026-07-27-sp3-onboarding-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 요구사항 목록

### SP3-REQ-001 — init 비대화형 생성과 SP1 정합
- 유형: Functional
- 우선순위: Must
- 설명: `tia init --topology in-process --sut-name x`가 tia.yml을 생성하고, 그 파일은 SP1 로더로 파싱되어 값이 일치하며, `filters.code` 주석에 package-relative 글로브 함정 경고가 포함된다.
- 수용기준:
  - Given 빈 git 레포, When 위 명령 실행, Then exit 0 + 생성 파일이 `TiaConfigLoader`로 파싱되고 sut-name이 "x"이며 파일 텍스트에 함정 경고 문구가 있다.
- 검증 레벨: E2E black-box (인프로세스 picocli)

### SP3-REQ-002 — init 덮어쓰기·섀도잉 가드
- 유형: Functional
- 우선순위: Must
- 설명: 쓰기 대상은 git 루트(비-git이면 cwd). 상향 탐색으로 기존 tia.yml이 발견되면 경로를 명시하며 exit 1, `--force`일 때만 대상 경로에 덮어쓴다.
- 수용기준:
  - Given 루트에 tia.yml이 있는 레포의 **서브디렉터리**, When `--search-root <subdir>` 상당 조건으로 init 실행, Then exit 1 + 루트 파일 경로가 메시지에 있다.
  - Given 같은 조건 + `--force`, When 실행, Then 루트 tia.yml이 덮어써진다(서브디렉터리에 새 파일이 생기지 않는다).
- 검증 레벨: E2E black-box

### SP3-REQ-003 — init 비TTY usage 에러
- 유형: Functional
- 우선순위: Must
- 설명: 비TTY에서 `--topology` 미지정이면 exit 2(picocli USAGE 정렬) + 플래그 안내.
- 수용기준:
  - Given 비TTY 실행 환경, When `tia init`(토폴로지 미지정), Then exit 2 + stderr에 `--topology` 안내.
- 검증 레벨: E2E black-box

### SP3-REQ-004 — doctor 체크·판정 계약
- 유형: Functional
- 우선순위: Must
- 설명: 6개 체크(JDK/git/tia.yml/DB존재/베이스라인↔HEAD/에이전트 jar)를 PASS/WARN/FAIL/SKIP + 처방으로 출력한다. 체크 2·5·6의 기준 디렉터리는 ConfigMixin의 search-root(`--search-root` ?: cwd)와 동일. 비-git이면 체크5 SKIP, 비-TIA 레포면 체크6 SKIP. FAIL ≥1 → exit 1, 아니면 0.
- 수용기준:
  - Given 빈 비-git 디렉터리(`--search-root`로 주입), When `tia doctor`, Then WARN(yml·DB)·SKIP(5·6) 포함 출력 + exit 0.
  - Given 깨진 tia.yml, When 실행, Then 체크3 FAIL + exit 1.
  - Given 유효 tia.yml + **초기 커밋이 있는 git 레포**(git init + local user 설정 + commit — ImpactCommandTest 헬퍼 패턴; 커밋 없으면 HEAD 미해석으로 체크5가 SKIP됨) + HEAD로 인덱스된 db, When 실행, Then 체크3·4·5 PASS.
- 검증 레벨: E2E black-box

### SP3-REQ-005 — doctor 읽기 전용 보장
- 유형: Non-functional (계약)
- 우선순위: Must
- 설명: doctor는 DB 파일이 존재할 때만 `CoverageStore`를 연다 — 진단 실행이 DB 파일·스키마를 생성하지 않는다.
- 수용기준:
  - Given DB 없는 디렉터리, When `tia doctor`, Then 해석된 DB 경로에 파일이 생기지 않는다.
- 검증 레벨: E2E black-box

### SP3-REQ-006 — doctor JSON 계약과 포맷 제한
- 유형: Functional
- 우선순위: Must
- 설명: `--format json`은 `{schemaVersion:1, command:"doctor", checks[]{id,status,detail,hint}, summary{pass,warn,fail,skip}}`를 출력한다. `--format`은 doctor 전용 2값(text|json) — summary/markdown은 usage 에러.
- 수용기준:
  - Given 아무 디렉터리, When `tia doctor --format json`, Then 위 필드가 존재하고 `schemaVersion == 1`.
  - When `tia doctor --format summary`, Then picocli usage 에러(비0 exit).
- 검증 레벨: E2E black-box

### SP3-REQ-007 — demo 레포 전제 검사
- 유형: Functional
- 우선순위: Must
- 설명: TIA 레포 밖에서 `tia demo` 실행 시 exit 1 + `git clone` 안내. 레포 감지는 공용 헬퍼(scripts/run-inprocess-e2e.sh 존재 + settings.gradle의 `rootProject.name = 'test-impact-analysis'`), 시작점은 히든 `--repo-root`(테스트 시임, 기본 cwd).
- 수용기준:
  - Given @TempDir(비-TIA), When `tia demo --repo-root <tempdir>`, Then exit 1 + 클론 안내 문구.
- 검증 레벨: E2E black-box

### SP3-REQ-008 — demo 전 구간 파이프라인 (index~report는 직접 구동)
- 유형: Functional
- 우선순위: Must
- 설명: demo는 `=== [N/6] ===` 단계 마커와 해설을 출력하며 ①setup-pjacoco ②**demo-collect.sh**(serial 단일 모드 수집+convert — 경량 전용 스크립트, 출력 디렉터리를 인자/`DEMO_OUT`으로 받음) 구동 후, ③`tia index`(demo 전용 DB `<out-dir>/demo-tia.db`, commit=HEAD) ④커버 라인 기반 diff 동적 생성(비파괴) ⑤`tia impact` ⑥`tia report`를 인프로세스로 구동한다. 산출물 기본 경로는 `build/inprocess-e2e/demo/`(히든 `--out-dir`로 오버라이드).
- 수용기준:
  - Given 스텁 scripts-dir(demo-collect 스텁이 전달받은 out-dir에 가짜 testwise_serial.json 생성)과 `--out-dir <tempdir>`, When `tia demo` 실행, Then ③~⑥이 실제 실행되어 demo-tia.db·demo.diff·report.html이 out-dir에 생성되고, stdout에 마커 6종(`[1/6]`..`[6/6]`)과 **정확히 DETERMINISTIC 1건**의 선별 라인, 마무리 CTA("내 프로젝트에 적용하려면: tia init")가 있다 + exit 0.
- 검증 레벨: E2E black-box (스텁+실구동 혼합 — index 이후 경로는 실 커버. 실 스크립트 전 구간은 구현 시 수동 스모크 + 후속 저빈도 CI 잡 백로그)

### SP3-REQ-009 — demo 실패 처리 (서브프로세스·인프로세스 양쪽)
- 유형: Functional
- 우선순위: Must
- 설명: 단계 실패 시 해당 단계 출력 마지막 20줄(서브프로세스는 stderr, 인프로세스 단계는 System.err 스왑 버퍼) + `tia doctor` 안내 + exit 1. 커버 라인 0개(수집 실패)는 ④에서 명확히 실패.
- 수용기준:
  - Given 실패하는 스텁 스크립트, When 실행, Then stderr 요약과 doctor 안내가 출력되고 exit 1.
  - Given **커버 라인이 없는 가짜 testwise_serial.json**을 만드는 스텁, When 실행, Then ④ 단계에서 수집 실패 안내 + doctor 안내 + exit 1 (인프로세스 실패 경로 검증).
- 검증 레벨: E2E black-box

### SP3-REQ-010 — CLI 배선(신규 서브커맨드 등록)
- 유형: Functional
- 우선순위: Must
- 설명: `TiaCommand` subcommands에 init/doctor/demo가 등록되고 usage 문자열이 갱신된다. doctor는 ConfigMixin+`--db`, init·demo는 표면 명세대로(§2·§4) — 표 밖 조합 없음. init은 ConfigMixin을 재사용하지 않고 전용 히든 `--search-root`만 선언(`--config` 누출 방지).
- 수용기준:
  - Given CLI, When CommandSpec 검사, Then 세 서브커맨드 존재 + doctor에 `--config`/`--db` 존재 + init에 `--exclude-code`·`--config` **부재** + demo에 `--scripts-dir`/`--out-dir`/`--repo-root`(hidden) 존재·`--db`/`--include-code` 부재.
- 검증 레벨: CLI acceptance (CliWiringTest 확장)

### SP3-REQ-011 — 문서 재구성 (docs 게이트)
- 유형: Non-functional (문서)
- 우선순위: Must
- 설명: GETTING-STARTED 2부 구조(1부 튜토리얼 ①~④, 2부에 기존 8절 전부 보존·링크), README 빠른 시작 demo 중심 갱신(79~93행 부근 run-inprocess-e2e.sh 오기술을 실제 범위로 교정) + 기대 출력 실제와 일치 + demo 소요("첫 실행 1~3분") 명시.
- 수용기준:
  - Given 갱신 문서, When 검토, Then 1부 4단계·2부 8절 매핑·README 오기술 교정·기대 출력 일치가 확인된다.
  - Given README.md·skills/tia/SKILL.md의 `](GETTING-STARTED.md#…)` 앵커 전수(grep), When 재구성 후 대조, Then 전부 실제 헤딩과 일치한다.
- 검증 레벨: build/docs 게이트

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| SP3-REQ-001 | init 생성·SP1 정합 | InitCommandE2ETest#nonInteractiveCreatesValidYml | E2E | 🟢 green |
| SP3-REQ-002 | init 가드·섀도잉 방지 | InitCommandE2ETest#subdirDetectsRootYml / #forceOverwritesAtRoot | E2E | 🟢 green |
| SP3-REQ-003 | init 비TTY usage | InitCommandE2ETest#nonTtyMissingTopologyExit2 | E2E | 🟢 green |
| SP3-REQ-004 | doctor 체크 계약 | DoctorCommandE2ETest#emptyDirWarnsAndSkips / #brokenYmlFails / #healthyProjectPasses | E2E | 🔴 planned |
| SP3-REQ-005 | doctor 읽기 전용 | DoctorCommandE2ETest#doesNotCreateDbFile | E2E | 🔴 planned |
| SP3-REQ-006 | doctor JSON·포맷 제한 | DoctorCommandE2ETest#jsonSchema / #summaryFormatRejected | E2E | 🔴 planned |
| SP3-REQ-007 | demo 레포 전제 | DemoCommandE2ETest#outsideRepoExit1 | E2E | 🔴 planned |
| SP3-REQ-008 | demo 파이프라인 | DemoCommandE2ETest#stubbedCollectRealIndexImpactReport | E2E | 🔴 planned |
| SP3-REQ-009 | demo 실패 처리(양쪽) | DemoCommandE2ETest#failingStubShowsStderrAndDoctorHint / #zeroCoveredLinesFailsAtDiffStage | E2E | 🔴 planned |
| SP3-REQ-010 | CLI 배선 | CliWiringTest#optionsForInitDoctorDemo | CLI | 🔴 planned |
| SP3-REQ-011 | 문서 재구성 | PR 전 docs 게이트 점검 | build | 🔴 planned |

Coverage: 3/11 green (27%) — target 100% (대상: Must 11 = 11)

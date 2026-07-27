# SP3 설계 — 온보딩 명령(init·doctor·demo) + GETTING-STARTED 재구성

- 날짜: 2026-07-27
- 상태: 3-벤더 리뷰(Sonnet ×3 대체 슬롯) 소견 반영 완료 — demo 파이프라인 재설계 포함
- 상위 맥락: 사용성 개선 5-SP 분해(SP1 spec §0)의 세 번째. 신규 사용자가 문서를 뒤지지 않고 명령이 안내하는 대로 첫 결과에 도달하게 한다.
- 사용자 확정 결정: `tia demo`는 **fixture-app 실수집**(내장 리소스 데모 아님 — 수집까지 체험).

## 1. 목표·비범위

**목표**

1. `tia init` — 프로젝트를 감지해 `tia.yml`을 생성하고, 수집 방식(스레드 토폴로지) 결정 트리를 안내한 뒤 다음 명령을 출력하는 마법사.
2. `tia doctor` — 환경·설정·인덱스 상태를 점검하고 각 항목의 고치는 법을 출력하는 진단기.
3. `tia demo` — TIA 레포 체크아웃 안에서 fixture-app **실수집 → 인덱싱 → diff → impact → 리포트** 전 과정을 한 번에 구동하며 해설하는 체험 러너. **주의: 수집 스크립트(`run-inprocess-e2e.sh`)는 수집+convert까지만 하므로, 인덱싱 이후는 DemoCommand가 직접 구동한다(§4).**
4. GETTING-STARTED를 "첫 결과까지" 튜토리얼 + 상세 레퍼런스 2부 구조로 재구성.

**비범위**

- init의 빌드파일 자동 수정(pjacoco 배선 주입) — 안내 텍스트만. 이후 SP2 연계 검토.
- doctor의 자동 수리(--fix) — 진단·안내만.
- demo의 배포 jar 단독 실행(레포 밖) — fixture-app이 레포 소속이므로 레포 체크아웃 전제(사용자 결정).
- 원격 인덱스/CI 연동 점검 — 로컬 진단만.

**공통 인프라**: `TiaCommand`의 `subcommands` 배열과 usage 문자열에 `init|doctor|demo`를 추가한다. TIA 레포 루트 감지는 **공용 헬퍼 1개**로 구현한다(휴리스틱: 상향 탐색으로 `scripts/run-inprocess-e2e.sh`가 존재하고 `settings.gradle`에 `rootProject.name = 'test-impact-analysis'` 문자열이 있는 디렉터리) — doctor 체크 6과 demo 전제 검사가 같은 헬퍼를 쓴다.

## 2. `tia init` — 마법사

**입력**: 대화형(TTY) 질문 + 비대화형 플래그 동치(모든 질문에 플래그):

| 질문 | 플래그 | 기본값 |
|---|---|---|
| 수집 토폴로지 | `--topology <in-process\|out-of-process>` | (질문; 비TTY+미지정이면 exit 2 + 안내) |
| SUT 이름 | `--sut-name <name>` | 디렉터리 basename |
| code 필터 시작값 | `--include-code <glob>` (반복) | 없음(전체) |
| 덮어쓰기 | `--force` | 기존/상위 tia.yml 있으면 exit 1 |

- exit 2는 picocli `ExitCode.USAGE`와 의도적으로 정렬(프로젝트 exit 체계: 0=ok, 1=error, 2=usage, 3=strict no-baseline).
- `--include-code`는 기존 `CodeFilterMixin`을 **재사용하지 않는다** — 의미가 다르다(소비 시 대체 vs 생성 시 시드). init 전용 옵션으로 선언하고 `--exclude-code`는 두지 않는다(시드는 include만 — exclude는 생성된 파일에서 직접 편집 안내).

**쓰기 위치·가드 (상향 탐색과의 상호작용)**

- **쓰기 대상**: git 루트가 발견되면 **git 루트의 `tia.yml`**(SP1 소비 규칙 "레포당 하나"와 일치), 비-git이면 cwd.
- **가드**: 쓰기 전 SP1 로더의 탐색과 동일한 상향 탐색으로 기존 tia.yml을 찾는다(대상 경로 포함). **발견되면 그 경로를 명시하며 exit 1**, `--force`일 때만 대상 경로에 덮어쓴다. 서브디렉터리에서 실행해도 루트 파일을 못 보고 섀도잉 파일을 만드는 일이 없다.

**동작**

1. 사전 감지: git 레포 여부(경고만), 빌드 도구(build.gradle/pom.xml → 안내 분기), 기존 tia.yml 가드(위).
2. 토폴로지 결정 트리(대화형): 질문 2개 + **혼합 분기** —
   "프로덕션 코드가 테스트 스레드에서 실행되나(단위/슬라이스)?" → in-process /
   "별도 서버·워커 스레드인가(RANDOM_PORT+RestAssured, WebSocket, @Async)?" → out-of-process /
   **둘 다 있으면**: 토폴로지는 테스트 단위 속성임을 안내하고, 여기서의 선택은 "다음 단계 명령을 어느 쪽부터 출력할지"만 정한다 + GETTING-STARTED §1의 per-test 분류 표 링크(두 모델 병용 안내).
   각 선택의 결과(잘못 고르면 커버리지 침묵 손실 → `tia convert`가 막음)를 출력.
3. `tia.yml` 생성 — SP1 스키마(version: 1, sut-name, filters 골격). **`filters.code` 주석에 package-relative 글로브 함정 경고를 포함**한다(SP1 §7의 완화 약속 이행: "`com/acme/**` 형태 — `src/main/java/` 접두어는 매칭되지 않음").
4. "다음 단계" 출력: 토폴로지별 수집 명령 시퀀스(GETTING-STARTED 링크), `tia doctor` 안내.

**구현 위치**: `tia-cli` `InitCommand`. 대화형 입력은 `System.console()` null(비TTY)이면 플래그 필수.

## 3. `tia doctor` — 진단기

**플래그 표면**: `@Mixin ConfigMixin`(`--config`/`--search-root`), `--db`(선택 — 미지정 시 tia.yml→기본값 해석), `--format <text|json>`(**doctor 전용 2값 enum** — OutputFormat 재사용 시 summary/markdown이 미정의 동작이 되므로 별도 enum; 미지원 값은 picocli usage 에러).

체크 항목(각 PASS/WARN/FAIL/SKIP + 한 줄 처방):

| # | 항목 | 판정 |
|---|---|---|
| 1 | JDK 17+ (`java.version`) | <17 FAIL |
| 2 | git 레포 여부 | 아니면 WARN(diff 기반 기능 제약) |
| 3 | tia.yml 존재·유효성 | 없음 WARN(기본값 동작 안내) / 파싱 실패 FAIL(SP1 로더 재사용, 오류 인용) |
| 4 | 인덱스 DB 존재(해석된 경로) | **`Files.exists()`로만 검사** — 없음 WARN. `CoverageStore`는 생성자가 DB 파일·스키마를 만들므로(부작용) **파일이 존재할 때만 연다**(진단의 읽기 전용 보장) |
| 5 | DB 베이스라인 ↔ HEAD 정렬 | 비-git이면 **SKIP**(체크 2와 연동). HEAD는 `git rev-parse HEAD` 헬퍼(명시적 workingDir 인자 — `ImpactCommand.runGitDiff(ref, workingDir)` 패턴; 실패 시 null → SKIP). DB 존재 시에만 열어 `store.load(head).tests().isEmpty()`(기존 no-baseline 판정 패턴)로 판정 — 비면 WARN(재인덱싱 안내). `builds.commit_sha` 미인덱스 풀스캔은 로컬 DB 규모에서 수용(명시) |
| 6 | pjacoco 에이전트 jar(`tools/pjacoco/jacocoagent-parallel.jar`) | TIA 레포(공용 헬퍼 감지)일 때만 — 없음 WARN(`scripts/setup-pjacoco.sh` 안내), 비-TIA 레포 SKIP |

**출력**: 항목별 `[PASS|WARN|FAIL|SKIP] 이름 — 상태 (처방)` + 요약 줄. **exit**: FAIL ≥1 → 1, 아니면 0(WARN/SKIP은 0). `--format json`은 `{ "schemaVersion": 1, "command": "doctor", "checks": [{id,status,detail,hint}], "summary": {pass,warn,fail,skip} }` — SP1 계약 관례(schemaVersion) 준수. **소비 안내**: 스크립트/에이전트는 exit code와 무관하게 stdout JSON을 항상 캡처하라(문서 명시 — FAIL이어도 전체 진단이 stdout에 있다).

**구현 위치**: `tia-cli` `DoctorCommand`. 진단은 예외로 죽지 않는다 — 개별 체크 실패는 그 항목 FAIL로 수렴. **체크 2·5·6의 기준 디렉터리는 ConfigMixin이 해석하는 search-root(`--search-root` ?: cwd)와 동일한 값**을 RepoPaths 호출에 그대로 전달한다(테스트가 @TempDir을 검사 대상으로 주입 가능).

## 4. `tia demo` — 체험 러너

**전제 검사**: 공용 헬퍼로 TIA 레포 루트 탐지(§1) — 시작점은 히든 `--repo-root`(테스트 시임, 기본 cwd). 실패 시 exit 1 + `git clone` 안내.
**부트스트랩**: demo는 `tia` 바이너리로 실행되므로 이미 빌드된 상태다 — 1부 튜토리얼이 `./gradlew :tia-cli:installDist` 단계를 명시한다(§5).
**수집 경로 분리(리뷰 반영)**: 기존 `run-inprocess-e2e.sh`는 3모드 풀빌드 **CI 회귀** 스크립트(분 단위·`--no-daemon` 5회·installDist 재빌드)라 온보딩용으로 부적합하다. demo는 **전용 경량 스크립트 `scripts/demo-collect.sh`** 를 쓴다: serial 단일 모드 수집 + `tia convert`만 수행하고(installDist 재빌드 없음 — 실행 중 바이너리 자기교체 회피), 산출물을 인자로 받은 출력 디렉터리에 쓴다(`DEMO_OUT` env 또는 $1 — 스텁도 같은 계약).

**산출물 격리**: 전부 `build/inprocess-e2e/demo/` 하위(회귀 스크립트의 `rm -rf build/inprocess-e2e`와 동시 실행돼도 서로의 산출물 계열이 명확; 완전 동시 실행은 비범위로 명시). E2E는 히든 `--out-dir`로 @TempDir 주입.

**히든 시임 3종**: `--repo-root`(전제검사 시작점) · `--scripts-dir`(스크립트 디렉터리, 기본 `<repoRoot>/scripts`) · `--out-dir`(산출물 디렉터리, 기본 `<repoRoot>/build/inprocess-e2e/demo`).

**동작** — 각 단계 시작에 **안정 마커 `=== [N/6] <제목> ===`** 와 해설을 출력(테스트가 마커를 단언); 3단계부터는 **인프로세스 picocli 호출**(별도 tia 프로세스 없음). 인프로세스 단계의 실패 출력 캡처는 **호출 주변에서 System.err를 버퍼로 임시 스왑(+finally 복원)** 으로 구현한다:

1. `[1/6]` `bash <scripts-dir>/setup-pjacoco.sh` — pjacoco 해소(v3 다운로드, 통상 수 초).
2. `[2/6]` `bash <scripts-dir>/demo-collect.sh <out-dir>` — fixture-app serial 수집+convert → `<out-dir>/testwise_serial.json`. 시작 전 "첫 실행은 1~3분 걸릴 수 있습니다" 안내.
3. `[3/6]` `tia index --report <out-dir>/testwise_serial.json --repo fixture --commit <git rev-parse HEAD> --db <out-dir>/demo-tia.db` — 데모 전용 DB(공유 DB 오염 방지).
4. `[4/6]` **diff 동적 생성**: testwise_serial.json에서 커버된 파일·라인 하나를 골라 old-side 라인 공간의 최소 unified diff를 `<out-dir>/demo.diff`로 생성(비파괴, HEAD 무관 라인 공간 일치). **커버 라인이 0이면 여기서 명확히 실패**(수집 문제 안내 + doctor 유도).
5. `[5/6]` `tia impact --db … --commit <HEAD> --diff-file <out-dir>/demo.diff` — **정확히 DETERMINISTIC 1건** 선별을 보여주고 해설.
6. `[6/6]` `tia report --testwise … --commit <HEAD> --out <out-dir>/report.html --sut-name fixture-app` (옵션 입력 `-`) — 경로 출력 + "브라우저로 여세요".
7. 마무리: 요약 4줄 + CTA 고정 문구 **"내 프로젝트에 적용하려면: tia init"**.

실패 시: 해당 단계의 출력 마지막 20줄(서브프로세스는 stderr, 인프로세스는 스왑 버퍼) + `tia doctor` 안내 + exit 1. 새 컨테이너/데몬 없음(in-process — 프로세스 잔존 없음). `poc-out/`은 docker-e2e 전용으로 demo와 무관.

**자동 커버리지 갭(명시)**: `--scripts-dir` 스텁 E2E는 실 스크립트 호출 계약을 검증하지 못한다 — 실 전 구간은 구현 시 수동 스모크 1회 + 후속으로 저빈도 CI 잡 추가를 백로그에 둔다.

## 5. GETTING-STARTED 재구성

2부 구조(기존 8개 절 전부의 행선지 명시 — 누락 금지):

- **1부 "첫 결과까지"**: ① 클론+빌드(`git clone` → `./gradlew :tia-cli:installDist`, CLI 별칭 안내) ② `tia demo` 체험(기대 출력 포함) ③ 내 프로젝트 적용 — `tia init` → 토폴로지별 최소 수집 명령 → `tia index`/`impact` ④ 막히면 `tia doctor`.
- **2부 "레퍼런스"**: 기존 **§0(설치)·§1(수집 상세·결정 트리)·§2(인덱싱)·§3(선별)·§4(리포트)·§5(CI/에이전트 통합)·tia.yml 절·플레이키 절 전부 그대로 유지**하고 1부에서 링크한다(§0은 1부 ①과 중복되는 부분만 슬림화).
- README 빠른 시작을 `tia demo` 중심으로 갱신(스크립트 직접 실행 경로 병기). **README 79~93행 부근의 `run-inprocess-e2e.sh` 동작 서술(index/impact/git diff까지 한다는 기존 오기술)을 실제 범위(수집+convert)로 교정**하고, 기대 출력 문구를 실제 출력과 일치시킨다. 튜토리얼에 demo 예상 소요("첫 실행 1~3분") 명시.
- **앵커 보존 게이트**: README.md·skills/tia/SKILL.md가 참조하는 `](GETTING-STARTED.md#…)` 앵커 전부를 grep으로 수집해, 재구성 후 GETTING-STARTED의 실제 헤딩과 일치함을 기계적으로 점검한다.

## 6. 에러 처리

- init: 기존/상위 tia.yml + `--force` 없음 → exit 1(경로 명시). 비TTY+토폴로지 미지정 → exit 2(usage). 생성 실패 → exit 1.
- doctor: 예외 없음 — 개별 체크 FAIL 수렴. FAIL ≥1 → exit 1.
- demo: 레포 밖 exit 1(클론 안내), 단계 실패 → stderr 요약+doctor 안내+exit 1.

## 7. 테스트 전략과 E2E/수용 명세

- **init E2E**(인프로세스 picocli): 비대화형 경로 — 생성 파일이 SP1 로더로 파싱·값 일치·함정 주석 포함; 기존 파일 보호(exit 1)·**서브디렉터리에서 실행 시 루트 tia.yml 감지·exit 1**(신규 — 섀도잉 방지); `--force` 덮어쓰기; 비TTY 토폴로지 미지정 exit 2. 대화형 TTY 루프는 JVM 테스트로 재현 불가 — 프롬프트 텍스트 생성만 unit 검증(한계 명시, 수동 스모크).
- **doctor E2E**: @TempDir 시나리오 — ① 빈 비-git 디렉터리(WARN·SKIP 다수, exit 0, **DB 파일이 생기지 않음을 단언** — 읽기 전용 보장) ② 깨진 tia.yml → FAIL+exit 1 ③ 유효 yml+인덱스 db 픽스처 → PASS/SKIP. `--format json` 스키마(schemaVersion/command/checks) 검증 + `--format summary` 거부(usage 에러) 검증.
- **demo E2E**: ① 레포 밖 → exit 1+클론 안내 ② **스텁+실구동 혼합**: `--scripts-dir`(히든, 테스트 시임 — hidden=true로 충분: 로컬 셸 접근자와 동일 권한이라 위협 없음)로 1·2단계를 스텁(가짜 testwise_serial.json 생성)으로 대체하고, **3~6단계는 실제 인프로세스로 구동** → demo-tia.db 생성·impact 선별 라인·report.html 존재를 단언(신규 경로에 실 커버리지 — "기존 CI가 커버" 논리는 index 이후 단계에 성립하지 않으므로 직접 검증) ③ 스텁 실패 시 stderr 요약+doctor 안내+exit 1.
- **문서**: PR 전 docs 게이트 — 1부/2부 8절 매핑, README demo 반영·기대 출력 일치.
- 기존 스위트 전부 무변경 green.

**완료 정의**: 위 E2E 전부 green + 요구 매트릭스 100% + 전체 스위트 green + 문서 게이트.

## 8. 리스크와 반론

- **대화형 경로의 테스트 사각**: 비대화형 플래그를 1급 경로로 설계(대화는 얇은 입력 수집만), 한계 명시. expect류 도입은 유지비 과다(기각).
- **demo의 레포 전제**: 배포 jar 단독 사용자에겐 데모 없음 — 사용자가 실수집 체험을 우선해 의도 선택. init/doctor는 레포 무관.
- **demo diff 동적 생성의 결합**: testwise 스키마(covered lines)에 의존 — 이미 SP1 파서가 계약을 고정하고 있어 안정적. 커버된 라인이 하나도 없으면(수집 실패) 5단계 전에 명확히 실패시킨다.
- **doctor 항목 시효**: 처방은 명령·문서 링크 위주로 짧게.

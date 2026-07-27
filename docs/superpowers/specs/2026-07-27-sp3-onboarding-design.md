# SP3 설계 — 온보딩 명령(init·doctor·demo) + GETTING-STARTED 재구성

- 날짜: 2026-07-27
- 상위 맥락: 사용성 개선 5-SP 분해(SP1 spec §0)의 세 번째. 신규 사용자가 문서를 뒤지지 않고 명령이 안내하는 대로 첫 결과에 도달하게 한다.
- 사용자 확정 결정: `tia demo`는 **fixture-app 실수집**(내장 리소스 데모 아님 — 수집까지 체험).

## 1. 목표·비범위

**목표**

1. `tia init` — 프로젝트를 감지해 `tia.yml`을 생성하고, 수집 방식(스레드 토폴로지) 결정 트리를 안내한 뒤 다음 명령을 출력하는 마법사.
2. `tia doctor` — 환경·설정·인덱스 상태를 점검하고 각 항목의 고치는 법을 출력하는 진단기.
3. `tia demo` — TIA 레포 체크아웃 안에서 fixture-app 실수집→인덱싱→impact→리포트를 한 번에 구동하며 각 단계를 해설하는 체험 러너.
4. GETTING-STARTED를 "5분 내 첫 결과(=demo)" 튜토리얼 + 상세 레퍼런스 2부 구조로 재구성.

**비범위**

- init의 빌드파일 자동 수정(Gradle/Maven에 pjacoco 배선 주입) — 안내 텍스트만(파괴적 변경 회피). 이후 SP2 연계 검토.
- doctor의 자동 수리(--fix) — 진단·안내만.
- demo의 배포 jar 단독 실행(레포 밖) — fixture-app이 레포 소속이므로 레포 체크아웃 전제(사용자 결정). 레포 밖 실행 시 클론 안내 에러.
- 원격 인덱스/CI 연동 점검 — 로컬 진단만.

## 2. `tia init` — 마법사

**입력**: 대화형(TTY) 질문 + **비대화형 플래그 동치**(CI·테스트·스크립트용 — 모든 질문에 플래그가 있다):

| 질문 | 플래그 | 기본값 |
|---|---|---|
| 수집 토폴로지 | `--topology <in-process\|out-of-process>` | (질문; 비TTY+미지정이면 exit 2 + 안내) |
| SUT 이름 | `--sut-name <name>` | 디렉터리 basename |
| code 필터 시작값 | `--include-code <glob>` (반복) | 없음(전체) |
| 덮어쓰기 | `--force` | 기존 tia.yml 있으면 exit 1 |

**동작**

1. 사전 감지: git 레포 여부(경고만), 빌드 도구(build.gradle/pom.xml → 안내 텍스트 분기), 기존 tia.yml(있으면 `--force` 없인 exit 1 — 파괴 방지).
2. 토폴로지 결정 트리(대화형일 때): GETTING-STARTED §1의 결정 기준을 질문 2개로 압축 —
   "프로덕션 코드가 테스트 스레드에서 실행되나(단위/슬라이스 테스트)?" → in-process /
   "별도 서버·워커 스레드로 실행되나(RANDOM_PORT+RestAssured, WebSocket, @Async)?" → out-of-process.
   각 선택의 결과(잘못 고르면 커버리지 침묵 손실 → `tia convert`가 막음)를 출력.
3. `tia.yml` 생성 — SP1 스키마(version: 1, sut-name, filters 골격 주석 포함). **stdout이 아니라 파일**로 쓰고, 요약을 출력.
4. "다음 단계" 출력: 선택 토폴로지에 맞는 수집 명령 시퀀스(GETTING-STARTED 해당 절 링크 포함), `tia doctor` 안내.

**구현 위치**: `tia-cli`의 `InitCommand`(picocli). 생성 로직은 tia-core에 두지 않는다(순수 CLI 편의 — core 오염 방지). 대화형 입력은 `System.console()`이 null이면(비TTY) 플래그 필수.

## 3. `tia doctor` — 진단기

체크 항목(각각 PASS/WARN/FAIL + 한 줄 처방):

| # | 항목 | 판정 |
|---|---|---|
| 1 | JDK 17+ (`java.version`) | <17 FAIL |
| 2 | git 레포 여부 | 아니면 WARN(diff 기반 기능 제약) |
| 3 | tia.yml 존재·유효성 | 없음 WARN(기본값 동작 안내) / 파싱 실패 FAIL(SP1 로더 재사용, 오류 메시지 인용) |
| 4 | 인덱스 DB 존재(`--db`/tia.yml/기본값 해석 경로) | 없음 WARN("첫 인덱싱 전" 안내) |
| 5 | DB 베이스라인 ↔ HEAD 정렬 | DB 있고 HEAD 커밋의 베이스라인 없으면 WARN(재인덱싱 안내) |
| 6 | pjacoco 에이전트 jar(`tools/pjacoco/jacocoagent-parallel.jar`, TIA 레포일 때만) | 없음 WARN(`scripts/setup-pjacoco.sh` 안내; 비-TIA 레포면 SKIP) |

**출력**: 항목별 `[PASS]/[WARN]/[FAIL] 이름 — 상태 (처방)` + 요약 줄. **exit code**: FAIL ≥1 → 1, 아니면 0(WARN은 0 — 게이트 아님). `--format json`(SP1 OutputFormat 재사용, `command: "doctor"`, `checks[]{id,status,detail,hint}`)도 지원 — 에이전트/스크립트 소비용.

**구현 위치**: `tia-cli` `DoctorCommand`. DB 접근은 기존 `CoverageStore`/`DbPaths` 재사용(읽기 전용).

## 4. `tia demo` — 체험 러너

**전제 검사**: cwd에서 상향 탐색으로 TIA 레포 루트(`scripts/run-inprocess-e2e.sh` + `settings.gradle`의 rootProject 확인)를 찾는다. 못 찾으면 exit 1 + "git clone <repo> 후 레포 루트에서 실행" 안내(사용자 결정: 레포 체크아웃 전제).

**동작**: 다음을 순서대로 서브프로세스로 구동하며, 각 단계 전에 "지금 무엇을 왜 하는지" 해설을 출력한다:

1. `bash scripts/setup-pjacoco.sh` (pjacoco 해소 — v3라 통상 다운로드 수 초)
2. `bash scripts/run-inprocess-e2e.sh` (수집→convert→index→diff→impact 전 과정; 스크립트가 이미 `✅ PASS` 마커 출력)
3. `tia report`(수집 산출물로 report.html 생성) — 생성 경로와 "브라우저로 여세요" 안내
4. 마무리 요약: 방금 일어난 일 4줄 + "내 프로젝트에 적용하려면 `tia init`" 안내

실패 시: 해당 단계의 stderr 마지막 20줄 + `tia doctor` 안내 후 exit 1. **정리**: 데모 산출물은 기존 스크립트 산출 경로(poc-out/…)를 그대로 쓰며 새 컨테이너/데몬을 만들지 않는다(스크립트가 in-process라 프로세스 잔존 없음).

**구현 위치**: `tia-cli` `DemoCommand`(ProcessBuilder로 bash 스크립트 구동). CI에서는 기존 in-process E2E 잡이 동일 경로를 이미 커버하므로 demo 자체의 CI 잡은 추가하지 않는다(스모크는 E2E로).

## 5. GETTING-STARTED 재구성

2부 구조로 재편(내용은 기존 자산 재배치+수정, 전면 재작성 아님):

- **1부 "5분 안에 첫 결과"**: ① `git clone` + `tia demo`(체험) ② 내 프로젝트에 적용 — `tia init` → 수집(토폴로지별 최소 명령) → `tia index`/`impact` ③ 막히면 `tia doctor`. 각 단계 예상 출력 포함.
- **2부 "레퍼런스"**: 기존 §1(수집 상세·결정 트리 전체)·§2·§3·tia.yml 절 유지(1부에서 링크).
- README의 빠른 시작 절도 `tia demo` 중심으로 갱신(기존 스크립트 직접 실행 경로는 유지 병기). README 빠른 시작의 기대 출력 문구를 스크립트 실제 출력(`✅ inprocess-e2e PASS`)과 일치시킨다(기왕의 드리프트 해소 — SP1 시절 발견 항목).

## 6. 에러 처리

- init: 기존 tia.yml + `--force` 없음 → exit 1(파괴 금지). 비TTY+토폴로지 미지정 → exit 2(usage) + 플래그 안내. 생성 실패(권한 등) → exit 1.
- doctor: 진단 자체는 절대 예외로 죽지 않는다 — 개별 체크 실패는 그 항목 FAIL로 수렴.
- demo: 레포 밖 exit 1(클론 안내), 단계 실패 시 stderr 요약+doctor 안내+exit 1.

## 7. 테스트 전략과 E2E/수용 명세

- **init**: E2E(인프로세스 picocli) — 비대화형 플래그 경로: `--topology in-process --sut-name x` → 생성된 tia.yml이 SP1 로더로 파싱되고 값 일치; 기존 파일 보호(exit 1)·`--force` 덮어쓰기; 비TTY 토폴로지 미지정 exit 2. 대화형(TTY) 경로는 System.console() 주입 불가로 **unit 레벨에서 프롬프트 텍스트 생성만** 검증(한계 명시 — 대화 루프는 수동 스모크).
- **doctor**: E2E — @TempDir 시나리오 3종(JDK는 현 JVM이라 PASS 고정): ① 빈 디렉터리(비git·no yml·no db → WARN 다수, exit 0) ② 깨진 tia.yml → FAIL + exit 1 ③ 유효 yml+인덱스된 db(픽스처) → 전부 PASS/SKIP. `--format json` 스키마 검증.
- **demo**: E2E — ① 레포 밖(@TempDir) 실행 → exit 1 + 클론 안내 ② **레포 안 전 구간 실행은 기존 in-process E2E와 중복이므로 스크립트 호출 배선만 검증**: 스텁 bash 스크립트(성공/실패)를 주입할 수 있게 DemoCommand에 스크립트 경로 오버라이드(히든 옵션 `--scripts-dir`, 테스트 시임)를 두고, 성공 시 해설 4단계·실패 시 stderr 요약+doctor 안내를 단언. 실 전 구간은 수동/기존 CI 커버(비례성 — demo가 부르는 스크립트 자체가 CI 검증됨).
- **문서**: PR 전 docs 게이트 — 1부/2부 구조, README demo 반영, 기대 출력 문구 일치.
- 기존 스위트 전부 무변경 green(하위호환 — 신규 서브커맨드 추가는 기존 계약 불변).

**완료 정의**: 위 E2E 전부 green + 요구 매트릭스 100% + 전체 스위트 green + 문서 게이트.

## 8. 리스크와 반론

- **대화형 경로의 테스트 사각**: System.console() 기반 TTY 대화는 JVM 테스트로 재현 불가 — 비대화형 플래그를 1급 경로로 설계해 로직을 공유시키고(대화는 얇은 입력 수집만), 한계를 명시. 반론: expect류 도구 도입은 유지비 과다(기각).
- **demo의 레포 전제**: 배포 jar 단독 사용자에겐 데모가 없다 — 사용자가 실수집 체험을 우선해 의도 선택. init/doctor는 레포 무관이므로 온보딩 공백은 제한적.
- **doctor 항목의 시효**: 환경이 변하면 처방 문구가 낡는다 — 처방은 명령·문서 링크 위주로 짧게 유지.

# SP3 구현 계획 — init·doctor·demo + 문서 재구성

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `tia init`/`doctor`/`demo` 서브커맨드 + GETTING-STARTED 2부 재구성.

**Architecture:** 세 커맨드 모두 `tia-cli`(picocli). TIA 레포 감지·git HEAD 해석은 공용 헬퍼로. demo는 스크립트(수집)+인프로세스 picocli(index~report) 혼합 오케스트레이션.

**참조:** spec `docs/superpowers/specs/2026-07-27-sp3-onboarding-design.md`(**설계 세부는 여기 따름 — 이 플랜은 골격**), 요구명세 `docs/superpowers/requirements/2026-07-27-sp3-requirements.md` (SP3-REQ-001..011; 매트릭스 직접 갱신)

## Global Constraints

- 프로젝트 exit 체계: 0=ok, 1=error, 2=usage(picocli USAGE 정렬) [SP3-REQ-003] — exit 3은 기존 ImpactCommand `--strict` 전용(SP3 범위 밖)
- doctor는 DB 파일이 존재할 때만 CoverageStore를 연다(생성자 부작용 회피) [SP3-REQ-005]
- doctor `--format`은 전용 2값 enum(text|json); json은 schemaVersion:1 [SP3-REQ-006]
- init 쓰기 대상=git 루트(비-git이면 cwd); 상향 탐색 가드; CodeFilterMixin 재사용 금지(전용 --include-code, --exclude-code 없음) [SP3-REQ-001/002]
- demo 3~6단계는 인프로세스 picocli(`new CommandLine(new TiaCommand()).execute(...)`), 산출물은 `build/inprocess-e2e/`(demo-tia.db·demo.diff·report.html) [SP3-REQ-008]
- 기존 스위트 무변경 green. 워크트리 브랜치 `feat/sp3-onboarding`. 커밋에 `[SP3-REQ-…]` + 세션 트레일러.
- E2E는 e2e 모듈 인프로세스 패턴(@TempDir, stdout/stderr 캡처 — SpecAcceptanceE2ETest·ConfigE2ETest 참고), @Execution(SAME_THREAD).

## File Structure

| 파일 | 책임 |
|---|---|
| `tia-cli/src/main/java/io/tia/cli/RepoPaths.java` (신규) | TIA 레포 루트 상향 탐색 헬퍼 + `git rev-parse HEAD` 헬퍼(실패 null) |
| `tia-cli/src/main/java/io/tia/cli/InitCommand.java` (신규) | 마법사(비대화형 1급, TTY 질문은 얇게) |
| `tia-cli/src/main/java/io/tia/cli/DoctorCommand.java` (신규) | 6체크 진단 + text/json |
| `tia-cli/src/main/java/io/tia/cli/DemoCommand.java` (신규) | 단계 마커·해설 + 오케스트레이션(§4, 히든 시임 3종) |
| `scripts/demo-collect.sh` (신규) | serial 단일 모드 수집+convert (경량; 출력 디렉터리 인자/`DEMO_OUT`) |
| `tia-cli/src/main/java/io/tia/cli/TiaCommand.java` (수정) | subcommands + usage 문자열 |
| e2e `io/tia/e2e/onboarding/InitCommandE2ETest·DoctorCommandE2ETest·DemoCommandE2ETest` (신규) | 매트릭스의 수용 테스트 |
| `tia-cli/src/test/java/io/tia/cli/CliWiringTest.java` (수정) | `optionsForInitDoctorDemo` 추가 |
| `GETTING-STARTED.md`·`README.md` (수정) | §5 재구성 |

핵심 시그니처(태스크 간 계약):

```java
// RepoPaths.java
public final class RepoPaths {
    /** start부터 상향: scripts/run-inprocess-e2e.sh 존재 && settings.gradle에
     *  "rootProject.name = 'test-impact-analysis'" 포함 → 그 디렉터리. 없으면 null. */
    public static Path findTiaRepoRoot(Path start);
    /** git rev-parse HEAD (workingDir 기준 — ImpactCommand.runGitDiff(ref, workingDir) 패턴,
     *  ProcessBuilder.directory(workingDir) 명시). 비-git/실패 → null. */
    public static String gitHead(Path workingDir);
    /** start부터 상향으로 .git 보유 디렉터리(git 루트). 없으면 null. */
    public static Path findGitRoot(Path start);
}
```

---

### Task 1: RepoPaths + InitCommand + E2E

**REQ-IDs:** SP3-REQ-001, SP3-REQ-002, SP3-REQ-003, SP3-REQ-010(부분)

- [ ] **Step 1: 실패 E2E 작성** — `InitCommandE2ETest`(케이스는 매트릭스 4개 + @DisplayName("SP3-REQ-00N: …")). cwd 독립을 위해 init에 히든 `--search-root <dir>`(탐색·쓰기 기준점; 프로덕션 기본 cwd)를 계약으로 사용. 비TTY는 테스트 환경이 원래 비TTY라 그대로 검증됨. red 확인(`./gradlew :e2e:test --tests 'io.tia.e2e.onboarding.Init*'`).
- [ ] **Step 2: RepoPaths + InitCommand 구현.** InitCommand 요점: `--topology`(enum in-process|out-of-process, 비TTY 미지정 → `spec.commandLine().getUsageMessage()` 대신 ParameterException으로 exit 2), `--sut-name`(기본 dir basename), `--include-code`(List, 전용 선언), `--force`, hidden `--search-root`. 쓰기 대상 = `RepoPaths.findGitRoot(searchRoot)` ?: searchRoot. 가드 = `TiaConfigLoader.load(null, searchRoot)` 상향 탐색 결과 존재 시 exit 1(경로 출력). 생성 YAML은 SP1 스키마 + 함정 경고 주석(spec §2 문구). "다음 단계" 출력은 토폴로지별 분기(+혼합 안내는 TTY 질문 텍스트에 포함).
- [ ] **Step 3: green + 회귀**(`:tia-cli:test`, SpecAcceptance 무변경) 후 Commit `feat(cli): tia init — tia.yml 마법사·섀도잉 가드 [SP3-REQ-001..003]` + 매트릭스 갱신.

### Task 2: DoctorCommand + E2E

**REQ-IDs:** SP3-REQ-004, SP3-REQ-005, SP3-REQ-006, SP3-REQ-010(부분)

- [ ] **Step 1: 실패 E2E 작성** — `DoctorCommandE2ETest` 6케이스(매트릭스). 픽스처 "healthy": @TempDir에 **ImpactCommandTest의 git 헬퍼 패턴 재사용**(`git init -q` → `git config user.email/user.name`(로컬) → `git add`+`git commit` — 초기 커밋 없으면 HEAD 미해석으로 체크5 SKIP) + 유효 tia.yml + `tia index`로 HEAD 인덱스 생성(spec-testwise.json 리소스 재사용; index는 파일 경로 실존을 검증하지 않아 합성 경로 OK).
- [ ] **Step 2: DoctorCommand 구현.** `@Mixin ConfigMixin` + `--db` + 전용 `DoctorFormat {text, json}`. **체크 2·5·6의 기준 디렉터리 = ConfigMixin의 resolved search-root(`--search-root` ?: cwd)를 RepoPaths에 그대로 전달**. 체크 구현은 spec §3 표·판정 그대로(4번: Files.exists만; 5번: gitHead null → SKIP, DB 존재 시에만 열어 `load(head).tests().isEmpty()`; 6번: findTiaRepoRoot null → SKIP). json은 jackson ObjectNode로 spec 스키마 그대로.
- [ ] **Step 3: green + 회귀 후 Commit** `feat(cli): tia doctor — 6체크 진단·읽기전용·json [SP3-REQ-004..006]` + 매트릭스 갱신.

### Task 3: DemoCommand + demo-collect.sh + CliWiring + E2E

**REQ-IDs:** SP3-REQ-007, SP3-REQ-008, SP3-REQ-009, SP3-REQ-010

- [x] **Step 1: 실패 E2E 작성** — `DemoCommandE2ETest` 4케이스(매트릭스: outsideRepoExit1·stubbedCollectRealIndexImpactReport·failingStubShowsStderrAndDoctorHint·zeroCoveredLinesFailsAtDiffStage) + `CliWiringTest#optionsForInitDoctorDemo`(세 커맨드 존재; doctor `--config`/`--db` 존재; init `--exclude-code`·`--config` 부재; demo `--scripts-dir`/`--out-dir`/`--repo-root`(hidden) 존재·`--db`/`--include-code` 부재). 스텁 scripts-dir(@TempDir): `setup-pjacoco.sh`(echo만)·`demo-collect.sh`(**인자로 받은 out-dir**에 가짜 testwise_serial.json 생성 — 형식은 e2e 리소스 spec-testwise.json 복사; 실패 스텁은 exit 1+stderr; zero-covered 스텁은 covered lines 없는 JSON 생성). 모든 데모 테스트는 `--repo-root <실제 TIA 레포 루트 or @TempDir>` + `--out-dir <@TempDir>`로 격리.
- [x] **Step 2: demo-collect.sh 작성.** run-inprocess-e2e.sh에서 serial 모드만 추출: `$1`(또는 `DEMO_OUT`)을 출력 디렉터리로 받고, `:fixture-app:classes`(installDist 재빌드 없음)와 serial 수집 태스크 1회 + `tia convert`(인프로세스 CLI가 아니라 스크립트에서는 기존 방식대로 `$REPO_ROOT`의 설치본 사용 대신 — 주의: demo가 이미 tia 바이너리로 돌므로 convert도 DemoCommand의 인프로세스로 옮기는 편이 단순하면 그렇게 하고 스크립트는 수집만 담당해도 됨; 결정과 근거를 리포트에 기록) → `<out>/testwise_serial.json`. **결정: convert는 스크립트에 남겼다**(spec §4 문구 "수집+convert"와 정합; 이미 설치된 `tia-cli/build/install/tia/bin/tia`를 재사용하므로 installDist 재빌드 없이도 안전).
- [x] **Step 3: DemoCommand 구현.** spec §4 그대로: 히든 시임 3종(`--repo-root`/`--scripts-dir`/`--out-dir`), 단계 마커 `=== [N/6] … ===`, 전제 검사(exit 1+클론 안내), 스크립트 2개 ProcessBuilder 구동(2단계 전 "첫 실행 1~3분" 안내), 인프로세스 index → diff 동적 생성(첫 covered line; `--- a/<f>\n+++ b/<f>\n@@ -<line>,1 +<line>,1 @@\n-x\n+y`; **커버 라인 0 → 명확 실패+doctor 안내+exit 1**) → 인프로세스 impact(정확히 DETERMINISTIC 1건 해설) → 인프로세스 report → 요약 4줄+CTA("내 프로젝트에 적용하려면: tia init"). **인프로세스 단계 실패 캡처: 각 execute() 주변에서 System.err를 버퍼로 스왑(+finally 복원) 후 마지막 20줄 요약.**
- [x] **Step 4: green + 회귀 + (레포 안 수동 스모크: `tia demo` 실구동 1회 — 소요 시간 포함 리포트 기록; 실 전 구간 자동화는 저빈도 CI 잡 백로그) 후 Commit** `feat(cli): tia demo — 경량 수집+인프로세스 index~report 오케스트레이션 [SP3-REQ-007..010]` + 매트릭스 갱신. **실구동 스모크 완료**: `./gradlew :tia-cli:installDist` 후 `tia demo` 실행, 45초 만에 전 구간(1~6단계) 성공 — pjacoco 에이전트 이미 캐시돼 있어 통상 "1~3분" 안내보다 빠름; 실제 fixture-app 8개 테스트가 수집돼 DETERMINISTIC 4건 선별(스텁 E2E의 "정확히 1건"은 통제된 고정 fixture 때문이며 실구동에서는 실제 커버리지 형상에 따라 달라짐 — 정상).

### Task 4: 문서 재구성

**REQ-IDs:** SP3-REQ-011

- [x] spec §5 그대로: GETTING-STARTED 1부(①클론+`./gradlew :tia-cli:installDist` ②`tia demo`(소요 1~3분 명시) ③`tia init`→수집→index/impact ④doctor) + 2부(기존 8절 전부 보존·링크, §0 슬림화). README 빠른 시작 demo 중심 + **79~93행 부근 run-inprocess-e2e.sh 오기술(index/impact/git diff 포함 서술)을 실제 범위(수집+convert)로 교정** + 기대 출력 실제 일치. skills/tia/SKILL.md에 doctor 한 줄. **앵커 보존 게이트**: `grep -o '](GETTING-STARTED.md#[^)]*' README.md skills/tia/SKILL.md`로 앵커 전수 수집 → 재구성 후 실제 헤딩과 전수 대조(결과를 리포트에 기록).
- [x] 전체 스위트 green + 매트릭스 11/11 + Coverage 갱신 → Commit `docs: GETTING-STARTED 2부 재구성 — demo/init/doctor 튜토리얼 [SP3-REQ-011]`.

## 완료 정의

매트릭스 11/11 + 전체 스위트 green + 문서 게이트 + demo 실구동 스모크 1회 기록.

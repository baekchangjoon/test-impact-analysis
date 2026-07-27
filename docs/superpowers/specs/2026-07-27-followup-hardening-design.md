# 후속 하드닝 배치 — 설계 + 요구사항명세 (통합)

> 5-SP(사용성 개선, PR #26~#31) 완료 후 이월 백로그의 일괄 처리. 항목이 소규모 하드닝·테스트·CI·문서
> 위주라 설계와 요구사항명세를 한 문서로 통합한다(비례성 — 각 항목은 원자적이고 상호 독립).
> 완료 정의(DoD): **머지 전** 매트릭스 8/9 green(FU-REQ-001..007, 009) + 전체 스위트 green.
> **FU-REQ-008은 명시적 후머지 게이트** — `workflow_dispatch` 검증이 본질상 머지 후에만 가능하므로
> 분모에 남기되 머지 직후 1회 실행·기록으로 마감한다(침묵 이월 아님 — 본 문단이 그 선언).

## 0. 범위와 기각

| 백로그 항목 | 처리 |
|---|---|
| JDK 22+ TTY 판별 파손 예정 | FU-REQ-001 |
| 비-git 디렉터리 상향 탐색이 FS 루트까지 | FU-REQ-002 |
| CoverageStore WAL 하드닝 | FU-REQ-003 |
| DbPaths.gitCommonDir git worktree 토폴로지 픽스처 부재 | FU-REQ-004 |
| doctorFailIsNotError 비허메틱(실캐시 DB 접촉 가능) | FU-REQ-005 |
| REQ-015 "N/M" 형식 E2E 단언 미보강 | FU-REQ-006 |
| pr-comment 실PR 스모크(permissions 필요) | FU-REQ-007 |
| demo 실경로 저빈도 CI 잡 | FU-REQ-008 |
| ReportBuilder.Inputs 시그니처 릴리스 노트 | FU-REQ-009 |
| tia-config 모듈 분리 후보 | **기각** — 현 `tia-core`의 `config` 패키지는 소비자가 tia-cli·tia-gradle-plugin 둘뿐이고 둘 다 이미 tia-core에 의존한다. 모듈 분리는 게시·버전·클래스패스 관리 비용만 늘리고 격리 이득이 없다. 재검토 조건: 제3의 소비자(예: 독립 MCP 배포물)가 tia-core 전체 의존을 부담스러워할 때. |

## 1. 요구사항

### FU-REQ-001 — TTY 판별의 JDK 22+ 호환 (Functional / Must)
- **문제**: `System.console() != null`은 JDK 22+에서 리다이렉트 시에도 non-null(JLine 통합) → 파이프에
  ANSI 누출 예정. 사용처 3곳: `ImpactCommand`(ansiColor), `FlakyCommand`(ansiColor), `InitCommand`(인터랙티브 게이트).
- **설계**: `tia-cli`에 `Tty.interactive()` 헬퍼 신설 — `Console.isTerminal()`을 **리플렉션**으로 탐지
  (JDK 17 컴파일 유지): 메서드가 있으면 그 결과, 없으면 `System.console() != null` 폴백. 리플렉션 실패는
  폴백으로 수렴(예외 전파 금지). Impact/Flaky의 boolean 체크는 헬퍼로 직접 교체. **InitCommand는 비대칭**:
  `readLine`을 위해 `Console` **객체**가 여전히 필요하므로 게이트만 `Tty.interactive()`로 바꾸고
  `System.console()` 획득은 유지(interactive && console != null일 때만 프롬프트). 기존 `NO_COLOR` 존중 불변.
- **수용기준**: Given JDK 17 런타임, When `Tty.interactive()` 호출, Then `System.console() != null`과 동일
  값(폴백 경로 실증 — 리플렉션 miss 분기 단위 테스트, `isTerminal` 존재 시 위임은 스텁 인터페이스로 단위 검증).
  회귀: `FormatE2ETest#impactSummaryPipedNoAnsi`(파이프 무-ANSI) + `InitCommandE2ETest#nonTtyMissingTopologyExit2`
  (InitCommand 비-TTY 게이트의 직접 회귀 가드) green.
- 검증 레벨: unit + 기존 E2E 회귀

### FU-REQ-002 — tia.yml 상향 탐색의 홈 경계 (Functional / Must)
- **문제**: `TiaConfigLoader.discover`는 `.git` 루트에서만 중단 — 비-git 디렉터리에선 FS 루트까지 올라가
  `/Users/tia.yml` 같은 무관 파일을 주울 수 있다.
- **설계**: `user.home` 도달 시 그 디렉터리의 candidate 확인 **후 중단**(홈 포함, 홈 상위 배제). 홈 밖에서
  시작한 탐색(예: `/tmp` 픽스처)은 기존 동작 유지(FS 루트까지 — 기존 E2E 하위호환). 테스트 시임:
  package-private `discover(Path searchStart, Path homeOverride)` 오버로드, 기존 public `discover(Path)`는
  `Path.of(System.getProperty("user.home"))`로 위임. 홈 도달 판정은 `toAbsolutePath().normalize()` 후
  `Path.equals`(symlink 미해석 — `toRealPath()` 안 씀). 심링크로 우회된 홈 경로는 경계가 안 걸리고 기존
  동작(FS 루트까지)으로 남는 **수용된 한계**다(안전 방향 실패 — 조기 중단이 아니라 과탐색).
- **수용기준**: Given 가짜 홈 `<h>`와 `<h>/a/b`(git 없음)+`<h>` 상위에 tia.yml, When `discover(<h>/a/b, <h>)`,
  Then null(상위 미탐색). Given `<h>`에 tia.yml, Then 발견. 기존 git-루트 중단·E2E 전부 무변경 green.
- 검증 레벨: unit + 전체 스위트 회귀

### FU-REQ-003 — CoverageStore SQLite 하드닝 (Non-functional / Must)
- **문제**: 커넥션에 PRAGMA 미설정 — 동시 접근(병렬 수집 머지, MCP 서버 세션과 CLI 병행) 시 즉시
  `SQLITE_BUSY` 실패 가능.
- **설계**: **읽기/쓰기 오픈 분리** — `journal_mode=WAL`은 DB 파일 헤더에 영속되는 **쓰기**라서 무조건
  적용하면 doctor의 문서화된 읽기 전용 불변식(진단 도구가 사용자 DB를 변형)을 깬다(리뷰 치명 소견).
  따라서: ① `PRAGMA busy_timeout=5000`은 모든 오픈에 적용(커넥션 스코프 — 파일 무변형). ② `PRAGMA
  journal_mode=WAL`은 **쓰기 오픈에만**(index/save 경로; 실패는 무시하고 busy_timeout만 유지 — 최선 노력).
  읽기 소비자(doctor·impact — report/flaky는 CoverageStore 미사용으로 해당 없음)는 읽기 오픈을 쓰며, 읽기
  오픈은 DB 파일·스키마를 **생성하지도 않는다**(파일 부재 시 무접속 빈 스토어, 존재 시 READONLY 접속). ③ `save()`
  의 builds INSERT + coverage 배치 INSERT를 **명시적 단일 트랜잭션**으로 묶는다(동시 reader가 coverage 없는
  builds 행을 관측하는 기존 원자성 공백 해소 — busy_timeout/WAL만으론 안 닫힘).
- **수용기준**: Given 쓰기 오픈, Then busy_timeout=5000·journal_mode=`wal`. Given 읽기 오픈(기존 non-WAL
  DB), Then busy_timeout=5000이고 **DB 파일의 journal_mode가 변하지 않음**(doctor 불변식 회귀 가드).
  Given save() 도중 예외, Then builds/coverage 모두 미반영(트랜잭션 롤백). 기존 스위트 무변경 green.
- 검증 레벨: unit + 전체 스위트 회귀

### FU-REQ-004 — DbPaths git worktree 토폴로지 테스트 (Test / Must)
- **설계**: `DbPathsTest`에 실제 `git worktree add` 픽스처 — 메인 레포 `<r>`에 **최초 커밋을 만든 뒤**
  (커밋 0개 레포의 worktree add는 git 버전별 orphan 자동 추론에 의존해 비결정적) 링크드 워크트리 `<w>`
  생성, `gitCommonDir(<w>)`가 `<r>/.git`(절대·정규화)을 반환함을 단언.
- **수용기준**: Given `git worktree add`로 만든 `<w>`, When `DbPaths.gitCommonDir(<w>)`, Then `<r>/.git`.
- 검증 레벨: unit(실 git 픽스처)

### FU-REQ-005 — doctorFailIsNotError 허메틱화 (Test / Must)
- **문제**: 해당 E2E의 working_dir 픽스처가 git 레포가 아니라 기본 DB가 실 XDG 캐시 경로로 해석될 수 있다.
- **설계**: 픽스처 temp 디렉터리를 `git init`(bare init으로 충분 — `DbPathsTest#workingDirGitRepoUsesItsCommonDir`
  가 커밋 0개로 동일 목표를 이미 실증; 커밋·git config 불필요)으로 만들어 기본 DB가 `<tmp>/.git/tia/tia.db`
  로 해석되게 한다(SP4 Task 1 시임 활용 — env 시임 불필요). 기존 단언(체크3 FAIL·isError=false)은 불변.
- **수용기준**: 테스트가 실 사용자 캐시 경로를 읽지 않음(픽스처 git 레포 내에서 해석) + 기존 단언 green.
- 검증 레벨: E2E(기존 테스트 강화)

### FU-REQ-006 — summary "N/M개 선별" E2E 단언 (Test / Must)
- **설계**: `FormatE2ETest#impactSummaryPipedNoAnsi`에 `영향 테스트 \d+/\d+개 선별` 정규식 단언 추가
  (SP1 REQ-015 수용기준의 "N/M개 선별" 형식 — 현재 unit만 검증).
- **수용기준**: When summary 출력, Then 위 정규식 매치.
- 검증 레벨: E2E(기존 테스트 강화)

### FU-REQ-007 — Action pr-comment 실PR 스모크 잡 (CI / Must)
- **문제**: SP5의 PR 코멘트는 스크립트 E2E(스텁 gh)까지만 검증 — 실 GitHub 권한·실 `gh api` 경로 미실증.
- **설계**: `ci.yml`에 잡 `action-pr-comment-smoke` 신설 — **온디맨드(label 게이트), 영구 per-PR 잡 아님**
  (모든 미래 PR에 이미지 빌드 비용+봇 코멘트를 영구 부과하지 않기 위해 — FU-REQ-008의 저빈도 취지와 일관).
  - **게이트/권한**: 잡 레벨 `if: github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && contains(github.event.pull_request.labels.*.name, 'tia-smoke')`.
    (`ci.yml`의 `on:`은 push에도 걸리므로 event 가드 필수; same-repo 가드는 fork 토큰 다운그레이드가 GitHub
    기본 설정("Send write tokens to workflows from fork PRs" OFF)에 의존하는 데 대한 코드 레벨 심층 방어 —
    그 설정 의존성 자체도 여기 명시해 둔다.) `permissions: pull-requests: write` + `contents: read`는
    **이 잡에만** 잡 레벨로 부여.
  - **스텝**: ① `:tia-cli:shadowJar` → `docker build -t tia:ci -f docker/Dockerfile .` ② 컨테이너로 픽스처
    인덱싱: `tia index --report <e2e 스펙 testwise JSON(FormatE2ETest 픽스처)> --repo fixture --commit C0
    --db tia-smoke.db`(--report/--repo는 required) ③ 인덱싱된 클래스에 매칭되는 최소 unified diff 파일
    생성(E2E `mixedDiff()`와 동형) ④ `uses: ./` — db/commit=C0/diff-file/`image: tia:ci`/`pr-comment: 'true'`
    ⑤ 검증: 스텝 outputs `run-all == 'false'`·`selected` 비어있지 않음 + PR 코멘트에 `<!-- tia-impact-comment -->`
    마커 존재 단언 — **`gh api --paginate`(또는 최신순 정렬)로 전체 코멘트 조회**(마커 코멘트가 30개 페이지
    밖으로 밀리는 오탐 방지).
  - **인젝션 방어**: PR 유래 값(제목·브랜치명 등)을 `run:` 블록에 `${{ }}` 직접 보간 금지 — action.yml의
    기존 관례대로 `env:` 경유만 허용.
  - **자기실증 절차**: 본 배치 PR 오픈 → `tia-smoke` 라벨 부착 → 커밋 푸시(synchronize 이벤트 — 라벨이
    payload에 실림)로 잡 트리거 → green + 마커 확인. 이후엔 라벨 붙인 PR에서만 온디맨드 재검증.
- **수용기준**: 이 배치의 PR 자체 CI에서 잡 green + 실코멘트 마커 확인(자기실증). 라벨 없는 PR·push에선
  잡 스킵.
- 검증 레벨: 실 CI(PR)

### FU-REQ-008 — demo 실경로 저빈도 CI 잡 (CI / Must)
- **설계**: `.github/workflows/demo-weekly.yml` 신설 — `schedule`(주 1회) + `workflow_dispatch`. 스텝:
  JDK 17 → Set up Gradle(cache — 기존 잡들과 동일 관례) → `scripts/setup-pjacoco.sh`(기존 CI와 동일 해소)
  → `./gradlew :tia-cli:installDist` → 설치된 실 바이너리로 `tia demo` 실행(히든 시임 미사용 — 실경로;
  `working-directory` 오버라이드 없이 체크아웃 루트 cwd 유지 — 레포 루트 탐지 전제) → 종료코드 0 +
  stdout `[6/6]` 스테이지 마커 + `build/inprocess-e2e/demo/report.html` 존재·비어있지 않음 단언.
- **수용기준**: `workflow_dispatch` 수동 1회 실행 green(머지 후 즉시 트리거해 기록). PR CI에는 미포함
  (저빈도 취지).
- 검증 레벨: 실 CI(dispatch)

### FU-REQ-009 — 릴리스 노트 신설 (Docs / Must)
- **설계**: `docs/RELEASE-NOTES.md` 신설(한국어) — 0.2.0 이후 변경 요약(SP1~SP4: tia.yml·필터·출력 4종·
  PR 코멘트·init/doctor/demo·플러그인 설정 소비·`tia mcp`)과 **호환성 절: `ReportBuilder.Inputs` 시그니처
  변경(SP1의 FilterSet 추가) 명시**. README에서 링크.
- **수용기준**: 파일 존재 + Inputs 변경 명시 + README 링크. (빌드/문서 게이트 수준.)
- 검증 레벨: docs 게이트

## 2. 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| FU-REQ-001 | TTY 판별 JDK22 호환 | TtyTest#fallbackOnJdk17 / #delegatesWhenIsTerminalPresent + FormatE2ETest 회귀 | unit+E2E | 🟢 green |
| FU-REQ-002 | tia.yml 홈 경계 | TiaConfigLoaderTest#discoverStopsAtHome / #discoverFindsAtHome | unit | 🟢 green |
| FU-REQ-003 | SQLite 하드닝(R/W 분리) | CoverageStoreTest#writeOpenAppliesPragmas / #readOpenKeepsJournalMode / #saveIsAtomic / #readOpenOnMissingFileDoesNotCreateAnything / #readOpenOnSchemaLessFileDoesNotMutateBytesAndReadsEmpty | unit | 🟢 green |
| FU-REQ-004 | worktree 토폴로지 | DbPathsTest#worktreeResolvesToMainCommonDir | unit | 🟢 green |
| FU-REQ-005 | doctor E2E 허메틱 | McpCommandE2ETest#doctorFailIsNotError(강화) | E2E | 🟢 green |
| FU-REQ-006 | "N/M개 선별" 단언 | FormatE2ETest#impactSummaryPipedNoAnsi(강화) | E2E | 🟢 green |
| FU-REQ-007 | pr-comment 실PR 스모크 | ci.yml `action-pr-comment-smoke` 잡 — PR #32 run 30268995437 자기실증(green + 마커 코멘트 1건) | CI | 🟢 green |
| FU-REQ-008 | demo 주간 잡 | demo-weekly.yml + dispatch 1회 기록 | CI | 🟡 작성됨 (dispatch 대기) |
| FU-REQ-009 | 릴리스 노트 | docs 게이트(파일·Inputs 명시·README 링크) | docs | 🟢 green |

Coverage: 8/9 green (89%) — FU-REQ-007은 PR #32에서 자기실증 완료(run 30268995437 green + `<!-- tia-impact-comment -->`
마커 코멘트 1건 확인). 잔여는 FU-REQ-008 dispatch(머지 후 게이트)뿐.
target: 머지 전 8/9(FU-REQ-008 제외) + 머지 후 FU-REQ-008 dispatch 기록으로 9/9 (대상: Must 9;
tia-config 분리는 기각으로 매트릭스 제외)

## 3. 태스크 플랜 (SDD)

- **Task 1 — 코드·테스트 하드닝** [FU-REQ-001..006]: Tty 헬퍼(+Init 비대칭 처리), discover 홈 경계+시임,
  CoverageStore R/W 분리 PRAGMA+save 트랜잭션, DbPaths worktree 테스트(초기 커밋 픽스처), doctor E2E
  git-init 픽스처, summary E2E 정규식. TDD(red→green), 전체 스위트 green, 커밋 1개.
- **Task 2 — CI 잡 2종** [FU-REQ-007..008]: ci.yml 스모크 잡(label 게이트·페이지네이션 단언·env 경유) +
  demo-weekly.yml(gradle cache 포함). 로컬 검증: `actionlint`(있으면)/yaml 파싱 + 스크립트 조각 shell
  문법 검사. 커밋 1개.
- **Task 3 — 릴리스 노트** [FU-REQ-009]: docs/RELEASE-NOTES.md + README 링크. 커밋 1개.
- 이후: whole-branch 리뷰 → PR 오픈 → `tia-smoke` 라벨 부착 → 커밋 푸시로 스모크 잡 트리거(FU-REQ-007
  자기실증) → CI green + 코멘트 마커 확인 → 리베이스 머지 → 머지 후 demo-weekly `workflow_dispatch` 1회
  실행·기록(FU-REQ-008 마감 — DoD 카브아웃 §문서 서두).

# Task 1 — 코드·테스트 하드닝 [FU-REQ-001..006] 실행 보고

- 브랜치: `chore/followup-hardening`
- 시작 HEAD: `b2767f2`
- 스펙: `docs/superpowers/specs/2026-07-27-followup-hardening-design.md` §1 FU-REQ-001..006 + §2 매트릭스

## 요약

FU-REQ-001..006 6개 항목 모두 구현·테스트 완료, 매트릭스 상태를 🔴 planned → 🟢 green으로 갱신.
전체 스위트(tia-core/tia-cli/e2e) green. 커밋 1개 생성.

## 항목별 처리

### FU-REQ-001 — TTY 판별 JDK 22+ 호환
- 신설: `tia-cli/src/main/java/io/tia/cli/Tty.java` — package-private `interactive()`(실 진입점, `System.console()` 사용)
  + `interactive(Object consoleOrNull)`(테스트 시임). 리플렉션으로 `Console.isTerminal()`을 탐지해 위임하고,
  없거나(JDK 17-21) 호출 실패 시 `true`로 수렴(consoleOrNull이 이미 non-null로 확정된 분기라 기존
  `System.console() != null` 폴백과 동치). 예외는 모두 흡수(전파 금지).
- `ImpactCommand.render`(구 line 125)·`FlakyCommand.render`(구 line 81)의 `System.console() != null` →
  `Tty.interactive()`로 교체(NO_COLOR 존중 불변).
- `InitCommand.resolveTopology` — 게이트만 `Tty.interactive()`로 교체하고 `System.console()` 객체 획득은
  유지(비대칭 — `readLine`을 위해 여전히 필요). `!Tty.interactive() || console == null`일 때 비대화형
  경로(exit 2)로 수렴.
- 신규 테스트: `tia-cli/src/test/java/io/tia/cli/TtyTest.java` —
  `fallbackOnJdk17`(실 JDK17 런타임에서 `System.console() != null`과 동치 검증) +
  `delegatesWhenIsTerminalPresent`(`isTerminal()`을 가진 익명 스텁 객체로 위임 분기 단위 검증) +
  `nullConsoleIsNotInteractive`(경계 케이스).
- 회귀: `FormatE2ETest#impactSummaryPipedNoAnsi`, `InitCommandE2ETest#nonTtyMissingTopologyExit2` green.

### FU-REQ-002 — tia.yml 홈 경계
- `TiaConfigLoader.discover(Path)`(public)는 `Path.of(System.getProperty("user.home"))`로 package-private
  `discover(Path searchStart, Path homeOverride)`에 위임하도록 변경. 신규 오버로드는 `toAbsolutePath()
  .normalize()` 후 `Path.equals`로 홈 도달을 판정 — 홈 디렉터리 자체의 candidate는 확인하되(발견 시 반환),
  그 이후 부모로는 올라가지 않는다(홈 상위 배제). `toRealPath()`는 쓰지 않음(심링크 우회는 수용된 한계 —
  안전 방향 실패인 과탐색으로 남음).
- 신규 테스트(`TiaConfigLoaderTest`): `discoverStopsAtHome`(가짜 홈 `<h>`의 부모에 있는 tia.yml을 홈
  경계 밖으로 판정해 null) / `discoverFindsAtHome`(`<h>` 자체의 tia.yml은 발견).
- 기존 git-root-stop 테스트(`upwardDiscoveryStopsAtGitRoot` 등)는 무변경 green — TempDir가 실 홈 밖이라
  홈 경계와 마주치지 않음.

### FU-REQ-003 — CoverageStore SQLite 하드닝(R/W 분리 + 원자적 save)
- `CoverageStore` 생성자를 write-open(기존 `public CoverageStore(Path)` 유지 — `IndexCommand`, 테스트
  픽스처 등 기존 호출부 전부 무변경)과 신설 `public static CoverageStore openRead(Path)`(read-open)로
  분리. 내부 private 생성자(`this(dbFile, write)`)가 `busy_timeout=5000`은 모든 오픈에, `journal_mode=WAL`
  전환은 write-open에만(best-effort — 실패 시 무시) 적용.
- `ImpactCommand`(구 line 64)·`DoctorCommand`(구 line 130)의 `new CoverageStore(effectiveDb)` →
  `CoverageStore.openRead(effectiveDb)`로 교체(읽기 전용 소비자). `IndexCommand`는 변경 없음(write).
  `report`/`flaky` 커맨드는 실제로 `CoverageStore`를 쓰지 않음을 확인(코드 조사 — 스펙의 "report/flaky
  paths" 언급은 해당 사항 없음).
- `save()`를 `conn.setAutoCommit(false)` + builds INSERT + coverage 배치 INSERT + `commit()`으로 묶고,
  임의 예외 시 `rollback()` 후 원 예외(RuntimeException은 그대로, 그 외는 래핑) 재던짐. `finally`에서
  `setAutoCommit(true)` 복원.
- 테스트 전용 package-private 시임 `busyTimeoutMillis()`/`journalMode()` 추가(둘 다 커넥션 자체에서
  PRAGMA 조회 — `busy_timeout`은 파일에 영속되지 않는 커넥션 스코프 값이라 별도 커넥션으로는 검증 불가).
- 신규 테스트(`CoverageStoreTest`): `writeOpenAppliesPragmas`(busy_timeout=5000·journal_mode=wal) /
  `readOpenKeepsJournalMode`(직접 JDBC로 non-WAL DB를 만든 뒤 `openRead`로 열어 journal_mode가 `delete`
  그대로임을 확인 — doctor 읽기 전용 불변식 회귀 가드) / `saveIsAtomic`(널 `RoaringBitmap` 값으로 NPE를
  주입해 builds INSERT가 커밋된 채 남지 않음을 `distinctBuildCount`로 확인).

### FU-REQ-004 — DbPaths git worktree 토폴로지 테스트
- `DbPathsTest#worktreeResolvesToMainCommonDir` 신설: 메인 레포에 최초 커밋(로컬 `user.name`/`user.email`
  설정 후)을 만들고 `git worktree add`로 링크드 워크트리 생성, `DbPaths.resolveDefault(worktree)`가
  `<메인레포>/.git/tia/tia.db`로 해석됨을 단언.
- 구현 중 발견한 이슈: macOS에서 `/var` → `/private/var` 심링크로 인해, linked worktree의
  `git rev-parse --git-common-dir`은 절대(실)경로를 반환하지만 JUnit `@TempDir`의 원경로는 미해석 상태라
  단순 `toAbsolutePath().normalize()` 비교가 실패했다. 기대값을 `mainRepo.toRealPath().resolve(".git")`
  으로 계산하도록 수정해 git과 동일한 기준으로 비교(테스트만의 수정 — `DbPaths` 프로덕션 코드는 무변경,
  기존 `gitCommonDir` private 로직 그대로).

### FU-REQ-005 — doctorFailIsNotError 허메틱화
- `McpCommandE2ETest#doctorFailIsNotError`의 `@TempDir work`에 `git init -q`(커밋·config 불필요) 추가 —
  기본 DB가 실 XDG 캐시 경로 대신 `<work>/.git/tia/tia.db`로 해석되도록 픽스처를 격리. 기존 단언(체크3
  FAIL·`isError=false`) 무변경.

### FU-REQ-006 — "N/M개 선별" E2E 단언
- `ImpactFormats.java:57`의 실제 문구(`"영향 테스트 " + N + "/" + M + "개 선별 (..."`) 확인 후,
  `FormatE2ETest#impactSummaryPipedNoAnsi`에 정규식 `영향 테스트 \d+/\d+개 선별` 단언 추가.

## 매트릭스 갱신

`docs/superpowers/specs/2026-07-27-followup-hardening-design.md` §2: FU-REQ-001..006 상태를
🔴 planned → 🟢 green으로 갱신, coverage 라인을 `6/9 green (67%)`로 갱신(FU-REQ-007..009는 Task 2/3 소관,
미변경).

## 검증

- `./gradlew :tia-core:test :tia-cli:test :e2e:test` — 전체 green.
  - tia-core: 76 tests, 0 failures/errors
  - tia-cli: 53 tests, 0 failures/errors (TtyTest 3 + DbPathsTest worktree 테스트 1 포함)
  - e2e: 80 tests, 0 failures/errors (강화된 doctorFailIsNotError·impactSummaryPipedNoAnsi 포함, 다른
    기존 테스트는 무변경)
- 컨테이너·백그라운드 프로세스를 띄우는 테스트 없음(순수 인프로세스 unit/E2E) — 누수 검증 게이트 해당 없음.

## 커밋

`fix(core,cli): 하드닝 — TTY 판별 JDK22 대비·tia.yml 홈 경계·SQLite R/W 분리·픽스처 보강 [FU-REQ-001..006]`

## 우려·비고

- `report`/`flaky` 커맨드는 `CoverageStore`를 전혀 쓰지 않아(코드 확인) FU-REQ-003의 "read consumers
  (doctor/impact/report/flaky paths)" 문구 중 report/flaky는 실질적으로 해당 사항 없음 — 별도 변경 불필요.
- FU-REQ-002의 심링크 우회 한계(수용된 한계, 스펙에 명시)는 그대로 유지 — `toRealPath()` 미사용.
- FU-REQ-004 테스트의 macOS 심링크 이슈는 테스트 코드 한정 수정이며, 다른 플랫폼(Linux CI 등, `/tmp`가
  심링크가 아닌 환경)에서도 `toRealPath()` 비교는 항상 유효(실경로가 곧 표시 경로).
- FU-REQ-007..009(Task 2/3)는 이번 태스크 범위 밖 — 미착수.

## Fix round 1

리뷰 Important 지적: `CoverageStore.openRead()`가 `initSchema()`를 무조건 실행해, 스키마 없는(또는
아예 존재하지 않는) DB 파일에 대한 읽기 오픈이 실제 CREATE TABLE 쓰기를 수행하고(게다가 JDBC sqlite
드라이버는 커넥션 오픈만으로 파일 자체를 생성) 읽기 전용 분리 의도와 모순된다는 지적을 반영했다.

### 원인

`private CoverageStore(Path dbFile, boolean write)` 생성자가 `write` 플래그와 무관하게
`initSchema()`를 항상 호출했다(구 line 52). `ImpactCommand`는 (Doctor와 달리) DB 파일 존재 여부를
사전 체크하지 않고 곧바로 `CoverageStore.openRead(effectiveDb)`를 호출하므로, "베이스라인 없음"
케이스(가장 흔한 최초 실행)마다 이 경로를 탔다.

### 수정

- `CoverageStore` 필드를 `Connection conn`(nullable) + `boolean hasSchema`로 확장.
- 읽기 오픈(`openRead`) 분기:
  - DB 파일이 아예 없으면(`Files.exists(dbFile)` false) **커넥션 자체를 열지 않는다** — JDBC sqlite가
    오픈만으로 빈 파일을 생성하는 부작용을 원천 차단. `conn = null`, `hasSchema = false`.
  - DB 파일이 있으면 `org.sqlite.SQLiteConfig#setReadOnly(true)`로 **SQLITE_OPEN_READONLY** 커넥션을
    연다(어떤 경로로도 파일 변형 불가 — 스키마 생성은 물론 `journal_mode` 전환도 물리적으로 차단).
    이어서 `sqlite_master`에서 `builds` 테이블 존재를 조회해 `hasSchema`를 결정(스키마 없는 기존 파일도
    빈 스토어로 취급).
  - 쓰기 오픈(기존 `public CoverageStore(Path)`)은 무변경 — 부모 디렉터리 생성 + `initSchema()` 그대로.
- `load()`/`distinctBuildCount()`는 `!hasSchema`면 쿼리를 실행하지 않고 곧장 빈 결과로 수렴(빈
  스냅샷/0) — 기존 "베이스라인 없음" CLI 관측 동작(`# tia:no-baseline` 마커, `strict` 종료 코드 등)은
  바이트 단위로 동일하게 유지된다. `close()`도 `conn == null`이면 no-op.

### 호출부 재확인

- `ImpactCommand`(line 64) — 사전 존재 체크 없이 `openRead` 직접 호출하는 유일한 소비자. 이번 수정으로
  파일 미존재 시에도 무생성 + 빈 스토어 시맨틱이 보장되어 기존 no-baseline 분기가 그대로 동작.
- `DoctorCommand`(line 130) — 이미 자체적으로 `dbExists` 가드 후에만 `openRead`를 호출(기존 방어),
  이번 수정은 추가 방어층으로 중첩 적용(회귀 없음).
- `report`/`flaky` 커맨드 — `CoverageStore` 미사용(Task 1 원 조사 그대로, 해당 없음).
- `IndexCommand` — 쓰기 생성자만 사용, 무변경.

### 테스트

`CoverageStoreTest`에 2건 추가(8 → 10 tests, 파일 클래스 기준):
- `readOpenOnMissingFileDoesNotCreateAnything` — 존재하지 않는 경로에 `openRead` 후 파일·부모
  디렉터리 모두 미생성 + `distinctBuildCount()==0`/`load().tests()` 빈 리스트 확인.
- `readOpenOnSchemaLessFileDoesNotMutateBytesAndReadsEmpty` — raw JDBC로 `dummy` 테이블만 있는(즉
  `builds`/`coverage` 없는) 기존 파일을 만든 뒤 `openRead`로 읽어, 오픈 전후 파일 바이트가
  `assertArrayEquals`로 완전히 동일함 + 빈 스토어로 읽힘을 확인.

기존 테스트(`writeOpenAppliesPragmas`, `readOpenKeepsJournalMode`, `saveIsAtomic` 등)는 무변경 —
어느 것도 스키마 자동 생성에 의존하지 않았음을 재확인.

### 검증

`./gradlew :tia-core:test :tia-cli:test :e2e:test` — 전체 green.
- tia-core: 78 tests, 0 failures/errors (CoverageStoreTest 10건 포함, +2)
- tia-cli: 53 tests, 0 failures/errors (변경 없음)
- e2e: 80 tests, 0 failures/errors (no-baseline 시나리오 포함 변경 없음)

컨테이너·백그라운드 프로세스 없음 — 누수 검증 게이트 해당 없음.

### 커밋

`fix(core): 읽기 오픈 무생성 보장 — 스키마/파일 미생성·빈 스토어 시맨틱 [FU-REQ-003]`
(df4db29 위에 새 커밋으로 추가, amend 아님)

### 우려·비고

- `SQLiteConfig.setReadOnly(true)`는 sqlite-jdbc(`org.xerial:sqlite-jdbc:3.46.1.3`, 이미 tia-core
  의존성에 존재)의 표준 API — 추가 의존성 불필요.
- 심링크/레이스(오픈 시점과 존재 체크 시점 사이 TOCTOU)로 파일이 오픈 직전 생성되는 극단적 동시성
  케이스는 다루지 않음(단일 CLI 프로세스 전제, 기존 설계와 동일한 수용 한계).

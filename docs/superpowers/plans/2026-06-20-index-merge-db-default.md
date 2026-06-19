# 멀티 build 병합(P1-3) + `--db` 기본값(P3-4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `tia` 인덱스가 한 commit의 모든 build를 test_id별 최신-build-wins로 병합해 멀티모듈 인덱스를 누락 없이 선별하고, `--db`를 생략하면 git-common-dir(비 git은 XDG) 기본 경로로 자동 해석한다.

**Architecture:** `CoverageStore.load`를 builds-우선 2단계 쿼리로 바꿔 병합(상태 없는 `distinctBuildCount`로 build 수 노출), 신규 `DbPaths`가 기본 경로를 해석(주입 가능한 seam), 두 CLI 커맨드가 `--db` optional + 기본값 INFO를 붙인다. core 단위(TDD inner) + CLI 블랙박스(E2E outer) 이중루프.

**Tech Stack:** Java 17, Gradle, picocli, SQLite(JDBC), RoaringBitmap, JUnit 5.

## Global Constraints

- 빌드/테스트는 **JDK 17+** 필요(`fixture-app`의 Spring Boot 3.3.x). 모든 gradle 명령 앞에
  `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home` 를 둔다.
- 작업 디렉터리: worktree `/Users/changjoonbaek/github_test-impact-analysis/test-impact-analysis/.claude/worktrees/feat+index-merge-db-default` (브랜치 `feat/index-merge-db-default`). 모든 경로는 이 worktree 기준.
- 스키마 변경 없음(`builds`/`coverage` 테이블 그대로). `save` 시그니처 불변.
- `build_id`는 `long`(`getLong` 사용). 병합 추적 맵은 `Map<String, Long>`.
- 커밋 메시지 trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` 와 `Claude-Session: https://claude.ai/code/session_01LXH8qJRxzMv4Jhewi4tyks`. gpg 서명은 `-c commit.gpgsign=false`.
- 각 E2E/단위 테스트는 검증 REQ-ID를 `@DisplayName`으로 참조한다.
- 구현 에이전트는 테스트 red→green 전이마다 요구사항명세 추적 매트릭스 상태를 갱신한다.

---

### Task 1: `CoverageStore` 2단계 병합 + `distinctBuildCount` + 부모 디렉터리 생성

**REQ-IDs:** REQ-001(core), REQ-002, REQ-003, REQ-005(core), REQ-009(부모 생성)

**Files:**
- Modify: `tia-core/src/main/java/io/tia/core/store/CoverageStore.java`
- Test: `tia-core/src/test/java/io/tia/core/store/CoverageStoreTest.java`

**Interfaces:**
- Consumes: 기존 `save(CoverageSnapshot): long`, 모델 `CoverageSnapshot(repo, commitSha, tests)`, `TestCoverage(testId, result, linesByFile)`.
- Produces:
  - `CoverageSnapshot load(String commitSha)` — 그 commit의 모든 build를 test_id별 최신-build-wins로 병합한 스냅샷(없으면 `repo=null, tests=[]`).
  - `int distinctBuildCount(String commitSha)` — commit의 distinct build 수(없으면 0).
  - 생성자가 `dbFile`의 부모 디렉터리를 자동 생성.

- [ ] **Step 1: 실패 테스트 작성 (병합·재인덱싱·카운트·부모생성)**

`tia-core/src/test/java/io/tia/core/store/CoverageStoreTest.java` 에 import와 테스트를 추가한다.
파일 상단 import 블록에 다음을 추가:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
```

클래스 본문에 다음 4개 테스트를 추가:

```java
@Test
@DisplayName("REQ-001/003: 같은 commit의 disjoint 2 build를 모두 병합 로드하고 build 수는 2")
void mergesDisjointBuildsForSameCommit(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("tia.db");
    try (CoverageStore store = new CoverageStore(db)) {
        store.save(new CoverageSnapshot("repoA", "c1", List.of(new TestCoverage("modA.T1", "PASSED",
            Map.of("io/tia/a/A.java", RoaringBitmap.bitmapOf(1, 2))))));
        store.save(new CoverageSnapshot("repoB", "c1", List.of(new TestCoverage("modB.T2", "PASSED",
            Map.of("io/tia/b/B.java", RoaringBitmap.bitmapOf(3, 4))))));

        CoverageSnapshot loaded = store.load("c1");
        assertEquals(2, loaded.tests().size(), "두 build의 테스트가 모두 보여야 함");
        assertTrue(loaded.tests().stream().anyMatch(t -> t.testId().equals("modA.T1")));
        assertTrue(loaded.tests().stream().anyMatch(t -> t.testId().equals("modB.T2")));
        assertEquals(2, store.distinctBuildCount("c1"));
    }
}

@Test
@DisplayName("REQ-002: 같은 test_id 재인덱싱 시 최신 build가 옛 라인을 대체(stale 제거)")
void reindexLatestBuildWinsPerTestId(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("tia.db");
    try (CoverageStore store = new CoverageStore(db)) {
        store.save(new CoverageSnapshot("r", "c1", List.of(new TestCoverage("T1", "PASSED",
            Map.of("io/tia/X.java", RoaringBitmap.bitmapOf(1, 2))))));
        store.save(new CoverageSnapshot("r", "c1", List.of(new TestCoverage("T1", "FAILED",
            Map.of("io/tia/X.java", RoaringBitmap.bitmapOf(2, 3))))));

        CoverageSnapshot loaded = store.load("c1");
        assertEquals(1, loaded.tests().size());
        TestCoverage t1 = loaded.tests().get(0);
        assertEquals(RoaringBitmap.bitmapOf(2, 3), t1.linesFor("io/tia/X.java"), "최신 {2,3}만, {1} 없음");
        assertEquals("FAILED", t1.result(), "result도 최신 build 값");
    }
}

@Test
@DisplayName("REQ-005: 단일 build 로드 결과·카운트(1) 회귀 없음")
void singleBuildUnchanged(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("tia.db");
    try (CoverageStore store = new CoverageStore(db)) {
        store.save(new CoverageSnapshot("fixture", "c0", List.of(new TestCoverage("T", "PASSED",
            Map.of("io/tia/A.java", RoaringBitmap.bitmapOf(5))))));
        CoverageSnapshot loaded = store.load("c0");
        assertEquals(1, loaded.tests().size());
        assertEquals(RoaringBitmap.bitmapOf(5), loaded.tests().get(0).linesFor("io/tia/A.java"));
        assertEquals(1, store.distinctBuildCount("c0"));
        assertEquals(0, store.distinctBuildCount("nope"));
    }
}

@Test
@DisplayName("REQ-009: 생성자가 부모 디렉터리를 자동 생성")
void constructorCreatesParentDirs(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("nested").resolve("sub").resolve("tia.db");
    assertFalse(java.nio.file.Files.exists(db.getParent()));
    try (CoverageStore store = new CoverageStore(db)) {
        store.save(new CoverageSnapshot("r", "c", List.of(new TestCoverage("T", "PASSED",
            Map.of("io/tia/A.java", RoaringBitmap.bitmapOf(1))))));
    }
    assertTrue(java.nio.file.Files.exists(db));
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :tia-core:test --tests 'io.tia.core.store.CoverageStoreTest' --console=plain`
Expected: FAIL — `mergesDisjointBuildsForSameCommit`은 `load`가 LIMIT 1이라 1개만 봐서 실패, `distinctBuildCount`는 컴파일 에러(메서드 없음). (컴파일 에러도 red로 본다.)

- [ ] **Step 3: `CoverageStore` 구현 (생성자·load·distinctBuildCount)**

`tia-core/src/main/java/io/tia/core/store/CoverageStore.java` 를 수정한다.

import 블록에 추가:
```java
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.stream.Collectors;
```
(`java.io.IOException`는 이미 있으면 중복 추가하지 말 것.)

생성자를 교체:
```java
public CoverageStore(Path dbFile) {
    try {
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString());
        initSchema();
    } catch (SQLException | IOException e) { throw new RuntimeException(e); }
}
```

`load` 메서드 전체를 교체:
```java
/** 해당 commit의 모든 build를 test_id별 최신-build-wins로 병합한 스냅샷. */
public CoverageSnapshot load(String commitSha) {
    try {
        // 1단계: builds 열거(오름차순). repo는 마지막 행 = 최대 build_id의 값.
        List<Long> buildIds = new ArrayList<>();
        String repo = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT build_id, repo FROM builds WHERE commit_sha=? ORDER BY build_id ASC")) {
            ps.setString(1, commitSha);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { buildIds.add(rs.getLong(1)); repo = rs.getString(2); }
            }
        }
        if (buildIds.isEmpty()) return new CoverageSnapshot(null, commitSha, List.of());

        // 2단계: coverage를 build_id 오름차순으로 읽어 test_id별 최신 build로 병합.
        // buildIds는 자체 DB의 AUTOINCREMENT long이라 IN 절 인라인이 안전.
        String inClause = buildIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<String, Map<String, RoaringBitmap>> byTest = new LinkedHashMap<>();
        Map<String, String> resultByTest = new LinkedHashMap<>();
        Map<String, Long> winningBuild = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT build_id, test_id, result, file, line_bitmap FROM coverage " +
                "WHERE build_id IN (" + inClause + ") ORDER BY build_id ASC")) {
            while (rs.next()) {
                long bid = rs.getLong(1);
                String tid = rs.getString(2);
                long w = winningBuild.getOrDefault(tid, -1L);   // 첫 만남이면 -1L → 항상 reset
                if (bid > w) {                                   // 더 높은 build → 기존 엔트리 리셋
                    winningBuild.put(tid, bid);
                    byTest.put(tid, new LinkedHashMap<>());
                    resultByTest.put(tid, rs.getString(3));
                }
                byTest.get(tid).put(rs.getString(4), fromBytes(rs.getBytes(5)));   // 같은 build면 누적
            }
        }
        List<TestCoverage> tests = new ArrayList<>();
        for (Map.Entry<String, Map<String, RoaringBitmap>> e : byTest.entrySet())
            tests.add(new TestCoverage(e.getKey(), resultByTest.get(e.getKey()), e.getValue()));
        return new CoverageSnapshot(repo, commitSha, tests);
    } catch (SQLException e) { throw new RuntimeException(e); }
}

/** 해당 commit의 distinct build 수(없으면 0). 상태 없는 쿼리 — thread-safe. */
public int distinctBuildCount(String commitSha) {
    try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COUNT(DISTINCT build_id) FROM builds WHERE commit_sha=?")) {
        ps.setString(1, commitSha);
        try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
    } catch (SQLException e) { throw new RuntimeException(e); }
}
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :tia-core:test --tests 'io.tia.core.store.CoverageStoreTest' --console=plain`
Expected: PASS (기존 `savesAndLoadsSnapshotByCommit` 포함 5개 green).

- [ ] **Step 5: 매트릭스 갱신 + 커밋**

요구사항명세 `docs/superpowers/requirements/2026-06-20-index-merge-db-default-requirements.md` 매트릭스에서 REQ-002·REQ-003·REQ-005·REQ-009(core 부분)·REQ-001(core 부분)의 Status를 🟢로(REQ-001/REQ-005는 E2E 부분이 남으면 🟡 유지하고 메모) 갱신.

```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home
git add tia-core/src/main/java/io/tia/core/store/CoverageStore.java \
        tia-core/src/test/java/io/tia/core/store/CoverageStoreTest.java \
        docs/superpowers/requirements/2026-06-20-index-merge-db-default-requirements.md
git -c commit.gpgsign=false commit -m "feat(core): commit의 모든 build를 test_id별 최신-build-wins 병합 [REQ-001/002/003/005/009]" \
  -m "load 2단계(builds 열거→coverage 병합), distinctBuildCount(상태없음), 생성자 부모 디렉터리 생성." \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01LXH8qJRxzMv4Jhewi4tyks"
```

---

### Task 2: `ImpactCommand` 멀티모듈 병합 E2E + 병합 INFO

**REQ-IDs:** REQ-001(E2E), REQ-004

**Files:**
- Modify: `tia-cli/src/main/java/io/tia/cli/ImpactCommand.java`
- Test: `tia-cli/src/test/java/io/tia/cli/ImpactCommandTest.java`

**Interfaces:**
- Consumes: `CoverageStore.distinctBuildCount(String)`, `CoverageStore.load(String)` (Task 1).
- Produces: `impact`가 distinct build ≥2면 stderr에 병합 INFO를 출력. 병합된 인덱스로 선별.

- [ ] **Step 1: 실패 테스트 작성 (멀티모듈 선별 + 병합 INFO)**

`tia-cli/src/test/java/io/tia/cli/ImpactCommandTest.java` 에 import 추가:
```java
import org.junit.jupiter.api.DisplayName;
```
클래스 본문에 추가:

```java
@Test
@DisplayName("REQ-001/004: 같은 commit 2 build(멀티모듈) 선별 + 병합 INFO(stderr)")
void mergesMultiModuleBuildsAndPrintsInfo(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("tia.db");
    try (CoverageStore store = new CoverageStore(db)) {     // 모듈 A, 모듈 B 각각 별도 build
        store.save(new CoverageSnapshot("modA", "c0", List.of(new TestCoverage("T_price", "PASSED",
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10))))));
        store.save(new CoverageSnapshot("modB", "c0", List.of(new TestCoverage("T_greet", "PASSED",
            Map.of("io/tia/fixture/GreetingService.java", RoaringBitmap.bitmapOf(6, 7))))));
    }
    Path diff = dir.resolve("d.diff");                      // 두 파일 모두 변경 → 둘 다 선별돼야 함
    Files.writeString(diff, """
        diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
        --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
        +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
        @@ -8,1 +8,1 @@
        -    return key.length() * 100;
        +    return key.length() * 200;
        diff --git a/fixture-app/src/main/java/io/tia/fixture/GreetingService.java b/fixture-app/src/main/java/io/tia/fixture/GreetingService.java
        --- a/fixture-app/src/main/java/io/tia/fixture/GreetingService.java
        +++ b/fixture-app/src/main/java/io/tia/fixture/GreetingService.java
        @@ -6,1 +6,1 @@
        -    return "hi " + name;
        +    return "hello " + name;
        """);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream prevOut = System.out, prevErr = System.err;
    System.setOut(new PrintStream(out)); System.setErr(new PrintStream(err));
    int code = new CommandLine(new TiaCommand()).execute(
        "impact", "--db", db.toString(), "--commit", "c0", "--diff-file", diff.toString());
    System.setOut(prevOut); System.setErr(prevErr);

    assertEquals(0, code);
    assertTrue(out.toString().contains("T_price"), out.toString());   // 모듈 A 테스트
    assertTrue(out.toString().contains("T_greet"), out.toString());   // 모듈 B 테스트 (LIMIT 1이면 누락 → red)
    assertTrue(err.toString().contains("build 2개"), "병합 INFO: " + err.toString());
}

@Test
@DisplayName("REQ-004: 단일 build면 병합 INFO 없음")
void singleBuildNoMergeInfo(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("tia.db");
    try (CoverageStore store = new CoverageStore(db)) {
        store.save(new CoverageSnapshot("fixture", "c0", List.of(new TestCoverage("T_price", "PASSED",
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10))))));
    }
    Path diff = dir.resolve("d.diff");
    Files.writeString(diff, """
        diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
        --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
        +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
        @@ -8,1 +8,1 @@
        -    return key.length() * 100;
        +    return key.length() * 200;
        """);
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream prevErr = System.err; System.setErr(new PrintStream(err));
    int code = new CommandLine(new TiaCommand()).execute(
        "impact", "--db", db.toString(), "--commit", "c0", "--diff-file", diff.toString());
    System.setErr(prevErr);
    assertEquals(0, code);
    assertFalse(err.toString().contains("병합"), err.toString());
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :tia-cli:test --tests 'io.tia.cli.ImpactCommandTest' --console=plain`
Expected: `mergesMultiModuleBuildsAndPrintsInfo` FAIL — 병합 INFO 미출력(아직 미구현). (load 병합은 Task 1로 이미 되므로 선별은 통과할 수 있으나 INFO 단언에서 실패.)

- [ ] **Step 3: `ImpactCommand`에 병합 INFO 추가**

`tia-cli/src/main/java/io/tia/cli/ImpactCommand.java` 의 `call()` 에서 store 로드 블록을 교체:
```java
        CoverageSnapshot snap;
        int buildCount;
        try (CoverageStore store = new CoverageStore(db)) {
            buildCount = store.distinctBuildCount(commit);   // try 블록 안에서 캡처(store 스코프 제한)
            snap = store.load(commit);
        }
        if (buildCount > 1) {
            System.err.println("INFO: commit " + commit + "에 build " + buildCount
                + "개 → test_id별 최신 build 병합(멀티모듈/재인덱싱).");
        }
```
(기존 `try (CoverageStore store = new CoverageStore(db)) { snap = store.load(commit); }` 한 줄을 위 블록으로 대체. 이후 no-baseline 처리·diff 로직은 그대로.)

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :tia-cli:test --tests 'io.tia.cli.ImpactCommandTest' --console=plain`
Expected: PASS (기존 테스트 포함 전부 green).

- [ ] **Step 5: 매트릭스 갱신 + 커밋**

매트릭스에서 REQ-001·REQ-004 Status를 🟢로.

```bash
git add tia-cli/src/main/java/io/tia/cli/ImpactCommand.java \
        tia-cli/src/test/java/io/tia/cli/ImpactCommandTest.java \
        docs/superpowers/requirements/2026-06-20-index-merge-db-default-requirements.md
git -c commit.gpgsign=false commit -m "feat(cli): impact가 멀티모듈 병합 인덱스 선별 + 병합 INFO [REQ-001/004]" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01LXH8qJRxzMv4Jhewi4tyks"
```

---

### Task 3: `DbPaths` 기본 경로 해석 헬퍼 + 단위 테스트

**REQ-IDs:** REQ-006(경로 빌드), REQ-007

**Files:**
- Create: `tia-cli/src/main/java/io/tia/cli/DbPaths.java`
- Test: `tia-cli/src/test/java/io/tia/cli/DbPathsTest.java`

**Interfaces:**
- Produces:
  - `static Path DbPaths.resolveDefault()` — 운영용(env=System.getenv, git-common-dir 실측).
  - `static Path DbPaths.resolveDefault(Function<String,String> env)` — env 주입.
  - `static Path DbPaths.resolveDefault(Function<String,String> env, Path gitCommonDirOrNull)` — git-common-dir까지 주입(테스트용 seam). non-null이면 `<common>/tia/tia.db`, null이면 XDG 폴백.

- [ ] **Step 1: 실패 테스트 작성**

`tia-cli/src/test/java/io/tia/cli/DbPathsTest.java` 생성:
```java
package io.tia.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbPathsTest {

    @Test
    @DisplayName("REQ-006: git-common-dir이 있으면 <common>/tia/tia.db")
    void gitCommonDirPath(@TempDir Path common) {
        Path p = DbPaths.resolveDefault(k -> null, common);
        assertEquals(common.resolve("tia").resolve("tia.db"), p);
    }

    @Test
    @DisplayName("REQ-007: git-common-dir 없고 XDG_CACHE_HOME 설정 시 <xdg>/tia/tia.db")
    void xdgFallbackWhenEnvSet(@TempDir Path xdg) {
        Map<String, String> env = Map.of("XDG_CACHE_HOME", xdg.toString());
        Path p = DbPaths.resolveDefault(env::get, null);
        assertEquals(xdg.resolve("tia").resolve("tia.db"), p);
    }

    @Test
    @DisplayName("REQ-007: git-common-dir 없고 XDG 미설정 시 ~/.cache/tia/tia.db")
    void homeCacheFallbackWhenNoXdg() {
        Path p = DbPaths.resolveDefault(k -> null, null);
        Path expected = Path.of(System.getProperty("user.home"), ".cache", "tia", "tia.db");
        assertEquals(expected, p);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :tia-cli:test --tests 'io.tia.cli.DbPathsTest' --console=plain`
Expected: FAIL — 컴파일 에러(`DbPaths` 없음).

- [ ] **Step 3: `DbPaths` 구현**

`tia-cli/src/main/java/io/tia/cli/DbPaths.java` 생성:
```java
package io.tia.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * `--db` 미지정 시 기본 인덱스 DB 경로를 해석한다.
 * git-common-dir(모든 worktree 공유) 우선, 비 git이면 XDG_CACHE_HOME(없으면 ~/.cache) 폴백.
 * 디렉터리 생성은 하지 않는다 — 부모 생성은 CoverageStore 생성자가 담당.
 */
final class DbPaths {
    private DbPaths() {}

    static Path resolveDefault() {
        return resolveDefault(System::getenv);
    }

    static Path resolveDefault(Function<String, String> env) {
        return resolveDefault(env, gitCommonDir());
    }

    /** 테스트 seam: git-common-dir과 env를 모두 주입. */
    static Path resolveDefault(Function<String, String> env, Path gitCommonDirOrNull) {
        if (gitCommonDirOrNull != null) {
            return gitCommonDirOrNull.resolve("tia").resolve("tia.db");
        }
        String xdg = env.apply("XDG_CACHE_HOME");
        Path base = (xdg != null && !xdg.isBlank())
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("tia").resolve("tia.db");
    }

    /** `git rev-parse --git-common-dir` → 절대경로. 비 git/실패면 null. stderr는 폐기. */
    private static Path gitCommonDir() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--git-common-dir")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int code = p.waitFor();
            if (code != 0 || out.isEmpty()) return null;
            return Path.of(out).toAbsolutePath().normalize();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :tia-cli:test --tests 'io.tia.cli.DbPathsTest' --console=plain`
Expected: PASS (3개 green).

- [ ] **Step 5: 매트릭스 갱신 + 커밋**

매트릭스에서 REQ-007 🟢, REQ-006은 경로빌드 단위 green이나 커맨드 E2E(Task 4)가 남으므로 🟡 유지.

```bash
git add tia-cli/src/main/java/io/tia/cli/DbPaths.java \
        tia-cli/src/test/java/io/tia/cli/DbPathsTest.java \
        docs/superpowers/requirements/2026-06-20-index-merge-db-default-requirements.md
git -c commit.gpgsign=false commit -m "feat(cli): DbPaths — git-common-dir 우선 / XDG 폴백 기본 DB 경로 [REQ-006/007]" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01LXH8qJRxzMv4Jhewi4tyks"
```

---

### Task 4: `--db` optional + 기본값 적용 + INFO (Index/Impact)

**REQ-IDs:** REQ-006(E2E), REQ-008, REQ-009(명시 경로 보존)

**Files:**
- Modify: `tia-cli/src/main/java/io/tia/cli/IndexCommand.java`
- Modify: `tia-cli/src/main/java/io/tia/cli/ImpactCommand.java`
- Test: `tia-cli/src/test/java/io/tia/cli/IndexCommandTest.java`
- Test: `tia-cli/src/test/java/io/tia/cli/ImpactCommandTest.java`

**Interfaces:**
- Consumes: `DbPaths.resolveDefault()` (Task 3).
- Produces: 두 커맨드가 `--db` 생략 시 기본 경로 사용 + stderr INFO; 명시 시 그 경로·무출력.

- [ ] **Step 1: 실패 테스트 작성**

`IndexCommandTest.java` 에 import 추가:
```java
import org.junit.jupiter.api.DisplayName;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.jupiter.api.Assertions.assertFalse;
```
클래스 본문에 추가:
```java
@Test
@DisplayName("REQ-006/008: --db 생략 시 기본 경로 생성 + 기본 DB INFO(stderr)")
void defaultDbWhenOmitted(@TempDir Path dir) throws Exception {
    Path report = dir.resolve("r.json");
    Files.writeString(report, """
        {"tests":[{"uniformPath":"io/tia/fixture/ApiSmokeTest/testPrice","result":"PASSED",
          "paths":[{"path":"io/tia/fixture","files":[{"fileName":"PricingService.java","coveredLines":"8-10"}]}]}]}""");
    Path expected = DbPaths.resolveDefault();        // 현재 JVM cwd 기준 실제 기본 경로
    boolean preexisting = Files.exists(expected);    // 사용자 실제 인덱스 보호 가드
    try {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream prevErr = System.err; System.setErr(new PrintStream(err));
        int code = new CommandLine(new TiaCommand()).execute(
            "index", "--report", report.toString(), "--repo", "fixture", "--commit", "cDEF");  // --db 없음
        System.setErr(prevErr);
        assertEquals(0, code);
        assertTrue(Files.exists(expected), "기본 DB 생성: " + expected);
        assertTrue(err.toString().contains("기본 인덱스 DB"), err.toString());
    } finally {
        // 테스트가 새로 만든 경우에만 정리(기존 인덱스가 있었다면 건드리지 않음).
        if (!preexisting) Files.deleteIfExists(expected);
    }
}

@Test
@DisplayName("REQ-009: --db 명시 시 부모 디렉터리 자동 생성 + INFO 없음")
void explicitDbCreatesParentNoInfo(@TempDir Path dir) throws Exception {
    Path report = dir.resolve("r.json");
    Files.writeString(report, """
        {"tests":[{"uniformPath":"io/tia/fixture/ApiSmokeTest/testPrice","result":"PASSED",
          "paths":[{"path":"io/tia/fixture","files":[{"fileName":"PricingService.java","coveredLines":"8-10"}]}]}]}""");
    Path db = dir.resolve("nested").resolve("tia.db");   // 부모 부재
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream prevErr = System.err; System.setErr(new PrintStream(err));
    int code = new CommandLine(new TiaCommand()).execute(
        "index", "--report", report.toString(), "--repo", "fixture", "--commit", "c0", "--db", db.toString());
    System.setErr(prevErr);
    assertEquals(0, code);
    assertTrue(Files.exists(db));
    assertFalse(err.toString().contains("기본 인덱스 DB"), err.toString());
}
```

`ImpactCommandTest.java` 에 추가(임포트는 이미 있음):
```java
@Test
@DisplayName("REQ-009: impact --db 명시 시 INFO 없음(기존 동작 보존)")
void explicitDbNoInfo(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("tia.db");
    try (CoverageStore store = new CoverageStore(db)) {
        store.save(new CoverageSnapshot("fixture", "c0", List.of(new TestCoverage("T", "PASSED",
            Map.of("io/tia/fixture/A.java", RoaringBitmap.bitmapOf(1))))));
    }
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream prevErr = System.err; System.setErr(new PrintStream(err));
    int code = new CommandLine(new TiaCommand()).execute(
        "impact", "--db", db.toString(), "--commit", "OTHER");   // no-baseline → exit 0
    System.setErr(prevErr);
    assertEquals(0, code);
    assertFalse(err.toString().contains("기본 인덱스 DB"), err.toString());
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :tia-cli:test --tests 'io.tia.cli.IndexCommandTest' --tests 'io.tia.cli.ImpactCommandTest' --console=plain`
Expected: FAIL — `index` 가 `--db` 필수라 `defaultDbWhenOmitted`에서 picocli MissingParameterException(비0 종료). `explicitDb...`는 INFO 단언 외엔 통과할 수 있음.

- [ ] **Step 3: 두 커맨드 `--db` optional + 기본값/INFO 구현**

`IndexCommand.java`:
- `@Option(names = "--db", required = true) Path db;` → `@Option(names = "--db") Path db;`
- `call()` 본문을 교체:
```java
    @Override public Integer call() throws Exception {
        Path effectiveDb = (db != null) ? db : DbPaths.resolveDefault();
        if (db == null) System.err.println("INFO: 기본 인덱스 DB: " + effectiveDb);
        try (InputStream in = Files.newInputStream(report)) {
            List<TestCoverage> tests = new TestwiseReportParser().parse(in);
            try (CoverageStore store = new CoverageStore(effectiveDb)) {
                store.save(new CoverageSnapshot(repo, commit, tests));
            }
            System.out.println("indexed " + tests.size() + " tests @ " + commit);
            return 0;
        }
    }
```

`ImpactCommand.java`:
- `@Option(names = "--db", required = true) Path db;` → `@Option(names = "--db") Path db;`
- `call()` 진입부(현재 `CoverageSnapshot snap; int buildCount; try (...)` 블록 바로 위)에 추가:
```java
        Path effectiveDb = (db != null) ? db : DbPaths.resolveDefault();
        if (db == null) System.err.println("INFO: 기본 인덱스 DB: " + effectiveDb);
```
- 이어지는 store 블록의 `new CoverageStore(db)` 를 `new CoverageStore(effectiveDb)` 로 변경.

- [ ] **Step 4: 통과 확인 (대상 + 전체 cli/core 회귀)**

Run: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :tia-cli:test :tia-core:test --console=plain`
Expected: PASS (전체 green).

- [ ] **Step 5: 매트릭스 갱신 + 커밋**

매트릭스에서 REQ-006·REQ-008·REQ-009 🟢. (이로써 9개 전부 🟢인지 확인.)

```bash
git add tia-cli/src/main/java/io/tia/cli/IndexCommand.java \
        tia-cli/src/main/java/io/tia/cli/ImpactCommand.java \
        tia-cli/src/test/java/io/tia/cli/IndexCommandTest.java \
        tia-cli/src/test/java/io/tia/cli/ImpactCommandTest.java \
        docs/superpowers/requirements/2026-06-20-index-merge-db-default-requirements.md
git -c commit.gpgsign=false commit -m "feat(cli): --db optional + git-common-dir/XDG 기본값 + 기본 DB INFO [REQ-006/008/009]" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01LXH8qJRxzMv4Jhewi4tyks"
```

---

### Task 5: 문서 갱신 (GETTING-STARTED, README)

**REQ-IDs:** (문서 동기화 — REQ 전반의 사용자 대면 반영)

**Files:**
- Modify: `GETTING-STARTED.md`
- Modify: `README.md`

- [ ] **Step 1: GETTING-STARTED 갱신**

`GETTING-STARTED.md` 의 "(`--db` 기본값 자동화는 향후 CLI 개선 예정 — 현재는 `--db`로 명시적으로 경로를 전달한다.)" 줄을 다음으로 교체:
```markdown
> **`--db` 기본값.** `--db`를 생략하면 git 레포에선 `<git-common-dir>/tia/tia.db`(예: `.git/tia/tia.db`,
> 모든 worktree 공유), 비 git 환경에선 `${XDG_CACHE_HOME:-~/.cache}/tia/tia.db`를 자동 사용한다.
> 기본 경로를 쓸 때 각 커맨드는 stderr에 `INFO: 기본 인덱스 DB: <path>`를 한 줄 안내한다.
> CI/컨테이너처럼 cwd가 불확실하거나 git이 없는 환경에선 `--db`로 명시 전달을 권장한다.
```
그리고 §2의 인덱싱 예시 아래에 `--db` 생략형 한 줄 추가:
```bash
# (--db 생략 시 git-common-dir 기본 경로 자동 사용)
"$CLI" index --report testwise.json --repo my-service --commit "$(git rev-parse HEAD)"
```

- [ ] **Step 2: README 갱신**

`README.md` "현재 범위 & 한계" 섹션 또는 멀티모듈 caveat 근처에, 기존 package-collision caveat와 **별개 bullet**로 추가:
```markdown
- **멀티모듈 인덱스 병합.** 같은 commit에 모듈별로 따로 인덱싱하면 build가 여러 개 쌓이는데, `impact`는
  이제 그 commit의 **모든 build를 test_id별 최신-build-wins로 병합**해 선별한다(과거엔 마지막 build만
  보여 다른 모듈 테스트가 누락됐다). 같은 commit을 재인덱싱하면 같은 test_id는 최신 build가 대체한다.
  (단, 같은 commit 재인덱싱 시 삭제된 test의 stale 커버리지는 남을 수 있다 — 필요 시 해당 db를 지우고
  clean 재인덱싱.)
```
(정확한 삽입 위치: `README.md` 의 멀티모듈/한계 관련 문단 바로 아래. package-collision 문장은 그대로 둔다.)

- [ ] **Step 3: 문서 빌드 영향 없음 확인 + 커밋**

문서만 변경이므로 테스트 불필요. 변경이 의도대로인지 육안 확인 후 커밋:
```bash
git add GETTING-STARTED.md README.md
git -c commit.gpgsign=false commit -m "docs: --db 기본값 + 멀티모듈 build 병합 반영 [REQ-006/008, P1-3]" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01LXH8qJRxzMv4Jhewi4tyks"
```

---

## 완료 기준 (PR 전)

- `JAVA_HOME=…corretto-17… ./gradlew :tia-core:test :tia-cli:test` 전체 green.
- 요구사항명세 추적 매트릭스 **9/9 🟢 (100%)**, 각 green REQ가 실제 통과 테스트(`@DisplayName`의 REQ-ID)와 대응.
- GETTING-STARTED·README 갱신 포함.
- PR 전 게이트(CLAUDE.md): spec-compliance 리뷰 → code-quality 리뷰(`pr-review-toolkit:code-reviewer`) → 회귀 green → 문서 동기화.

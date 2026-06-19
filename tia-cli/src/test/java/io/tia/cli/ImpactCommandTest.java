package io.tia.cli;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.TestCoverage;
import io.tia.core.store.CoverageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactCommandTest {
    @Test
    void printsOnlyImpactedTests(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "c0", List.of(
                new TestCoverage("T_price", "PASSED",
                    Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10))),
                new TestCoverage("T_greet", "PASSED",
                    Map.of("io/tia/fixture/GreetingService.java", RoaringBitmap.bitmapOf(6, 7))))));
        }
        Path diff = dir.resolve("d.diff");
        Files.writeString(diff, """
            diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
            --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
            +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
            @@ -8,1 +8,1 @@
            -    return key.length() * 100;
            +    return key.length() * 200;
            """);   // 레포 상대 경로 → PathNormalizer가 커버리지 키(io/tia/fixture/...)와 교차되게 정규화

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out; System.setOut(new PrintStream(out));
        int code = new CommandLine(new TiaCommand()).execute(
            "impact", "--db", db.toString(), "--commit", "c0", "--diff-file", diff.toString());
        System.setOut(prev);

        assertEquals(0, code);
        String printed = out.toString();
        assertTrue(printed.contains("T_price"), printed);
        assertFalse(printed.contains("T_greet"), printed);
        assertTrue(printed.contains("DETERMINISTIC"), printed);
    }

    @Test
    void noBaselineEmitsRunAllMarkerAndSucceeds(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "c0", List.of(
                new TestCoverage("T", "PASSED",
                    Map.of("io/tia/fixture/A.java", RoaringBitmap.bitmapOf(1))))));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out; System.setOut(new PrintStream(out));
        // ask for a commit NOT in the index → no baseline → run-all signal, exit 0
        int code = new CommandLine(new TiaCommand()).execute(
            "impact", "--db", db.toString(), "--commit", "OTHER_COMMIT");
        System.setOut(prev);

        assertEquals(0, code);
        assertTrue(out.toString().contains(ImpactCommand.NO_BASELINE_MARKER), out.toString());
    }

    @Test
    void strictFailsWhenNoBaseline(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "c0", List.of(
                new TestCoverage("T", "PASSED",
                    Map.of("io/tia/fixture/A.java", RoaringBitmap.bitmapOf(1))))));
        }
        int code = new CommandLine(new TiaCommand()).execute(
            "impact", "--db", db.toString(), "--commit", "OTHER_COMMIT", "--strict");
        assertEquals(3, code);
    }

    @Test
    void runGitDiffProducesUnifiedDiffFromRepo(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "tester");
        Files.writeString(repo.resolve("A.java"), "class A { int x = 1; }\n");
        git(repo, "add", "A.java");
        git(repo, "commit", "-q", "-m", "init");
        Files.writeString(repo.resolve("A.java"), "class A { int x = 2; }\n");  // unstaged change

        String diff = ImpactCommand.runGitDiff("HEAD", repo.toFile());

        assertTrue(diff.contains("A.java"), diff);
        assertTrue(diff.contains("+class A { int x = 2; }"), diff);
        assertTrue(diff.contains("@@"), "unified diff hunk header present: " + diff);
    }

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

    @Test
    @DisplayName("REQ-008: impact --db 생략 시 기본 DB INFO(stderr)")
    void defaultDbWhenOmitted() throws Exception {
        Path expected = DbPaths.resolveDefault();
        boolean preexisting = Files.exists(expected);
        // 충돌 없는 고유 커밋 식별자 — impact는 이 commit을 조회만 하므로 coverage 행은 삽입되지 않음.
        // 단, impact가 DB 파일을 생성(schema init)할 수 있으므로 동일한 cleanup 패턴을 유지.
        final String TEST_COMMIT = "REQ008_UNIQUE_NB";
        try {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream prevErr = System.err; System.setErr(new PrintStream(err));
            // --db 없음, commit "REQ008_UNIQUE_NB" → no-baseline → exit 0
            int code = new CommandLine(new TiaCommand()).execute(
                "impact", "--commit", TEST_COMMIT);
            System.setErr(prevErr);
            assertEquals(0, code);
            assertTrue(err.toString().contains("기본 인덱스 DB"), err.toString());
        } finally {
            if (preexisting) {
                // 기존 DB가 있었다면: 이 테스트가 삽입한 행만 정확히 삭제 → 실제 인덱스 오염 방지
                // (impact는 READ-only이므로 실제로는 행이 없지만, 일관성을 위해 동일 패턴 유지)
                deleteTestCommitRows(expected, TEST_COMMIT);
            } else {
                // 이 테스트가 새로 만든 파일이라면: 파일 전체 삭제
                Files.deleteIfExists(expected);
            }
        }
    }

    /**
     * 기본 DB에서 특정 commit_sha의 coverage·builds 행만 삭제.
     * preexisting DB가 있을 때 테스트 오염 방지용.
     */
    private static void deleteTestCommitRows(Path dbPath, String commitSha) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement delCov = conn.prepareStatement(
                 "DELETE FROM coverage WHERE build_id IN (SELECT build_id FROM builds WHERE commit_sha=?)");
             PreparedStatement delBuild = conn.prepareStatement(
                 "DELETE FROM builds WHERE commit_sha=?")) {
            delCov.setString(1, commitSha);
            delCov.executeUpdate();
            delBuild.setString(1, commitSha);
            delBuild.executeUpdate();
        }
    }

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

    private static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}

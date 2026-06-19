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

package io.tia.e2e.filter;

import io.tia.cli.TiaCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP1 아우터 루프 — impact의 code/test 필터가 DiffSummary 세 필드 전면에 적용되고(REQ-007),
 * 전부-제외 diff는 0건+WARN+exit0(REQ-008), 제외 테스트는 모든 Confidence에서 배제되며(REQ-009),
 * code 글로브는 정규화 경로(REQ-004)·test 글로브는 `#`→`/` 정규화(REQ-005)로 매칭된다.
 * Task 4 시점에는 --config, --exclude-test, --include-code 등이 배선되지 않아 전부 red가 정상.
 */
@Execution(ExecutionMode.SAME_THREAD)
class FilterE2ETest {

    static final String PRICE = "io/tia/fixture/ApiSmokeTest/testPrice";
    static final String GREET = "io/tia/fixture/ApiSmokeTest/testGreeting";

    @TempDir Path work;
    Path db;

    @BeforeEach
    void index() throws Exception {
        db = work.resolve("tia.db");
        Path report = work.resolve("testwise.json");
        copyResource("/spec-testwise.json", report);
        assertEquals(0, run("index", "--report", report.toString(),
                "--repo", "fixture", "--commit", "C0", "--db", db.toString()).code());
    }

    // 준비: index --report spec-testwise.json --repo fixture --commit C0 --db work/tia.db
    // tia.yml은 work/ 아래 작성, --config로 명시 주입(경로 독립).

    @Test
    @DisplayName("REQ-009: 제외 테스트는 DETERMINISTIC이어도 출력되지 않는다")
    void excludedTestNeverOutput() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  test:
                    exclude: ["**/testPrice"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiff("PricingService.java", 8).toString(),
                "--config", yml.toString());
        assertEquals(0, r.code());
        assertFalse(r.out().contains("testPrice"), r.out());   // DETERMINISTIC 히트였을 테스트
    }

    @Test
    @DisplayName("REQ-008: 전부 제외된 diff → 0건 + stderr WARN + exit 0")
    void allExcludedDiffZeroSelection() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    exclude: ["com/acme/gen/**"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiffFor("src/main/java/com/acme/gen/G.java", 3).toString(),
                "--config", yml.toString());
        assertEquals(0, r.code());
        assertTrue(r.out().contains("영향 테스트 0개"), r.out());
        assertTrue(r.err().contains("# WARN: excluded change ignored: com/acme/gen/G.java"), r.err());
    }

    @Test
    @DisplayName("REQ-007: 좁은 include여도 build.gradle 변경은 CONSERVATIVE 발동")
    void unmappableBypassesInclude() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    include: ["com/acme/**"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", unmappableDiff("build.gradle").toString(),
                "--config", yml.toString());
        assertTrue(r.out().contains("보수적 전체 선택"), r.out());
    }

    @Test
    @DisplayName("REQ-004: code 글로브는 정규화된(package-relative) 경로에 매칭된다")
    void codeGlobMatchesCanonicalPath() throws Exception {
        // diff 헤더는 src/main/java/... 이지만 글로브는 정규화된 io/tia/fixture/... 에 매칭돼야 함.
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    exclude: ["io/tia/fixture/PricingService.java"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiff("PricingService.java", 8).toString(),
                "--config", yml.toString());
        assertEquals(0, r.code(), r.err());
        assertFalse(r.out().contains(PRICE), r.out());
    }

    @Test
    @DisplayName("REQ-005: test 글로브는 Class#method 형태 testId도 `#`→`/` 정규화 후 매칭한다")
    void hashTestIdNormalization() throws Exception {
        Path hashDb = work.resolve("hash.db");
        Path report = work.resolve("hash-testwise.json");
        copyResource("/hash-testwise.json", report);
        assertEquals(0, run("index", "--report", report.toString(), "--repo", "fixture", "--commit", "H0",
                "--db", hashDb.toString()).code());
        Path diff = modifyDiffFor("auth/AuthApi.java", 5);

        Exec baseline = run("impact", "--db", hashDb.toString(), "--commit", "H0", "--diff-file", diff.toString());
        assertTrue(baseline.out().contains("AuthApiBlackBoxIT#loginReturns400"), baseline.out());

        Path yml = writeYml("""
                version: 1
                filters:
                  test:
                    exclude: ["AuthApiBlackBoxIT/**"]
                """);
        Exec filtered = run("impact", "--db", hashDb.toString(), "--commit", "H0",
                "--diff-file", diff.toString(), "--config", yml.toString());
        assertEquals(0, filtered.code(), filtered.err());
        assertFalse(filtered.out().contains("AuthApiBlackBoxIT#loginReturns400"), filtered.out());
    }

    @Test
    @DisplayName("REQ-007: 제외 경로의 신규 파일만 있는 diff는 CONSERVATIVE를 발동하지 않는다")
    void excludedNewFileNoConservative() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    exclude: ["com/acme/gen/**"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", newFileDiffFor("com/acme/gen/Generated.java").toString(),
                "--config", yml.toString());
        assertEquals(0, r.code(), r.err());
        assertFalse(r.out().contains("보수적 전체 선택"), r.out());
    }

    @Test
    @DisplayName("REQ-007: 제외 경로 파일이 섞인 diff는 제거된 파일 수만큼 WARN이 stderr에 나온다")
    void warnPerIgnoredFile() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    exclude: ["com/acme/gen/**"]
                """);
        String d = """
                diff --git a/com/acme/gen/A.java b/com/acme/gen/A.java
                --- a/com/acme/gen/A.java
                +++ b/com/acme/gen/A.java
                @@ -1,1 +1,1 @@
                -old
                +new
                diff --git a/com/acme/gen/B.java b/com/acme/gen/B.java
                --- a/com/acme/gen/B.java
                +++ b/com/acme/gen/B.java
                @@ -1,1 +1,1 @@
                -old
                +new
                """;
        Path diff = work.resolve("multi.diff");
        Files.writeString(diff, d);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", diff.toString(), "--config", yml.toString());
        assertEquals(0, r.code(), r.err());
        long warnCount = r.err().lines().filter(l -> l.contains("WARN: excluded change ignored:")).count();
        assertEquals(2, warnCount, r.err());
    }

    @Test
    @DisplayName("REQ-009: CONSERVATIVE 전체 선택 집합에서도 제외 테스트는 빠진다")
    void excludedFromConservativeSet() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  test:
                    exclude: ["**/testGreeting"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiff("application.yml", 1).toString(),
                "--config", yml.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("보수적 전체 선택"), r.out());
        assertFalse(r.out().contains(GREET), r.out());
        assertTrue(r.out().contains(PRICE), r.out());
    }

    @Test
    @DisplayName("REQ-007: 매칭되지 않는 exclude가 있어도 unmappable 변경은 필터 없음과 동일하게 CONSERVATIVE를 발동한다")
    void nonMatchingFilterKeepsConservative() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    exclude: ["never/matches/anything/**"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiff("application.yml", 1).toString(),
                "--config", yml.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("보수적 전체 선택"), r.out());
        assertTrue(r.out().contains("CONSERVATIVE\t" + PRICE), r.out());
        assertTrue(r.out().contains("CONSERVATIVE\t" + GREET), r.out());
    }

    // ---- 공통 헬퍼 (SpecAcceptanceE2ETest 패턴 복사) ----

    /** stdout/stderr 캡처 실행 헬퍼. */
    record Exec(int code, String out, String err) {}

    static Exec run(String... args) {
        PrintStream oo = System.out, oe = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new TiaCommand()).execute(args);
            return new Exec(code, bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(oo); System.setErr(oe); }
    }

    private void copyResource(String res, Path dest) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(res)) {
            assertNotNull(in, "resource missing: " + res);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 한 줄 수정 diff(레포 상대 경로 → PathNormalizer 정규화). old-side line N. SpecAcceptanceE2ETest#modifyDiff와 동일(3-인자). */
    Path modifyDiff(String fileName, int line, String content) throws IOException {
        String path = fileName.endsWith(".java")
                ? "fixture-app/src/main/java/io/tia/fixture/" + fileName
                : "fixture-app/src/main/resources/" + fileName;
        String diff = "diff --git a/" + path + " b/" + path + "\n"
                + "--- a/" + path + "\n"
                + "+++ b/" + path + "\n"
                + "@@ -" + line + ",1 +" + line + ",1 @@\n"
                + "-" + content + "\n"
                + "+" + content + " // changed\n";
        Path p = work.resolve("d-" + fileName + "-" + line + ".diff");
        Files.writeString(p, diff);
        return p;
    }

    /** 2-인자 편의 오버로드 — content는 GitDiffParser가 검증하지 않으므로(라인 번호만 유효) 고정 placeholder 사용. */
    Path modifyDiff(String fileName, int line) throws IOException {
        return modifyDiff(fileName, line, "placeholder");
    }

    /** 임의 경로의 .java 1라인 수정 diff (old-side 라인 공간). */
    Path modifyDiffFor(String repoPath, int line) throws IOException {
        String d = """
                diff --git a/%1$s b/%1$s
                index 1111111..2222222 100644
                --- a/%1$s
                +++ b/%1$s
                @@ -%2$d,1 +%2$d,1 @@
                -old
                +new
                """.formatted(repoPath, line);
        Path f = work.resolve("change-" + repoPath.replace('/', '_') + "-" + line + ".diff");
        Files.writeString(f, d);
        return f;
    }

    /** 비-.java(unmappable) 파일 수정 diff — CONSERVATIVE 트리거용. */
    Path unmappableDiff(String repoPath) throws IOException { return modifyDiffFor(repoPath, 1); }

    /** 신규 파일 추가(addition-only) diff — 임의 경로. */
    Path newFileDiffFor(String repoPath) throws IOException {
        String d = """
                diff --git a/%1$s b/%1$s
                --- /dev/null
                +++ b/%1$s
                @@ -0,0 +1,2 @@
                +package x;
                +public class X {}
                """.formatted(repoPath);
        Path f = work.resolve("new-" + repoPath.replace('/', '_') + ".diff");
        Files.writeString(f, d);
        return f;
    }

    Path writeYml(String yaml) throws IOException {
        Path f = work.resolve("tia.yml");
        Files.writeString(f, yaml);
        return f;
    }
}

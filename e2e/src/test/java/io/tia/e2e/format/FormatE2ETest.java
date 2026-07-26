package io.tia.e2e.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP1 아우터 루프 — impact/flaky의 4-포맷(text/summary/json/markdown) 계약(REQ-013~018).
 * Task 4 시점에는 --format이 배선되지 않아 전부 red(picocli usage exit 2)가 정상.
 */
@Execution(ExecutionMode.SAME_THREAD)
class FormatE2ETest {

    static final String PRICE = "io/tia/fixture/ApiSmokeTest/testPrice";

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

    @Test
    @DisplayName("REQ-013: impact --format json은 schemaVersion/command/tests[].id,confidence 등 고정 스키마를 낸다")
    void impactJsonSchema() throws Exception {
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiffFor("fixture-app/src/main/java/io/tia/fixture/PricingService.java", 8).toString(),
                "--format", "json");
        assertEquals(0, r.code(), r.err());
        JsonNode root = new ObjectMapper().readTree(r.out());
        assertEquals(1, root.path("schemaVersion").asInt(), r.out());
        assertEquals("impact", root.path("command").asText(), r.out());
        assertTrue(root.path("tests").isArray() && root.path("tests").size() > 0, r.out());
        JsonNode t0 = root.path("tests").get(0);
        assertTrue(t0.has("id"), r.out());
        assertTrue(t0.has("confidence"), r.out());
        assertEquals(PRICE, t0.path("id").asText(), r.out());
    }

    @Test
    @DisplayName("REQ-014: flaky --format json은 ratio/totalTests/flakyTests를 내고 commit 키가 없다")
    void flakyJsonSchema() throws Exception {
        Path r1 = work.resolve("run1.json"); Files.writeString(r1, "{\"results\":{\"T_ok\":true,\"T_flaky\":true}}");
        Path r2 = work.resolve("run2.json"); Files.writeString(r2, "{\"results\":{\"T_ok\":true,\"T_flaky\":false}}");
        Exec r = run("flaky", "--runs", r1 + "," + r2, "--format", "json");
        assertEquals(0, r.code(), r.err());
        JsonNode root = new ObjectMapper().readTree(r.out());
        assertTrue(root.has("ratio"), r.out());
        assertTrue(root.has("totalTests"), r.out());
        assertTrue(root.has("flakyTests"), r.out());
        assertFalse(root.has("commit"), r.out());
    }

    @Test
    @DisplayName("REQ-015: --format summary는 blind spot/무시 수/다음 행동을 사람용 텍스트로 낸다(파이프 시 ANSI 없음)")
    void impactSummaryPipedNoAnsi() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    exclude: ["com/acme/gen/**"]
                """);
        Path diff = mixedDiff();
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", diff.toString(), "--config", yml.toString(), "--format", "summary");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().toLowerCase().contains("blind spot"), r.out());
        assertTrue(r.out().contains("무시"), r.out());
        assertTrue(r.out().contains("다음"), r.out());
        assertFalse(r.out().contains("["), "파이프 실행 시 ANSI 이스케이프가 없어야 함: " + r.out());
    }

    @Test
    @DisplayName("REQ-016: --format markdown은 요약 테이블(| 행)과 <details> 상세를 낸다")
    void impactMarkdownTableAndDetails() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    exclude: ["com/acme/gen/**"]
                """);
        Path diff = mixedDiff();
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", diff.toString(), "--config", yml.toString(), "--format", "markdown");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("|"), r.out());
        assertTrue(r.out().contains("<details>"), r.out());
        assertTrue(r.out().contains("무시"), r.out());
    }

    @Test
    @DisplayName("REQ-017: 신규 WARN은 stderr에만, 포맷 데이터는 stdout에만 — json은 stdout만으로 파싱되고 text의 기존 stdout 라인은 불변")
    void stderrWarnStdoutData() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    exclude: ["com/acme/gen/**"]
                """);
        Path diff = modifyDiffFor("com/acme/gen/G.java", 1);

        Exec json = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", diff.toString(), "--config", yml.toString(), "--format", "json");
        assertEquals(0, json.code(), json.err());
        assertDoesNotThrow(() -> new ObjectMapper().readTree(json.out()), "stdout만으로 JSON 파싱 실패: " + json.out());

        Exec text = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", diff.toString(), "--config", yml.toString());
        assertEquals(0, text.code(), text.err());
        assertTrue(text.err().contains("WARN: excluded change ignored:"), text.err());
        assertFalse(text.out().contains("WARN: excluded change ignored:"), text.out());
        assertTrue(text.out().contains("영향 테스트 0개"), text.out());   // 기존 stdout 데이터 라인은 그대로(REQ-012 정합)
    }

    @Test
    @DisplayName("REQ-018: impact — text/summary/json/markdown 네 포맷의 exit code가 같다")
    void exitCodeFormatIndependent_impact() throws Exception {
        Path diffFile = modifyDiffFor("fixture-app/src/main/java/io/tia/fixture/PricingService.java", 8);
        int textCode = run("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diffFile.toString()).code();
        int summaryCode = run("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diffFile.toString(), "--format", "summary").code();
        int jsonCode = run("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diffFile.toString(), "--format", "json").code();
        int mdCode = run("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diffFile.toString(), "--format", "markdown").code();
        assertEquals(textCode, summaryCode, "summary exit code 불일치");
        assertEquals(textCode, jsonCode, "json exit code 불일치");
        assertEquals(textCode, mdCode, "markdown exit code 불일치");
    }

    @Test
    @DisplayName("REQ-018: flaky — text/summary/json/markdown 네 포맷의 exit code가 같다")
    void exitCodeFormatIndependent_flaky() throws Exception {
        Path r1 = work.resolve("run1.json"); Files.writeString(r1, "{\"results\":{\"T_ok\":true,\"T_flaky\":true}}");
        Path r2 = work.resolve("run2.json"); Files.writeString(r2, "{\"results\":{\"T_ok\":true,\"T_flaky\":false}}");
        String runs = r1 + "," + r2;
        int textCode = run("flaky", "--runs", runs).code();
        int summaryCode = run("flaky", "--runs", runs, "--format", "summary").code();
        int jsonCode = run("flaky", "--runs", runs, "--format", "json").code();
        int mdCode = run("flaky", "--runs", runs, "--format", "markdown").code();
        assertEquals(textCode, summaryCode, "summary exit code 불일치");
        assertEquals(textCode, jsonCode, "json exit code 불일치");
        assertEquals(textCode, mdCode, "markdown exit code 불일치");
    }

    // ---- 시나리오 헬퍼 ----

    /** 선별 1건(PricingService) + 제외 대상 1건(com/acme/gen/G.java)이 섞인 diff — summary/markdown의
     *  선별 수·무시 수 표기를 동시에 검증하기 위한 조합. */
    private Path mixedDiff() throws IOException {
        String d = """
                diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                @@ -8,1 +8,1 @@
                -old
                +new
                diff --git a/com/acme/gen/G.java b/com/acme/gen/G.java
                --- a/com/acme/gen/G.java
                +++ b/com/acme/gen/G.java
                @@ -1,1 +1,1 @@
                -old
                +new
                """;
        Path f = work.resolve("mixed.diff");
        Files.writeString(f, d);
        return f;
    }

    // ---- 공통 헬퍼 (SpecAcceptanceE2ETest 패턴 복사) ----

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

    Path writeYml(String yaml) throws IOException {
        Path f = work.resolve("tia.yml");
        Files.writeString(f, yaml);
        return f;
    }
}

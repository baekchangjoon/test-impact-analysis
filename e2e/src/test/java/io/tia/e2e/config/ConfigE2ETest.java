package io.tia.e2e.config;

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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP1(사용성 개선 기반) 아우터 루프 — tia.yml 탐색·검증·기본값 적용(REQ-001~003, REQ-023, REQ-024).
 * Task 4 시점에는 CLI에 --config/--search-root 등이 배선되지 않아 전부 red(picocli usage exit 2 등)가
 * 정상 — Task 5에서 배선되면 green으로 전환된다. absentYmlUnchanged만 예외(기존 동작 재검증, green).
 */
@Execution(ExecutionMode.SAME_THREAD)
class ConfigE2ETest {

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

    @Test
    @DisplayName("REQ-001: --config가 최우선 — 탐색 경로 상의 깨진 tia.yml을 완전히 우회한다")
    void configFlagPrecedence() throws Exception {
        // 탐색 시작점(work)에 '깨진' tia.yml — --config가 최우선이면 이 파일은 파싱조차 안 됨.
        Files.writeString(work.resolve("tia.yml"), "not: [valid: yaml: at all");
        Path validYml = work.resolve("explicit.yml");
        Files.writeString(validYml, """
                version: 1
                filters:
                  code:
                    exclude: ["nothing/matches/**"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiff("PricingService.java", 8, "        return key.length() * 100;").toString(),
                "--config", validYml.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains(PRICE), r.out());
    }

    @Test
    @DisplayName("REQ-001: --config 없이 상향 탐색 — git 루트의 tia.yml이 발견·적용된다")
    void upwardDiscovery() throws Exception {
        Files.createDirectories(work.resolve(".git"));   // git 루트 마커
        Files.writeString(work.resolve("tia.yml"), """
                version: 1
                filters:
                  test:
                    exclude: ["**/testPrice"]
                """);
        Path startDir = Files.createDirectories(work.resolve("sub/deeper"));   // 탐색 시작점(하위 디렉터리)

        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiff("PricingService.java", 8, "        return key.length() * 100;").toString(),
                "--search-root", startDir.toString());   // Task 5에서 배선될 히든 옵션
        assertEquals(0, r.code(), r.err());
        assertFalse(r.out().contains(PRICE), r.out());   // 상위 tia.yml의 exclude가 적용됨
    }

    @Test
    @DisplayName("REQ-003: 깨진 tia.yml 4변형 각각 exit 1 + stderr에 파일 경로·원인이 포함된다")
    void invalidYmlFailsFast() throws Exception {
        Map<String, String> variants = new LinkedHashMap<>();
        variants.put("broken-yaml.yml", "not: [valid: yaml: at all");
        variants.put("bad-version.yml", "version: 99\nfilters:\n  code:\n    exclude: [\"a/**\"]\n");
        variants.put("typo-key.yml", "version: 1\nfiltres:\n  code:\n    exclude: [\"a/**\"]\n");
        variants.put("bad-glob.yml", "version: 1\nfilters:\n  code:\n    exclude: [\"[unterminated\"]\n");

        for (Map.Entry<String, String> e : variants.entrySet()) {
            Path yml = work.resolve(e.getKey());
            Files.writeString(yml, e.getValue());
            Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                    "--diff-file", modifyDiff("PricingService.java", 8, "        return key.length() * 100;").toString(),
                    "--config", yml.toString());
            assertEquals(1, r.code(), e.getKey() + ": exit=" + r.code() + " err=" + r.err());
            assertTrue(r.err().contains(yml.toString()), e.getKey() + ": " + r.err());
        }
    }

    @Test
    @DisplayName("REQ-003: bad-glob tia.yml은 index 등 필터를 소비하지 않는 명령에서도 exit 1 + 파일 경로 포함(로더 단계 검증)")
    void invalidGlobFailsFastOnIndexToo() throws Exception {
        Path yml = writeYml("version: 1\nfilters:\n  code:\n    exclude: [\"[unterminated\"]\n");
        Path report = work.resolve("index-badglob-testwise.json");
        copyResource("/spec-testwise.json", report);
        Exec r = run("index", "--report", report.toString(), "--repo", "fixture", "--commit", "C0",
                "--db", work.resolve("badglob.db").toString(), "--config", yml.toString());
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().contains(yml.toString()), r.err());
    }

    @Test
    @DisplayName("REQ-002: --exclude-test 플래그가 tia.yml의 test.exclude 목록을 대체한다(병합 아님)")
    void flagReplacesListNotMerge() throws Exception {
        // yml은 testGreeting을 제외; 플래그는 testPrice를 제외 — 플래그가 이기면 yml의 exclude는 무효화된다.
        Path yml = writeYml("""
                version: 1
                filters:
                  test:
                    exclude: ["**/testGreeting"]
                """);
        // TextUtil.java 6라인은 testPrice·testGreeting 둘 다 커버 → 필터 전이면 둘 다 선별됨.
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiff("TextUtil.java", 6, "        return s;").toString(),
                "--config", yml.toString(),
                "--exclude-test", "**/testPrice");
        assertEquals(0, r.code(), r.err());
        assertFalse(r.out().contains(PRICE), r.out());   // 플래그의 exclude가 적용됨
        assertTrue(r.out().contains(GREET), r.out());    // yml의 exclude는 대체되어 무효 — testGreeting은 살아남음
    }

    @Test
    @DisplayName("REQ-023: tia.yml의 상대경로 db는 yml 파일 위치 기준으로 해석돼 index/impact 기본값이 된다")
    void ymlDbDefaultRelativeToYml() throws Exception {
        Path configDir = Files.createDirectories(work.resolve("cfg"));
        Path yml = configDir.resolve("tia.yml");
        Files.writeString(yml, "version: 1\ndb: ../data/tia.db\n");
        Path expectedDb = work.resolve("data/tia.db");

        Path report = work.resolve("testwise2.json");
        copyResource("/spec-testwise.json", report);
        Exec idx = run("index", "--report", report.toString(), "--repo", "fixture", "--commit", "C9",
                "--config", yml.toString());
        assertEquals(0, idx.code(), idx.err());
        assertTrue(Files.exists(expectedDb), "yml 기준 상대경로 db 파일이 생성되지 않음: " + expectedDb);

        Exec r = run("impact", "--commit", "C9",
                "--diff-file", modifyDiff("PricingService.java", 8, "        return key.length() * 100;").toString(),
                "--config", yml.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains(PRICE), r.out());
    }

    @Test
    @DisplayName("REQ-023: --db 플래그가 tia.yml의 db 선언보다 우선한다")
    void dbFlagBeatsYml() throws Exception {
        Path yml = writeYml("""
                version: 1
                db: ignored/tia.db
                """);
        Path flagDb = work.resolve("flag.db");
        Path report = work.resolve("testwise3.json");
        copyResource("/spec-testwise.json", report);
        Exec idx = run("index", "--report", report.toString(), "--repo", "fixture", "--commit", "C8",
                "--config", yml.toString(), "--db", flagDb.toString());
        assertEquals(0, idx.code(), idx.err());
        assertTrue(Files.exists(flagDb), "플래그로 지정한 db가 생성되지 않음: " + flagDb);
        assertFalse(Files.exists(work.resolve("ignored/tia.db")), "yml의 db가 사용됨(플래그 우선순위 실패)");
    }

    @Test
    @DisplayName("REQ-023: tia.yml에 db 미선언 시 기존 git-common-dir 기본값이 그대로 유지된다")
    void noDbKeepsCommonDirDefault() throws Exception {
        Path yml = writeYml("version: 1\n");   // db 미선언
        Path report = work.resolve("testwise4.json");
        copyResource("/spec-testwise.json", report);

        Exec withConfig = run("index", "--report", report.toString(), "--repo", "fixture", "--commit", "C7",
                "--config", yml.toString());
        Exec withoutConfig = run("index", "--report", report.toString(), "--repo", "fixture", "--commit", "C7");
        assertEquals(withoutConfig.err(), withConfig.err(),
                "db 미선언이면 --config 유무와 무관하게 동일한 기본 인덱스 DB 경로가 사용돼야 함");
    }

    @Test
    @DisplayName("REQ-024: tia.yml의 sut-name이 report --sut-name 기본값으로 적용된다")
    void ymlSutNameDefault() throws Exception {
        Path yml = writeYml("""
                version: 1
                sut-name: Acme-Checkout
                """);
        Path testwise = work.resolve("rep-testwise.json");
        copyResource("/spec-testwise.json", testwise);
        Path out = work.resolve("report.html");
        Exec r = run("report", "--testwise", testwise.toString(), "--commit", "C0",
                "--out", out.toString(), "--config", yml.toString());
        assertEquals(0, r.code(), r.err());
        String html = Files.readString(out);
        assertTrue(html.contains("Acme-Checkout"), "리포트 타이틀에 tia.yml의 sut-name이 반영되지 않음");
    }

    @Test
    @DisplayName("REQ-024: --sut-name 플래그가 tia.yml의 sut-name보다 우선한다")
    void sutNameFlagBeatsYml() throws Exception {
        Path yml = writeYml("""
                version: 1
                sut-name: FromYml
                """);
        Path testwise = work.resolve("rep-testwise2.json");
        copyResource("/spec-testwise.json", testwise);
        Path out = work.resolve("report2.html");
        Exec r = run("report", "--testwise", testwise.toString(), "--commit", "C0",
                "--out", out.toString(), "--config", yml.toString(), "--sut-name", "FromFlag");
        assertEquals(0, r.code(), r.err());
        String html = Files.readString(out);
        assertTrue(html.contains("FromFlag"), html);
        assertFalse(html.contains("FromYml"), html);
    }

    @Test
    @DisplayName("REQ-001: tia.yml이 어디에도 없으면 기존 출력·exit code가 도입 전과 동일하다")
    void absentYmlUnchanged() throws Exception {
        // 의도적으로 --config/필터 플래그 미사용 — SpecAcceptanceE2ETest#purpose1과 동일 시나리오 재실행.
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiff("PricingService.java", 8, "        return key.length() * 100;").toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("영향 테스트 1개"), r.out());
        assertTrue(r.out().contains("DETERMINISTIC\t" + PRICE), r.out());
        assertFalse(r.out().contains(GREET), r.out());
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

    /** 한 줄 수정 diff(레포 상대 경로 → PathNormalizer 정규화). old-side line N. SpecAcceptanceE2ETest#modifyDiff와 동일. */
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
        Path f = work.resolve("change.diff");
        Files.writeString(f, d);
        return f;
    }

    /** 비-.java(unmappable) 파일 수정 diff — CONSERVATIVE 트리거용. */
    Path unmappableDiff(String repoPath) throws IOException { return modifyDiffFor(repoPath, 1); }

    Path writeYml(String yaml) throws IOException {
        Path f = work.resolve("tia.yml");
        Files.writeString(f, yaml);
        return f;
    }
}

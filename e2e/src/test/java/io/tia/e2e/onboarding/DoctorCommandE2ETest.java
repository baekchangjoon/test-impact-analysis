package io.tia.e2e.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tia.cli.RepoPaths;
import io.tia.cli.TiaCommand;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP3-REQ-004..006 — {@code tia doctor} 진단기: 6체크 판정 계약, 읽기 전용 보장, JSON 계약·포맷 제한.
 * 인프로세스 picocli 구동(InitCommandE2ETest·SpecAcceptanceE2ETest 패턴).
 */
@Execution(ExecutionMode.SAME_THREAD)
class DoctorCommandE2ETest {

    @TempDir Path work;

    @Test
    @DisplayName("SP3-REQ-004: 빈 비-git 디렉터리 — tia.yml/DB WARN + 베이스라인/에이전트 SKIP, exit 0")
    void emptyDirWarnsAndSkips() throws Exception {
        Path db = work.resolve("nope.db");

        Exec r = run("doctor", "--search-root", work.toString(), "--db", db.toString());

        assertEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("[WARN]") && r.out().contains("tia.yml"), r.out());
        assertTrue(r.out().contains("[WARN]") && containsBoth(r.out(), "WARN", db.toString()), r.out());
        assertTrue(countOccurrences(r.out(), "[SKIP]") >= 2, "SKIP 2건(베이스라인·에이전트) 기대: " + r.out());
        assertFalse(Files.exists(db), "빈 디렉터리 진단이 DB 파일을 생성함(읽기 전용 위반)");
    }

    @Test
    @DisplayName("SP3-REQ-004: 깨진 tia.yml — 체크3 FAIL + exit 1")
    void brokenYmlFails() throws Exception {
        Files.writeString(work.resolve("tia.yml"), "not: [valid: yaml: at all");
        Path db = work.resolve("broken.db");

        Exec r = run("doctor", "--search-root", work.toString(), "--db", db.toString());

        assertEquals(1, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("[FAIL]") && r.out().contains("tia.yml"), r.out());
    }

    @Test
    @DisplayName("SP3-REQ-004: 유효 tia.yml + 초기 커밋 있는 git 레포 + HEAD 인덱스 db — 체크3/4/5 PASS")
    void healthyProjectPasses() throws Exception {
        git(work, "init", "-q");
        git(work, "config", "user.email", "t@example.com");
        git(work, "config", "user.name", "tester");
        Files.writeString(work.resolve("README.md"), "hello\n");
        git(work, "add", "README.md");
        git(work, "commit", "-q", "-m", "init");
        String head = RepoPaths.gitHead(work);
        assertNotNull(head, "HEAD가 해석돼야 함(초기 커밋 필요)");

        Files.writeString(work.resolve("tia.yml"), "version: 1\nsut-name: healthy\n");

        Path db = work.resolve("healthy.db");
        Path report = work.resolve("testwise.json");
        copyResource("/spec-testwise.json", report);
        Exec idx = run("index", "--report", report.toString(), "--repo", "fixture",
                "--commit", head, "--db", db.toString());
        assertEquals(0, idx.code(), idx.out() + idx.err());

        Exec r = run("doctor", "--search-root", work.toString(), "--db", db.toString());

        assertEquals(0, r.code(), r.out() + r.err());
        long passCount = countOccurrences(r.out(), "[PASS]");
        assertTrue(passCount >= 3, "체크3/4/5 PASS 기대(git PASS 포함 4개 이상 가능): " + r.out());
        assertFalse(r.out().contains("[FAIL]"), r.out());
    }

    @Test
    @DisplayName("SP3-REQ-005: DB 없는 디렉터리 — 진단 후에도 해석된 DB 경로에 파일이 생기지 않는다")
    void doesNotCreateDbFile() throws Exception {
        Path db = work.resolve("subdir/does-not-exist.db");

        Exec r = run("doctor", "--search-root", work.toString(), "--db", db.toString());

        assertFalse(Files.exists(db), "doctor가 DB 파일을 생성함(읽기 전용 보장 위반): " + r.out());
        assertFalse(Files.exists(db.getParent()), "doctor가 DB 부모 디렉터리를 생성함: " + r.out());
    }

    @Test
    @DisplayName("SP3-REQ-006: --format json은 schemaVersion/command/checks/summary 스키마를 낸다")
    void jsonSchema() throws Exception {
        Path db = work.resolve("json.db");

        Exec r = run("doctor", "--search-root", work.toString(), "--db", db.toString(), "--format", "json");

        assertEquals(0, r.code(), r.out() + r.err());
        JsonNode root = new ObjectMapper().readTree(r.out());
        assertEquals(1, root.path("schemaVersion").asInt(), r.out());
        assertEquals("doctor", root.path("command").asText(), r.out());
        assertTrue(root.path("checks").isArray() && root.path("checks").size() == 6, r.out());
        for (JsonNode check : root.path("checks")) {
            assertTrue(check.hasNonNull("id"), r.out());
            assertTrue(check.hasNonNull("status"), r.out());
            assertTrue(check.has("detail"), r.out());
            assertTrue(check.has("hint"), r.out());
        }
        JsonNode summary = root.path("summary");
        assertTrue(summary.has("pass") && summary.has("warn") && summary.has("fail") && summary.has("skip"), r.out());
    }

    @Test
    @DisplayName("SP3-REQ-006: --format summary는 doctor 전용 2값(text|json) 밖이라 usage 에러(비0 exit)")
    void summaryFormatRejected() throws Exception {
        Exec r = run("doctor", "--search-root", work.toString(), "--format", "summary");
        assertNotEquals(0, r.code(), r.out() + r.err());
    }

    // ---- 공통 헬퍼 ----

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

    private static boolean containsBoth(String haystack, String a, String b) {
        return haystack.contains(a) && haystack.contains(b);
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

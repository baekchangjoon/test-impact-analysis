package io.tia.e2e.onboarding;

import io.tia.cli.RepoPaths;
import io.tia.cli.TiaCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP3-REQ-007..009 — {@code tia demo} 체험 러너: 레포 전제 검사, 스텁+실구동 혼합 전 구간
 * 파이프라인(index~report는 인프로세스로 직접 구동), 서브프로세스·인프로세스 양쪽 실패 처리.
 * 히든 시임 3종(--repo-root/--scripts-dir/--out-dir)으로 격리(design spec §4/§7).
 */
@Execution(ExecutionMode.SAME_THREAD)
class DemoCommandE2ETest {

    @TempDir Path work;

    private static final String VALID_TESTWISE = """
            {
              "tests": [
                {
                  "uniformPath": "io/tia/fixture/ApiSmokeTest/testPrice",
                  "result": "PASSED",
                  "paths": [
                    { "path": "io/tia/fixture", "files": [
                      { "fileName": "PricingService.java", "coveredLines": "6-8" }
                    ]}
                  ]
                }
              ]
            }
            """;

    private static final String ZERO_COVERED_TESTWISE = """
            {
              "tests": [
                {
                  "uniformPath": "io/tia/fixture/ApiSmokeTest/testNothing",
                  "result": "PASSED",
                  "paths": [
                    { "path": "io/tia/fixture", "files": [
                      { "fileName": "PricingService.java", "coveredLines": "" }
                    ]}
                  ]
                }
              ]
            }
            """;

    @Test
    @DisplayName("SP3-REQ-007: TIA 레포 밖(--repo-root)이면 exit 1 + git clone 안내")
    void outsideRepoExit1() {
        Exec r = run("demo", "--repo-root", work.toString(), "--out-dir", work.resolve("out").toString());
        assertEquals(1, r.code(), r.out() + r.err());
        assertTrue(r.err().contains("git clone"), r.err());
    }

    @Test
    @DisplayName("SP3-REQ-008: 스텁 수집(1·2단계) + 실구동 인덱싱~리포트(3~6단계) — 마커·DETERMINISTIC 1건·CTA·산출물")
    void stubbedCollectRealIndexImpactReport() throws Exception {
        Path repoRoot = realTiaRepoRoot();
        Path scriptsDir = Files.createDirectories(work.resolve("scripts"));
        writeScript(scriptsDir.resolve("setup-pjacoco.sh"), """
                #!/usr/bin/env bash
                echo "stub pjacoco resolved" >&2
                exit 0
                """);
        writeScript(scriptsDir.resolve("demo-collect.sh"), """
                #!/usr/bin/env bash
                set -euo pipefail
                OUT="$1"
                mkdir -p "$OUT"
                cat > "$OUT/testwise_serial.json" <<'JSON'
                %s
                JSON
                """.formatted(VALID_TESTWISE));
        Path outDir = work.resolve("out");

        Exec r = run("demo", "--repo-root", repoRoot.toString(),
                "--scripts-dir", scriptsDir.toString(), "--out-dir", outDir.toString());

        assertEquals(0, r.code(), r.out() + r.err());
        for (int i = 1; i <= 6; i++) {
            assertTrue(r.out().contains("[" + i + "/6]"), "마커 [" + i + "/6] 누락: " + r.out());
        }
        long deterministicSelections = r.out().lines()
                .filter(line -> line.startsWith("DETERMINISTIC\t")).count();
        assertEquals(1, deterministicSelections,
                "정확히 DETERMINISTIC 1건(impact 선별 라인) 기대: " + r.out());
        assertTrue(r.out().contains("tia init"), "마무리 CTA 누락: " + r.out());
        assertTrue(Files.exists(outDir.resolve("demo-tia.db")), "demo-tia.db 미생성");
        assertTrue(Files.exists(outDir.resolve("demo.diff")), "demo.diff 미생성");
        assertTrue(Files.exists(outDir.resolve("report.html")), "report.html 미생성");
    }

    @Test
    @DisplayName("SP3-REQ-009: 실패하는 스텁 스크립트 — stderr 요약 + doctor 안내 + exit 1")
    void failingStubShowsStderrAndDoctorHint() throws Exception {
        Path repoRoot = realTiaRepoRoot();
        Path scriptsDir = Files.createDirectories(work.resolve("scripts"));
        writeScript(scriptsDir.resolve("setup-pjacoco.sh"), """
                #!/usr/bin/env bash
                echo "stub pjacoco resolved" >&2
                exit 0
                """);
        writeScript(scriptsDir.resolve("demo-collect.sh"), """
                #!/usr/bin/env bash
                echo "boom: fixture-app 수집 중 실패했습니다" >&2
                exit 1
                """);
        Path outDir = work.resolve("out");

        Exec r = run("demo", "--repo-root", repoRoot.toString(),
                "--scripts-dir", scriptsDir.toString(), "--out-dir", outDir.toString());

        assertEquals(1, r.code(), r.out() + r.err());
        assertTrue(r.err().contains("boom"), r.err());
        assertTrue(r.err().contains("tia doctor"), r.err());
    }

    @Test
    @DisplayName("SP3-REQ-009: 커버 라인 0개 — 4단계(diff 생성)에서 명확히 실패 + doctor 안내 + exit 1")
    void zeroCoveredLinesFailsAtDiffStage() throws Exception {
        Path repoRoot = realTiaRepoRoot();
        Path scriptsDir = Files.createDirectories(work.resolve("scripts"));
        writeScript(scriptsDir.resolve("setup-pjacoco.sh"), """
                #!/usr/bin/env bash
                echo "stub pjacoco resolved" >&2
                exit 0
                """);
        writeScript(scriptsDir.resolve("demo-collect.sh"), """
                #!/usr/bin/env bash
                set -euo pipefail
                OUT="$1"
                mkdir -p "$OUT"
                cat > "$OUT/testwise_serial.json" <<'JSON'
                %s
                JSON
                """.formatted(ZERO_COVERED_TESTWISE));
        Path outDir = work.resolve("out");

        Exec r = run("demo", "--repo-root", repoRoot.toString(),
                "--scripts-dir", scriptsDir.toString(), "--out-dir", outDir.toString());

        assertEquals(1, r.code(), r.out() + r.err());
        assertTrue(r.err().contains("tia doctor"), r.err());
        assertFalse(Files.exists(outDir.resolve("demo.diff")), "커버 라인 0개인데 demo.diff가 생성됨");
    }

    // ---- 공통 헬퍼 ----

    /** 이 테스트 JVM 자신이 도는 TIA 레포 루트(상향 탐색) — 실 인덱싱~리포트 단계가 구동될 레포. */
    private static Path realTiaRepoRoot() {
        Path found = RepoPaths.findTiaRepoRoot(Path.of("").toAbsolutePath());
        assertNotNull(found, "테스트가 TIA 레포 안에서 돌고 있어야 함(상향 탐색 실패)");
        return found;
    }

    private static void writeScript(Path file, String content) throws Exception {
        Files.writeString(file, content);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // 비-POSIX 파일시스템(예: 일부 CI Windows) — DemoCommand가 "bash <script>"로 구동하므로
            // 실행권한이 없어도 무방하다.
        }
    }

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
}

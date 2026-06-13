package io.tia.e2e;

import io.tia.cli.TiaCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * 설계 명세(docs/superpowers/specs/2026-06-13-test-impact-analysis-design.md)의 Phase 0 동작을
 * end-to-end로 검증한다. 실제 teamscale 에이전트 없이 캡처된 testwise JSON 픽스처로 전체
 * 파이프라인(index → SQLite 저장 → git diff 교차 → 영향 선별/플레이키)을 CLI로 구동한다.
 *
 * 픽스처(spec-testwise.json):
 *   testPrice   → PricingService {6,7,8} + TextUtil {6}
 *   testGreeting→ GreetingService {6,7}  + TextUtil {6}
 */
class SpecAcceptanceE2ETest {

    @TempDir Path work;
    Path db;

    @BeforeEach
    void index() throws Exception {
        db = work.resolve("tia.db");
        Path report = work.resolve("testwise.json");
        copyResource("/spec-testwise.json", report);
        assertEquals(0, run("index", "--report", report.toString(),
                "--repo", "fixture", "--commit", "C0", "--db", db.toString()));
    }

    /** §1.1 목적1 + §3.0 D11: 커버된 라인(8) 변경 → testPrice DETERMINISTIC, testGreeting 미선별. */
    @Test
    void purpose1_changeOnCoveredLine_selectsCoveringTest() throws Exception {
        Path diff = modifyDiff("PricingService.java", 8, "        return key.length() * 100;");
        String out = capture("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diff.toString());
        assertTrue(out.contains("testPrice"), out);
        assertFalse(out.contains("testGreeting"), out);
        assertTrue(out.contains("DETERMINISTIC"), out);
    }

    /** §3.0 D11(음성): 커버 안 된 라인(5=클래스 선언) 변경 → 미선별(파일 수준이면 선별됐을 것). */
    @Test
    void d11_changeOnUncoveredLine_doesNotSelect_provingLineNotFileGranularity() throws Exception {
        Path diff = modifyDiff("PricingService.java", 5, "public class PricingService {");
        String out = capture("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diff.toString());
        assertFalse(out.contains("testPrice"), "라인 5는 커버집합{6,7,8} 밖 → 미선별이어야 함: " + out);
        assertTrue(out.contains("0개"), out);
    }

    /** §1.1 목적2(triage): 실패한 testGreeting이 PricingService 변경과 무관 → 영향 집합 비포함 → 코드 무관(infra). */
    @Test
    void purpose2_failingTestUnrelatedToDiff_isNotImpacted() throws Exception {
        Path diff = modifyDiff("PricingService.java", 8, "        return key.length() * 100;");
        String out = capture("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diff.toString());
        assertFalse(out.contains("testGreeting"), "testGreeting 커버 ∩ diff = ∅ → 영향 아님(실패 시 infra): " + out);
    }

    /** §1.3 D12 [1-A]: 비코드(yml) 변경 → 보수적 전체 선택. */
    @Test
    void rule1A_nonCodeChange_forcesConservativeSelectAll() throws Exception {
        Path diff = modifyDiff("application.yml", 1, "server.port: 8080");
        String out = capture("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diff.toString());
        assertTrue(out.contains("보수적 전체 선택"), out);
        assertTrue(out.contains("testPrice") && out.contains("testGreeting"), "전체 선택이어야 함: " + out);
    }

    /** §1.1 [1-B]: 신규 .java → 보수적 전체 선택(과거 커버리지·정적 그래프 없음). */
    @Test
    void rule1B_newJavaFile_forcesConservativeSelectAll() throws Exception {
        Path diff = newFileDiff("NewFeature.java");
        String out = capture("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diff.toString());
        assertTrue(out.contains("보수적 전체 선택"), out);
    }

    /** §6.1: 커밋 1급 키 — 다른 커밋(C1)에 뒤바뀐 매핑을 인덱싱해도 커밋별로 격리된다. */
    @Test
    void commitIsFirstClassKey_mappingsIsolatedPerCommit() throws Exception {
        Path report2 = work.resolve("testwise-c1.json");
        copyResource("/spec-testwise-c1.json", report2);
        assertEquals(0, run("index", "--report", report2.toString(),
                "--repo", "fixture", "--commit", "C1", "--db", db.toString()));

        Path diff = modifyDiff("PricingService.java", 8, "        return key.length() * 100;");
        String c0 = capture("impact", "--db", db.toString(), "--commit", "C0", "--diff-file", diff.toString());
        String c1 = capture("impact", "--db", db.toString(), "--commit", "C1", "--diff-file", diff.toString());
        assertTrue(c0.contains("testPrice") && !c0.contains("testGreeting"), "C0: " + c0);
        assertTrue(c1.contains("testGreeting") && !c1.contains("testPrice"), "C1: " + c1);
    }

    /** Phase 0: 플레이키 비율 측정. */
    @Test
    void phase0_flakyRatioMeasurement() throws Exception {
        Path r1 = work.resolve("run1.json"); Files.writeString(r1, "{\"results\":{\"T_ok\":true,\"T_flaky\":true}}");
        Path r2 = work.resolve("run2.json"); Files.writeString(r2, "{\"results\":{\"T_ok\":true,\"T_flaky\":false}}");
        Path r3 = work.resolve("run3.json"); Files.writeString(r3, "{\"results\":{\"T_ok\":true,\"T_flaky\":true}}");
        String out = capture("flaky", "--runs", r1 + "," + r2 + "," + r3);
        assertTrue(out.contains("T_flaky"), out);
        assertTrue(out.contains("1/2") || out.contains("0.5"), out);
    }

    // ---- helpers ----

    private int run(String... args) {
        return new CommandLine(new TiaCommand()).execute(args);
    }

    private String capture(String... args) {
        PrintStream prev = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            new CommandLine(new TiaCommand()).execute(args);
        } finally {
            System.setOut(prev);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private void copyResource(String res, Path dest) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(res)) {
            assertNotNull(in, "resource missing: " + res);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 한 줄 수정 diff(레포 상대 경로 → PathNormalizer 정규화). old-side line N. */
    private Path modifyDiff(String fileName, int line, String content) throws IOException {
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

    /** 신규 파일 추가 diff(addition-only). */
    private Path newFileDiff(String fileName) throws IOException {
        String path = "fixture-app/src/main/java/io/tia/fixture/" + fileName;
        String diff = "diff --git a/" + path + " b/" + path + "\n"
                + "--- /dev/null\n"
                + "+++ b/" + path + "\n"
                + "@@ -0,0 +1,2 @@\n"
                + "+package io.tia.fixture;\n"
                + "+public class NewFeature {}\n";
        Path p = work.resolve("d-new-" + fileName + ".diff");
        Files.writeString(p, diff);
        return p;
    }
}

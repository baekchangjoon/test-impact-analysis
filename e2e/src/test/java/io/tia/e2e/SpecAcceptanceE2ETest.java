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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설계 명세(docs/superpowers/specs/2026-06-13-test-impact-analysis-design.md)의 현재 구현 동작을
 * end-to-end로 검증한다. 캡처된 testwise JSON 픽스처로 전체 파이프라인(index → SQLite 저장 →
 * git diff 교차 → 영향 선별/플레이키)을 CLI로 구동하고, 출력을 **구조적으로 파싱해 정확히** 검증한다.
 *
 * 픽스처(spec-testwise.json): testPrice → PricingService{6,7,8}+TextUtil{6}, testGreeting → GreetingService{6,7}+TextUtil{6}
 */
class SpecAcceptanceE2ETest {

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
                "--repo", "fixture", "--commit", "C0", "--db", db.toString()));
    }

    /** §1.1 목적1 + §3.0 D11: 커버된 라인(8) 변경 → 정확히 {testPrice=DETERMINISTIC}만, count=1, 비보수적. */
    @Test
    void purpose1_changeOnCoveredLine_selectsExactlyCoveringTest() throws Exception {
        Impact r = impact(modifyDiff("PricingService.java", 8, "        return key.length() * 100;"), "C0");
        assertEquals(1, r.count, r.raw);
        assertFalse(r.conservativeSelectAll, r.raw);
        assertEquals(Map.of(PRICE, "DETERMINISTIC"), r.byTest, r.raw);   // 정확히 testPrice 하나, DETERMINISTIC
    }

    /** §3.0 D11(음성): 커버 안 된 라인(5) 변경 → count=0, 선별 집합 공집합(파일 수준이면 선별됐을 것). */
    @Test
    void d11_changeOnUncoveredLine_selectsNothing_provingLineNotFileGranularity() throws Exception {
        Impact r = impact(modifyDiff("PricingService.java", 5, "public class PricingService {"), "C0");
        assertEquals(0, r.count, "라인 5는 커버집합{6,7,8} 밖 → 0개여야 함: " + r.raw);
        assertTrue(r.byTest.isEmpty(), r.raw);
        assertFalse(r.conservativeSelectAll, r.raw);
    }

    /** §1.1 목적2(triage): PricingService 변경 시, testGreeting∉영향 → 실패 시 '코드 무관(infra)';
     *  testPrice∈영향 → '코드 관련'. 두 귀속을 모두 검증해 triage 판정을 근거화. */
    @Test
    void purpose2_triageGrounding_failingTestClassifiedByImpactMembership() throws Exception {
        Impact r = impact(modifyDiff("PricingService.java", 8, "        return key.length() * 100;"), "C0");
        assertTrue(r.byTest.containsKey(PRICE), "testPrice는 변경 라인 커버 → 코드 관련: " + r.raw);
        assertFalse(r.byTest.containsKey(GREET), "testGreeting ∩ diff = ∅ → 실패해도 코드 무관(infra): " + r.raw);
    }

    /** §1.3 D12 [1-A]: 비코드(yml) 변경 → 보수적 전체 선택(두 테스트 모두 CONSERVATIVE) + 사유 메시지. */
    @Test
    void rule1A_nonCodeChange_forcesConservativeSelectAll() throws Exception {
        Impact r = impact(modifyDiff("application.yml", 1, "server.port: 8080"), "C0");
        assertTrue(r.conservativeSelectAll, r.raw);
        assertEquals(2, r.count, r.raw);
        assertEquals("CONSERVATIVE", r.byTest.get(PRICE), r.raw);
        assertEquals("CONSERVATIVE", r.byTest.get(GREET), r.raw);
        assertTrue(r.raw.contains("매핑 불가"), "1-A 사유 메시지 필요: " + r.raw);
    }

    /** §1.1 [1-B]: 신규 .java → 보수적 전체 선택(두 테스트 모두 선별). */
    @Test
    void rule1B_newJavaFile_forcesConservativeSelectAll() throws Exception {
        Impact r = impact(newFileDiff("NewFeature.java"), "C0");
        assertTrue(r.conservativeSelectAll, r.raw);
        assertEquals(Set.of(PRICE, GREET), r.byTest.keySet(), r.raw);
        assertTrue(r.raw.contains("신규 .java"), "1-B 사유 메시지 필요: " + r.raw);
    }

    /** §6.1: 커밋 1급 키 — C1에 뒤바뀐 매핑을 인덱싱해도 커밋별로 정확히 격리(C0→{price}, C1→{greet}). */
    @Test
    void commitIsFirstClassKey_mappingsIsolatedPerCommit() throws Exception {
        Path report2 = work.resolve("testwise-c1.json");
        copyResource("/spec-testwise-c1.json", report2);
        assertEquals(0, run("index", "--report", report2.toString(),
                "--repo", "fixture", "--commit", "C1", "--db", db.toString()));

        Path diff = modifyDiff("PricingService.java", 8, "        return key.length() * 100;");
        Impact c0 = impact(diff, "C0");
        Impact c1 = impact(diff, "C1");
        assertEquals(Set.of(PRICE), c0.byTest.keySet(), "C0: " + c0.raw);   // C0: testPrice가 PricingService 커버
        assertEquals(Set.of(GREET), c1.byTest.keySet(), "C1: " + c1.raw);   // C1: 뒤바뀐 매핑 → testGreeting
    }

    /** 플레이키 비율 측정 — ratio=0.5, 플레이키={T_flaky}, T_ok는 비플레이키. */
    @Test
    void flakyRatioMeasurement() throws Exception {
        Path r1 = work.resolve("run1.json"); Files.writeString(r1, "{\"results\":{\"T_ok\":true,\"T_flaky\":true}}");
        Path r2 = work.resolve("run2.json"); Files.writeString(r2, "{\"results\":{\"T_ok\":true,\"T_flaky\":false}}");
        Path r3 = work.resolve("run3.json"); Files.writeString(r3, "{\"results\":{\"T_ok\":true,\"T_flaky\":true}}");
        String out = capture("flaky", "--runs", r1 + "," + r2 + "," + r3);

        Matcher m = Pattern.compile("flaky ratio: ([0-9.]+) \\((\\d+)/(\\d+)\\)").matcher(out);
        assertTrue(m.find(), "ratio 라인 파싱 실패: " + out);
        assertEquals(0.5, Double.parseDouble(m.group(1)), 1e-9, out);   // 정확히 0.5
        assertEquals(1, Integer.parseInt(m.group(2)), out);             // 플레이키 1
        assertEquals(2, Integer.parseInt(m.group(3)), out);             // 전체 2
        assertTrue(out.contains("FLAKY\tT_flaky"), out);                 // T_flaky는 플레이키
        assertFalse(out.contains("FLAKY\tT_ok"), out);                   // T_ok는 비플레이키
    }

    // ---- impact 출력 구조 파싱 ----

    /** ImpactCommand 출력: 헤더(영향 테스트 N개[, 보수적 전체 선택]) + "<CONFIDENCE>\t<testId>" 라인들 + "# 주의:" 사유. */
    record Impact(int count, boolean conservativeSelectAll, Map<String, String> byTest, String raw) {}

    private Impact impact(Path diffFile, String commit) {
        String out = capture("impact", "--db", db.toString(), "--commit", commit, "--diff-file", diffFile.toString());
        int count = -1;
        Matcher cm = Pattern.compile("영향 테스트 (\\d+)개").matcher(out);
        if (cm.find()) count = Integer.parseInt(cm.group(1));
        boolean conservative = out.contains("보수적 전체 선택");
        Map<String, String> byTest = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                String conf = line.substring(0, tab);
                if (conf.equals("DETERMINISTIC") || conf.equals("CONSERVATIVE") || conf.equals("LOW_CONFIDENCE")) {
                    byTest.put(line.substring(tab + 1).trim(), conf);
                }
            }
        }
        return new Impact(count, conservative, byTest, out);
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

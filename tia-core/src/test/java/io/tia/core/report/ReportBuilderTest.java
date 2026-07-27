package io.tia.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tia.core.filter.FilterSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportBuilderTest {
    private final ObjectMapper om = new ObjectMapper();

    private ReportBuilder.Inputs inputs(Path tmp, Path scenarios, Path flaky, Path prod) throws Exception {
        Path tw = tmp.resolve("testwise.json");
        Files.writeString(tw, """
            {"tests":[
              {"uniformPath":"BTest#b","result":"PASSED","paths":[{"path":"com/x","files":[{"fileName":"A.java","coveredLines":"1-3,5"}]}]},
              {"uniformPath":"ATest#a","result":"PASSED","paths":[{"path":"com/x","files":[{"fileName":"A.java","coveredLines":"1-2"}]}]}
            ]}""");
        return new ReportBuilder.Inputs(tw, scenarios, flaky, prod, "deadbeefcafe", "acme-svc", "jacoco", null, "",
                FilterSet.none());
    }

    @Test
    void buildModelComputesPerTestReverseBlindAndCounts(@TempDir Path tmp) throws Exception {
        Path prod = tmp.resolve("prod.txt");
        Files.writeString(prod, "com/x/A.java\ncom/x/Uncovered.java\n");

        Map<String, Object> model = new ReportBuilder().buildModel(inputs(tmp, null, null, prod));
        JsonNode d = om.valueToTree(model);

        assertEquals("acme-svc", d.get("sut").asText());
        assertEquals(2, d.get("nTests").asInt());
        assertEquals(2, d.get("nProd").asInt());
        assertEquals(1, d.get("nCovered").asInt());
        assertEquals(6, d.get("totalPoints").asInt(), "lines_of(1-3,5)=4 + lines_of(1-2)=2");

        // perTest sorted by id; ATest#a first with lines=2
        assertEquals("ATest#a", d.get("perTest").get(0).get("id").asText());
        assertEquals(2, d.get("perTest").get(0).get("lines").asInt());
        assertEquals(4, d.get("perTest").get(1).get("lines").asInt());
        assertEquals(1, d.get("perTest").get(0).get("files").get(0).get("line").asInt(), "first covered line");

        // reverse: A.java fan-in 2
        assertEquals("com/x/A.java", d.get("reverse").get(0).get("file").asText());
        assertEquals(2, d.get("reverse").get(0).get("n").asInt());

        // blind: the uncovered prod file
        assertEquals(1, d.get("blind").size());
        assertEquals("com/x/Uncovered.java", d.get("blind").get(0).asText());

        // optional inputs omitted → scenarios [], flaky null (graceful)
        assertTrue(d.get("scenarios").isArray() && d.get("scenarios").isEmpty());
        assertTrue(d.get("flaky").isNull());
    }

    @Test
    void renderInjectsTemplateAndModel(@TempDir Path tmp) throws Exception {
        String html = new ReportBuilder().render(inputs(tmp, null, null, null));
        assertTrue(html.contains("<title>TIA report · acme-svc blackbox</title>"), "SUT in title");
        assertTrue(html.contains("const D = {"), "model injected");
        assertFalse(html.contains("__DATA__"), "placeholder replaced");
        assertFalse(html.contains("__SUT__"), "placeholder replaced");
        // data island must parse as JSON (model round-trips), and "</" is escaped to survive <script>
        assertEquals(1, html.split("</script>", -1).length - 1, "only the real closing script tag");
    }

    @Test
    void prefixStripShortensPaths(@TempDir Path tmp) throws Exception {
        Path tw = tmp.resolve("tw.json");
        Files.writeString(tw, """
            {"tests":[{"uniformPath":"T#m","result":"PASSED","paths":[{"path":"org/acme/app","files":[{"fileName":"X.java","coveredLines":"1"}]}]}]}""");
        var in = new ReportBuilder.Inputs(tw, null, null, null, "c", "s", "jacoco", null, "org/acme/",
                FilterSet.none());
        JsonNode d = om.valueToTree(new ReportBuilder().buildModel(in));
        assertEquals("…/app/X.java", d.get("perTest").get(0).get("files").get(0).get("f").asText());
    }

    @Test
    void filtersExcludeTestRowsAndFilesFromSurvivingTestsReverseIndexAndProdList(@TempDir Path tmp) throws Exception {
        Path tw = tmp.resolve("testwise.json");
        Files.writeString(tw, """
            {"tests":[
              {"uniformPath":"BTest#b","result":"PASSED","paths":[{"path":"com/x","files":[
                {"fileName":"A.java","coveredLines":"1-3,5"},
                {"fileName":"Excluded.java","coveredLines":"1"}
              ]}]},
              {"uniformPath":"ExcludedTest#e","result":"PASSED","paths":[{"path":"com/x","files":[{"fileName":"A.java","coveredLines":"1-2"}]}]}
            ]}""");
        Path prod = tmp.resolve("prod.txt");
        Files.writeString(prod, "com/x/A.java\ncom/x/Excluded.java\n");

        FilterSet filters = FilterSet.of(List.of(), List.of("com/x/Excluded.java"),
                List.of(), List.of("**/ExcludedTest/*"));
        var in = new ReportBuilder.Inputs(tw, null, null, prod, "deadbeefcafe", "acme-svc", "jacoco", null, "",
                filters);
        Map<String, Object> model = new ReportBuilder().buildModel(in);
        JsonNode d = om.valueToTree(model);

        // excluded testId dropped from perTest rows entirely
        assertEquals(1, d.get("perTest").size());
        assertEquals("BTest#b", d.get("perTest").get(0).get("id").asText());

        // excluded file dropped from the surviving test's file list
        List<String> survivingFiles = new java.util.ArrayList<>();
        d.get("perTest").get(0).get("files").forEach(f -> survivingFiles.add(f.get("f").asText()));
        assertFalse(survivingFiles.contains("com/x/Excluded.java"), "excluded file leaked into surviving test's files");
        assertTrue(survivingFiles.contains("com/x/A.java"));

        // excluded file dropped from the reverse index too
        List<String> reverseFiles = new java.util.ArrayList<>();
        d.get("reverse").forEach(r -> reverseFiles.add(r.get("file").asText()));
        assertFalse(reverseFiles.contains("com/x/Excluded.java"), "excluded file leaked into reverse index");

        // excluded file dropped from prod-files (blind-spot denominator)
        assertEquals(1, d.get("nProd").asInt(), "excluded file must not count toward prod denominator");
        List<String> blind = new java.util.ArrayList<>();
        d.get("blind").forEach(b -> blind.add(b.asText()));
        assertFalse(blind.contains("com/x/Excluded.java"), "excluded file must not appear as a blind spot either");
    }

    @Test
    @DisplayName("SP5-REQ-008: 렌더된 HTML에 5개 탭 각각 tab-guide 블록이 정확히 한 번씩 존재한다")
    void tabGuidesRenderedFiveTimes(@TempDir Path tmp) throws Exception {
        Path prod = tmp.resolve("prod.txt");
        Files.writeString(prod, "com/x/A.java\n");

        String html = new ReportBuilder().render(inputs(tmp, null, null, prod));

        int occurrences = html.split("class=\"tab-guide\"", -1).length - 1;
        assertEquals(5, occurrences, "one tab-guide block per section (per-test/reverse/impact/flaky/blind)");
        assertTrue(html.contains("이 탭 읽는 법"), "guide summary label present");
    }

    @Test
    @DisplayName("SP5-REQ-009: 테스트 0건 + 빈 prod로 렌더하면 탭 1·2·5에 빈 상태 안내 문구가 있다")
    void emptyStateHintsForSparseTabs(@TempDir Path tmp) throws Exception {
        Path tw = tmp.resolve("empty-testwise.json");
        Files.writeString(tw, """
            {"tests":[]}""");
        var in = new ReportBuilder.Inputs(tw, null, null, null, "deadbeefcafe", "acme-svc", "jacoco", null, "",
                FilterSet.none());

        String html = new ReportBuilder().render(in);

        assertTrue(html.contains("데이터가 없습니다"), "empty-state hint present for sparse per-test/reverse tabs");
        assertTrue(html.contains("입력이 비었습니다") || html.contains("입력 없음"),
                "tab 5 missing-input hint present when nProd === 0");
    }

    @Test
    @DisplayName("SP5-REQ-009: prod 파일이 있고 blind 0건이면 탭 5에 전체 커버 긍정 메시지가 표시된다(결측 안내 아님)")
    void fullCoverageBlindTabShowsPositiveMessage(@TempDir Path tmp) throws Exception {
        Path prod = tmp.resolve("prod.txt");
        Files.writeString(prod, "com/x/A.java\n");   // fully covered by both fixture tests → blind == []

        String html = new ReportBuilder().render(inputs(tmp, null, null, prod));
        JsonNode d = om.valueToTree(new ReportBuilder().buildModel(inputs(tmp, null, null, prod)));
        assertEquals(1, d.get("nProd").asInt());
        assertTrue(d.get("blind").isEmpty(), "fixture prod file is covered by both tests");

        assertTrue(html.contains("전체 커버") && html.contains("사각지대가 없습니다"),
                "positive full-coverage message present for the nProd>0 && blind==0 branch");
    }
}

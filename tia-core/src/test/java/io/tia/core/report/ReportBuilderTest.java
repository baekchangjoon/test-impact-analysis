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
import java.util.regex.Pattern;

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

    /**
     * Structural check, not a runtime-behavior check: {@code render()} only substitutes
     * {@code __SUT__}/{@code __DATA__} into the static template — the {@code <script>} JS is
     * never executed, so a plain {@code String#contains(message)} can't tell "this message is
     * reachable only inside this guard's branch" from "this message is dead text that is always
     * present in the source regardless of the model". This asserts the actual coupling instead:
     * {@code guard} opens a code block that contains {@code message} before {@code stop} (e.g. a
     * {@code return;} that prevents fallthrough to the normal-data render path) — i.e. the
     * condition and its message are the same branch in the emitted JS source. Real runtime branch
     * execution (which of several mutually-exclusive branches the browser actually takes for a
     * given {@code D}) is outside a JVM unit test's reach without a headless browser; that gap is
     * accepted and documented, not silently claimed as covered.
     */
    private static void assertGuardGatesMessageThenStops(String html, String guard, String message, String stop) {
        Pattern p = Pattern.compile(
                Pattern.quote(guard) + ".{0,150}?" + Pattern.quote(message) + ".{0,200}?" + Pattern.quote(stop),
                Pattern.DOTALL);
        assertTrue(p.matcher(html).find(),
                () -> "expected `" + guard + "` to gate message `" + message + "` before `" + stop + "`");
    }

    @Test
    @DisplayName("SP5-REQ-009: 테스트 0건 + 빈 prod — buildModel 값 및 탭 1·2·5 가드→메시지 결합 구조가 성립한다")
    void emptyStateHintsForSparseTabs(@TempDir Path tmp) throws Exception {
        Path tw = tmp.resolve("empty-testwise.json");
        Files.writeString(tw, """
            {"tests":[]}""");
        var in = new ReportBuilder.Inputs(tw, null, null, null, "deadbeefcafe", "acme-svc", "jacoco", null, "",
                FilterSet.none());

        // real model-value assertions (these are gated by actual input, unlike raw HTML text search)
        JsonNode d = om.valueToTree(new ReportBuilder().buildModel(in));
        assertEquals(0, d.get("nTests").asInt());
        assertTrue(d.get("perTest").isEmpty());
        assertTrue(d.get("reverse").isEmpty());
        assertEquals(0, d.get("nProd").asInt());

        String html = new ReportBuilder().render(in);

        // structural: each guard's block actually contains its empty-state message and a `return;`
        // that skips the normal table-render path — not just message text floating unconditionally
        // in the script.
        assertGuardGatesMessageThenStops(html, "if(!D.perTest.length){", "데이터가 없습니다", "return;");
        assertGuardGatesMessageThenStops(html, "if(!D.reverse.length){", "데이터가 없습니다", "return;");
        assertGuardGatesMessageThenStops(html, "if(D.nProd===0){", "입력이 없거나 필터로 모두 제외되었습니다", "}else if(D.blind.length===0){");
    }

    @Test
    @DisplayName("SP5-REQ-009: prod 있음 + blind 0건 — nProd/blind 모델 값과 if/else-if/else 분기의 긍정 메시지 결합이 성립한다")
    void fullCoverageBlindTabShowsPositiveMessage(@TempDir Path tmp) throws Exception {
        Path prod = tmp.resolve("prod.txt");
        Files.writeString(prod, "com/x/A.java\n");   // fully covered by both fixture tests → blind == []

        // real model-value assertions
        JsonNode d = om.valueToTree(new ReportBuilder().buildModel(inputs(tmp, null, null, prod)));
        assertEquals(1, d.get("nProd").asInt());
        assertTrue(d.get("blind").isEmpty(), "fixture prod file is covered by both tests");

        String html = new ReportBuilder().render(inputs(tmp, null, null, prod));

        // structural: the whole if(nProd===0)/else-if(blind==0)/else chain is intact — i.e. the
        // positive message is the else-if branch (mutually exclusive with the missing-input
        // branch), not just text present somewhere in the script regardless of data.
        Pattern chain = Pattern.compile(
                Pattern.quote("if(D.nProd===0){") + ".{0,150}?"
                        + Pattern.quote("입력이 없거나 필터로 모두 제외되었습니다") + ".{0,200}?"
                        + Pattern.quote("}else if(D.blind.length===0){") + ".{0,150}?"
                        + Pattern.quote("전체 커버") + ".{0,150}?" + Pattern.quote("사각지대가 없습니다") + ".{0,200}?"
                        + Pattern.quote("}else{"),
                Pattern.DOTALL);
        assertTrue(chain.matcher(html).find(),
                "expected an if(nProd===0)/else-if(blind==0 → positive message)/else chain (mutual exclusivity)");
    }
}

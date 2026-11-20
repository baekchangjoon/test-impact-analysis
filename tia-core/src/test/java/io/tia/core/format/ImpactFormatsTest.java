package io.tia.core.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tia.core.filter.FilterSet;
import io.tia.core.model.Confidence;
import io.tia.core.model.ImpactedTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ImpactFormats 골든 검증 [REQ-013/015/016]. */
class ImpactFormatsTest {

    private static ImpactFormats.Payload payload() {
        List<ImpactedTest> tests = List.of(
                new ImpactedTest("io/tia/fixture/ApiSmokeTest/testPrice", Confidence.DETERMINISTIC),
                new ImpactedTest("io/tia/fixture/ApiSmokeTest/testBulk", Confidence.CONSERVATIVE));
        Map<String, List<String>> testsByFile = new LinkedHashMap<>();
        testsByFile.put("com/acme/PricingService.java", List.of("io/tia/fixture/ApiSmokeTest/testPrice"));
        testsByFile.put("com/acme/Unused.java", List.of());   // blind spot 후보
        FilterSet filters = FilterSet.of(List.of(), List.of("com/acme/gen/**"), List.of(), List.of());
        return new ImpactFormats.Payload("C0", tests, false, List.of(),
                List.of("com/acme/gen/G.java"), testsByFile, filters, 5);
    }

    @Test
    void jsonHasFixedSchemaAndReasonMapping() throws Exception {
        String json = ImpactFormats.json(payload());
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals(1, root.path("schemaVersion").asInt(), json);
        assertEquals("impact", root.path("command").asText(), json);
        assertEquals("C0", root.path("commit").asText(), json);
        assertTrue(root.path("appliedFilters").isObject(), json);

        JsonNode tests = root.path("tests");
        assertTrue(tests.isArray(), json);
        assertEquals(2, tests.size(), json);
        JsonNode t0 = tests.get(0);
        assertEquals("io/tia/fixture/ApiSmokeTest/testPrice", t0.path("id").asText(), json);
        assertEquals("DETERMINISTIC", t0.path("confidence").asText(), json);
        assertEquals("covered-line intersects diff", t0.path("reason").asText(), json);
        JsonNode t1 = tests.get(1);
        assertEquals("CONSERVATIVE", t1.path("confidence").asText(), json);
        assertEquals("conservative select-all", t1.path("reason").asText(), json);

        assertTrue(root.path("ignoredChangedFiles").isArray(), json);
        assertEquals(1, root.path("ignoredChangedFiles").size(), json);
        assertEquals("com/acme/gen/G.java", root.path("ignoredChangedFiles").get(0).asText(), json);
        assertTrue(root.path("warnings").isArray(), json);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("RPT-REQ-001: json은 totalTests/conservative/changedFiles[].file,kind,tests 를 추가로 낸다(schemaVersion 유지)")
    void jsonCarriesTotalsAndPerFileImpact() throws Exception {
        ImpactFormats.Payload base = payload();
        ImpactFormats.Payload p = new ImpactFormats.Payload(base.commit(), base.tests(), true, base.reasons(),
                base.ignoredFiles(), base.testsByFile(), base.filters(), base.totalTests(),
                List.of("com/acme/NewFeature.java"), List.of("src/main/resources/application.yml"));
        JsonNode root = new ObjectMapper().readTree(ImpactFormats.json(p));

        assertEquals(1, root.path("schemaVersion").asInt(), "additive fields only");
        assertEquals(5, root.path("totalTests").asInt());
        assertTrue(root.path("conservative").asBoolean());

        JsonNode changed = root.path("changedFiles");
        assertEquals(4, changed.size(), changed.toString());
        assertEquals("com/acme/PricingService.java", changed.get(0).path("file").asText());
        assertEquals("modified", changed.get(0).path("kind").asText());
        assertEquals("io/tia/fixture/ApiSmokeTest/testPrice", changed.get(0).path("tests").get(0).asText());
        assertEquals("modified", changed.get(1).path("kind").asText());
        assertTrue(changed.get(1).path("tests").isEmpty(), "blind spot: modified + no tests");
        assertEquals("new", changed.get(2).path("kind").asText());
        assertFalse(changed.get(2).has("tests"), "new file has no line-level mapping");
        assertEquals("unmappable", changed.get(3).path("kind").asText());

        // 기존 8-인자 형태(종류 정보 없음)도 그대로 동작한다
        JsonNode legacy = new ObjectMapper().readTree(ImpactFormats.json(base));
        assertEquals(2, legacy.path("changedFiles").size());
        assertFalse(legacy.path("conservative").asBoolean());
    }

    @Test
    void summaryPlainHasBlindSpotIgnoredCountAndNextStepsNoAnsi() {
        String out = ImpactFormats.summary(payload(), false);

        assertTrue(out.contains("영향 테스트 2/5개 선별"), "선별/전체 카운트 표기(2/5)가 있어야 함: " + out);
        assertTrue(out.toLowerCase().contains("blind spot"), out);
        assertTrue(out.contains("무시"), out);
        assertTrue(out.contains("다음"), out);
        assertFalse(out.contains("["), "no ANSI escape when ansiColor=false: " + out);
    }

    @Test
    void summaryColorEmitsAnsi() {
        String out = ImpactFormats.summary(payload(), true);
        assertTrue(out.contains("["), "ANSI escape expected when ansiColor=true: " + out);
    }

    @Test
    void markdownHasTableAndDetails() {
        String out = ImpactFormats.markdown(payload());

        assertTrue(out.contains("|"), out);
        assertTrue(out.contains("<details>"), out);
        assertTrue(out.contains("무시"), out);
        assertTrue(out.contains("io/tia/fixture/ApiSmokeTest/testPrice"), out);
        assertTrue(out.contains("io/tia/fixture/ApiSmokeTest/testBulk"), out);
    }
}

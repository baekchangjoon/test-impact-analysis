package io.tia.core.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tia.core.filter.FilterSet;
import io.tia.core.flaky.FlakyReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FlakyFormats 골든 검증 [REQ-014]. commit 개념 없음 — json에 "commit" 키가 없어야 한다. */
class FlakyFormatsTest {

    private static FlakyReport report() {
        return new FlakyReport(0.5, List.of("io/tia/fixture/ApiSmokeTest/testFlaky"), 2);
    }

    private static FilterSet filters() {
        return FilterSet.of(List.of(), List.of(), List.of(), List.of("io/tia/fixture/**/testNoise"));
    }

    @Test
    void jsonHasFixedSchemaAndNoCommitKey() throws Exception {
        String json = FlakyFormats.json(report(), filters(), List.of());
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals(1, root.path("schemaVersion").asInt(), json);
        assertEquals("flaky", root.path("command").asText(), json);
        assertTrue(root.path("appliedFilters").isObject(), json);
        assertEquals(0.5, root.path("ratio").asDouble(), 1e-9, json);
        assertEquals(2, root.path("totalTests").asInt(), json);
        assertTrue(root.path("flakyTests").isArray(), json);
        assertEquals(1, root.path("flakyTests").size(), json);
        assertEquals("io/tia/fixture/ApiSmokeTest/testFlaky", root.path("flakyTests").get(0).asText(), json);
        assertTrue(root.path("warnings").isArray(), json);

        assertFalse(root.has("commit"), json);
    }

    @Test
    void jsonCarriesWarnings() throws Exception {
        String json = FlakyFormats.json(report(), filters(), List.of("필터 적용 후 테스트가 0개 — ratio 0.0"));
        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals(1, root.path("warnings").size(), json);
        assertEquals("필터 적용 후 테스트가 0개 — ratio 0.0", root.path("warnings").get(0).asText(), json);
    }

    @Test
    void summaryPlainHasCountsAndNextStepNoAnsi() {
        String out = FlakyFormats.summary(report(), false);

        assertTrue(out.contains("io/tia/fixture/ApiSmokeTest/testFlaky"), out);
        assertTrue(out.contains("FLAKY"), out);
        assertTrue(out.contains("다음"), out);
        assertFalse(out.contains("["), "no ANSI escape when ansiColor=false: " + out);
    }

    @Test
    void summaryColorEmitsAnsi() {
        String out = FlakyFormats.summary(report(), true);
        assertTrue(out.contains("["), "ANSI escape expected when ansiColor=true: " + out);
    }

    @Test
    void markdownHasTableAndDetails() {
        String out = FlakyFormats.markdown(report());

        assertTrue(out.contains("|"), out);
        assertTrue(out.contains("<details>"), out);
        assertTrue(out.contains("io/tia/fixture/ApiSmokeTest/testFlaky"), out);
    }
}

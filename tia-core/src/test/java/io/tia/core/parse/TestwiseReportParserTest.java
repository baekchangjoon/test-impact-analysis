package io.tia.core.parse;

import io.tia.core.model.TestCoverage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestwiseReportParserTest {
    private static final String SAMPLE = """
        {"tests":[
          {"uniformPath":"io/tia/fixture/ApiSmokeTest/testPrice","result":"PASSED",
           "paths":[{"path":"io/tia/fixture","files":[
             {"fileName":"PricingService.java","coveredLines":"8-10"},
             {"fileName":"TextUtil.java","coveredLines":"6"}]}]}
        ]}""";

    @Test
    void parsesTestsFilesAndLines() {
        InputStream in = new ByteArrayInputStream(SAMPLE.getBytes(StandardCharsets.UTF_8));
        List<TestCoverage> tests = new TestwiseReportParser().parse(in);
        assertEquals(1, tests.size());
        TestCoverage t = tests.get(0);
        assertEquals("io/tia/fixture/ApiSmokeTest/testPrice", t.testId());
        assertTrue(t.linesFor("io/tia/fixture/PricingService.java").contains(9));
        assertTrue(t.covers("io/tia/fixture/TextUtil.java"));
    }

    @Test
    void parsesRealCapturedReport() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/sample-testwise.json")) {
            assertNotNull(in, "Task 4에서 캡처한 sample-testwise.json 필요");
            List<TestCoverage> tests = new TestwiseReportParser().parse(in);
            assertFalse(tests.isEmpty(), "캡처본에 최소 1개 테스트가 있어야 함");
        }
    }
}

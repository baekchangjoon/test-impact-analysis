package io.tia.core.parse;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import io.tia.core.model.TestCoverage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestwiseReportParserCompatTest {
    @Test @DisplayName("CLC-REQ-005: parser ignores new loss fields, parses existing fields")
    void ignoresNewFields() {
        String json = "{\"tests\":[{\"uniformPath\":\"T1\",\"result\":\"passed\","
                + "\"incompleteAttribution\":true,\"droppedProbes\":3,"
                + "\"paths\":[{\"path\":\"io/x\",\"files\":[{\"fileName\":\"A.java\",\"coveredLines\":\"1-3\"}]}]}]}";
        List<TestCoverage> out = new TestwiseReportParser()
                .parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, out.size());
        assertEquals("T1", out.get(0).testId());
        assertEquals("passed", out.get(0).result());
    }
}

package io.tia.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
        return new ReportBuilder.Inputs(tw, scenarios, flaky, prod, "deadbeefcafe", "acme-svc", "jacoco", null, "");
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
        var in = new ReportBuilder.Inputs(tw, null, null, null, "c", "s", "jacoco", null, "org/acme/");
        JsonNode d = om.valueToTree(new ReportBuilder().buildModel(in));
        assertEquals("…/app/X.java", d.get("perTest").get(0).get("files").get(0).get("f").asText());
    }
}

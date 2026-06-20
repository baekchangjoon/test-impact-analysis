package io.tia.core.convert;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestwiseConverterLossTest {
    private final ObjectMapper M = new ObjectMapper();

    private void exec(Path dir, String id) throws Exception {
        // minimal valid .exec via the converter's own writer is overkill; write empty file is enough for paths analysis (0 covered)
        Files.write(dir.resolve(id + ".exec"), new byte[0]);
    }
    private void sidecar(Path dir, String id, String json) throws Exception {
        Files.writeString(dir.resolve(id + ".json"), json);
    }

    @Test @DisplayName("CLC-REQ-004: incompleteAttribution+droppedProbes propagate to testwise")
    void propagatesFlags(@TempDir Path dir) throws Exception {
        exec(dir, "T1");
        sidecar(dir, "T1", "{\"result\":\"passed\",\"incompleteAttribution\":true,\"droppedProbes\":3}");
        var doc = new TestwiseConverter().convert(dir, dir /*no classes -> 0 covered, fine*/);
        var t = doc.tests().get(0);
        assertEquals(Boolean.TRUE, t.incompleteAttribution());
        assertEquals(3L, t.droppedProbes());
    }

    @Test @DisplayName("CLC-REQ-004: droppedProbes-only propagates, incompleteAttribution absent")
    void propagatesDroppedProbesOnly(@TempDir Path dir) throws Exception {
        exec(dir, "T2");
        sidecar(dir, "T2", "{\"result\":\"passed\",\"droppedProbes\":5}");
        var t = new TestwiseConverter().convert(dir, dir).tests().get(0);
        assertNull(t.incompleteAttribution());
        assertEquals(5L, t.droppedProbes());
    }

    @Test @DisplayName("CLC-REQ-004: no loss -> fields omitted from JSON (NON_NULL)")
    void omitsWhenNoLoss(@TempDir Path dir) throws Exception {
        exec(dir, "T3");
        sidecar(dir, "T3", "{\"result\":\"passed\",\"droppedProbes\":0}");
        var conv = new TestwiseConverter();
        var doc = conv.convert(dir, dir);
        Path out = dir.resolve("tw.json");
        conv.write(doc, out);
        String json = Files.readString(out);
        assertFalse(json.contains("incompleteAttribution"), json);
        assertFalse(json.contains("droppedProbes"), json);
    }

    @Test @DisplayName("CLC-REQ-001: isLoss true on flag or droppedProbes>0, false otherwise")
    void detectsLoss(@TempDir Path dir) throws Exception {
        exec(dir, "L"); sidecar(dir, "L", "{\"incompleteAttribution\":true}");
        exec(dir, "D"); sidecar(dir, "D", "{\"droppedProbes\":2}");
        exec(dir, "OK"); sidecar(dir, "OK", "{\"result\":\"passed\"}");
        var doc = new TestwiseConverter().convert(dir, dir);
        java.util.Map<String,Boolean> loss = new java.util.HashMap<>();
        for (var t : doc.tests()) loss.put(t.uniformPath(), TestwiseConverter.isLoss(t));
        assertTrue(loss.get("L")); assertTrue(loss.get("D")); assertFalse(loss.get("OK"));
    }
}

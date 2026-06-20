package io.tia.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ConvertGateTest {
    private static void exec(Path d, String id) throws Exception { Files.write(d.resolve(id+".exec"), new byte[0]); }
    private static void side(Path d, String id, String j) throws Exception { Files.writeString(d.resolve(id+".json"), j); }
    private static int run(String... args) { return new CommandLine(new ConvertCommand()).execute(args); }

    @Test @DisplayName("CLC-REQ-001: incompleteAttribution sidecar fails convert by default")
    void incompleteSidecar_failsByDefault(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"incompleteAttribution\":true}");
        int code = run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString());
        assertEquals(1, code);
        assertTrue(Files.exists(d.resolve("o.json")));   // testwise still written
    }

    @Test @DisplayName("CLC-REQ-001: droppedProbes-only sidecar fails by default")
    void droppedProbesOnly_failsByDefault(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"droppedProbes\":4}");
        assertEquals(1, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString()));
    }

    @Test @DisplayName("CLC-REQ-001: no loss -> exit 0")
    void noLoss_exit0(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"result\":\"passed\"}");
        assertEquals(0, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString()));
    }

    @Test @DisplayName("CLC-REQ-002: --allow-incomplete downgrades to warn, exit 0")
    void allowIncomplete_warnsExit0(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"incompleteAttribution\":true}");
        assertEquals(0, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString(), "--allow-incomplete"));
    }

    @Test @DisplayName("CLC-REQ-003: empty coverage passes by default")
    void empty_passesByDefault(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"result\":\"passed\"}");   // 0 covered (no classes), no loss flag
        assertEquals(0, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString()));
    }

    @Test @DisplayName("CLC-REQ-003: --fail-on-empty fails on 0-covered")
    void failOnEmpty_fails(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"result\":\"passed\"}");
        assertEquals(1, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString(), "--fail-on-empty"));
    }

    @Test @DisplayName("CLC-REQ-003: --allow-incomplete does not silence --fail-on-empty")
    void allowIncompleteWithFailOnEmpty_emptyStillFires(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"incompleteAttribution\":true}");   // also 0-covered
        assertEquals(1, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString(), "--allow-incomplete", "--fail-on-empty"));
    }
}

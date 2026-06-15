package io.tia.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-1 (design §7): drive the real {@code tia} CLI end-to-end — {@code convert} then
 * {@code report} — over a hermetic JaCoCo fixture (no Python, no petclinic). Proves the
 * wired commands produce a correct testwise.json and an equivalent self-contained report.
 */
class D0AcceptanceTest {
    private static final String FQCN = "io.tia.cli.fixture.Probe";
    private static final ObjectMapper OM = new ObjectMapper();

    private int run(String... args) {
        return new CommandLine(new TiaCommand()).execute(args);
    }

    @Test
    void convertThenReportProducesCorrectArtifacts(@TempDir Path tmp) throws Exception {
        // --- arrange: a real .exec for the executed fixture + its classfile on disk ---
        byte[] original = readResource();
        Path execDir = tmp.resolve("cov");
        Files.createDirectories(execDir);
        recordExecution(original, execDir.resolve("ProbeIT#runsElse.exec"));

        Path classesDir = tmp.resolve("classes");
        Path classFile = classesDir.resolve("io/tia/cli/fixture/Probe.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, original);

        // --- act 1: tia convert ---
        Path testwise = tmp.resolve("testwise.json");
        assertEquals(0, run("convert", "--exec-dir", execDir.toString(),
                "--classes", classesDir.toString(), "--out", testwise.toString()));

        // --- assert: convert produced covered lines, excluding the dead branch ---
        JsonNode tw = OM.readTree(testwise.toFile());
        assertEquals(1, tw.get("tests").size());
        assertEquals("ProbeIT#runsElse", tw.get("tests").get(0).get("uniformPath").asText());
        JsonNode file = tw.get("tests").get(0).get("paths").get(0).get("files").get(0);
        assertEquals("Probe.java", file.get("fileName").asText());
        assertFalse(file.get("coveredLines").asText().isEmpty(), "executed fixture has covered lines");

        // --- act 2: tia report (chains the convert output) ---
        Path html = tmp.resolve("report.html");
        assertEquals(0, run("report", "--testwise", testwise.toString(),
                "--commit", "deadbeefcafe", "--out", html.toString(), "--sut-name", "acme-svc"));

        // --- assert: equivalent self-contained report ---
        String report = Files.readString(html);
        assertTrue(report.contains("<title>TIA report · acme-svc blackbox</title>"), "SUT in title");
        assertTrue(report.contains("const D = {"), "model injected");
        assertFalse(report.contains("__DATA__"), "placeholder replaced");
        assertFalse(report.contains("__SUT__"), "placeholder replaced");
        assertEquals(1, report.split("</script>", -1).length - 1, "only the real closing script tag");

        // the report model round-trips and reflects the converted coverage
        JsonNode d = dataIsland(report);
        assertEquals(1, d.get("nTests").asInt());
        assertEquals("acme-svc", d.get("sut").asText());
        assertEquals("ProbeIT#runsElse", d.get("perTest").get(0).get("id").asText());
        assertTrue(d.get("perTest").get(0).get("lines").asInt() > 0);
    }

    @Test
    void reportDegradesGracefullyWithoutOptionalInputs(@TempDir Path tmp) throws Exception {
        Path testwise = tmp.resolve("tw.json");
        Files.writeString(testwise, """
            {"tests":[{"uniformPath":"T#m","result":"PASSED","paths":[]}]}""");
        Path html = tmp.resolve("r.html");
        // scenarios/flaky/prod omitted (the "-" sentinel) → must not crash
        assertEquals(0, run("report", "--testwise", testwise.toString(), "--commit", "c",
                "--out", html.toString(), "--scenarios", "-", "--flaky", "-", "--prod-files", "-"));
        JsonNode d = dataIsland(Files.readString(html));
        assertTrue(d.get("scenarios").isArray() && d.get("scenarios").isEmpty());
        assertTrue(d.get("flaky").isNull());
        assertEquals(0, d.get("nProd").asInt());
    }

    // ---- helpers ----

    private static JsonNode dataIsland(String html) throws Exception {
        for (String line : html.split("\n")) {
            if (line.startsWith("const D = ")) {
                String json = line.substring("const D = ".length()).trim();
                json = json.substring(0, json.lastIndexOf(';')).replace("<\\/", "</");
                return OM.readTree(json);
            }
        }
        throw new AssertionError("no `const D =` island in report");
    }

    private static byte[] readResource() throws Exception {
        try (InputStream in = D0AcceptanceTest.class.getResourceAsStream("/io/tia/cli/fixture/Probe.class")) {
            if (in == null) {
                throw new AssertionError("Probe.class not on test classpath");
            }
            return in.readAllBytes();
        }
    }

    private static void recordExecution(byte[] original, Path execFile) throws Exception {
        IRuntime runtime = new LoggerRuntime();
        byte[] instrumented = new Instrumenter(runtime).instrument(original, FQCN);
        RuntimeData data = new RuntimeData();
        runtime.startup(data);
        try {
            MemoryClassLoader loader = new MemoryClassLoader();
            loader.put(FQCN, instrumented);
            Runnable r = (Runnable) loader.loadClass(FQCN).getDeclaredConstructor().newInstance();
            r.run();
            ExecutionDataStore execStore = new ExecutionDataStore();
            SessionInfoStore sessStore = new SessionInfoStore();
            data.collect(execStore, sessStore, false);
            try (OutputStream out = Files.newOutputStream(execFile)) {
                ExecutionDataWriter writer = new ExecutionDataWriter(out);
                sessStore.accept(writer);
                execStore.accept(writer);
            }
        } finally {
            runtime.shutdown();
        }
    }

    private static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> defs = new HashMap<>();

        void put(String name, byte[] bytes) {
            defs.put(name, bytes);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = defs.get(name);
            if (bytes != null) {
                Class<?> c = defineClass(name, bytes, 0, bytes.length);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }
}

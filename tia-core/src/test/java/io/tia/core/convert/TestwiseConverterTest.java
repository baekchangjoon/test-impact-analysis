package io.tia.core.convert;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestwiseConverterTest {

    private static final String FQCN = "io.tia.core.convert.fixture.SampleTarget";
    private static final String RESOURCE = "/io/tia/core/convert/fixture/SampleTarget.class";

    // ---- compress() (pure, exact) ----
    @Test
    void compressRangesAndSortsAndDedupes() {
        assertEquals("1-3,5", TestwiseConverter.compress(Arrays.asList(1, 2, 3, 5)));
        assertEquals("1-3,5", TestwiseConverter.compress(Arrays.asList(5, 3, 1, 2, 2)));
        assertEquals("7", TestwiseConverter.compress(List.of(7)));
        assertEquals("", TestwiseConverter.compress(List.of()));
    }

    // ---- analyze(): real jacoco.core path against a hermetic .exec fixture ----
    @Test
    void analyzeReportsPartialCoverageForExecutedFixture(@TempDir Path tmp) throws Exception {
        byte[] original = readResource();
        Path execFile = tmp.resolve("SampleIT#runsElseBranch.exec");
        recordExecution(original, execFile);

        Path classesDir = tmp.resolve("classes");
        Path classFile = classesDir.resolve("io/tia/core/convert/fixture/SampleTarget.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, original);

        List<Testwise.PathCov> paths = new TestwiseConverter().analyze(execFile, classesDir);

        Testwise.PathCov pkg = paths.stream()
                .filter(p -> p.path().equals("io/tia/core/convert/fixture"))
                .findFirst().orElseThrow(() -> new AssertionError("package not found: " + paths));
        Testwise.FileCov file = pkg.files().stream()
                .filter(f -> f.fileName().equals("SampleTarget.java"))
                .findFirst().orElseThrow(() -> new AssertionError("SampleTarget.java not found"));

        TreeSet<Integer> covered = parse(file.coveredLines());
        assertFalse(covered.isEmpty(), "executed fixture must have covered lines");
        // `value = -1` (line 16) is the unreached branch → must not be covered.
        assertFalse(covered.contains(16), "unreached branch line must not be covered: " + covered);
        // run() body lines (a=1 / b=a+2 around 13-14) must be covered.
        assertTrue(covered.contains(13) || covered.contains(14),
                "executed statements must be covered: " + covered);
    }

    // ---- convert(): directory of execs → testwise document, sorted, with companion result ----
    @Test
    void convertReadsCompanionResultAndSortsTests(@TempDir Path tmp) throws Exception {
        byte[] original = readResource();
        Path classesDir = tmp.resolve("classes");
        Path classFile = classesDir.resolve("io/tia/core/convert/fixture/SampleTarget.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, original);

        Path execDir = tmp.resolve("cov");
        Files.createDirectories(execDir);
        recordExecution(original, execDir.resolve("BTest#b.exec"));
        recordExecution(original, execDir.resolve("ATest#a.exec"));
        Files.writeString(execDir.resolve("ATest#a.json"), "{\"result\":\"passed\"}");

        Testwise.Document doc = new TestwiseConverter().convert(execDir, classesDir);

        assertEquals(2, doc.tests().size());
        assertEquals("ATest#a", doc.tests().get(0).uniformPath(), "sorted by name");
        assertEquals("BTest#b", doc.tests().get(1).uniformPath());
        assertEquals("PASSED", doc.tests().get(0).result(), "companion .json result upper-cased");
        assertEquals("UNKNOWN", doc.tests().get(1).result(), "no companion → UNKNOWN");
        assertFalse(doc.tests().get(0).paths().isEmpty(), "covered fixture yields paths");
    }

    // ---- convert(): pjacoco's whole-run aggregate.exec (aggregate defaults ON) must NOT become a test ----
    @Test
    void convertSkipsPjacocoAggregateExec(@TempDir Path tmp) throws Exception {
        byte[] original = readResource();
        Path classesDir = tmp.resolve("classes");
        Path classFile = classesDir.resolve("io/tia/core/convert/fixture/SampleTarget.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, original);

        Path execDir = tmp.resolve("cov");
        Files.createDirectories(execDir);
        recordExecution(original, execDir.resolve("ATest#a.exec"));
        recordExecution(original, execDir.resolve("aggregate.exec")); // pjacoco whole-run aggregate

        Testwise.Document doc = new TestwiseConverter().convert(execDir, classesDir);

        assertEquals(1, doc.tests().size(), "aggregate.exec must be excluded: " + doc.tests());
        assertEquals("ATest#a", doc.tests().get(0).uniformPath());
        assertTrue(doc.tests().stream().noneMatch(t -> t.uniformPath().equals("aggregate")),
                "no bogus 'aggregate' test");
    }

    // ---- helpers ----

    private static byte[] readResource() throws Exception {
        try (InputStream in = TestwiseConverterTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new AssertionError("fixture class not on test classpath: " + RESOURCE);
            }
            return in.readAllBytes();
        }
    }

    /** Instrument the fixture, run it under an isolated loader, and dump a real .exec file. */
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

    private static TreeSet<Integer> parse(String ranges) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String part : ranges.split(",")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.contains("-")) {
                String[] lo = part.split("-");
                for (int n = Integer.parseInt(lo[0]); n <= Integer.parseInt(lo[1]); n++) {
                    out.add(n);
                }
            } else {
                out.add(Integer.parseInt(part));
            }
        }
        return out;
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

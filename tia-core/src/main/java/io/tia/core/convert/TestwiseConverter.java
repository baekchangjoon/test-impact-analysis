package io.tia.core.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IPackageCoverage;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * D0: ports {@code petclinic-demo/exec_to_testwise.py} to an in-process JaCoCo
 * library call. For each {@code <testId>.exec} in a directory, it analyzes the
 * production classfiles against that test's execution data and emits one testwise
 * {@code tests[]} entry (per-source-file covered lines, instruction coverage &gt; 0).
 *
 * <p>Unlike the Python bridge, this uses {@code org.jacoco.core} ({@link ExecFileLoader}
 * + {@link Analyzer}) directly — no {@code jacococli} subprocess, no {@code ~/.m2} lookup.
 */
public final class TestwiseConverter {
    private final ObjectMapper mapper = new ObjectMapper();

    /** pjacoco writes a whole-run aggregate here when its {@code aggregate} option is left ON (the
     *  default, file name {@code aggregate.exec}). It is the union of all tests, not a per-test file,
     *  so it must never be ingested as a test (it would appear to cover everything). TIA also disables
     *  it at the source via the Gradle plugin ({@code aggregate=false}); this skip is defense for runs
     *  that collected with pjacoco defaults. */
    static final String PJACOCO_AGGREGATE_EXEC = "aggregate.exec";

    /** Convert every per-test {@code *.exec} under {@code execDir} (sorted by name) into a testwise
     *  document. The pjacoco whole-run {@code aggregate.exec} (if present) is excluded. */
    public Testwise.Document convert(Path execDir, Path classesDir) throws IOException {
        List<Testwise.Test> tests = new ArrayList<>();
        try (Stream<Path> execs = Files.list(execDir)) {
            List<Path> sorted = execs
                    .filter(p -> p.getFileName().toString().endsWith(".exec"))
                    .filter(p -> !p.getFileName().toString().equals(PJACOCO_AGGREGATE_EXEC))
                    .sorted()
                    .toList();
            for (Path exec : sorted) {
                String name = exec.getFileName().toString();
                String testId = name.substring(0, name.length() - ".exec".length());
                tests.add(new Testwise.Test(testId, resultFor(exec), analyze(exec, classesDir)));
            }
        }
        return new Testwise.Document(tests);
    }

    /** Analyze one {@code .exec} against {@code classesDir}; returns covered lines grouped by package/source file. */
    public List<Testwise.PathCov> analyze(Path execFile, Path classesDir) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(execFile.toFile());
        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
        analyzer.analyzeAll(classesDir.toFile());

        IBundleCoverage bundle = builder.getBundle("tia");
        List<Testwise.PathCov> paths = new ArrayList<>();
        for (IPackageCoverage pkg : bundle.getPackages()) {
            List<Testwise.FileCov> files = new ArrayList<>();
            for (ISourceFileCoverage sf : pkg.getSourceFiles()) {
                List<Integer> covered = new ArrayList<>();
                for (int nr = sf.getFirstLine(); nr <= sf.getLastLine(); nr++) {
                    if (sf.getLine(nr).getInstructionCounter().getCoveredCount() > 0) {
                        covered.add(nr);
                    }
                }
                if (!covered.isEmpty()) {
                    files.add(new Testwise.FileCov(sf.getName(), compress(covered)));
                }
            }
            if (!files.isEmpty()) {
                paths.add(new Testwise.PathCov(pkg.getName(), files));
            }
        }
        return paths;
    }

    /** Write a testwise document to {@code out} as JSON (indented, matching the demo bridge). */
    public void write(Testwise.Document doc, Path out) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Optional companion {@code <testId>.json} carries the pass/fail result; default UNKNOWN. */
    private String resultFor(Path execFile) {
        String s = execFile.toString();
        Path companion = Path.of(s.substring(0, s.length() - ".exec".length()) + ".json");
        if (Files.exists(companion)) {
            try {
                Object r = mapper.readValue(companion.toFile(), java.util.Map.class).get("result");
                if (r != null) {
                    return r.toString().toUpperCase();
                }
            } catch (IOException ignored) {
                // fall through to UNKNOWN
            }
        }
        return "UNKNOWN";
    }

    /** {@code [1,2,3,5] -> "1-3,5"} (testwise coveredLines range syntax). */
    static String compress(Collection<Integer> input) {
        List<Integer> nums = new ArrayList<>(new TreeSet<>(input));
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < nums.size()) {
            int j = i;
            while (j + 1 < nums.size() && nums.get(j + 1) == nums.get(j) + 1) {
                j++;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(i == j ? String.valueOf(nums.get(i)) : nums.get(i) + "-" + nums.get(j));
            i = j + 1;
        }
        return sb.toString();
    }
}

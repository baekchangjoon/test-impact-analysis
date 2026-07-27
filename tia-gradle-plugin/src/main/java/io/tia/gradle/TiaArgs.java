package io.tia.gradle;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure builders for the {@code tia} CLI argument vectors. Kept free of Gradle types so the
 * argument wiring is unit-testable in isolation (the tasks just feed extension values in).
 */
final class TiaArgs {
    private TiaArgs() {}

    static List<String> index(String testwise, String repo, String commit, String db) {
        return new ArrayList<>(List.of("index", "--report", testwise, "--repo", repo,
                "--commit", commit, "--db", db));
    }

    static List<String> impact(String db, String commit, String diffFile, String gitRef, boolean strict) {
        List<String> a = new ArrayList<>(List.of("impact", "--db", db, "--commit", commit));
        if (notBlank(diffFile)) {            // D3.2: CI용 precomputed diff 오버라이드
            a.add("--diff-file");
            a.add(diffFile);
        }
        if (notBlank(gitRef)) {
            a.add("--git-ref");
            a.add(gitRef);
        }
        if (strict) {
            a.add("--strict");
        }
        return a;
    }

    static List<String> report(String testwise, String commit, String out, String sut,
                               String jacocoDir, String testSrcRoot, String prefixStrip) {
        List<String> a = new ArrayList<>(List.of("report", "--testwise", testwise,
                "--commit", commit, "--out", out, "--sut-name", sut,
                "--jacoco-dir", jacocoDir, "--prefix-strip", prefixStrip == null ? "" : prefixStrip));
        if (notBlank(testSrcRoot)) {
            a.add("--test-src-root");
            a.add(testSrcRoot);
        }
        return a;
    }

    /** D3.1: -javaagent for the per-test coverage agent — real contract verified against
     *  the agent (io.pjacoco.agent.AgentOptions): {@code destfile=<dir>} (per-test .exec output dir),
     *  {@code port=<ctrl>} (fixed control endpoint; the test driver connects via -Dpjacoco.control-url),
     *  {@code includes=<pattern>} (default {@code *}), {@code excludes=<pattern>} (default empty) —
     *  both are colon(':')-separated {@code WildcardMatcher} patterns (SP2 §3: GlobToClassPattern
     *  converts SP1 path globs into this form). The control port is FIXED (not auto/ephemeral), so for
     *  the Test-JVM attach helper, the test JVM must be single (maxParallelForks=1) — see attachCoverageAgent.
     *  Parallel test runs use a single-SUT topology (agent attached once to SUT, not to Test JVM).
     *  {@code aggregate=false}: TIA consumes per-test {@code .exec} only. pjacoco's {@code aggregate}
     *  defaults ON and would otherwise write a whole-run {@code aggregate.exec} into the same dir, which
     *  {@code tia convert} would mistake for a test covering everything (TestwiseConverter also skips it
     *  defensively, but disabling it at the source is cleaner). */
    static String coverageAgentJvmArg(String agentJarAbsPath, String destDir, int controlPort, String includes) {
        return coverageAgentJvmArg(agentJarAbsPath, destDir, controlPort, includes, null);
    }

    /** SP2-REQ-004: excludes-aware overload (SP2 §3) — the 4-arg form above delegates here with a
     *  null excludes so existing callers/tests are unaffected. */
    static String coverageAgentJvmArg(String agentJarAbsPath, String destDir, int controlPort, String includes, String excludes) {
        String inc = notBlank(includes) ? ",includes=" + includes : "";
        String exc = notBlank(excludes) ? ",excludes=" + excludes : "";
        return "-javaagent:" + agentJarAbsPath + "=destfile=" + destDir + ",port=" + controlPort + ",aggregate=false" + inc + exc;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

package io.tia.gradle;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TiaPluginTest {

    // ---- pure arg builders ----
    @org.junit.jupiter.api.Test
    void indexArgs() {
        assertEquals(List.of("index", "--report", "tw.json", "--repo", "r", "--commit", "c", "--db", "tia.db"),
                TiaArgs.index("tw.json", "r", "c", "tia.db"));
    }

    @org.junit.jupiter.api.Test
    void impactArgsVariants() {
        assertEquals(List.of("impact", "--db", "d", "--commit", "c"),
                TiaArgs.impact("d", "c", null, null, false));
        assertEquals(List.of("impact", "--db", "d", "--commit", "c", "--diff-file", "x.diff", "--git-ref", "base", "--strict"),
                TiaArgs.impact("d", "c", "x.diff", "base", true));
    }

    @org.junit.jupiter.api.Test
    void reportArgsOptionalTestSrc() {
        assertEquals(List.of("report", "--testwise", "tw.json", "--commit", "c", "--out", "r.html",
                        "--sut-name", "svc", "--jacoco-dir", "jacoco", "--prefix-strip", ""),
                TiaArgs.report("tw.json", "c", "r.html", "svc", "jacoco", null, ""));
        assertTrue(TiaArgs.report("tw.json", "c", "r.html", "svc", "jacoco", "/src/test", "p/")
                .containsAll(List.of("--test-src-root", "/src/test")));
    }

    @org.junit.jupiter.api.Test
    void coverageAgentJvmArgMatchesRealAgentContract() {
        // verified against io.pjacoco.agent.AgentOptions: destfile(dir)/port(ctrl)/includes.
        // aggregate=false: TIA consumes per-test .exec only; pjacoco's aggregate defaults ON and would
        // otherwise drop a whole-run aggregate.exec into the same dir (see TestwiseConverter skip).
        assertEquals("-javaagent:/opt/agent.jar=destfile=/tmp/cov,port=6310,aggregate=false,includes=com.acme.*",
                TiaArgs.coverageAgentJvmArg("/opt/agent.jar", "/tmp/cov", 6310, "com.acme.*"));
        assertEquals("-javaagent:/opt/agent.jar=destfile=/tmp/cov,port=6310,aggregate=false",
                TiaArgs.coverageAgentJvmArg("/opt/agent.jar", "/tmp/cov", 6310, null));
    }

    @org.junit.jupiter.api.Test
    void coverageAgentJvmArgWithExcludes() {
        // SP2-REQ-004 (TiaArgs 오버로드 부분): 5-인자 오버로드가 excludes=<v>를 방출한다.
        assertEquals("-javaagent:/opt/agent.jar=destfile=/tmp/cov,port=6310,aggregate=false,includes=com.acme.*,excludes=com.acme.Gen*",
                TiaArgs.coverageAgentJvmArg("/opt/agent.jar", "/tmp/cov", 6310, "com.acme.*", "com.acme.Gen*"));
        assertEquals("-javaagent:/opt/agent.jar=destfile=/tmp/cov,port=6310,aggregate=false,excludes=com.acme.Gen*",
                TiaArgs.coverageAgentJvmArg("/opt/agent.jar", "/tmp/cov", 6310, null, "com.acme.Gen*"));
    }

    // ---- plugin wiring (ProjectBuilder) ----
    @org.junit.jupiter.api.Test
    void registersTasksExtensionAndCliConfig() {
        Project project = ProjectBuilder.builder().withName("acme-svc").build();
        project.setVersion("9.9.9");
        project.getPlugins().apply(TiaPlugin.class);

        TiaExtension ext = project.getExtensions().getByType(TiaExtension.class);
        assertEquals("acme-svc", ext.getSutName().get(), "sut-name conventions to project name");
        assertEquals("acme-svc", ext.getRepo().get());
        assertEquals("jacoco", ext.getJacocoDir().get());
        assertFalse(ext.getStrict().get());
        assertEquals("io.tia:tia-cli:9.9.9", ext.getCliCoordinates().get(), "CLI coords default to project version");

        assertTrue(project.getConfigurations().getNames().contains("tiaCli"));
        for (String name : List.of("tiaIndex", "tiaImpact", "tiaReport")) {
            var task = project.getTasks().getByName(name);
            assertInstanceOf(JavaExec.class, task, name + " is JavaExec");
            assertEquals("io.tia.cli.Main", ((JavaExec) task).getMainClass().get());
            assertEquals("tia", task.getGroup());
        }
    }

    @org.junit.jupiter.api.Test
    void attachCoverageAgentWiresAgentControlUrlAndSingleFork() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        Test test = (Test) project.getTasks().getByName("test");
        TiaPlugin.attachCoverageAgent(test, new File("/opt/agent.jar"), new File("/tmp/cov"), 6310, "com.acme.*");
        assertTrue(test.getJvmArgs().stream()
                        .anyMatch(a -> a.equals("-javaagent:/opt/agent.jar=destfile=/tmp/cov,port=6310,aggregate=false,includes=com.acme.*")),
                "agent jvmArg: " + test.getJvmArgs());
        assertEquals("http://127.0.0.1:6310", test.getSystemProperties().get("pjacoco.control-url"));
        assertEquals(1, test.getMaxParallelForks(), "fixed control port → single fork");
    }

    @org.junit.jupiter.api.Test
    void coverageHelperPinsSingleFork() {
        Project p = ProjectBuilder.builder().build();
        Test t = p.getTasks().create("itTest", Test.class);
        TiaPlugin.attachCoverageAgent(t, new File("/opt/agent.jar"), new File("/tmp/cov"), 6310, "com.acme.*");
        assertEquals(1, t.getMaxParallelForks(),
                "내장 헬퍼는 에이전트를 Test JVM에 붙이므로 직렬 유지(병렬은 단일-SUT 토폴로지)");
        assertEquals("http://127.0.0.1:6310", t.getSystemProperties().get("pjacoco.control-url"));
    }

    // ---- SP2: tia.yml consumption (SP2-REQ-001/002) ----

    @org.junit.jupiter.api.Test
    void ymlDbSutNameConventions(@TempDir Path projectDir) throws IOException {
        gitMarker(projectDir);
        writeYml(projectDir, "version: 1\n" +
                "sut-name: yml-sut\n" +
                "db: yml.db\n");
        Project project = ProjectBuilder.builder().withName("acme-svc").withProjectDir(projectDir.toFile()).build();
        project.getPlugins().apply(TiaPlugin.class);

        TiaExtension ext = project.getExtensions().getByType(TiaExtension.class);
        assertTrue(ext.getDb().get().endsWith("yml.db"), "db from tia.yml: " + ext.getDb().get());
        assertEquals("yml-sut", ext.getSutName().get(), "sut-name from tia.yml beats project.name default");
    }

    @org.junit.jupiter.api.Test
    void dslBeatsYml(@TempDir Path projectDir) throws IOException {
        gitMarker(projectDir);
        writeYml(projectDir, "version: 1\n" +
                "sut-name: yml-sut\n" +
                "db: yml.db\n");
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPlugins().apply(TiaPlugin.class);
        TiaExtension ext = project.getExtensions().getByType(TiaExtension.class);

        // simulates `tia { db = ...; sutName = ... }` in a consumer build script — explicit .set()
        // always wins over any convention() regardless of call order (Property semantics).
        ext.getDb().set("dsl.db");
        ext.getSutName().set("dsl-sut");

        assertEquals("dsl.db", ext.getDb().get(), "DSL db beats yml db");
        assertEquals("dsl-sut", ext.getSutName().get(), "DSL sutName beats yml sut-name");
    }

    @org.junit.jupiter.api.Test
    void reqMessageMentionsYml(@TempDir Path projectDir) throws IOException {
        gitMarker(projectDir);
        // no tia.yml — db stays unresolved; testwise/commit are set so req(db) is what fails.
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPlugins().apply(TiaPlugin.class);
        TiaExtension ext = project.getExtensions().getByType(TiaExtension.class);
        ext.getTestwise().set("tw.json");
        ext.getCommit().set("c1");

        JavaExec tiaIndex = (JavaExec) project.getTasks().getByName("tiaIndex");
        GradleException ex = assertThrows(GradleException.class, () -> runDoFirstActions(tiaIndex));
        assertTrue(ex.getMessage().contains("tia.yml"), "message should mention tia.yml as alternative source: " + ex.getMessage());
    }

    @org.junit.jupiter.api.Test
    void brokenYmlFailsApply(@TempDir Path projectDir) throws IOException {
        gitMarker(projectDir);
        writeYml(projectDir, "version: 2\n"); // unsupported version -> TiaConfigException at load
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();

        GradleException ex = assertThrows(GradleException.class, () -> project.getPlugins().apply(TiaPlugin.class));
        assertTrue(causeChainContains(ex, "tia.yml"), "message chain should surface the tia.yml cause: " + ex.getMessage());
    }

    // ---- SP2: attachCoverageAgentFromConfig filter propagation (SP2-REQ-004) ----

    @org.junit.jupiter.api.Test
    void fromConfigAttachesIncludesExcludes(@TempDir Path projectDir) throws IOException {
        gitMarker(projectDir);
        writeYml(projectDir, "version: 1\n" +
                "filters:\n" +
                "  code:\n" +
                "    include: [\"com/acme/**\"]\n" +
                "    exclude: [\"com/acme/Gen1.java\", \"com/acme/Gen2.java\"]\n");
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPlugins().apply("java");
        Test test = (Test) project.getTasks().getByName("test");

        TiaPlugin.attachCoverageAgentFromConfig(project, test, new File("/opt/agent.jar"), new File("/tmp/cov"), 6310);

        String jvmArg = test.getJvmArgs().stream().filter(a -> a.startsWith("-javaagent:")).findFirst()
                .orElseThrow(() -> new AssertionError("no -javaagent jvmArg: " + test.getJvmArgs()));
        assertTrue(jvmArg.contains("includes=com.acme.*"), jvmArg);
        assertTrue(jvmArg.contains("excludes=com.acme.Gen1*:com.acme.Gen2*"), jvmArg);
    }

    @org.junit.jupiter.api.Test
    void noFiltersOmitsOptions(@TempDir Path projectDir) throws IOException {
        gitMarker(projectDir);
        writeYml(projectDir, "version: 1\n" +
                "db: yml.db\n"); // no filters section at all
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPlugins().apply("java");
        Test test = (Test) project.getTasks().getByName("test");

        TiaPlugin.attachCoverageAgentFromConfig(project, test, new File("/opt/agent.jar"), new File("/tmp/cov"), 6310);

        String jvmArg = test.getJvmArgs().stream().filter(a -> a.startsWith("-javaagent:")).findFirst()
                .orElseThrow(() -> new AssertionError("no -javaagent jvmArg: " + test.getJvmArgs()));
        assertFalse(jvmArg.contains("includes="), jvmArg);
        assertFalse(jvmArg.contains("excludes="), jvmArg);
    }

    @org.junit.jupiter.api.Test
    void absentYmlOmitsOptions(@TempDir Path projectDir) {
        gitMarker(projectDir); // tia.yml itself absent — no error, both options simply omitted
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPlugins().apply("java");
        Test test = (Test) project.getTasks().getByName("test");

        TiaPlugin.attachCoverageAgentFromConfig(project, test, new File("/opt/agent.jar"), new File("/tmp/cov"), 6310);

        String jvmArg = test.getJvmArgs().stream().filter(a -> a.startsWith("-javaagent:")).findFirst()
                .orElseThrow(() -> new AssertionError("no -javaagent jvmArg: " + test.getJvmArgs()));
        assertEquals("-javaagent:/opt/agent.jar=destfile=/tmp/cov,port=6310,aggregate=false", jvmArg);
    }

    private static void gitMarker(Path projectDir) {
        try {
            Files.createDirectories(projectDir.resolve(".git"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void writeYml(Path projectDir, String content) throws IOException {
        Files.writeString(projectDir.resolve("tia.yml"), content);
    }

    private static void runDoFirstActions(Task task) {
        for (Action<? super Task> action : task.getActions()) {
            action.execute(task);
        }
    }

    /** ProjectBuilder.apply() wraps our GradleException in a PluginApplicationException whose own
     *  message is generic ("Failed to apply plugin class ...") — walk the cause chain instead. */
    private static boolean causeChainContains(Throwable t, String needle) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null && cur.getMessage().contains(needle)) {
                return true;
            }
        }
        return false;
    }

}

package io.tia.gradle;

import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        // verified against io.pjacoco.agent.AgentOptions: destfile(dir)/port(ctrl)/includes
        assertEquals("-javaagent:/opt/agent.jar=destfile=/tmp/cov,port=6310,includes=com.acme.*",
                TiaArgs.coverageAgentJvmArg("/opt/agent.jar", "/tmp/cov", 6310, "com.acme.*"));
        assertEquals("-javaagent:/opt/agent.jar=destfile=/tmp/cov,port=6310",
                TiaArgs.coverageAgentJvmArg("/opt/agent.jar", "/tmp/cov", 6310, null));
    }

    @org.junit.jupiter.api.Test
    void teamscaleAgentJvmArgMatchesTestwiseContract() {
        // contract from scripts/run-poc.sh & docker-compose.e2e.yml
        assertEquals("-javaagent:/opt/ts.jar=mode=TESTWISE,includes=io.acme.*,http-server-port=8123,class-dir=/c,out=/o",
                TiaArgs.teamscaleAgentJvmArg("/opt/ts.jar", "/o", 8123, "/c", "io.acme.*"));
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
                        .anyMatch(a -> a.equals("-javaagent:/opt/agent.jar=destfile=/tmp/cov,port=6310,includes=com.acme.*")),
                "agent jvmArg: " + test.getJvmArgs());
        assertEquals("http://127.0.0.1:6310", test.getSystemProperties().get("pjacoco.control-url"));
        assertEquals(1, test.getMaxParallelForks(), "fixed control port → single fork");
    }

    @org.junit.jupiter.api.Test
    void attachTeamscaleAgentWiresAgentUrlAndSingleFork() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        Test test = (Test) project.getTasks().getByName("test");
        TiaPlugin.attachTeamscaleAgent(test, new File("/opt/ts.jar"), new File("/o"), 8123, new File("/c"), "io.acme.*");
        assertTrue(test.getJvmArgs().stream()
                        .anyMatch(a -> a.equals("-javaagent:/opt/ts.jar=mode=TESTWISE,includes=io.acme.*,http-server-port=8123,class-dir=/c,out=/o")),
                "teamscale jvmArg: " + test.getJvmArgs());
        assertEquals("http://localhost:8123", test.getSystemProperties().get("tia.agent.url"));
        assertEquals(1, test.getMaxParallelForks(), "single http-server-port → single fork");
    }
}

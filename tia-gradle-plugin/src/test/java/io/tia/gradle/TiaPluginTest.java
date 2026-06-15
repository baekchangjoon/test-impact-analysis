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
    void coverageAgentJvmArgUsesDynamicPort() {
        String arg = TiaArgs.coverageAgentJvmArg("/opt/agent.jar", "com.acme.*");
        assertEquals("-javaagent:/opt/agent.jar=port=0,includes=com.acme.*", arg);
        assertEquals("-javaagent:/opt/agent.jar=port=0", TiaArgs.coverageAgentJvmArg("/opt/agent.jar", null));
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
    void attachCoverageAgentAddsJvmArg() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        Test test = (Test) project.getTasks().getByName("test");
        TiaPlugin.attachCoverageAgent(test, new File("/opt/agent.jar"), "com.acme.*");
        assertTrue(test.getJvmArgs().stream()
                        .anyMatch(a -> a.equals("-javaagent:/opt/agent.jar=port=0,includes=com.acme.*")),
                "agent jvmArg attached: " + test.getJvmArgs());
    }
}

package io.tia.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DecisionChecklistDocTest {
    /** Resolve repo root by walking up until settings.gradle is found (Gradle runs tests with the subproject CWD). */
    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle")) && !Files.exists(p.resolve("settings.gradle.kts"))) {
            p = p.getParent();
        }
        assertNotNull(p, "repo root (settings.gradle) not found");
        return p;
    }
    private static String read(String rel) throws Exception { return Files.readString(repoRoot().resolve(rel)); }

    @Test @DisplayName("CLC-REQ-006: docs carry the thread-topology decision tree and gate flags")
    void docsHaveTopologyTreeAndFlags() throws Exception {
        String gs = read("GETTING-STARTED.md");
        assertTrue(gs.contains("RANDOM_PORT"), "GETTING-STARTED must mention RANDOM_PORT");
        assertTrue(gs.contains("MockMvc"), "GETTING-STARTED must mention MockMvc");
        assertTrue(gs.contains("tia.inprocess.failOnWebServer"), "GETTING-STARTED must document the fail-fast property");
        String skill = read("skills/tia/SKILL.md");
        assertTrue(skill.contains("RANDOM_PORT") || skill.contains("토폴로지") || skill.contains("topology"),
                "SKILL.md must point to the topology decision checklist");
        String readme = read("README.md");
        assertTrue(readme.contains("--allow-incomplete"), "README must document --allow-incomplete");
        assertTrue(readme.contains("--fail-on-empty"), "README must document --fail-on-empty");
    }
}

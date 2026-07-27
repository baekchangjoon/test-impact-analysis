package io.tia.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP2-REQ-007: the ONE functional (GradleRunner/TestKit) smoke in this suite — everything else is
 * ProjectBuilder (spec §4). ProjectBuilder cannot exercise real apply-time configuration-cache
 * behaviour, so this exercises the plugin actually applied to a real consumer project under
 * {@code --configuration-cache} across three phases, using {@code tia.sutName} (it always has a
 * value — the built-in {@code project.name} convention — so phase 0 with NO tia.yml at all is
 * observable without a required-property failure, unlike {@code tia.db}):
 * <ol>
 *   <li><b>absent → created</b>: no tia.yml at all → 1st build (cache stored, project-name
 *       default printed) → create tia.yml A → rebuild → A reflected. This is the gap a prior
 *       round of this test missed: registering only the file {@code TiaConfigLoader.discover}
 *       actually found means a project with NO tia.yml registers nothing, so a tia.yml created
 *       later would silently keep reusing the stale no-config cache entry.</li>
 *   <li><b>changed</b>: A → rebuild → B → rebuild, B reflected (the original regression this
 *       test was written for).</li>
 * </ol>
 * A bare exit-code-0 smoke would miss all of this cache staleness; a fake root-tia.yml-without-
 * apply setup would be a false green since the plugin was never applied — both are explicitly
 * disallowed by spec §4/REQ-007.
 */
class PluginCcSmokeFunctionalTest {

    @org.junit.jupiter.api.Test
    void ymlChangeReflectedUnderConfigurationCache(@TempDir Path consumerDir) throws IOException {
        Files.createDirectories(consumerDir.resolve(".git")); // contain tia.yml upward-search inside the temp tree

        Files.writeString(consumerDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(consumerDir.resolve("build.gradle"),
                "plugins {\n" +
                        "    id 'io.tia'\n" +
                        "}\n" +
                        "\n" +
                        "tasks.register('printSut') {\n" +
                        "    def sutProvider = tia.sutName\n" + // capture the Provider at configuration time (CC-safe)
                        "    doLast {\n" +
                        "        println('SUT=' + sutProvider.get())\n" + // .get() deferred to execution time
                        "    }\n" +
                        "}\n");

        Path tiaYml = consumerDir.resolve("tia.yml");

        // phase 0: no tia.yml at all — built-in convention (project.name = 'consumer') applies.
        BuildResult phase0 = runner(consumerDir).build();
        assertTrue(phase0.getOutput().contains("SUT=") && phase0.getOutput().contains("SUT=consumer"),
                "with no tia.yml, sutName should fall back to project.name: " + phase0.getOutput());

        // phase 1: tia.yml created for the first time — MUST invalidate the phase-0 cache entry
        // (absent -> created), not silently keep reusing the no-config default.
        Files.writeString(tiaYml, "version: 1\nsut-name: sutA\n");
        BuildResult phase1 = runner(consumerDir).build();
        assertTrue(phase1.getOutput().contains("SUT=") && phase1.getOutput().contains("SUT=sutA"),
                "after creating tia.yml, sutName must reflect it (not the stale absent-yml default): " + phase1.getOutput());

        // phase 2: tia.yml content changed — MUST invalidate again (the original A -> B regression).
        Files.writeString(tiaYml, "version: 1\nsut-name: sutB\n");
        BuildResult phase2 = runner(consumerDir).build();
        assertTrue(phase2.getOutput().contains("SUT=") && phase2.getOutput().contains("SUT=sutB"),
                "after rewriting tia.yml, sutName must reflect the new value, not a stale cached one: " + phase2.getOutput());
    }

    private static GradleRunner runner(Path consumerDir) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(consumerDir.toFile())
                // --no-watch-fs: consecutive builds in this test run back-to-back in the SAME
                // TestKit daemon with a sub-second gap between rewriting tia.yml and the next
                // build's task-graph calculation. With file-system watching on, Gradle trusts its
                // in-memory VFS snapshot and only invalidates once the OS delivers the
                // FSEvents/inotify change notification — which is asynchronous and can lag past
                // that gap, so a later build can intermittently "Reuse" the cache with a stale
                // value (observed directly: reran this test back-to-back and saw both an
                // immediate correct "Calculating task graph ... file 'tia.yml' has changed" and,
                // without this flag, an occasional stale "Reusing configuration cache" printing
                // the OLD value). Disabling watching forces a fresh, synchronous re-check of
                // tracked file inputs every build, removing the race.
                .withArguments(List.of("printSut", "--configuration-cache", "--no-watch-fs"));
    }
}

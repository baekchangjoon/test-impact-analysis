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
 * behaviour, so this exercises the plugin actually applied to a real consumer project, twice, under
 * {@code --configuration-cache}: first build stores the cache with tia.yml=A, then tia.yml is
 * rewritten to B and rebuilt — the printed db must reflect B, not a stale cached A (a bare
 * exit-code-0 smoke would miss cache staleness; a fake root-tia.yml-without-apply setup would be a
 * false green since the plugin was never applied — both are explicitly disallowed by spec §4/REQ-007).
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
                        "tasks.register('printDb') {\n" +
                        "    def dbProvider = tia.db\n" + // capture the Provider at configuration time (CC-safe)
                        "    doLast {\n" +
                        "        println('DB=' + dbProvider.get())\n" + // .get() deferred to execution time
                        "    }\n" +
                        "}\n");

        Path tiaYml = consumerDir.resolve("tia.yml");
        Files.writeString(tiaYml, "version: 1\ndb: a.db\n");

        BuildResult first = runner(consumerDir).build();
        assertTrue(first.getOutput().contains("DB=") && first.getOutput().contains("a.db"),
                "1st build should print the tia.yml=A db: " + first.getOutput());

        Files.writeString(tiaYml, "version: 1\ndb: b.db\n");

        BuildResult second = runner(consumerDir).build();
        assertTrue(second.getOutput().contains("DB=") && second.getOutput().contains("b.db"),
                "2nd build must reflect the rewritten tia.yml=B, not a stale cached A: " + second.getOutput());
    }

    private static GradleRunner runner(Path consumerDir) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(consumerDir.toFile())
                // --no-watch-fs: the two builds run back-to-back in the SAME TestKit daemon with a
                // sub-second gap between rewriting tia.yml and the next build's task-graph
                // calculation. With file-system watching on, Gradle trusts its in-memory VFS
                // snapshot and only invalidates once the OS delivers the FSEvents/inotify change
                // notification — which is asynchronous and can lag past that gap, so the 2nd build
                // intermittently "Reuse"s the cache with the stale value (observed directly: reran
                // this test back-to-back and saw both an immediate correct "Calculating task graph
                // ... file 'tia.yml' has changed" and, without this flag, an occasional stale
                // "Reusing configuration cache" printing the OLD db). Disabling watching forces a
                // fresh, synchronous re-check of tracked file inputs every build, removing the race.
                .withArguments(List.of("printDb", "--configuration-cache", "--no-watch-fs"));
    }
}

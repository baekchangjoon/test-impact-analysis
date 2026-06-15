package io.tia.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.testing.Test;

import java.io.File;

/**
 * D3: the {@code io.tia} Gradle plugin. Wires the {@code tia} CLI into the build as
 * {@code tiaIndex}/{@code tiaImpact}/{@code tiaReport} tasks (run via {@code javaexec} against
 * a resolvable {@code tiaCli} classpath), and exposes {@link #attachCoverageAgent} for D3.1
 * (injecting the per-test coverage agent into a {@code Test} task with a per-fork dynamic port).
 *
 * <p>The CLI is resolved from the {@code tiaCli} configuration (default {@code io.tia:tia-cli:<ver>},
 * overridable via {@code tia.cliCoordinates}) — no PATH/`tia` dependency.
 */
public class TiaPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        TiaExtension ext = project.getExtensions().create("tia", TiaExtension.class);
        ext.getSutName().convention(project.getName());
        ext.getRepo().convention(project.getName());
        ext.getReportOut().convention("report.html");
        ext.getJacocoDir().convention("jacoco");
        ext.getPrefixStrip().convention("");
        ext.getStrict().convention(false);
        ext.getCliCoordinates().convention("io.tia:tia-cli:" + version(project));

        Configuration cli = project.getConfigurations().create("tiaCli", c -> {
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setVisible(false);
        });
        // default dependency = the published CLI; lazily so an override of cliCoordinates wins
        cli.defaultDependencies(deps ->
                deps.add(project.getDependencies().create(ext.getCliCoordinates().get())));

        project.getTasks().register("tiaIndex", JavaExec.class, t -> {
            t.setGroup("tia");
            t.setDescription("testwise 리포트를 SQLite 스냅샷으로 인덱싱 (tia index)");
            t.setClasspath(cli);
            t.getMainClass().set("io.tia.cli.Main");
            t.doFirst(s -> t.setArgs(TiaArgs.index(
                    req(ext.getTestwise(), "testwise"), ext.getRepo().get(),
                    req(ext.getCommit(), "commit"), req(ext.getDb(), "db"))));
        });

        project.getTasks().register("tiaImpact", JavaExec.class, t -> {
            t.setGroup("tia");
            t.setDescription("변경 diff로 영향 테스트 선별 (tia impact)");
            t.setClasspath(cli);
            t.getMainClass().set("io.tia.cli.Main");
            t.doFirst(s -> t.setArgs(TiaArgs.impact(
                    req(ext.getDb(), "db"), req(ext.getCommit(), "commit"),
                    ext.getDiffFile().getOrNull(), ext.getGitRef().getOrNull(),
                    ext.getStrict().getOrElse(false))));
        });

        project.getTasks().register("tiaReport", JavaExec.class, t -> {
            t.setGroup("tia");
            t.setDescription("인터랙티브 HTML 리포트 생성 (tia report)");
            t.setClasspath(cli);
            t.getMainClass().set("io.tia.cli.Main");
            t.doFirst(s -> t.setArgs(TiaArgs.report(
                    req(ext.getTestwise(), "testwise"), req(ext.getCommit(), "commit"),
                    ext.getReportOut().get(), ext.getSutName().get(), ext.getJacocoDir().get(),
                    ext.getTestSrcRoot().getOrNull(), ext.getPrefixStrip().get())));
        });
    }

    /**
     * D3.1: attach the per-test coverage agent to a {@code Test} task. Uses {@code port=0} so each
     * forked JVM gets a free control port (no {@code BindException} at {@code maxParallelForks > 1}).
     * The agent jar itself is provided by the caller (TIA does not bundle it — §5.3).
     */
    public static void attachCoverageAgent(Test test, File agentJar, String includes) {
        test.jvmArgs(TiaArgs.coverageAgentJvmArg(agentJar.getAbsolutePath(), includes));
    }

    private static String req(Property<String> p, String name) {
        if (!p.isPresent()) {
            throw new GradleException("tia: '" + name + "' 가 필요합니다 — tia { " + name + " = ... } 설정");
        }
        return p.get();
    }

    private static String version(Project project) {
        Object v = project.getVersion();
        return (v == null || "unspecified".equals(v.toString())) ? "+" : v.toString();
    }
}

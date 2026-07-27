package io.tia.gradle;

import io.tia.core.config.TiaConfig;
import io.tia.core.config.TiaConfigException;
import io.tia.core.config.TiaConfigLoader;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.testing.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * D3: the {@code io.tia} Gradle plugin. Wires the {@code tia} CLI into the build as
 * {@code tiaIndex}/{@code tiaImpact}/{@code tiaReport} tasks (run via {@code javaexec} against
 * a resolvable {@code tiaCli} classpath), and exposes {@link #attachCoverageAgent} for D3.1
 * (injecting the per-test coverage agent into a {@code Test} task with a per-fork dynamic port).
 *
 * <p>The CLI is resolved from the {@code tiaCli} configuration (default {@code io.tia:tia-cli:<ver>},
 * overridable via {@code tia.cliCoordinates}) — no PATH/`tia` dependency.
 *
 * <p>SP2: apply-time also consumes the repo's {@code tia.yml} (project-dir-relative upward search,
 * same {@link TiaConfigLoader} SP1 uses) to seed {@code db}/{@code sutName} conventions — DSL >
 * tia.yml > built-in default (§2). A broken tia.yml fails {@code apply} fast with a
 * {@link GradleException} (SP1 fail-fast parity — the plugin is a consumer too).
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

        // SP2-REQ-001/002: MUST come after the built-in convention() calls above — convention()
        // is last-call-wins, so tia.yml values here override the project.name/etc defaults but
        // still lose to an explicit `tia { db = ... }` DSL value (Property semantics: an explicit
        // .set() always wins over any convention regardless of call order).
        applyYmlConventions(project, ext);

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
     * D3.1: attach the per-test coverage agent to a {@code Test} task (out-of-process; agent on the Test JVM — serial bridge).
     * Emits the agent's real options ({@code destfile}/{@code port}/{@code includes}), points the
     * per-test driver at the control endpoint via {@code -Dpjacoco.control-url}, and — because the
     * control port is FIXED (not ephemeral) — pins {@code maxParallelForks = 1} for this helper (Test-JVM attach).
     * Parallel test runs use a single-SUT topology (agent attached once to SUT, not to Test JVM).
     * Per-test {@code <testId>.exec} land in {@code destDir} for {@code tia convert}.
     * The agent jar is caller-provided (TIA does not bundle it — §5.3).
     */
    public static void attachCoverageAgent(Test test, File agentJar, File destDir, int controlPort, String includes) {
        test.jvmArgs(TiaArgs.coverageAgentJvmArg(agentJar.getAbsolutePath(), destDir.getAbsolutePath(), controlPort, includes));
        test.systemProperty("pjacoco.control-url", "http://127.0.0.1:" + controlPort);
        test.setMaxParallelForks(1);   // fixed control port → single fork
    }

    /**
     * SP2-REQ-004: config-driven variant of {@link #attachCoverageAgent} — (re)loads the project's
     * {@code tia.yml} (a plain, cheap reload each call; the loader is pure — no cached state, spec §2)
     * and translates {@code filters.code} into agent {@code includes}/{@code excludes} via
     * {@link GlobToClassPattern} (spec §3). An empty include/exclude list — or no tia.yml at all —
     * omits the corresponding option (agent falls back to its own default, no error). The existing
     * 5-arg {@link #attachCoverageAgent} is unchanged (explicit includes always wins, no excludes
     * propagation — callers that need excludes must migrate to this method, see README).
     */
    public static void attachCoverageAgentFromConfig(Project project, Test test, File agentJar, File destDir, int controlPort) {
        Optional<TiaConfig> cfg = loadConfig(project);
        String includes = "";
        String excludes = "";
        if (cfg.isPresent()) {
            TiaConfig.FilterLists code = cfg.get().code();
            includes = GlobToClassPattern.convertAll(code.include());
            excludes = GlobToClassPattern.convertAll(code.exclude());
        }
        test.jvmArgs(TiaArgs.coverageAgentJvmArg(agentJar.getAbsolutePath(), destDir.getAbsolutePath(), controlPort,
                includes.isEmpty() ? null : includes, excludes.isEmpty() ? null : excludes));
        test.systemProperty("pjacoco.control-url", "http://127.0.0.1:" + controlPort);
        test.setMaxParallelForks(1);   // fixed control port → single fork
    }

    private static void applyYmlConventions(Project project, TiaExtension ext) {
        loadConfig(project).ifPresent(cfg -> {
            if (cfg.db() != null) {
                ext.getDb().convention(cfg.db().toString());
            }
            if (cfg.sutName() != null) {
                ext.getSutName().convention(cfg.sutName());
            }
        });
    }

    /** SP2-REQ-002: apply-/attach-time fail-fast — a present-but-broken tia.yml fails the build. */
    private static Optional<TiaConfig> loadConfig(Project project) {
        registerConfigCacheInput(project);
        try {
            return TiaConfigLoader.load(null, project.getProjectDir().toPath());
        } catch (TiaConfigException e) {
            throw new GradleException("tia: tia.yml 로드 실패 — " + e.getMessage(), e);
        }
    }

    /**
     * SP2-REQ-007: {@link TiaConfigLoader} reads {@code tia.yml} via plain {@code java.nio.file.Files}
     * calls — Gradle's configuration-cache automatic input detection only covers a fixed set of
     * {@code java.io.File} checks (exists/isFile/length/...), NOT {@code java.nio.file}, so those
     * reads are invisible to the cache by default (confirmed by the CC functional smoke: a 2nd build
     * silently reused a stale cache after tia.yml changed). Reading the discovered file through
     * {@link org.gradle.api.provider.ProviderFactory#fileContents} is the Gradle-recognized way to
     * register an arbitrary file as a tracked configuration input — we only need the registration
     * side effect here, not the returned text.
     */
    private static void registerConfigCacheInput(Project project) {
        Path discovered = TiaConfigLoader.discover(project.getProjectDir().toPath());
        if (discovered == null) {
            return;
        }
        Provider<RegularFile> file = project.getLayout().file(project.provider(discovered::toFile));
        project.getProviders().fileContents(file).getAsText().getOrElse("");
    }

    private static String req(Property<String> p, String name) {
        if (!p.isPresent()) {
            String ymlHint = "db".equals(name) ? " (또는 레포 루트 tia.yml의 db 설정)" : "";
            throw new GradleException("tia: '" + name + "' 가 필요합니다 — tia { " + name + " = ... } 설정" + ymlHint);
        }
        return p.get();
    }

    private static String version(Project project) {
        Object v = project.getVersion();
        return (v == null || "unspecified".equals(v.toString())) ? "+" : v.toString();
    }
}

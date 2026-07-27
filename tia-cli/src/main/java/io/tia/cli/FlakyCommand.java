package io.tia.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tia.core.config.TiaConfig;
import io.tia.core.config.TiaConfigException;
import io.tia.core.filter.FilterSet;
import io.tia.core.flaky.FlakyAnalyzer;
import io.tia.core.flaky.FlakyReport;
import io.tia.core.flaky.RunResult;
import io.tia.core.format.FlakyFormats;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "flaky", description = "N회 실행 결과(run-result JSON)로 플레이키 비율 측정")
public class FlakyCommand implements Callable<Integer> {
    @Mixin ConfigMixin configMixin;
    @Mixin TestFilterMixin testFilter;   // code 필터 믹스인 없음 — 설계 §2 표 [REQ-022]

    @Option(names = "--runs", required = true, split = ",",
            description = "run-result JSON 파일들 (쉼표 구분)") List<Path> runs;
    @Option(names = "--format", defaultValue = "text",
            description = "출력 형식: ${COMPLETION-CANDIDATES} (기본 text = 기존 출력)") OutputFormat format;

    @Override public Integer call() throws Exception {
        TiaConfig cfg;
        FilterSet filters;
        try {
            cfg = configMixin.loadConfig();
            filters = ConfigMixin.filtersOf(cfg, null, testFilter);
        } catch (TiaConfigException e) {   // [REQ-003], ImpactCommand와 동일 패턴
            System.err.println("ERROR: " + e.getMessage());
            return 1;
        }

        ObjectMapper mapper = new ObjectMapper();
        List<RunResult> parsed = new ArrayList<>();
        for (Path p : runs) {
            JsonNode results = mapper.readTree(Files.readAllBytes(p)).path("results");
            Map<String, Boolean> m = new LinkedHashMap<>();
            results.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asBoolean()));
            parsed.add(new RunResult(m));
        }

        // 집계 '전' 필터 [REQ-010]: 제외된 테스트는 분모·분자 계산에서 완전히 빠진다.
        List<RunResult> filteredRuns = parsed.stream()
                .map(r -> new RunResult(r.passedByTest().entrySet().stream()
                        .filter(e -> filters.acceptsTest(e.getKey()))
                        .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll)))
                .toList();

        FlakyReport r = new FlakyAnalyzer().aggregate(filteredRuns);
        List<String> warnings = new ArrayList<>();
        if (r.totalTests() == 0 && filters.hasTestFilters()) {
            String warn = "필터 적용 후 테스트가 0개 — ratio 0.0";
            System.err.println("WARN: " + warn);   // [REQ-010]
            warnings.add(warn);
        }

        if (format == OutputFormat.text) {   // 기존 출력 경로 그대로 — 바이트 동일 [REQ-012]
            System.out.printf("flaky ratio: %.3f (%d/%d)%n", r.ratio(), r.flakyTests().size(), r.totalTests());
            for (String t : r.flakyTests()) System.out.println("FLAKY\t" + t);
            return 0;
        }

        System.out.println(render(format, r, filters, warnings));
        return 0;
    }

    /** ansiColor는 TTY로 실행될 때만 true(파이프/CI/E2E는 항상 false) [FU-REQ-001]. */
    private static String render(OutputFormat format, FlakyReport r, FilterSet filters, List<String> warnings) {
        boolean ansiColor = Tty.interactive() && System.getenv("NO_COLOR") == null;
        return switch (format) {
            case json -> FlakyFormats.json(r, filters, warnings);
            case summary -> FlakyFormats.summary(r, ansiColor);
            case markdown -> FlakyFormats.markdown(r);
            case text -> throw new IllegalStateException("text는 호출측에서 분기 처리됨");
        };
    }
}

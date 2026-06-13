package io.tia.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tia.core.flaky.FlakyAnalyzer;
import io.tia.core.flaky.FlakyReport;
import io.tia.core.flaky.RunResult;
import picocli.CommandLine.Command;
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
    @Option(names = "--runs", required = true, split = ",",
            description = "run-result JSON 파일들 (쉼표 구분)") List<Path> runs;

    @Override public Integer call() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<RunResult> parsed = new ArrayList<>();
        for (Path p : runs) {
            JsonNode results = mapper.readTree(Files.readAllBytes(p)).path("results");
            Map<String, Boolean> m = new LinkedHashMap<>();
            results.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asBoolean()));
            parsed.add(new RunResult(m));
        }
        FlakyReport r = new FlakyAnalyzer().aggregate(parsed);
        System.out.printf("flaky ratio: %.3f (%d/%d)%n", r.ratio(), r.flakyTests().size(), r.totalTests());
        for (String t : r.flakyTests()) System.out.println("FLAKY\t" + t);
        return 0;
    }
}

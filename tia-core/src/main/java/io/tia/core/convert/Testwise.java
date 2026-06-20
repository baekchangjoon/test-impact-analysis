package io.tia.core.convert;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Jackson-serializable testwise.json model — the contract consumed by {@code tia index}
 * (parser: {@code io.tia.core.parse.TestwiseReportParser}). Record component names map
 * 1:1 to the JSON keys: {@code tests / uniformPath / result / paths / path / files /
 * fileName / coveredLines}.
 */
public final class Testwise {
    private Testwise() {}

    public record Document(List<Test> tests) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Test(String uniformPath, String result, List<PathCov> paths,
                       Boolean incompleteAttribution, Long droppedProbes) {}

    public record PathCov(String path, List<FileCov> files) {}

    public record FileCov(String fileName, String coveredLines) {}
}

package io.tia.core.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tia.core.model.TestCoverage;
import io.tia.core.path.PathNormalizer;
import org.roaringbitmap.RoaringBitmap;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TestwiseReportParser {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<TestCoverage> parse(InputStream json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<TestCoverage> out = new ArrayList<>();
            for (JsonNode test : root.path("tests")) {
                String id = test.path("uniformPath").asText();
                String result = test.path("result").asText("UNKNOWN");
                Map<String, RoaringBitmap> byFile = new LinkedHashMap<>();
                for (JsonNode path : test.path("paths")) {
                    String dir = path.path("path").asText("");
                    for (JsonNode file : path.path("files")) {
                        String fn = file.path("fileName").asText();
                        String full = PathNormalizer.canonical(dir.isEmpty() ? fn : dir + "/" + fn);  // 정규형 키
                        RoaringBitmap lines = LineRangeParser.parse(file.path("coveredLines").asText(""));
                        byFile.merge(full, lines, (a, b) -> { a.or(b); return a; });
                    }
                }
                out.add(new TestCoverage(id, result, byFile));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

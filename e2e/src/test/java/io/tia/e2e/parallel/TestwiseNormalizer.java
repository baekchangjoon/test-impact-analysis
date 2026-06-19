package io.tia.e2e.parallel;

import io.tia.core.model.TestCoverage;
import io.tia.core.parse.TestwiseReportParser;
import org.roaringbitmap.RoaringBitmap;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** testwise JSON → {@code Map<testId, Map<fileKey, RoaringBitmap>>}. 직렬/병렬 산출물 동등 비교용 공유 유틸. */
final class TestwiseNormalizer {
    private TestwiseNormalizer() {}

    static Map<String, Map<String, RoaringBitmap>> normalize(Path testwiseJson) throws Exception {
        try (InputStream in = Files.newInputStream(testwiseJson)) {
            List<TestCoverage> tests = new TestwiseReportParser().parse(in);
            Map<String, Map<String, RoaringBitmap>> out = new LinkedHashMap<>();
            for (TestCoverage t : tests) {
                Map<String, RoaringBitmap> byFile = new LinkedHashMap<>(t.linesByFile());
                out.put(t.testId(), byFile);
            }
            return out;
        }
    }
}

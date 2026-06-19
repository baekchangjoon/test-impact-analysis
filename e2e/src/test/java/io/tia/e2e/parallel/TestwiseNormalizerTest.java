package io.tia.e2e.parallel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestwiseNormalizerTest {
    @Test
    void normalizesTestwiseToTestFileLineMap(@TempDir Path tmp) throws Exception {
        Path j = tmp.resolve("tw.json");
        Files.writeString(j, "{\"tests\":[{\"uniformPath\":\"ApiIT#a\",\"result\":\"PASSED\","
            + "\"paths\":[{\"path\":\"io/tia/fixture\",\"files\":[{\"fileName\":\"PricingService.java\",\"coveredLines\":\"6-8\"}]}]}]}");
        Map<String, Map<String, RoaringBitmap>> m = TestwiseNormalizer.normalize(j);
        RoaringBitmap expected = RoaringBitmap.bitmapOf(6, 7, 8);
        assertEquals(Map.of("ApiIT#a", Map.of("io/tia/fixture/PricingService.java", expected)), m);
    }
}

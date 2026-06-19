package io.tia.e2e.inprocess;

import io.tia.e2e.parallel.TestwiseNormalizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** in-process per-test 수집이 정확하고 병렬==직렬임을 검증. 산출물은 run-inprocess-e2e.sh가 생성. */
@Tag("inprocess-e2e")
public class InProcessCollectionE2E {
    static Path dir;
    @BeforeAll static void locate() {
        String d = System.getProperty("tia.inprocess.artifacts");
        assumeTrue(d != null && Files.isDirectory(Path.of(d)),
                "tia.inprocess.artifacts 미설정 — scripts/run-inprocess-e2e.sh 로 산출 후 실행");
        dir = Path.of(d);
    }
    private static Map<String, Map<String, RoaringBitmap>> tw(String n) throws Exception {
        return TestwiseNormalizer.normalize(dir.resolve(n));
    }

    @Test @DisplayName("REQ-001: in-JVM per-test 귀속 — greeting/price 테스트가 각자 서비스 파일을 커버")
    void serialPerTestAttribution() throws Exception {
        Map<String, Map<String, RoaringBitmap>> m = tw("testwise_serial.json");
        assertEquals(8, m.size(), "8개 테스트");
        m.forEach((id, files) -> {
            String joined = String.join(",", files.keySet());
            if (id.contains("GreetingInProcessIT")) assertTrue(joined.contains("GreetingService.java"), id + " → " + joined);
            if (id.contains("PriceInProcessIT")) assertTrue(joined.contains("PricingService.java"), id + " → " + joined);
        });
    }

    @Test @DisplayName("REQ-002: forks 병렬 수집이 직렬과 per-test 동일")
    void forksMatchesSerial() throws Exception { assertEquals(tw("testwise_serial.json"), tw("testwise_forks.json")); }

    @Test @DisplayName("REQ-003: in-JVM 병렬 수집이 직렬과 per-test 동일")
    void inJvmMatchesSerial() throws Exception { assertEquals(tw("testwise_serial.json"), tw("testwise_injvm.json")); }

    @Test @DisplayName("REQ-004: 모든 병렬 산출 테스트의 커버리지가 non-empty")
    void everyTestHasNonEmptyCoverage() throws Exception {
        for (String f : new String[]{"testwise_forks.json", "testwise_injvm.json"}) {
            Map<String, Map<String, RoaringBitmap>> m = tw(f);
            assertFalse(m.isEmpty(), f);
            m.forEach((id, files) -> assertTrue(
                    files.values().stream().mapToLong(RoaringBitmap::getLongCardinality).sum() > 0,
                    f + " / " + id + ": 커버 라인 0"));
        }
    }

    @Test @DisplayName("REQ-005: in-JVM 모드가 실제 동시 실행됨(겹침≥1) + 직렬은 겹침 0")
    void inJvmRunsConcurrently() throws Exception {
        assertTrue(OverlapProbeAnalyzer.overlappingPairs(dir.resolve("overlap_injvm.csv")) >= 1, "in-JVM 동시 실행 미관측");
        assertEquals(0, OverlapProbeAnalyzer.overlappingPairs(dir.resolve("overlap_serial.csv")), "직렬인데 겹침 발생");
    }
}

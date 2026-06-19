package io.tia.e2e.parallel;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** out-of-process 병렬 수집이 직렬과 동일함을 검증. 산출물(testwise_*.json/timings.json)은 오케스트레이터가 생성. */
@Tag("parallel-e2e")
class ParallelCollectionE2E {
    static Path dir;
    @BeforeAll static void locate() {
        String d = System.getProperty("tia.parallel.artifacts");
        assumeTrue(d != null && Files.isDirectory(Path.of(d)),
                "tia.parallel.artifacts 미설정 — scripts/run-parallel-e2e.sh 로 산출 후 실행");
        dir = Path.of(d);
    }

    private static Map<String, Map<String, RoaringBitmap>> tw(String name) throws Exception {
        return TestwiseNormalizer.normalize(dir.resolve(name));
    }

    @Test @DisplayName("REQ-001: forks 병렬 수집이 직렬과 per-test 동일")
    void forksParallelMatchesSerial() throws Exception {
        assertEquals(tw("testwise_serial.json"), tw("testwise_forks.json"));
    }

    @Test @DisplayName("REQ-002: in-JVM 병렬 수집이 직렬과 per-test 동일")
    void inJvmParallelMatchesSerial() throws Exception {
        assertEquals(tw("testwise_serial.json"), tw("testwise_injvm.json"));
    }

    @Test @DisplayName("REQ-003: 모든 병렬 산출 테스트의 커버리지가 non-empty")
    void everyTestHasNonEmptyCoverage() throws Exception {
        for (String f : new String[]{"testwise_forks.json", "testwise_injvm.json"}) {
            Map<String, Map<String, RoaringBitmap>> m = tw(f);
            assertFalse(m.isEmpty(), f + ": 테스트 0건");
            m.forEach((id, files) -> {
                long covered = files.values().stream().mapToLong(RoaringBitmap::getLongCardinality).sum();
                assertTrue(covered > 0, f + " / " + id + ": 커버 라인 0 (baggage 전파 실패 의심)");
            });
        }
    }

    @Test @DisplayName("REQ-004/009: 세 모드 벽시계 기록 존재 + 동일 테스트 집합")
    void recordsWallClockPerMode() throws Exception {
        String timings = Files.readString(dir.resolve("timings.json"));
        for (String mode : new String[]{"serial", "forks", "injvm"}) {
            assertTrue(timings.contains("\"" + mode + "\""), "timings.json 에 " + mode + " 누락");
        }
        assertEquals(tw("testwise_serial.json").keySet(), tw("testwise_forks.json").keySet());
        assertEquals(tw("testwise_serial.json").keySet(), tw("testwise_injvm.json").keySet());
    }

    @Test @DisplayName("REQ-010: 병렬 모드가 실제로 동시 실행됨")
    void parallelModesRunConcurrently() throws Exception {
        String json = Files.readString(dir.resolve("concurrency.json"));
        int serial = extractInt(json, "serial");
        int forks  = extractInt(json, "forks");
        int injvm  = extractInt(json, "injvm");
        assertEquals(1, serial, "serial maxConcurrent == 1 (직렬 검증)");
        assertTrue(forks >= 2, "forks maxConcurrent >= 2 (실제 포크 병렬 확인), 실제값=" + forks);
        assertTrue(injvm >= 2, "injvm maxConcurrent >= 2 (실제 in-JVM 병렬 확인), 실제값=" + injvm);
    }

    private static int extractInt(String json, String key) {
        // matches "key": N
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) return Integer.parseInt(m.group(1));
        throw new IllegalArgumentException("concurrency.json에 '" + key + "' 없음: " + json);
    }
}

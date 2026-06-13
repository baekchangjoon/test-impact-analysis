package io.tia.core.flaky;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlakyAnalyzerTest {
    @Test
    void detectsTestWithBothPassAndFail() {
        List<RunResult> runs = List.of(
            new RunResult(Map.of("T_ok", true,  "T_flaky", true)),
            new RunResult(Map.of("T_ok", true,  "T_flaky", false)),
            new RunResult(Map.of("T_ok", true,  "T_flaky", true)));
        FlakyReport r = new FlakyAnalyzer().aggregate(runs);
        assertEquals(List.of("T_flaky"), r.flakyTests());
        assertEquals(2, r.totalTests());
        assertEquals(0.5, r.ratio(), 1e-9);   // 2개 중 1개
    }
}

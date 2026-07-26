package io.tia.e2e.filter;

import io.tia.cli.TiaCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP1 아우터 루프 — flaky test 필터는 run-result 집계 전에 적용되고, ratio·totalTests는 필터 후
 * 집합 기준으로 재계산된다(REQ-010). 전체 제외 시 0-나눗셈/NaN 없이 ratio 0.0 + stderr 경고 + exit 0.
 * Task 4 시점에는 --config가 배선되지 않아 전부 red가 정상.
 */
@Execution(ExecutionMode.SAME_THREAD)
class FlakyFilterE2ETest {

    @TempDir Path work;

    @Test
    @DisplayName("REQ-010: 집계 전 필터 적용 — flaky 제외 테스트는 목록에서 빠지고 분모가 재계산된다")
    void excludedBeforeAggregation() throws Exception {
        Path r1 = work.resolve("run1.json"); Files.writeString(r1, "{\"results\":{\"T_ok\":true,\"T_flaky\":true}}");
        Path r2 = work.resolve("run2.json"); Files.writeString(r2, "{\"results\":{\"T_ok\":true,\"T_flaky\":false}}");
        Path yml = writeYml("""
                version: 1
                filters:
                  test:
                    exclude: ["T_flaky"]
                """);
        Exec r = run("flaky", "--runs", r1 + "," + r2, "--config", yml.toString());
        assertEquals(0, r.code(), r.err());
        assertFalse(r.out().contains("T_flaky"), r.out());
        assertTrue(r.out().contains("flaky ratio: 0.000 (0/1)"), r.out());   // T_ok만 남은 분모=1, 플레이키=0
    }

    @Test
    @DisplayName("REQ-010: 전체 제외 → ratio 0.000 (0/0) + stderr 경고 + exit 0(0-나눗셈/NaN 없음)")
    void allExcludedRatioZero() throws Exception {
        Path r1 = work.resolve("run1.json"); Files.writeString(r1, "{\"results\":{\"T_flaky\":true}}");
        Path r2 = work.resolve("run2.json"); Files.writeString(r2, "{\"results\":{\"T_flaky\":false}}");
        Path yml = writeYml("""
                version: 1
                filters:
                  test:
                    exclude: ["T_flaky"]
                """);
        Exec r = run("flaky", "--runs", r1 + "," + r2, "--config", yml.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("flaky ratio: 0.000 (0/0)"), r.out());
        assertTrue(r.err().toUpperCase().contains("WARN"), r.err());
    }

    // ---- 공통 헬퍼 (SpecAcceptanceE2ETest 패턴 복사) ----

    record Exec(int code, String out, String err) {}

    static Exec run(String... args) {
        PrintStream oo = System.out, oe = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new TiaCommand()).execute(args);
            return new Exec(code, bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(oo); System.setErr(oe); }
    }

    Path writeYml(String yaml) throws IOException {
        Path f = work.resolve("tia.yml");
        Files.writeString(f, yaml);
        return f;
    }
}

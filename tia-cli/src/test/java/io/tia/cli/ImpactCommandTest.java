package io.tia.cli;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.TestCoverage;
import io.tia.core.store.CoverageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactCommandTest {
    @Test
    void printsOnlyImpactedTests(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "c0", List.of(
                new TestCoverage("T_price", "PASSED",
                    Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10))),
                new TestCoverage("T_greet", "PASSED",
                    Map.of("io/tia/fixture/GreetingService.java", RoaringBitmap.bitmapOf(6, 7))))));
        }
        Path diff = dir.resolve("d.diff");
        Files.writeString(diff, """
            diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
            --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
            +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
            @@ -8,1 +8,1 @@
            -    return key.length() * 100;
            +    return key.length() * 200;
            """);   // 레포 상대 경로 → PathNormalizer가 커버리지 키(io/tia/fixture/...)와 교차되게 정규화

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out; System.setOut(new PrintStream(out));
        int code = new CommandLine(new TiaCommand()).execute(
            "impact", "--db", db.toString(), "--commit", "c0", "--diff-file", diff.toString());
        System.setOut(prev);

        assertEquals(0, code);
        String printed = out.toString();
        assertTrue(printed.contains("T_price"), printed);
        assertFalse(printed.contains("T_greet"), printed);
        assertTrue(printed.contains("DETERMINISTIC"), printed);
    }
}

package io.tia.core.store;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.TestCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoverageStoreTest {
    @Test
    void savesAndLoadsSnapshotByCommit(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        TestCoverage t = new TestCoverage("T1", "PASSED",
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10)));
        CoverageSnapshot snap = new CoverageSnapshot("fixture", "abc123", List.of(t));

        try (CoverageStore store = new CoverageStore(db)) {
            store.save(snap);
            CoverageSnapshot loaded = store.load("abc123");
            assertEquals("fixture", loaded.repo());
            assertEquals(1, loaded.tests().size());
            assertEquals(RoaringBitmap.bitmapOf(8, 9, 10),
                loaded.tests().get(0).linesFor("io/tia/fixture/PricingService.java"));
        }
    }
}

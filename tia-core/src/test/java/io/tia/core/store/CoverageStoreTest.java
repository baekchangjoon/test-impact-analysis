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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

class CoverageStoreTest {
    @Test
    @DisplayName("REQ-001/003: 같은 commit의 disjoint 2 build를 모두 병합 로드하고 build 수는 2")
    void mergesDisjointBuildsForSameCommit(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("repoA", "c1", List.of(new TestCoverage("modA.T1", "PASSED",
                Map.of("io/tia/a/A.java", RoaringBitmap.bitmapOf(1, 2))))));
            store.save(new CoverageSnapshot("repoB", "c1", List.of(new TestCoverage("modB.T2", "PASSED",
                Map.of("io/tia/b/B.java", RoaringBitmap.bitmapOf(3, 4))))));

            CoverageSnapshot loaded = store.load("c1");
            assertEquals(2, loaded.tests().size(), "두 build의 테스트가 모두 보여야 함");
            assertTrue(loaded.tests().stream().anyMatch(t -> t.testId().equals("modA.T1")));
            assertTrue(loaded.tests().stream().anyMatch(t -> t.testId().equals("modB.T2")));
            assertEquals(2, store.distinctBuildCount("c1"));
        }
    }

    @Test
    @DisplayName("REQ-002: 같은 test_id 재인덱싱 시 최신 build가 옛 라인을 대체(stale 제거)")
    void reindexLatestBuildWinsPerTestId(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("r", "c1", List.of(new TestCoverage("T1", "PASSED",
                Map.of("io/tia/X.java", RoaringBitmap.bitmapOf(1, 2))))));
            store.save(new CoverageSnapshot("r", "c1", List.of(new TestCoverage("T1", "FAILED",
                Map.of("io/tia/X.java", RoaringBitmap.bitmapOf(2, 3))))));

            CoverageSnapshot loaded = store.load("c1");
            assertEquals(1, loaded.tests().size());
            TestCoverage t1 = loaded.tests().get(0);
            assertEquals(RoaringBitmap.bitmapOf(2, 3), t1.linesFor("io/tia/X.java"), "최신 {2,3}만, {1} 없음");
            assertEquals("FAILED", t1.result(), "result도 최신 build 값");
        }
    }

    @Test
    @DisplayName("REQ-005: 단일 build 로드 결과·카운트(1) 회귀 없음")
    void singleBuildUnchanged(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "c0", List.of(new TestCoverage("T", "PASSED",
                Map.of("io/tia/A.java", RoaringBitmap.bitmapOf(5))))));
            CoverageSnapshot loaded = store.load("c0");
            assertEquals(1, loaded.tests().size());
            assertEquals(RoaringBitmap.bitmapOf(5), loaded.tests().get(0).linesFor("io/tia/A.java"));
            assertEquals(1, store.distinctBuildCount("c0"));
            assertEquals(0, store.distinctBuildCount("nope"));
        }
    }

    @Test
    @DisplayName("REQ-009: 생성자가 부모 디렉터리를 자동 생성")
    void constructorCreatesParentDirs(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("nested").resolve("sub").resolve("tia.db");
        assertFalse(java.nio.file.Files.exists(db.getParent()));
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("r", "c", List.of(new TestCoverage("T", "PASSED",
                Map.of("io/tia/A.java", RoaringBitmap.bitmapOf(1))))));
        }
        assertTrue(java.nio.file.Files.exists(db));
    }

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

package io.tia.core.store;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.TestCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    @DisplayName("FU-REQ-003: 쓰기 오픈은 busy_timeout=5000·journal_mode=wal을 적용한다")
    void writeOpenAppliesPragmas(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            assertEquals(5000, store.busyTimeoutMillis());
            assertEquals("wal", store.journalMode());
        }
    }

    @Test
    @DisplayName("FU-REQ-003: 읽기 오픈은 busy_timeout=5000이되 기존 non-WAL DB의 journal_mode를 바꾸지 않는다(doctor 불변식)")
    void readOpenKeepsJournalMode(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        // 기존 non-WAL DB 시뮬레이션 — CoverageStore를 거치지 않고 직접 JDBC로 파일만 만든다
        // (SQLite 기본 journal_mode는 'delete').
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            raw.createStatement().execute("CREATE TABLE dummy(x INTEGER)");
        }

        try (CoverageStore store = CoverageStore.openRead(db)) {
            assertEquals(5000, store.busyTimeoutMillis());
            assertEquals("delete", store.journalMode(), "읽기 오픈이 기존 DB의 journal_mode를 바꾸면 안 됨(doctor 불변식)");
        }
    }

    @Test
    @DisplayName("FU-REQ-003: save() 도중 예외가 나면 builds/coverage 모두 롤백된다(원자성)")
    void saveIsAtomic(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            Map<String, RoaringBitmap> broken = new HashMap<>();
            broken.put("io/tia/Broken.java", null);   // toBytes(null) → NPE로 결함 주입

            CoverageSnapshot snap = new CoverageSnapshot("r", "c-atomic",
                List.of(new TestCoverage("T", "PASSED", broken)));

            assertThrows(RuntimeException.class, () -> store.save(snap));
            assertEquals(0, store.distinctBuildCount("c-atomic"),
                "실패한 save()의 builds 행이 남아있으면 안 됨(트랜잭션 미롤백)");
        }
    }

    @Test
    @DisplayName("FU-REQ-003 fix round1: 읽기 오픈은 DB 파일이 없으면 파일·부모 디렉터리를 생성하지 않고 빈 스토어로 수렴한다")
    void readOpenOnMissingFileDoesNotCreateAnything(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("nope").resolve("tia.db");
        assertFalse(Files.exists(db.getParent()), "사전조건: 부모 디렉터리도 아직 없어야 함");

        try (CoverageStore store = CoverageStore.openRead(db)) {
            assertEquals(0, store.distinctBuildCount("c1"), "빈 스토어는 build 수 0");
            assertTrue(store.load("c1").tests().isEmpty(), "빈 스토어는 load()가 빈 스냅샷");
        }

        assertFalse(Files.exists(db), "읽기 오픈이 DB 파일을 생성하면 안 됨(무생성 불변식)");
        assertFalse(Files.exists(db.getParent()), "읽기 오픈이 부모 디렉터리를 생성하면 안 됨(부작용 없음)");
    }

    @Test
    @DisplayName("FU-REQ-003 fix round1: 읽기 오픈은 스키마 없는 기존 DB 파일 바이트를 변형하지 않고 빈 스토어로 읽는다")
    void readOpenOnSchemaLessFileDoesNotMutateBytesAndReadsEmpty(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            raw.createStatement().execute("CREATE TABLE dummy(x INTEGER)");
        }
        byte[] before = Files.readAllBytes(db);

        try (CoverageStore store = CoverageStore.openRead(db)) {
            assertEquals(0, store.distinctBuildCount("c1"), "builds 테이블이 없으면 빈 스토어 취급");
            assertTrue(store.load("c1").tests().isEmpty(), "builds 테이블이 없으면 load()도 빈 스냅샷");
        }

        byte[] after = Files.readAllBytes(db);
        assertArrayEquals(before, after,
            "읽기 오픈이 스키마 없는 기존 DB 파일의 바이트를 변형하면 안 됨(스키마 자동 생성 금지)");
    }
}

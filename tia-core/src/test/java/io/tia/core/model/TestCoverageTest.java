package io.tia.core.model;

import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCoverageTest {
    @Test
    void linesFor_returnsBitmap_andEmptyWhenAbsent() {
        RoaringBitmap b = RoaringBitmap.bitmapOf(10, 11, 12);
        TestCoverage tc = new TestCoverage("T1", "PASSED", Map.of("A.java", b));
        assertTrue(tc.linesFor("A.java").contains(11));
        assertTrue(tc.covers("A.java"));
        assertFalse(tc.covers("B.java"));
        assertTrue(tc.linesFor("B.java").isEmpty());
    }
}

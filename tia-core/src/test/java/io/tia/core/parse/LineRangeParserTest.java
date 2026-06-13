package io.tia.core.parse;

import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineRangeParserTest {
    @Test
    void parsesRangesAndSingletons() {
        RoaringBitmap b = LineRangeParser.parse("10-12, 20 ,25-25");
        assertEquals(RoaringBitmap.bitmapOf(10, 11, 12, 20, 25), b);
    }

    @Test
    void blankYieldsEmpty() {
        assertTrue(LineRangeParser.parse("").isEmpty());
        assertTrue(LineRangeParser.parse(null).isEmpty());
    }
}

package io.tia.core.model;

import org.roaringbitmap.RoaringBitmap;

import java.util.Map;

public record TestCoverage(String testId, String result, Map<String, RoaringBitmap> linesByFile) {
    public RoaringBitmap linesFor(String file) {
        return linesByFile.getOrDefault(file, new RoaringBitmap());
    }

    public boolean covers(String file) {
        RoaringBitmap b = linesByFile.get(file);
        return b != null && !b.isEmpty();
    }
}

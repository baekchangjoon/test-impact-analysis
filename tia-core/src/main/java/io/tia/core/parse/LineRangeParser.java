package io.tia.core.parse;

import org.roaringbitmap.RoaringBitmap;

public final class LineRangeParser {
    private LineRangeParser() {}

    /** "10-12,20" 형태(teamscale coveredLines)를 RoaringBitmap으로. */
    public static RoaringBitmap parse(String ranges) {
        RoaringBitmap b = new RoaringBitmap();
        if (ranges == null || ranges.trim().isEmpty()) return b;
        for (String part : ranges.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int dash = p.indexOf('-');
            if (dash < 0) {
                b.add(Integer.parseInt(p));
            } else {
                int lo = Integer.parseInt(p.substring(0, dash).trim());
                int hi = Integer.parseInt(p.substring(dash + 1).trim());
                b.add((long) lo, (long) hi + 1);   // add(start,end)은 [start,end)
            }
        }
        return b;
    }
}

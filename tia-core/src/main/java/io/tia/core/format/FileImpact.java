package io.tia.core.format;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.DiffSummary;
import io.tia.core.model.TestCoverage;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 변경 파일 → 그 변경 라인을 커버하는 테스트 목록(빈 리스트 = blind spot 후보). */
public final class FileImpact {
    private FileImpact() {}

    public static Map<String, List<String>> testsByChangedFile(CoverageSnapshot snap, DiffSummary diff) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        diff.changedOldLinesByJavaFile().forEach((file, lines) -> {
            List<String> hits = new ArrayList<>();
            for (TestCoverage t : snap.tests())
                if (!RoaringBitmap.and(t.linesFor(file), lines).isEmpty()) hits.add(t.testId());
            out.put(file, List.copyOf(hits));
        });
        return out;
    }
}

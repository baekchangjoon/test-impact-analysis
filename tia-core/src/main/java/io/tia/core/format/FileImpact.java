package io.tia.core.format;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.DiffSummary;
import io.tia.core.model.TestCoverage;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** 변경 파일 → 그 변경 라인을 커버하는 테스트 목록(빈 리스트 = blind spot 후보).
 *  test 필터로 제외된 테스트는 이 실행에서 돌지 않으므로 매핑에서도 제외한다 [REQ-009] —
 *  그 결과 유일한 커버 테스트가 제외된 파일은 blind spot으로 보인다(이 실행 기준으로는 맞는 표시). */
public final class FileImpact {
    private FileImpact() {}

    public static Map<String, List<String>> testsByChangedFile(CoverageSnapshot snap, DiffSummary diff,
                                                                 Predicate<String> acceptsTest) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        diff.changedOldLinesByJavaFile().forEach((file, lines) -> {
            List<String> hits = new ArrayList<>();
            for (TestCoverage t : snap.tests())
                if (acceptsTest.test(t.testId()) && !RoaringBitmap.and(t.linesFor(file), lines).isEmpty())
                    hits.add(t.testId());
            out.put(file, List.copyOf(hits));
        });
        return out;
    }
}

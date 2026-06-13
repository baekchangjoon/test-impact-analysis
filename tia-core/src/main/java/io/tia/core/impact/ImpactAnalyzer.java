package io.tia.core.impact;

import io.tia.core.model.Confidence;
import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.DiffSummary;
import io.tia.core.model.ImpactResult;
import io.tia.core.model.ImpactedTest;
import io.tia.core.model.TestCoverage;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ImpactAnalyzer {

    public ImpactResult select(CoverageSnapshot snapshot, DiffSummary diff) {
        List<String> reasons = new ArrayList<>();

        // 1) DETERMINISTIC: 수정/삭제된 old-side 라인과 커버 라인 교차 (비트 AND)
        Map<String, Confidence> hit = new LinkedHashMap<>();
        for (TestCoverage t : snapshot.tests()) {
            for (Map.Entry<String, RoaringBitmap> e : diff.changedOldLinesByJavaFile().entrySet()) {
                if (!RoaringBitmap.and(t.linesFor(e.getKey()), e.getValue()).isEmpty()) {
                    hit.put(t.testId(), Confidence.DETERMINISTIC);
                }
            }
        }

        // 2) Phase 0에서 해결 불가한 변경 → 보수적 전체 선택(안전). skip 기본 OFF이라 과선택 허용.
        //    - 비코드(매핑 불가) [1-A]
        //    - 신규 .java 파일: 과거 커버리지 없음 + 호출자 탐색(정적 그래프)은 Phase 2 → 영향 불명 [1-B]
        //      (주의: 신규 파일은 어떤 기존 테스트도 커버하지 못하므로 'covers()' 기반 선택은 항상 0 → 보수적이 유일한 안전책)
        boolean conservative = !diff.unmappableFiles().isEmpty() || !diff.additionOnlyJavaFiles().isEmpty();
        if (!diff.unmappableFiles().isEmpty())
            reasons.add("커버리지 매핑 불가 변경 " + diff.unmappableFiles()
                    + " → 보수적 전체 선택 (목적1) / triage UNKNOWN (목적2)");
        if (!diff.additionOnlyJavaFiles().isEmpty())
            reasons.add("신규 .java " + diff.additionOnlyJavaFiles()
                    + " → 과거 커버리지 없음, 정적 폴백은 Phase 2 → 보수적 전체 선택");

        if (conservative) {
            List<ImpactedTest> all = new ArrayList<>();
            for (TestCoverage t : snapshot.tests())   // 정밀 히트는 DETERMINISTIC 유지, 나머지는 CONSERVATIVE
                all.add(new ImpactedTest(t.testId(), hit.getOrDefault(t.testId(), Confidence.CONSERVATIVE)));
            return new ImpactResult(all, true, reasons);
        }

        List<ImpactedTest> impacted = new ArrayList<>();
        for (Map.Entry<String, Confidence> e : hit.entrySet())
            impacted.add(new ImpactedTest(e.getKey(), e.getValue()));
        return new ImpactResult(impacted, false, reasons);
    }
}

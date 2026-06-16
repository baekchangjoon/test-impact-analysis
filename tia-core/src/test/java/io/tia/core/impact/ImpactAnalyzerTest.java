package io.tia.core.impact;

import io.tia.core.model.Confidence;
import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.DiffSummary;
import io.tia.core.model.ImpactResult;
import io.tia.core.model.ImpactedTest;
import io.tia.core.model.TestCoverage;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactAnalyzerTest {
    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();

    private CoverageSnapshot snapshot() {   // 실제 PricingService 커버 라인 ~{6,7,8}
        TestCoverage price = new TestCoverage("T_price", "PASSED",
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(6, 7, 8)));
        TestCoverage greet = new TestCoverage("T_greet", "PASSED",
            Map.of("io/tia/fixture/GreetingService.java", RoaringBitmap.bitmapOf(6, 7)));
        return new CoverageSnapshot("fixture", "c0", List.of(price, greet));
    }

    @Test
    void deterministic_selectsOnlyTestsHittingChangedLines() {
        DiffSummary diff = new DiffSummary(
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8)),
            Set.of(), Set.of());
        ImpactResult r = analyzer.select(snapshot(), diff);
        assertFalse(r.conservativeSelectAll());
        assertEquals(List.of("T_price"), ids(r));
        assertEquals(Confidence.DETERMINISTIC, r.impacted().get(0).confidence());
    }

    @Test
    void unmappableChange_forcesConservativeAll() {     // 1-A
        DiffSummary diff = new DiffSummary(Map.of(), Set.of(),
            Set.of("application.yml"));
        ImpactResult r = analyzer.select(snapshot(), diff);
        assertTrue(r.conservativeSelectAll());
        assertEquals(Set.of("T_price", "T_greet"), new HashSet<>(ids(r)));
        assertEquals(Confidence.CONSERVATIVE, r.impacted().get(0).confidence());
    }

    @Test
    void newJavaFile_forcesConservativeAll_sinceNoCoverageNorStaticGraph() {   // 1-B
        DiffSummary diff = new DiffSummary(Map.of(),
            Set.of("io/tia/fixture/NewFeature.java"), Set.of());   // 과거 커버리지 없는 신규 파일
        ImpactResult r = analyzer.select(snapshot(), diff);
        assertTrue(r.conservativeSelectAll());
        assertEquals(Set.of("T_price", "T_greet"), new HashSet<>(ids(r)));
    }

    @Test
    void deterministicHitPlusNewFile_selectsAllButKeepsDeterministicLabel() {
        DiffSummary diff = new DiffSummary(
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8)),
            Set.of("io/tia/fixture/NewFeature.java"), Set.of());
        ImpactResult r = analyzer.select(snapshot(), diff);
        assertTrue(r.conservativeSelectAll());
        Map<String, Confidence> byId = new HashMap<>();
        for (ImpactedTest t : r.impacted()) byId.put(t.testId(), t.confidence());
        assertEquals(Confidence.DETERMINISTIC, byId.get("T_price"));   // 정밀 히트는 유지
        assertEquals(Confidence.CONSERVATIVE, byId.get("T_greet"));
    }

    private static List<String> ids(ImpactResult r) {
        List<String> out = new ArrayList<>();
        for (ImpactedTest t : r.impacted()) out.add(t.testId());
        return out;
    }
}

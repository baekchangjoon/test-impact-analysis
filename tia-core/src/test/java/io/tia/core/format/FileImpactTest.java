package io.tia.core.format;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.DiffSummary;
import io.tia.core.model.TestCoverage;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** test 필터가 파일→테스트 매핑에도 적용되는지 [REQ-009/015] — 제외된 테스트는 blind-spot
 *  판정에서 "커버함"으로 취급되면 안 된다(그 테스트는 이번 실행에서 돌지 않으므로). */
class FileImpactTest {

    @Test
    void excludedTestDoesNotAppearButIncludedDoes() {
        CoverageSnapshot snap = new CoverageSnapshot("repo", "C0", List.of(
                new TestCoverage("io/tia/fixture/ApiSmokeTest/testIncluded", "PASSED",
                        Map.of("com/acme/PricingService.java", RoaringBitmap.bitmapOf(8))),
                new TestCoverage("io/tia/fixture/ApiSmokeTest/testExcluded", "PASSED",
                        Map.of("com/acme/PricingService.java", RoaringBitmap.bitmapOf(8)))));
        DiffSummary diff = new DiffSummary(
                Map.of("com/acme/PricingService.java", RoaringBitmap.bitmapOf(8)),
                Set.of(), Set.of());

        Map<String, List<String>> result = FileImpact.testsByChangedFile(snap, diff,
                testId -> !testId.endsWith("testExcluded"));

        List<String> hits = result.get("com/acme/PricingService.java");
        assertTrue(hits.contains("io/tia/fixture/ApiSmokeTest/testIncluded"), hits.toString());
        assertFalse(hits.contains("io/tia/fixture/ApiSmokeTest/testExcluded"), hits.toString());
    }

    @Test
    void fileWhoseOnlyCoveringTestIsExcludedBecomesBlindSpot() {
        CoverageSnapshot snap = new CoverageSnapshot("repo", "C0", List.of(
                new TestCoverage("io/tia/fixture/ApiSmokeTest/testExcluded", "PASSED",
                        Map.of("com/acme/Unused.java", RoaringBitmap.bitmapOf(3)))));
        DiffSummary diff = new DiffSummary(
                Map.of("com/acme/Unused.java", RoaringBitmap.bitmapOf(3)),
                Set.of(), Set.of());

        Map<String, List<String>> result = FileImpact.testsByChangedFile(snap, diff,
                testId -> !testId.endsWith("testExcluded"));

        assertEquals(List.of(), result.get("com/acme/Unused.java"));
    }
}

package io.tia.core.filter;

import io.tia.core.model.DiffSummary;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiffSummaryFilterTest {

    private DiffSummary diff() {
        return new DiffSummary(
                Map.of("com/acme/Svc.java", RoaringBitmap.bitmapOf(5),
                       "com/acme/gen/Dto.java", RoaringBitmap.bitmapOf(9)),
                Set.of("com/acme/gen/NewGen.java"),
                Set.of("build.gradle", "conf/app.yml"));
    }

    @Test void filtersAllThreeFields() {   // [REQ-007]
        FilterSet f = FilterSet.of(List.of(), List.of("**/gen/**", "conf/**"), List.of(), List.of());
        DiffFilter.Result r = DiffFilter.apply(diff(), f);
        assertEquals(Set.of("com/acme/Svc.java"), r.diff().changedOldLinesByJavaFile().keySet());
        assertTrue(r.diff().additionOnlyJavaFiles().isEmpty());          // 신규 파일도 제거
        assertEquals(Set.of("build.gradle"), r.diff().unmappableFiles()); // conf/app.yml 제거
        assertEquals(3, r.ignoredFiles().size());
    }

    @Test void includeDoesNotDropUnmappable() {   // [REQ-007] include만으로 build.gradle이 사라지면 안 됨
        FilterSet f = FilterSet.of(List.of("com/acme/**"), List.of(), List.of(), List.of());
        DiffFilter.Result r = DiffFilter.apply(diff(), f);
        assertEquals(Set.of("build.gradle", "conf/app.yml"), r.diff().unmappableFiles());
        assertTrue(r.ignoredFiles().isEmpty());
    }

    @Test void allExcludedYieldsEmptyDiff() {   // [REQ-008] 코어 전제
        FilterSet f = FilterSet.of(List.of(), List.of("**"), List.of(), List.of());
        DiffFilter.Result r = DiffFilter.apply(diff(), f);
        assertTrue(r.diff().changedOldLinesByJavaFile().isEmpty());
        assertTrue(r.diff().additionOnlyJavaFiles().isEmpty());
        assertTrue(r.diff().unmappableFiles().isEmpty());
        assertEquals(5, r.ignoredFiles().size());
    }

    @Test void noFiltersIsIdentity() {
        DiffFilter.Result r = DiffFilter.apply(diff(), FilterSet.none());
        assertEquals(diff(), r.diff());
        assertTrue(r.ignoredFiles().isEmpty());
    }
}

package io.tia.core.filter;

import io.tia.core.model.DiffSummary;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** code 필터를 DiffSummary 세 필드 전부에 적용 [REQ-007].
 *  - 매핑 가능(.java 키): include ∧ ¬exclude
 *  - unmappable(비-Java): include 우회, exclude만 (CONSERVATIVE 안전망 보존) */
public final class DiffFilter {
    private DiffFilter() {}

    public record Result(DiffSummary diff, List<String> ignoredFiles) {}

    public static Result apply(DiffSummary diff, FilterSet filters) {
        List<String> ignored = new ArrayList<>();
        Map<String, RoaringBitmap> changed = new LinkedHashMap<>();
        diff.changedOldLinesByJavaFile().forEach((file, lines) -> {
            if (filters.acceptsCode(file)) changed.put(file, lines); else ignored.add(file);
        });
        Set<String> additions = new LinkedHashSet<>();
        for (String f : diff.additionOnlyJavaFiles()) {
            if (filters.acceptsCode(f)) additions.add(f); else ignored.add(f);
        }
        Set<String> unmappable = new LinkedHashSet<>();
        for (String f : diff.unmappableFiles()) {
            if (filters.acceptsUnmappable(f)) unmappable.add(f); else ignored.add(f);
        }
        return new Result(new DiffSummary(changed, additions, unmappable), List.copyOf(ignored));
    }
}

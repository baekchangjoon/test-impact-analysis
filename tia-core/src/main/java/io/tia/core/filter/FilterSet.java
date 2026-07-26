package io.tia.core.filter;

import java.util.List;

/** 해석 완료된 code/test include·exclude 필터. exclude가 include보다 우선, include 비면 전체 [REQ-006]. */
public final class FilterSet {
    private final List<String> codeInc, codeExc, testInc, testExc;
    private final GlobMatcher codeIncM, codeExcM, testIncM, testExcM;

    private FilterSet(List<String> ci, List<String> ce, List<String> ti, List<String> te) {
        this.codeInc = ci; this.codeExc = ce; this.testInc = ti; this.testExc = te;
        this.codeIncM = GlobMatcher.compile(ci); this.codeExcM = GlobMatcher.compile(ce);
        this.testIncM = GlobMatcher.compile(ti); this.testExcM = GlobMatcher.compile(te);
    }

    public static FilterSet of(List<String> codeInc, List<String> codeExc,
                               List<String> testInc, List<String> testExc) {
        return new FilterSet(List.copyOf(codeInc), List.copyOf(codeExc),
                List.copyOf(testInc), List.copyOf(testExc));
    }

    public static FilterSet none() { return of(List.of(), List.of(), List.of(), List.of()); }

    /** 매핑 가능한 .java 정규화 경로용: include(빈=전체) ∧ ¬exclude [REQ-004]. */
    public boolean acceptsCode(String canonicalPath) {
        if (codeExcM.matchesAny(canonicalPath)) return false;
        return codeInc.isEmpty() || codeIncM.matchesAny(canonicalPath);
    }

    /** 비-Java(unmappable)용: include 우회, 명시적 exclude만 평가 [REQ-007]. */
    public boolean acceptsUnmappable(String path) { return !codeExcM.matchesAny(path); }

    /** testId는 `#`→`/` 정규화 후 매칭 (out-of-process id 지원) [REQ-005]. */
    public boolean acceptsTest(String testId) {
        String norm = testId.replace('#', '/');
        if (testExcM.matchesAny(norm)) return false;
        return testInc.isEmpty() || testIncM.matchesAny(norm);
    }

    public boolean hasCodeFilters() { return !codeInc.isEmpty() || !codeExc.isEmpty(); }
    public boolean hasTestFilters() { return !testInc.isEmpty() || !testExc.isEmpty(); }
    public List<String> codeInclude() { return codeInc; }
    public List<String> codeExclude() { return codeExc; }
    public List<String> testInclude() { return testInc; }
    public List<String> testExclude() { return testExc; }
}

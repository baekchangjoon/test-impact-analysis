package io.tia.core.config;

import java.nio.file.Path;
import java.util.List;

/** tia.yml 파싱 결과. 리스트는 항상 non-null(기본 빈 리스트). */
public record TiaConfig(int version, String sutName, Path db, FilterLists code, FilterLists test) {
    public record FilterLists(List<String> include, List<String> exclude) {
        public static FilterLists empty() { return new FilterLists(List.of(), List.of()); }
    }
    public static TiaConfig empty() {
        return new TiaConfig(1, null, null, FilterLists.empty(), FilterLists.empty());
    }
}

package io.tia.core.model;

import org.roaringbitmap.RoaringBitmap;

import java.util.Map;
import java.util.Set;

/** git diff 분석 결과. 라인은 모두 매핑 기준 커밋(old-side) 번호 공간. */
public record DiffSummary(
        Map<String, RoaringBitmap> changedOldLinesByJavaFile,  // 수정/삭제된 .java old-side 라인
        Set<String> additionOnlyJavaFiles,                     // 추가만 있는 .java (신규 코드)
        Set<String> unmappableFiles                            // 비-.java 변경(yml/sql/gradle 등)
) {}

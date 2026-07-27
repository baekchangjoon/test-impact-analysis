package io.tia.gradle;

import org.jacoco.core.runtime.WildcardMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP2-REQ-003: 경로 글로브 → JaCoCo 클래스 패턴 변환 (일괄 과포함 규칙).
 * 전부 실제 {@link WildcardMatcher} 매칭으로 단언한다(문자열 비교 아님) — spec §3 / 요구명세 REQ-003.
 */
class GlobToClassPatternTest {

    @Test
    void trailingDoubleStarMatchesTopAndSubPackages() {
        String pattern = GlobToClassPattern.convertOne("com/acme/**");
        assertTrue(new WildcardMatcher(pattern).matches("com.acme.Svc"), pattern);
        assertTrue(new WildcardMatcher(pattern).matches("com.acme.sub.X"), pattern);
    }

    @Test
    void leadingDoubleStarMatchesDefaultAndNestedPackage() {
        String pattern = GlobToClassPattern.convertOne("**/gen/**");
        assertTrue(new WildcardMatcher(pattern).matches("gen.Foo"), pattern); // 기본 패키지 (0-세그먼트)
        assertTrue(new WildcardMatcher(pattern).matches("a.gen.B"), pattern);
    }

    @Test
    void leadingDoubleStarWithSuffixMatchesDefaultAndNestedPackage() {
        String pattern = GlobToClassPattern.convertOne("**/*Dto.java");
        assertTrue(new WildcardMatcher(pattern).matches("FooDto"), pattern); // 기본 패키지
        assertTrue(new WildcardMatcher(pattern).matches("a.BDto"), pattern);
    }

    @Test
    void midPathDoubleStarMatchesZeroAndMultiSegment() {
        String pattern = GlobToClassPattern.convertOne("com/acme/**/dto/**");
        assertTrue(new WildcardMatcher(pattern).matches("com.acme.dto.X"), pattern); // 0-세그먼트
        assertTrue(new WildcardMatcher(pattern).matches("com.acme.a.dto.X"), pattern);
    }

    @Test
    void exactFileMatchesInnerAnonymousAndLambdaClasses() {
        String pattern = GlobToClassPattern.convertOne("com/acme/PricingService.java");
        assertTrue(new WildcardMatcher(pattern).matches("com.acme.PricingService"), pattern);
        assertTrue(new WildcardMatcher(pattern).matches("com.acme.PricingService$Builder"), pattern);
        assertTrue(new WildcardMatcher(pattern).matches("com.acme.PricingService$1"), pattern);
    }

    @Test
    void midPathSingleCharWildcardStillMatchesInnerClasses() {
        // 중간 `?` — "*" 접미가 보편 적용되어 내부 클래스까지 매칭 (정확-파일 특수 판정 불요)
        String pattern = GlobToClassPattern.convertOne("com/acme/Prici?gService.java");
        assertTrue(new WildcardMatcher(pattern).matches("com.acme.PricingService$Builder"), pattern);
    }

    @Test
    void singleStarOverIncludesSubPackages() {
        String pattern = GlobToClassPattern.convertOne("com/acme/*.java");
        assertTrue(new WildcardMatcher(pattern).matches("com.acme.X"), pattern); // 과포함 허용(명시)
    }

    @Test
    void multipleEntriesJoinedWithColonMatchIndependentlyAsCombinedString() {
        // 다중 엔트리는 `:` 결합 — 결합 문자열 그대로 WildcardMatcher에 넣어 각각의 대응 FQN이 매칭돼야 한다
        String combined = GlobToClassPattern.convertAll(List.of("com/acme/**", "com/other/Foo.java"));
        WildcardMatcher matcher = new WildcardMatcher(combined);
        assertTrue(matcher.matches("com.acme.Svc"), combined);
        assertTrue(matcher.matches("com.other.Foo"), combined);
    }
}

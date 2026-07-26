package io.tia.core.filter;

import io.tia.core.config.TiaConfigException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GlobFilterTest {

    @Test void excludeWins() {   // [REQ-006]
        FilterSet f = FilterSet.of(List.of("com/acme/**"), List.of("com/acme/gen/**"), List.of(), List.of());
        assertTrue(f.acceptsCode("com/acme/Svc.java"));
        assertFalse(f.acceptsCode("com/acme/gen/Dto.java"));   // include에도 걸리지만 exclude 우선
    }

    @Test void emptyIncludeMeansAll() {   // [REQ-006]
        FilterSet f = FilterSet.of(List.of(), List.of("**/gen/**"), List.of(), List.of());
        assertTrue(f.acceptsCode("anything/At/All.java"));
        assertFalse(f.acceptsCode("x/gen/Y.java"));
    }

    @Test void codeGlobMatchesCanonicalPackageRelativePath() {   // [REQ-004]
        FilterSet f = FilterSet.of(List.of("com/acme/**"), List.of(), List.of(), List.of());
        assertTrue(f.acceptsCode("com/acme/pricing/PricingService.java"));
        assertFalse(f.acceptsCode("org/other/Thing.java"));
    }

    @Test void unmappableBypassesInclude() {   // [REQ-007] build.gradle은 include 미매칭이어도 생존
        FilterSet f = FilterSet.of(List.of("com/acme/**"), List.of(), List.of(), List.of());
        assertTrue(f.acceptsUnmappable("build.gradle"));
        FilterSet g = FilterSet.of(List.of("com/acme/**"), List.of("**/*.gradle"), List.of(), List.of());
        assertFalse(g.acceptsUnmappable("build.gradle"));      // 명시적 exclude만 제거
    }

    @Test void testIdHashNormalizedBeforeMatch() {   // [REQ-005]
        FilterSet f = FilterSet.of(List.of(), List.of(), List.of(), List.of("AuthApiBlackBoxIT/**"));
        assertFalse(f.acceptsTest("AuthApiBlackBoxIT#loginWithInvalidCredentialsReturns400"));
        assertTrue(f.acceptsTest("io/tia/fixture/ApiSmokeTest/testPrice"));
    }

    @Test void singleStarDoesNotCrossSlash_doubleStarDoes() {   // [REQ-021]
        FilterSet f = FilterSet.of(List.of(), List.of("com/*/Svc.java"), List.of(), List.of());
        assertFalse(f.acceptsCode("com/a/Svc.java"));
        assertTrue(f.acceptsCode("com/a/b/Svc.java"));          // `*`는 `/` 못 넘음
        FilterSet g = FilterSet.of(List.of(), List.of("com/**/Svc.java"), List.of(), List.of());
        assertFalse(g.acceptsCode("com/a/b/Svc.java"));          // `**`는 넘음
        assertFalse(g.acceptsCode("com/Svc.java"));              // `**/`는 0개 세그먼트도 허용
    }

    @Test void doubleStarSlashMatchesRootLevel() {   // 글로브 표준 의미론 (리뷰 소견 반영)
        FilterSet f = FilterSet.of(List.of(), List.of("**/gen/**"), List.of(), List.of());
        assertFalse(f.acceptsCode("gen/Foo.java"));              // 루트 레벨 gen도 매칭
        assertFalse(f.acceptsCode("a/b/gen/Foo.java"));
        assertTrue(f.acceptsCode("agen/Foo.java"));              // 세그먼트 경계 존중
    }

    @Test void unixSyntaxFixedMatcher() {   // [REQ-021] 매트릭스 지정 테스트명
        FilterSet f = FilterSet.of(List.of(), List.of("com/*"), List.of(), List.of());
        // 백슬래시는 구분자가 아니라 미지원 문법(fail-fast) — OS separator에 의존하지 않음
        assertThrows(TiaConfigException.class,
                () -> FilterSet.of(List.of("com\\acme\\**"), List.of(), List.of(), List.of()));
        assertFalse(f.acceptsCode("com/X.java"));   // `/` 고정 구분 매칭
    }

    @Test void unsupportedGlobSyntaxFailsFast() {   // [REQ-003] 지원 어휘 밖
        assertThrows(TiaConfigException.class,
                () -> FilterSet.of(List.of("[unterminated"), List.of(), List.of(), List.of()));
        assertThrows(TiaConfigException.class,
                () -> FilterSet.of(List.of("{a,b}/**"), List.of(), List.of(), List.of()));
    }
}

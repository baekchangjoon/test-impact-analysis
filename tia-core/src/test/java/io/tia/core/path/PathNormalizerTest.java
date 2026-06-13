package io.tia.core.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathNormalizerTest {
    @Test
    void stripsModuleAndSourceRoot() {   // 레포 상대(실제 git diff) → 패키지 상대(커버리지 키)
        assertEquals("io/tia/fixture/PricingService.java",
            PathNormalizer.canonical("fixture-app/src/main/java/io/tia/fixture/PricingService.java"));
    }

    @Test
    void leavesAlreadyCanonicalUnchanged() {   // testwise 리포트는 이미 패키지 상대 → 무변경
        assertEquals("io/tia/fixture/PricingService.java",
            PathNormalizer.canonical("io/tia/fixture/PricingService.java"));
    }

    @Test
    void stripsTestRootAndResources() {
        assertEquals("io/tia/fixture/ApiSmokeTest.java",
            PathNormalizer.canonical("src/test/java/io/tia/fixture/ApiSmokeTest.java"));
        assertEquals("application.yml",
            PathNormalizer.canonical("module/src/main/resources/application.yml"));
    }

    @Test
    void stripsLeadingDotSlash() {   // 'git diff'가 './' 접두를 붙이는 경우 정규화
        assertEquals("io/tia/fixture/Foo.java", PathNormalizer.canonical("./io/tia/fixture/Foo.java"));
    }
}

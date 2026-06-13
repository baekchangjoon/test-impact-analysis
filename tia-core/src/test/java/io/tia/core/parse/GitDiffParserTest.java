package io.tia.core.parse;

import io.tia.core.model.DiffSummary;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitDiffParserTest {
    // 실제 git diff처럼 '레포 상대' 경로 사용 → PathNormalizer가 패키지 상대로 정규화됨을 함께 검증.
    private static final String DIFF =
        "diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java\n"
        + "--- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java\n"
        + "+++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java\n"
        + "@@ -8,3 +8,3 @@ public class PricingService {\n"
        + " public int priceOf(String sku) {\n"
        + "-    String key = TextUtil.normalize(sku);\n"
        + "-    return key.length() * 100;\n"
        + "+    String key = TextUtil.normalize(sku);\n"
        + "+    return key.length() * 200;\n"
        + "diff --git a/fixture-app/src/main/resources/application.yml b/fixture-app/src/main/resources/application.yml\n"
        + "--- a/fixture-app/src/main/resources/application.yml\n"
        + "+++ b/fixture-app/src/main/resources/application.yml\n"
        + "@@ -1,2 +1,2 @@\n"
        + "-server.port: 8080\n"
        + "+server.port: 9090\n"
        + "diff --git a/fixture-app/src/main/java/io/tia/fixture/NewFeature.java b/fixture-app/src/main/java/io/tia/fixture/NewFeature.java\n"
        + "--- /dev/null\n"
        + "+++ b/fixture-app/src/main/java/io/tia/fixture/NewFeature.java\n"
        + "@@ -0,0 +1,2 @@\n"
        + "+package io.tia.fixture;\n"
        + "+public class NewFeature {}\n";

    @Test
    void normalizesRepoRelativePathsAndClassifies() {
        DiffSummary d = new GitDiffParser().parse(DIFF);

        // 레포 상대 → 패키지 상대 정규형 키(커버리지 키와 동일 공간), old-side 9,10 라인
        RoaringBitmap changed = d.changedOldLinesByJavaFile().get("io/tia/fixture/PricingService.java");
        assertNotNull(changed, "정규화 후 패키지 상대 키여야 함");
        assertTrue(changed.contains(9) && changed.contains(10));

        // 비-.java → unmappable (1-A)
        assertTrue(d.unmappableFiles().contains("application.yml"));

        // 순수 추가 .java → additionOnly (1-B), changed map엔 없음
        assertTrue(d.additionOnlyJavaFiles().contains("io/tia/fixture/NewFeature.java"));
        assertFalse(d.changedOldLinesByJavaFile().containsKey("io/tia/fixture/NewFeature.java"));
    }
}

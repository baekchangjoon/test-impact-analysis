package io.tia.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code tia init}의 prompt/안내 text builder들(design spec §2)에 대한 순수 unit 테스트.
 * Console을 요구하는 대화형 프롬프트 자체는 JVM 테스트로 재현 불가하므로(InitCommandE2ETest 상단 주석
 * 참고), 텍스트 생성 로직만 package-private static 메서드로 분리해 여기서 관측한다.
 */
class InitCommandTest {

    @Test
    @DisplayName("SP3-REQ-001 §2 step2: 토폴로지 프롬프트 텍스트가 결정 질문 2개 + 혼합 토폴로지 안내를 포함한다")
    void topologyPromptTextHasDecisionQuestionsAndMixedGuidance() {
        String text = InitCommand.topologyPromptText();

        assertTrue(text.contains("프로덕션 코드가 테스트 스레드에서 실행되나요"), text);
        assertTrue(text.contains("in-process"), text);
        assertTrue(text.contains("별도 서버·워커 스레드인가요"), text);
        assertTrue(text.contains("out-of-process"), text);
        assertTrue(text.contains("둘 다 해당하면"), text);
        assertTrue(text.contains("두 모델 병용"), text);
    }

    @Test
    @DisplayName("git 루트 부재 경고는 diff 기반 기능 제약을 한 줄로 안내한다")
    void gitMissingWarningMentionsDiffConstraint() {
        String text = InitCommand.gitMissingWarningText();
        assertTrue(text.contains("git"), text);
        assertTrue(text.contains("diff"), text);
        assertTrue(text.lines().count() == 1, "한 줄 경고여야 함: " + text);
    }

    @Test
    @DisplayName("섀도잉 잔존 파일 경고는 두 경로(잔존/쓰기 대상)를 모두 담는다")
    void shadowFileWarningMentionsBothPaths() {
        Path existing = Path.of("/repo/sub/tia.yml");
        Path target = Path.of("/repo/tia.yml");
        String text = InitCommand.shadowFileWarningText(existing, target);
        assertTrue(text.contains(existing.toString()), text);
        assertTrue(text.contains(target.toString()), text);
    }

    @Test
    @DisplayName("토폴로지 오선택 경고는 침묵 손실 + tia convert 차단을 명시한다")
    void silentLossWarningMentionsConvertGate() {
        String text = InitCommand.silentLossWarningText();
        assertTrue(text.contains("침묵 손실"), text);
        assertTrue(text.contains("tia convert"), text);
    }

    @Test
    @DisplayName("빌드 도구 감지: build.gradle/build.gradle.kts -> GRADLE, pom.xml -> MAVEN, 그 외 -> UNKNOWN")
    void buildToolDetection(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        assertEquals(InitCommand.BuildTool.UNKNOWN, InitCommand.detectBuildTool(dir));

        java.nio.file.Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertEquals(InitCommand.BuildTool.MAVEN, InitCommand.detectBuildTool(dir));
        java.nio.file.Files.delete(dir.resolve("pom.xml"));

        java.nio.file.Files.writeString(dir.resolve("build.gradle"), "");
        assertEquals(InitCommand.BuildTool.GRADLE, InitCommand.detectBuildTool(dir));
    }

    @Test
    @DisplayName("빌드 도구별 다음 단계 안내: gradle은 기존 문구 유지(추가 줄 없음=null), maven/unknown은 한 줄 추가")
    void buildToolHintTextBranches() {
        assertNull(InitCommand.buildToolHintText(InitCommand.BuildTool.GRADLE),
                "gradle은 기존 문구를 유지해야 하므로 추가 줄이 없어야 함");

        String maven = InitCommand.buildToolHintText(InitCommand.BuildTool.MAVEN);
        assertTrue(maven != null && maven.contains("maven-plugin"), maven);

        String unknown = InitCommand.buildToolHintText(InitCommand.BuildTool.UNKNOWN);
        assertTrue(unknown != null && unknown.contains("자동감지하지 못했습니다"), unknown);
    }

    @Test
    @DisplayName("YAML 단일 인용 이스케이프: 내장 작은따옴표는 두 번 반복된다")
    void yamlSingleQuoteEscapesEmbeddedQuote() {
        assertEquals("'plain'", InitCommand.yamlSingleQuote("plain"));
        assertEquals("'weird:name#hash'", InitCommand.yamlSingleQuote("weird:name#hash"));
        assertEquals("'it''s quoted'", InitCommand.yamlSingleQuote("it's quoted"));
    }
}

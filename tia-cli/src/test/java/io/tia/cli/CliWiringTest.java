package io.tia.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP1 CLI 옵션 배선 표 검증(REQ-022) — design spec §2의 커맨드×옵션 표대로 각 서브커맨드에
 * --config/필터/--format이 배선되고, 표 밖 조합은 노출되지 않는지 picocli {@link CommandLine.Model.CommandSpec}
 * 으로 확인한다. 커맨드당 1개 {@code @Test}로 분리 — 배선이 태스크별로 진행되므로(index=Task5,
 * impact=Task6, flaky=Task7, report=Task8) 부분 green을 표현할 수 있어야 한다. convert는 처음부터 green
 * (소비할 tia.yml 값이 없어 --config를 배선하지 않음 — 이미 참).
 */
class CliWiringTest {

    static boolean hasOption(CommandLine tia, String sub, String opt) {
        return tia.getSubcommands().get(sub).getCommandSpec().optionsMap().containsKey(opt);
    }
    static CommandLine tia() { return new CommandLine(new TiaCommand()); }

    @Test void optionsForImpact() {
        assertTrue(hasOption(tia(), "impact", "--config"));
        assertTrue(hasOption(tia(), "impact", "--include-code"));
        assertTrue(hasOption(tia(), "impact", "--include-test"));
        assertTrue(hasOption(tia(), "impact", "--format"));
    }
    @Test void optionsForFlaky() {
        assertTrue(hasOption(tia(), "flaky", "--config"));
        assertTrue(hasOption(tia(), "flaky", "--include-test"));
        assertTrue(hasOption(tia(), "flaky", "--format"));
        assertFalse(hasOption(tia(), "flaky", "--include-code"));   // 표 밖 조합 금지
    }
    @Test void optionsForReport() {
        assertTrue(hasOption(tia(), "report", "--config"));
        assertTrue(hasOption(tia(), "report", "--include-code"));
        assertTrue(hasOption(tia(), "report", "--include-test"));
        assertFalse(hasOption(tia(), "report", "--format"));        // HTML 전용
    }
    @Test void optionsForIndex() {
        assertTrue(hasOption(tia(), "index", "--config"));
        assertFalse(hasOption(tia(), "index", "--include-code"));
        assertFalse(hasOption(tia(), "index", "--include-test"));
    }
    @Test void optionsForConvert() {
        assertFalse(hasOption(tia(), "convert", "--config"));       // 소비할 값 없음
    }

    /** SP3-REQ-010 — init/doctor/demo 배선: 세 서브커맨드 존재 + 표 밖 조합 부재. */
    @Test void optionsForInitDoctorDemo() {
        CommandLine tia = tia();
        assertTrue(tia.getSubcommands().containsKey("init"));
        assertTrue(tia.getSubcommands().containsKey("doctor"));
        assertTrue(tia.getSubcommands().containsKey("demo"));

        assertTrue(hasOption(tia, "doctor", "--config"));
        assertTrue(hasOption(tia, "doctor", "--db"));

        assertFalse(hasOption(tia, "init", "--exclude-code"));      // 시드는 include만
        assertFalse(hasOption(tia, "init", "--config"));            // ConfigMixin 재사용 금지(누출 방지)

        assertTrue(hasOption(tia, "demo", "--scripts-dir"));
        assertTrue(hasOption(tia, "demo", "--out-dir"));
        assertTrue(hasOption(tia, "demo", "--repo-root"));
        assertTrue(tia.getSubcommands().get("demo").getCommandSpec()
                .optionsMap().get("--repo-root").hidden(), "--repo-root는 히든 시임이어야 함");
        assertFalse(hasOption(tia, "demo", "--db"));
        assertFalse(hasOption(tia, "demo", "--include-code"));
    }
}

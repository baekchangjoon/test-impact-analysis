package io.tia.e2e.onboarding;

import io.tia.cli.TiaCommand;
import io.tia.core.config.TiaConfig;
import io.tia.core.config.TiaConfigLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP3-REQ-001..003 — {@code tia init} 마법사: 비대화형 생성·SP1 정합, 덮어쓰기·섀도잉 가드,
 * 비TTY usage 에러. 인프로세스 picocli 구동(SpecAcceptanceE2ETest·ConfigE2ETest 패턴).
 *
 * 테스트 JVM은 원래 비TTY(System.console() == null)라 별도 조작 없이 SP3-REQ-003 시나리오가 그대로
 * 재현된다. 대화형(TTY) 프롬프트 경로는 JVM 테스트로 재현 불가 — 이 한계는 design spec §7/§8에 명시.
 */
@Execution(ExecutionMode.SAME_THREAD)
class InitCommandE2ETest {

    @TempDir Path work;

    @Test
    @DisplayName("SP3-REQ-001: 비대화형 init이 SP1 로더로 파싱되는 유효한 tia.yml을 생성하고 함정 경고 주석을 포함한다")
    void nonInteractiveCreatesValidYml() throws Exception {
        Files.createDirectories(work.resolve(".git"));   // git 루트 마커 — 쓰기 대상 = work 자체

        Exec r = run("init", "--topology", "in-process", "--sut-name", "x", "--search-root", work.toString());
        assertEquals(0, r.code(), r.err());

        Path generated = work.resolve("tia.yml");
        assertTrue(Files.isRegularFile(generated), "tia.yml이 생성되지 않음: " + generated);

        Optional<TiaConfig> parsed = TiaConfigLoader.load(null, work);
        assertTrue(parsed.isPresent(), "생성된 tia.yml을 SP1 로더가 파싱하지 못함");
        assertEquals("x", parsed.get().sutName(), "sut-name이 --sut-name 값과 일치하지 않음");

        String text = Files.readString(generated);
        assertTrue(text.contains("src/main/java/") && text.contains("매칭되지 않"),
                "package-relative 글로브 함정 경고 주석이 없음: " + text);
    }

    @Test
    @DisplayName("SP3-REQ-002: 서브디렉터리에서 실행해도 루트 tia.yml을 감지해 exit 1 + 루트 경로를 메시지에 담는다")
    void subdirDetectsRootYml() throws Exception {
        Files.createDirectories(work.resolve(".git"));
        Path rootYml = work.resolve("tia.yml");
        Files.writeString(rootYml, "version: 1\nsut-name: existing\n");
        Path subdir = Files.createDirectories(work.resolve("sub/deeper"));

        Exec r = run("init", "--topology", "in-process", "--search-root", subdir.toString());
        assertEquals(1, r.code(), r.out() + r.err());
        assertTrue(r.err().contains(rootYml.toString()), r.err());
        assertFalse(Files.exists(subdir.resolve("tia.yml")), "섀도잉 파일이 서브디렉터리에 생성됨");
    }

    @Test
    @DisplayName("SP3-REQ-002: --force면 서브디렉터리 실행에서도 루트 tia.yml만 덮어쓰고 새 파일을 만들지 않는다")
    void forceOverwritesAtRoot() throws Exception {
        Files.createDirectories(work.resolve(".git"));
        Path rootYml = work.resolve("tia.yml");
        Files.writeString(rootYml, "version: 1\nsut-name: existing\n");
        Path subdir = Files.createDirectories(work.resolve("sub/deeper"));

        Exec r = run("init", "--topology", "out-of-process", "--sut-name", "forced",
                "--search-root", subdir.toString(), "--force");
        assertEquals(0, r.code(), r.out() + r.err());

        Optional<TiaConfig> parsed = TiaConfigLoader.load(null, work);
        assertTrue(parsed.isPresent());
        assertEquals("forced", parsed.get().sutName(), "루트 tia.yml이 덮어써지지 않음");
        assertFalse(Files.exists(subdir.resolve("tia.yml")), "서브디렉터리에 새 파일이 생성됨");
    }

    @Test
    @DisplayName("SP3-REQ-003: 비TTY에서 --topology 미지정이면 exit 2 + stderr에 --topology 안내")
    void nonTtyMissingTopologyExit2() throws Exception {
        Exec r = run("init", "--search-root", work.toString());
        assertEquals(2, r.code(), r.out() + r.err());
        assertTrue(r.err().contains("--topology"), r.err());
        assertFalse(Files.exists(work.resolve("tia.yml")), "usage 에러 경로에서 tia.yml이 생성됨");
    }

    @Test
    @DisplayName("SP3-REQ-002: --force로 진행해도 쓰기 대상이 아닌 중간 경로의 잔존 tia.yml 경로를 경고에 담는다")
    void forceWarnsAboutMidDirShadowFile() throws Exception {
        Files.createDirectories(work.resolve(".git"));
        Path midShadow = work.resolve("sub/tia.yml");
        Files.createDirectories(midShadow.getParent());
        Files.writeString(midShadow, "version: 1\nsut-name: shadow\n");
        Path deeper = Files.createDirectories(work.resolve("sub/deeper"));

        Exec r = run("init", "--topology", "in-process", "--sut-name", "root",
                "--search-root", deeper.toString(), "--force");
        assertEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.out().contains(midShadow.toString()),
                "중간 경로 잔존 tia.yml 경로가 안내에 없음: " + r.out());

        Optional<TiaConfig> parsed = TiaConfigLoader.load(null, work);
        assertTrue(parsed.isPresent());
        assertEquals("root", parsed.get().sutName(), "루트 tia.yml이 생성/덮어써지지 않음");
    }

    @Test
    @DisplayName("SP3-REQ-001: sut-name/include 글로브에 YAML 특수문자(#, :)가 있어도 이스케이프되어 안전하게 파싱된다")
    void sutNameWithYamlSpecialCharsIsEscaped() throws Exception {
        Files.createDirectories(work.resolve(".git"));
        String trickyName = "weird:name#with-hash";
        String trickyGlob = "com/acme:extra#glob/**";

        Exec r = run("init", "--topology", "in-process", "--sut-name", trickyName,
                "--include-code", trickyGlob, "--search-root", work.toString());
        assertEquals(0, r.code(), r.out() + r.err());

        Optional<TiaConfig> parsed = TiaConfigLoader.load(null, work);
        assertTrue(parsed.isPresent(), "특수문자 포함 sut-name의 tia.yml을 SP1 로더가 파싱하지 못함: "
                + Files.readString(work.resolve("tia.yml")));
        assertEquals(trickyName, parsed.get().sutName());
        assertEquals(java.util.List.of(trickyGlob), parsed.get().code().include());
    }

    // ---- 공통 헬퍼 (SpecAcceptanceE2ETest·ConfigE2ETest 패턴 복사) ----

    record Exec(int code, String out, String err) {}

    static Exec run(String... args) {
        PrintStream oo = System.out, oe = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new TiaCommand()).execute(args);
            return new Exec(code, bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(oo); System.setErr(oe); }
    }
}

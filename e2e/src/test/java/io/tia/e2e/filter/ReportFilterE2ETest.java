package io.tia.e2e.filter;

import io.tia.cli.TiaCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP1 아우터 루프 — report는 testwise/prod-files를 파싱한 뒤 렌더링 직전에 code/test 필터를
 * 적용한다(REQ-011). 필터는 테스트 행뿐 아니라 살아남은 테스트의 파일 목록·역인덱스 축에도
 * 적용되고, 입력 파일 자체는 변경되지 않는다. Task 4 시점에는 --config가 배선되지 않아 red가 정상.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ReportFilterE2ETest {

    @TempDir Path work;

    @Test
    @DisplayName("REQ-011: 제외 테스트·제외 코드 경로는 렌더된 HTML에 나타나지 않고 입력 파일은 불변한다")
    void filteredAxesNotRendered() throws Exception {
        Path testwise = work.resolve("rep-testwise.json");
        copyResource("/spec-testwise.json", testwise);
        String testwiseBefore = Files.readString(testwise);

        Path prodFiles = work.resolve("prod-files.txt");
        String prodContent = String.join("\n",
                "io/tia/fixture/PricingService.java",
                "io/tia/fixture/GreetingService.java",
                "io/tia/fixture/TextUtil.java",
                "io/tia/fixture/Excluded.java") + "\n";
        Files.writeString(prodFiles, prodContent);

        Path yml = writeYml("""
                version: 1
                filters:
                  test:
                    exclude: ["**/testGreeting"]
                  code:
                    exclude: ["io/tia/fixture/Excluded.java"]
                """);
        Path out = work.resolve("report.html");
        Exec r = run("report", "--testwise", testwise.toString(), "--prod-files", prodFiles.toString(),
                "--commit", "C0", "--out", out.toString(), "--config", yml.toString());
        assertEquals(0, r.code(), r.err());

        String html = Files.readString(out);
        assertFalse(html.contains("testGreeting"), "제외 테스트가 렌더된 HTML에 남아있음");
        assertFalse(html.contains("Excluded.java"), "제외 파일이 파일목록/역인덱스에 남아있음");
        assertTrue(html.contains("testPrice"), "비제외 테스트는 남아있어야 함");

        assertEquals(testwiseBefore, Files.readString(testwise), "입력 testwise 파일이 변경됨(입력 무변경 위반)");
        assertEquals(prodContent, Files.readString(prodFiles), "입력 prod-files 파일이 변경됨(입력 무변경 위반)");
    }

    // ---- 공통 헬퍼 (SpecAcceptanceE2ETest 패턴 복사) ----

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

    private void copyResource(String res, Path dest) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(res)) {
            assertNotNull(in, "resource missing: " + res);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    Path writeYml(String yaml) throws IOException {
        Path f = work.resolve("tia.yml");
        Files.writeString(f, yaml);
        return f;
    }
}

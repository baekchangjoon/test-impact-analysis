package io.tia.e2e.action;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * scripts/pr-comment.sh 블랙박스 E2E [SP5-REQ-002..006].
 * 스크립트를 실제 bash 서브프로세스로 구동해 DRY_RUN/gh 스텁/실패 경로/절단 로직을 검증.
 */
@Execution(ExecutionMode.SAME_THREAD) // 스텁 gh·GH_ARGS_OUT 파일 상태를 테스트 간 레이스 없이 순차 관측하기 위해 직렬 고정
class PrCommentScriptE2ETest {

    private static Path script;

    @BeforeAll
    static void locateScript() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("scripts").resolve("pr-comment.sh");
            if (Files.isRegularFile(candidate)) {
                script = candidate;
                return;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "scripts/pr-comment.sh not found by walking up from " + Path.of("").toAbsolutePath());
    }

    @TempDir Path work;

    record Exec(int code, String out) {}

    /** 기본 실행 — 실제 PATH 상속(로컬/CI엔 진짜 gh가 있음). */
    Exec run(Map<String, String> env) throws Exception {
        return exec(env, null);
    }

    /** stub 디렉터리를 real PATH 앞에 prepend — command -v gh가 스텁을 먼저 찾음(실 gh 위치와 무관하게 항상 shadow). */
    Exec run(Map<String, String> env, Path pathPrepend) throws Exception {
        return exec(env, pathPrepend);
    }

    // 절대경로로 bash를 기동 — 어떤 PATH 조작 케이스에서도 인터프리터 자체의 탐색 실패(exit 127)를 배제.
    private static final String BASH = "/bin/bash";

    private Exec exec(Map<String, String> env, Path pathPrepend) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(BASH, script.toString());
        pb.redirectErrorStream(true);
        pb.environment().putAll(env);
        if (pathPrepend != null) {
            pb.environment().put("PATH", pathPrepend + ":" + System.getenv("PATH"));
        }
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        p.waitFor();
        return new Exec(p.exitValue(), out);
    }

    private Path writeBody(String content) throws IOException {
        Path body = work.resolve("body.md");
        Files.writeString(body, content, UTF_8);
        return body;
    }

    /** 기록형 스텁 gh: 받은 인자를 GH_ARGS_OUT에 한 줄씩 기록하고 exitCode로 종료. */
    private Path writeStubGh(int exitCode) throws IOException {
        Path stubDir = work.resolve("stub-bin");
        Files.createDirectories(stubDir);
        Path gh = stubDir.resolve("gh");
        Files.writeString(gh, "#!/bin/bash\nprintf '%s\\n' \"$@\" > \"$GH_ARGS_OUT\"\nexit " + exitCode + "\n", UTF_8);
        Files.setPosixFilePermissions(gh, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        return stubDir;
    }

    private static final String POISON_MARKER = "POISON_GH_INVOKED";

    /**
     * 포이즌 필 gh: 실제로 호출되면 고유 마커를 찍고 exit 99로 종료 — DRY_RUN 분기가 새어 gh를 부르는
     * 회귀를 어떤 러너(로컬 macOS의 /opt/homebrew/bin/gh든 GitHub-hosted ubuntu-latest의 /usr/bin/gh든)
     * 에서도 결정적으로 검출한다. PATH 선두에 prepend하면 실제 gh 위치와 무관하게 항상 이 스텁이 먼저
     * 발견되므로, PATH를 통째로 치환할 필요가 없다(치환 시 wc/awk/head 등 coreutils까지 command-not-found
     * 로 죽어 DRY_RUN 로직 자체를 검증할 수 없었음 — round-1 리뷰로 대체).
     */
    private Path writePoisonGh() throws IOException {
        Path stubDir = work.resolve("poison-bin");
        Files.createDirectories(stubDir);
        Path gh = stubDir.resolve("gh");
        Files.writeString(gh, "#!/bin/bash\necho " + POISON_MARKER + "\nexit 99\n", UTF_8);
        Files.setPosixFilePermissions(gh, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        return stubDir;
    }

    /** ImpactFormats.markdown()과 동형(요약 테이블 + 선택적 <details>)인 대량 픽스처를 approxBytes 이상으로 생성. */
    private static String impactMarkdownFixture(boolean includeDetails, int approxBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("| 선별 | DETERMINISTIC | CONSERVATIVE | 무시된 변경 |\n");
        sb.append("|---|---|---|---|\n");
        sb.append("| 500 | 400 | 100 | 3 |\n\n");
        if (includeDetails) {
            sb.append("<details><summary>선별 목록</summary>\n\n");
        }
        int i = 0;
        while (sb.toString().getBytes(UTF_8).length < approxBytes) {
            sb.append("- `io.tia.fixture.GeneratedTest#method").append(i++).append("` — DETERMINISTIC\n");
        }
        if (includeDetails) {
            sb.append("\n</details>\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("SP5-REQ-002: DRY_RUN=1이면 gh 호출 없이 API 경로+본문을 표준출력에 찍고 exit 0")
    void dryRunPrintsApiPathAndBody() throws Exception {
        Path poisonDir = writePoisonGh();
        Path body = writeBody("hello world body");

        Map<String, String> env = new HashMap<>();
        env.put("BODY_FILE", body.toString());
        env.put("PR_NUMBER", "7");
        env.put("REPO", "o/r");
        env.put("DRY_RUN", "1");

        Exec result = run(env, poisonDir);

        assertEquals(0, result.code());
        assertTrue(result.out().contains("repos/o/r/issues/7/comments"), result.out());
        assertTrue(result.out().contains("hello world body"), result.out());
        assertFalse(result.out().contains(POISON_MARKER),
                "DRY_RUN 분기가 gh를 실제로 호출했다면 포이즌 마커가 출력됐을 것: " + result.out());
    }

    @Test
    @DisplayName("SP5-REQ-003: PR_NUMBER가 비어 있으면 ::warning 출력 후 exit 0(PR 컨텍스트 아님)")
    void emptyPrNumberWarnsExitZero() throws Exception {
        Path body = writeBody("some body");

        Map<String, String> env = new HashMap<>();
        env.put("BODY_FILE", body.toString());
        env.put("PR_NUMBER", "");

        Exec result = run(env);

        assertEquals(0, result.code());
        assertTrue(result.out().contains("::warning"), result.out());
    }

    @Test
    @DisplayName("SP5-REQ-003: BODY_FILE이 존재하지 않으면 exit 1")
    void missingBodyFileFails() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("BODY_FILE", work.resolve("does-not-exist.md").toString());

        Exec result = run(env);

        assertEquals(1, result.code());
        assertTrue(result.out().contains("BODY_FILE"), result.out());
    }

    @Test
    @DisplayName("SP5-REQ-004: gh 스텁이 -F body=@file로 전달받은 파일 내용이 원본 본문과 일치")
    void stubGhReceivesFileBody() throws Exception {
        String bodyContent = "impact report body\nwith multiple lines\n";
        Path body = writeBody(bodyContent);
        Path stubDir = writeStubGh(0);
        Path ghArgsOut = work.resolve("gh-args-out.txt");

        Map<String, String> env = new HashMap<>();
        env.put("BODY_FILE", body.toString());
        env.put("PR_NUMBER", "7");
        env.put("REPO", "o/r");
        env.put("GH_ARGS_OUT", ghArgsOut.toString());
        env.put("GITHUB_TOKEN", "fake-token");

        Exec result = run(env, stubDir);

        assertEquals(0, result.code());
        assertTrue(Files.exists(ghArgsOut), "stub gh가 GH_ARGS_OUT을 기록해야 함");
        java.util.List<String> args = Files.readAllLines(ghArgsOut, UTF_8);
        String bodyArg = args.stream().filter(a -> a.startsWith("body=@")).findFirst()
                .orElseThrow(() -> new AssertionError("gh 인자에 body=@... 토큰 없음: " + args));
        Path capturedBodyFile = Path.of(bodyArg.substring("body=@".length()));
        String capturedContent = Files.readString(capturedBodyFile, UTF_8);
        assertEquals(bodyContent, capturedContent);
    }

    @Test
    @DisplayName("SP5-REQ-005: gh 실패 시 pull-requests: write 권한 힌트를 담은 ::warning 출력 후 exit 0")
    void ghFailureWarnsWithPermissionHint() throws Exception {
        Path body = writeBody("body for failure case");
        Path stubDir = writeStubGh(1);
        Path ghArgsOut = work.resolve("gh-args-out.txt");

        Map<String, String> env = new HashMap<>();
        env.put("BODY_FILE", body.toString());
        env.put("PR_NUMBER", "7");
        env.put("REPO", "o/r");
        env.put("GH_ARGS_OUT", ghArgsOut.toString());
        env.put("GITHUB_TOKEN", "fake-token");

        Exec result = run(env, stubDir);

        assertEquals(0, result.code());
        assertTrue(result.out().contains("::warning"), result.out());
        assertTrue(result.out().contains("pull-requests: write"), result.out());
    }

    @Test
    @DisplayName("SP5-REQ-006: <details> 포함 70000바이트 본문은 요약 표만 남기고 절단 안내를 덧붙여 65536바이트 미만이 됨")
    void oversizedBodyTruncated() throws Exception {
        String fixture = impactMarkdownFixture(true, 70000);
        assertTrue(fixture.getBytes(UTF_8).length >= 70000);
        Path body = writeBody(fixture);

        Map<String, String> env = new HashMap<>();
        env.put("BODY_FILE", body.toString());
        env.put("PR_NUMBER", "7");
        env.put("REPO", "o/r");
        env.put("DRY_RUN", "1");

        Exec result = run(env);

        assertEquals(0, result.code());
        String printedBody = result.out().substring(result.out().indexOf('\n') + 1);
        assertTrue(printedBody.getBytes(UTF_8).length < 65536, "truncated body must be < 65536 bytes");
        assertTrue(printedBody.contains("생략했습니다"), printedBody);
        assertTrue(printedBody.contains("| 500 | 400 | 100 | 3 |"), "첫 테이블 행은 보존돼야 함: " + printedBody);
        assertFalse(printedBody.contains("<details>"), "<details> 이후는 절단되어야 함");
    }

    @Test
    @DisplayName("SP5-REQ-006: <details> 없는 70000바이트 본문은 하드캡으로 65536바이트 미만이 됨")
    void oversizedHeadHardCapped() throws Exception {
        String fixture = impactMarkdownFixture(false, 70000);
        assertTrue(fixture.getBytes(UTF_8).length >= 70000);
        Path body = writeBody(fixture);

        Map<String, String> env = new HashMap<>();
        env.put("BODY_FILE", body.toString());
        env.put("PR_NUMBER", "7");
        env.put("REPO", "o/r");
        env.put("DRY_RUN", "1");

        Exec result = run(env);

        assertEquals(0, result.code());
        String printedBody = result.out().substring(result.out().indexOf('\n') + 1);
        assertTrue(printedBody.getBytes(UTF_8).length < 65536, "hard-capped body must be < 65536 bytes");
    }
}

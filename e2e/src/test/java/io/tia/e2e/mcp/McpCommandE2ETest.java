package io.tia.e2e.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tia.cli.TiaCommand;
import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.TestCoverage;
import io.tia.core.store.CoverageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.roaringbitmap.RoaringBitmap;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SP4-REQ-001..007 — {@code tia mcp} 최소 stdio MCP 서버의 인프로세스 stdio 블랙박스 E2E.
 * 요구사항명세 추적 매트릭스의 17개 테스트명이 유일한 소스오브트루스(design spec §6).
 *
 * <p>System.in을 준비된 JSON-RPC 라인 스트림으로, System.out을 캡처 버퍼로 스왑해 {@code tia mcp}를
 * 실행하고 응답 라인을 파싱·단언한다. System.setIn은 이 코드베이스 최초 도입 — @Execution(SAME_THREAD) +
 * 각 헬퍼 내부 try/finally 복원 + @AfterEach 안전망(복원 누락 방지 이중 방어).
 */
@Execution(ExecutionMode.SAME_THREAD)
class McpCommandE2ETest {

    private static final ObjectMapper OM = new ObjectMapper();

    private InputStream originalIn;
    private PrintStream originalOut;

    @BeforeEach
    void captureStreams() {
        originalIn = System.in;
        originalOut = System.out;
    }

    @AfterEach
    void restoreStreams() {
        System.setIn(originalIn);
        System.setOut(originalOut);
    }

    // ==== SP4-REQ-001: initialize 협상 ====

    @Test
    @DisplayName("SP4-REQ-001: 지원 목록에 있는 protocolVersion 요청 → 에코 + capabilities.tools + 접두 없는 version")
    void initializeEchoesSupportedVersion() throws Exception {
        List<JsonNode> res = run(initializeRequest(0, "2025-11-25"));

        JsonNode result = responseFor(res, 0).path("result");
        assertEquals("2025-11-25", result.path("protocolVersion").asText());
        assertTrue(result.path("capabilities").path("tools").isObject(), result.toString());
        assertEquals("tia", result.path("serverInfo").path("name").asText());
        String version = result.path("serverInfo").path("version").asText();
        assertFalse(version.isBlank(), result.toString());
        assertFalse(version.startsWith("tia "), "serverInfo.version에 CLI 배너 접두어가 섞임: " + version);
    }

    @Test
    @DisplayName("SP4-REQ-001: 지원 목록 밖 protocolVersion 요청 → 2025-06-18로 응답")
    void unsupportedVersionFallsBack() throws Exception {
        List<JsonNode> res = run(initializeRequest(0, "1999-01-01"));

        JsonNode result = responseFor(res, 0).path("result");
        assertEquals("2025-06-18", result.path("protocolVersion").asText());
    }

    // ==== SP4-REQ-002: tools/list 계약 ====

    @Test
    @DisplayName("SP4-REQ-002: tools/list → 도구 2개 + impact required=commit + 두 도구 description 비어있지 않음")
    void toolsListSchema() throws Exception {
        List<JsonNode> res = run(request(1, "tools/list", Map.of()));

        JsonNode tools = responseFor(res, 1).path("result").path("tools");
        assertEquals(2, tools.size(), tools.toString());

        JsonNode impact = findTool(tools, "tia_impact");
        JsonNode doctor = findTool(tools, "tia_doctor");
        assertNotNull(impact, tools.toString());
        assertNotNull(doctor, tools.toString());
        assertFalse(impact.path("description").asText("").isBlank());
        assertFalse(doctor.path("description").asText("").isBlank());

        List<String> required = new ArrayList<>();
        impact.path("inputSchema").path("required").forEach(n -> required.add(n.asText()));
        assertTrue(required.contains("commit"), required.toString());
    }

    // ==== SP4-REQ-003: tia_impact 호출(JSON 계약·git_ref 매핑) ====

    @Test
    @DisplayName("SP4-REQ-003: 절대경로 db+diff_file 호출 → SP1 impact JSON(schemaVersion=1, tests[]) + isError=false")
    void impactReturnsSp1Json(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "c0", List.of(
                    new TestCoverage("T_price", "PASSED",
                            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10))),
                    new TestCoverage("T_greet", "PASSED",
                            Map.of("io/tia/fixture/GreetingService.java", RoaringBitmap.bitmapOf(6, 7))))));
        }
        Path diff = dir.resolve("d.diff");
        Files.writeString(diff, """
                diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                @@ -8,1 +8,1 @@
                -    return key.length() * 100;
                +    return key.length() * 200;
                """);

        Map<String, Object> args = new HashMap<>();
        args.put("commit", "c0");
        args.put("db", db.toString());
        args.put("diff_file", diff.toString());
        List<JsonNode> res = run(toolsCallRequest(2, "tia_impact", args));

        JsonNode response = responseFor(res, 2);
        assertFalse(response.path("result").path("isError").asBoolean(true), response.toString());
        JsonNode payload = OM.readTree(toolText(response));
        assertEquals(1, payload.path("schemaVersion").asInt(), payload.toString());
        List<String> testIds = new ArrayList<>();
        payload.path("tests").forEach(n -> testIds.add(n.path("id").asText()));
        assertTrue(testIds.contains("T_price"), testIds.toString());
        assertFalse(testIds.contains("T_greet"), testIds.toString());
    }

    @Test
    @DisplayName("SP4-REQ-003: git_ref 인자가 --git-ref로 전달되어 결과가 ref에 따라 달라진다")
    void gitRefMappedToCliOption(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "tester");
        Path src = repo.resolve("fixture-app/src/main/java/io/tia/fixture/PricingService.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "class PricingService {\n    int price() { return 100; }\n}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "c1");
        String commit1 = headCommit(repo);

        Files.writeString(src, "class PricingService {\n    int price() { return 200; }\n}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "c2");
        String commit2 = headCommit(repo);

        Path db = repo.resolve("index.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "BASELINE", List.of(
                    new TestCoverage("T_price", "PASSED",
                            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(2))))));
        }

        Map<String, Object> argsWithParentRef = new HashMap<>();
        argsWithParentRef.put("commit", "BASELINE");
        argsWithParentRef.put("db", db.toString());
        argsWithParentRef.put("working_dir", repo.toString());
        argsWithParentRef.put("git_ref", commit1);
        JsonNode payloadA = OM.readTree(toolText(responseFor(
                run(toolsCallRequest(2, "tia_impact", argsWithParentRef)), 2)));
        assertTrue(payloadA.path("tests").size() > 0,
                "git_ref=부모커밋(변경 포함 diff) → 선별 비-0 기대: " + payloadA);

        Map<String, Object> argsWithHeadRef = new HashMap<>();
        argsWithHeadRef.put("commit", "BASELINE");
        argsWithHeadRef.put("db", db.toString());
        argsWithHeadRef.put("working_dir", repo.toString());
        argsWithHeadRef.put("git_ref", commit2);
        JsonNode payloadB = OM.readTree(toolText(responseFor(
                run(toolsCallRequest(2, "tia_impact", argsWithHeadRef)), 2)));
        assertEquals(0, payloadB.path("tests").size(),
                "git_ref=HEAD(작업트리와 동일, diff 없음) → 선별 0건 기대: " + payloadB);
    }

    // ==== SP4-REQ-004: working_dir 실효(diff·기본 DB·상대경로 db/diff) ====

    @Test
    @DisplayName("SP4-REQ-004: db·diff_file 생략 + working_dir=다른 @TempDir 레포 → 결과가 그 레포 기준(비-0 선별)")
    void workingDirGovernsDiffAndDb(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "tester");
        Path src = repo.resolve("fixture-app/src/main/java/io/tia/fixture/PricingService.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "class PricingService {\n    int price() { return 100; }\n}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "init");
        String head = headCommit(repo);
        Files.writeString(src, "class PricingService {\n    int price() { return 200; }\n}\n");   // unstaged

        // DbPaths.resolveDefault(workingDir) 계산 경로(일반 레포: <repo>/.git/tia/tia.db)에 직접 인덱싱.
        Path expectedDb = repo.resolve(".git").resolve("tia").resolve("tia.db");
        try (CoverageStore store = new CoverageStore(expectedDb)) {
            store.save(new CoverageSnapshot("fixture", head, List.of(
                    new TestCoverage("T_price", "PASSED",
                            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(2))))));
        }

        Map<String, Object> args = new HashMap<>();
        args.put("commit", head);
        args.put("working_dir", repo.toString());   // db·diff_file 모두 생략
        JsonNode payload = OM.readTree(toolText(responseFor(run(toolsCallRequest(3, "tia_impact", args)), 3)));

        List<String> testIds = new ArrayList<>();
        payload.path("tests").forEach(n -> testIds.add(n.path("id").asText()));
        assertTrue(testIds.contains("T_price"), "no-baseline 0건 성공으로 새면 안 됨: " + payload);
    }

    @Test
    @DisplayName("SP4-REQ-004: 상대 diff_file + working_dir → working_dir 기준 해석 성공(비-0 선별)")
    void relativeDiffFileResolvedAgainstWorkingDir(@TempDir Path workDir) throws Exception {
        Path db = workDir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "c0", List.of(
                    new TestCoverage("T_price", "PASSED",
                            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10))))));
        }
        Files.writeString(workDir.resolve("d.diff"), """
                diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                @@ -8,1 +8,1 @@
                -    return key.length() * 100;
                +    return key.length() * 200;
                """);

        Map<String, Object> args = new HashMap<>();
        args.put("commit", "c0");
        args.put("db", db.toString());
        args.put("diff_file", "d.diff");   // 상대 경로 — working_dir 기준으로 절대화돼야 함
        args.put("working_dir", workDir.toString());
        JsonNode response = responseFor(run(toolsCallRequest(4, "tia_impact", args)), 4);
        assertFalse(response.path("result").path("isError").asBoolean(true), response.toString());
        JsonNode payload = OM.readTree(toolText(response));
        List<String> testIds = new ArrayList<>();
        payload.path("tests").forEach(n -> testIds.add(n.path("id").asText()));
        assertTrue(testIds.contains("T_price"), payload.toString());
    }

    @Test
    @DisplayName("SP4-REQ-004: 상대 db + working_dir → working_dir 기준 경로의 인덱스 사용(비-0 선별, 오경로 빈 DB 침묵성공 차단)")
    void relativeDbResolvedAgainstWorkingDir(@TempDir Path workDir, @TempDir Path diffDir) throws Exception {
        Path db = workDir.resolve("sub").resolve("tia.db");   // working_dir 기준 상대 db가 가리킬 실제 경로
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "c0", List.of(
                    new TestCoverage("T_price", "PASSED",
                            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10))))));
        }
        Path diff = diffDir.resolve("d.diff");
        Files.writeString(diff, """
                diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
                @@ -8,1 +8,1 @@
                -    return key.length() * 100;
                +    return key.length() * 200;
                """);

        Map<String, Object> args = new HashMap<>();
        args.put("commit", "c0");
        args.put("db", "sub/tia.db");   // 상대 경로 — working_dir 기준으로 절대화돼야 함
        args.put("diff_file", diff.toString());
        args.put("working_dir", workDir.toString());
        JsonNode response = responseFor(run(toolsCallRequest(5, "tia_impact", args)), 5);
        assertFalse(response.path("result").path("isError").asBoolean(true), response.toString());
        JsonNode payload = OM.readTree(toolText(response));
        List<String> testIds = new ArrayList<>();
        payload.path("tests").forEach(n -> testIds.add(n.path("id").asText()));
        assertTrue(testIds.contains("T_price"),
                "오경로(서버 cwd 기준)의 빈 DB로 조용히 0건 성공하면 안 됨: " + payload);
    }

    // ==== SP4-REQ-005: tia_doctor 호출(FAIL≠isError 실증) ====

    @Test
    @DisplayName("SP4-REQ-005: 깨진 tia.yml working_dir(FAIL 확정 픽스처) → doctor JSON + isError=false")
    void doctorFailIsNotError(@TempDir Path work) throws Exception {
        Files.writeString(work.resolve("tia.yml"), "not: [valid: yaml: at all");

        Map<String, Object> args = new HashMap<>();
        args.put("working_dir", work.toString());
        JsonNode response = responseFor(run(toolsCallRequest(6, "tia_doctor", args)), 6);

        assertFalse(response.path("result").path("isError").asBoolean(true),
                "call()이 정상 반환한 FAIL(exit 1)은 isError가 아니어야 함: " + response);
        JsonNode payload = OM.readTree(toolText(response));
        assertEquals("doctor", payload.path("command").asText());
        boolean hasFail = false;
        for (JsonNode check : payload.path("checks")) {
            if ("FAIL".equals(check.path("status").asText())) hasFail = true;
        }
        assertTrue(hasFail, "깨진 tia.yml인데 FAIL 체크가 없음: " + payload);
    }

    // ==== SP4-REQ-006: 오류·수명 계약 ====

    @Test
    @DisplayName("SP4-REQ-006: 알 수 없는 메서드 → -32601")
    void unknownMethod32601() throws Exception {
        List<JsonNode> res = run(request(7, "frobnicate", Map.of()));
        JsonNode response = responseFor(res, 7);
        assertEquals(-32601, response.path("error").path("code").asInt(), response.toString());
    }

    @Test
    @DisplayName("SP4-REQ-006: 깨진 JSON 라인 → -32700(id=null) 후 후속 요청 정상 처리(서버 생존)")
    void parseError32700ThenAlive() throws Exception {
        List<JsonNode> res = run("{not valid json", request(8, "ping", Map.of()));

        assertEquals(2, res.size(), res.toString());
        JsonNode parseErrorResponse = res.get(0);
        assertTrue(parseErrorResponse.path("id").isNull(), parseErrorResponse.toString());
        assertEquals(-32700, parseErrorResponse.path("error").path("code").asInt(), parseErrorResponse.toString());

        JsonNode pingResponse = responseFor(res, 8);
        assertTrue(pingResponse.path("result").isObject(), pingResponse.toString());
    }

    @Test
    @DisplayName("SP4-REQ-006: 미존재 도구 → -32602")
    void unknownTool32602() throws Exception {
        List<JsonNode> res = run(toolsCallRequest(9, "not_a_tool", Map.of()));
        JsonNode response = responseFor(res, 9);
        assertEquals(-32602, response.path("error").path("code").asInt(), response.toString());
    }

    @Test
    @DisplayName("SP4-REQ-006: required 필드(commit) 부재 → -32602")
    void missingRequired32602() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("db", "/tmp/whatever.db");   // commit 없음
        List<JsonNode> res = run(toolsCallRequest(10, "tia_impact", args));
        JsonNode response = responseFor(res, 10);
        assertEquals(-32602, response.path("error").path("code").asInt(), response.toString());
    }

    @Test
    @DisplayName("SP4-REQ-006: impact 실행 실패(존재하지 않는 diff_file) → isError=true")
    void execFailureIsError(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {   // 베이스라인 존재 → no-baseline 조기반환 우회
            store.save(new CoverageSnapshot("fixture", "c0", List.of(
                    new TestCoverage("T_price", "PASSED",
                            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(1))))));
        }
        Map<String, Object> args = new HashMap<>();
        args.put("commit", "c0");
        args.put("db", db.toString());
        args.put("diff_file", dir.resolve("does-not-exist.diff").toString());
        JsonNode response = responseFor(run(toolsCallRequest(11, "tia_impact", args)), 11);
        assertTrue(response.path("result").path("isError").asBoolean(false), response.toString());
        assertFalse(toolText(response).isBlank(), response.toString());
    }

    @Test
    @DisplayName("SP4-REQ-006: notification(id 없음) → 응답 없음")
    void notificationNoResponse() throws Exception {
        List<JsonNode> res = run(notification("ping", Map.of()));
        assertTrue(res.isEmpty(), res.toString());
    }

    @Test
    @DisplayName("SP4-REQ-006: 입력 EOF → exit 0(빈 입력에도 정상 종료)")
    void eofExitsZero() throws Exception {
        List<JsonNode> res = run();   // 요청 없이 즉시 EOF — run()이 내부적으로 exit code 0 단언
        assertTrue(res.isEmpty(), res.toString());
    }

    @Test
    @DisplayName("SP4-REQ-006: ping → {}")
    void pingReturnsEmptyObject() throws Exception {
        List<JsonNode> res = run(request(12, "ping", Map.of()));
        JsonNode response = responseFor(res, 12);
        assertTrue(response.path("result").isObject(), response.toString());
        assertEquals(0, response.path("result").size(), response.toString());
    }

    // ---- 공통 헬퍼 ----

    /** System.in을 준비된 라인들로, System.out을 캡처 버퍼로 스왑해 `tia mcp`를 1회 구동한다.
     *  finally로 반드시 복원(@AfterEach가 이중 안전망). EOF에서 exit 0을 항상 단언한다. */
    private static List<JsonNode> run(String... lines) throws Exception {
        String input = lines.length == 0 ? "" : String.join("\n", lines) + "\n";
        InputStream prevIn = System.in;
        PrintStream prevOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code;
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            code = new CommandLine(new TiaCommand()).execute("mcp");
        } finally {
            System.setIn(prevIn);
            System.setOut(prevOut);
        }
        assertEquals(0, code, "tia mcp는 EOF에서 exit 0이어야 함");
        return parseResponses(buf.toString(StandardCharsets.UTF_8));
    }

    private static List<JsonNode> parseResponses(String captured) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String line : captured.split("\n", -1)) {
            if (line.isBlank()) continue;
            out.add(OM.readTree(line));
        }
        return out;
    }

    private static JsonNode responseFor(List<JsonNode> responses, int id) {
        return responses.stream()
                .filter(r -> r.path("id").isInt() && r.path("id").asInt() == id)
                .findFirst()
                .orElseGet(() -> fail("id=" + id + " 응답 없음: " + responses));
    }

    private static String toolText(JsonNode response) {
        return response.path("result").path("content").get(0).path("text").asText();
    }

    private static JsonNode findTool(JsonNode tools, String name) {
        for (JsonNode t : tools) {
            if (name.equals(t.path("name").asText())) return t;
        }
        return null;
    }

    private static String request(int id, String method, Object params) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        root.put("method", method);
        root.put("params", params);
        return OM.writeValueAsString(root);
    }

    private static String notification(String method, Object params) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("jsonrpc", "2.0");
        root.put("method", method);
        root.put("params", params);
        return OM.writeValueAsString(root);
    }

    private static String initializeRequest(int id, String protocolVersion) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", protocolVersion);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "claude", "version", "1.0"));
        return request(id, "initialize", params);
    }

    private static String toolsCallRequest(int id, String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        return request(id, "tools/call", params);
    }

    private static String headCommit(Path dir) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git rev-parse HEAD failed: " + out);
        }
        return out;
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}

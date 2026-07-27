package io.tia.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code tia mcp} — 최소 stdio MCP(Model Context Protocol) 서버(design spec §3).
 * 개행 구분(newline-delimited) JSON-RPC 2.0 메시지를 stdin에서 한 줄씩 읽어 stdout에 응답한다
 * (로그·진단은 stderr). 표면이 initialize/tools-list/tools-call/ping 뿐이라 외부 MCP SDK 없이
 * jackson만으로 수제 구현한다(design spec §1 비범위).
 *
 * <p><b>stdout 안전 불변식</b>: 부트스트랩 시 저장한 실제 stdout {@link PrintStream} 참조({@code
 * realOut})로만 JSON-RPC 응답을 쓴다. {@code tools/call}이 impact/doctor를 인프로세스로 실행하며
 * System.out/err를 캡처용으로 스왑하더라도(그리고 설령 그 복원이 누락되더라도) 응답 채널은 이
 * 저장된 참조 덕에 구조적으로 오염될 수 없다(design spec §3/§7).
 *
 * <p>동시성: stdio 단일 클라이언트를 순차 처리한다(멀티스레드 없음, design spec §7).
 */
@Command(name = "mcp",
        description = "stdio MCP 서버 — tools/list·tools/call로 impact/doctor를 자연어 에이전트 클라이언트에 노출")
public class McpCommand implements Callable<Integer> {

    private static final ObjectMapper OM = new ObjectMapper();

    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final String FALLBACK_PROTOCOL_VERSION = "2025-06-18";

    private static final String IMPACT_DESCRIPTION =
            "Select the tests impacted by a code change. Given a baseline commit (and optionally a "
            + "diff file or git ref), returns the minimal set of tests to run as JSON (schemaVersion 1).";
    private static final String DOCTOR_DESCRIPTION =
            "Diagnose the TIA environment and configuration (JDK, git, tia.yml, index DB, baseline "
            + "alignment). Returns per-check status and fix hints as JSON.";

    private static final int STDERR_TAIL_LINES = 10;

    @Override
    public Integer call() throws IOException {
        PrintStream realOut = System.out;   // §3 stdout 안전 불변식 — 이후 모든 JSON-RPC 응답은 이 참조로만
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;   // 빈 줄은 프로토콜 메시지가 아님 — 조용히 건너뜀
            handleLine(line, realOut);
        }
        return 0;   // EOF에서 정상 종료
    }

    private void handleLine(String line, PrintStream out) {
        JsonNode req;
        try {
            req = OM.readTree(line);
        } catch (Exception e) {
            writeError(out, null, -32700, "Parse error: " + e.getMessage());
            return;
        }
        if (req == null || !req.isObject()) {
            writeError(out, null, -32700, "Parse error: request must be a JSON object");
            return;
        }

        boolean isNotification = !req.has("id");   // id 없는 요청 = notification(응답 없음, JSON-RPC 2.0)
        JsonNode id = req.get("id");
        String method = req.path("method").asText("");
        JsonNode params = req.path("params");

        switch (method) {
            case "initialize" -> {
                if (!isNotification) respond(out, id, initializeResult(params));
            }
            case "notifications/initialized" -> { /* 무응답(사양) */ }
            case "tools/list" -> {
                if (!isNotification) respond(out, id, toolsListResult());
            }
            case "tools/call" -> {
                if (!isNotification) handleToolsCall(id, params, out);
                // notification(id 없음)의 tools/call은 응답 대상이 없어 사실상 무의미 — 실행하지 않는다.
            }
            case "ping" -> {
                if (!isNotification) respond(out, id, OM.createObjectNode());
            }
            default -> {
                if (!isNotification) writeError(out, id, -32601, "Method not found: " + method);
            }
        }
    }

    // ---- initialize ----

    private ObjectNode initializeResult(JsonNode params) {
        String requested = params.path("protocolVersion").asText(null);
        String negotiated = (requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested))
                ? requested : FALLBACK_PROTOCOL_VERSION;
        ObjectNode result = OM.createObjectNode();
        result.put("protocolVersion", negotiated);
        result.putObject("capabilities").putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "tia");
        serverInfo.put("version", loadRawVersion());
        return result;
    }

    /** {@code /tia-version.properties}의 raw {@code version} 프로퍼티(VersionProvider의 "tia X.Y.Z"
     *  CLI 배너 문자열 재사용 금지 — 접두어 오염 방지, design spec §3). */
    private static String loadRawVersion() {
        try (InputStream in = McpCommand.class.getResourceAsStream("/tia-version.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            return p.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    // ---- tools/list ----

    private ObjectNode toolsListResult() {
        ObjectNode result = OM.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(impactToolDefinition());
        tools.add(doctorToolDefinition());
        return result;
    }

    private static ObjectNode impactToolDefinition() {
        ObjectNode t = OM.createObjectNode();
        t.put("name", "tia_impact");
        t.put("description", IMPACT_DESCRIPTION);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("commit").put("type", "string");
        props.putObject("db").put("type", "string");
        props.putObject("diff_file").put("type", "string");
        props.putObject("git_ref").put("type", "string");
        props.putObject("working_dir").put("type", "string");
        schema.putArray("required").add("commit");
        return t;
    }

    private static ObjectNode doctorToolDefinition() {
        ObjectNode t = OM.createObjectNode();
        t.put("name", "tia_doctor");
        t.put("description", DOCTOR_DESCRIPTION);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("working_dir").put("type", "string");
        schema.putArray("required");
        return t;
    }

    // ---- tools/call ----

    /** 사전 검사(-32602)는 의도적으로 축소한다 — 도구 미존재·required 필드 부재 두 가지만
     *  (수제 구현엔 완전한 JSON Schema 검증기가 없음, design spec §5). 그 외 타입/값 문제는
     *  커맨드 실행 실패(isError: true)로 수렴한다. */
    private void handleToolsCall(JsonNode id, JsonNode params, PrintStream out) {
        String toolName = params.path("name").asText(null);
        JsonNode arguments = params.path("arguments");
        if (!arguments.isObject()) arguments = OM.createObjectNode();

        if (!"tia_impact".equals(toolName) && !"tia_doctor".equals(toolName)) {
            writeError(out, id, -32602, "Unknown tool: " + toolName);
            return;
        }
        if ("tia_impact".equals(toolName) && !arguments.has("commit")) {
            writeError(out, id, -32602, "Missing required argument: commit");
            return;
        }

        ToolCallResult callResult;
        try {
            callResult = "tia_impact".equals(toolName) ? callImpact(arguments) : callDoctor(arguments);
        } catch (Exception e) {   // 어댑터 수준 실패(경로 해석 등) — 커맨드 실행 실패와 동일하게 error로 수렴
            callResult = ToolCallResult.error("adapter error: " + e.getMessage());
        }

        ObjectNode result = OM.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", callResult.text());
        result.put("isError", callResult.isError());
        respond(out, id, result);
    }

    private static ToolCallResult callImpact(JsonNode arguments) throws Exception {
        Path workingDir = resolveWorkingDir(arguments);
        List<String> args = new ArrayList<>();
        args.add("impact");
        args.add("--format");
        args.add("json");
        args.add("--commit");
        args.add(arguments.get("commit").asText());
        if (arguments.has("db")) {
            args.add("--db");
            args.add(absolutize(arguments.get("db").asText(), workingDir));
        }
        if (arguments.has("diff_file")) {
            args.add("--diff-file");
            args.add(absolutize(arguments.get("diff_file").asText(), workingDir));
        }
        if (arguments.has("git_ref")) {
            args.add("--git-ref");
            args.add(arguments.get("git_ref").asText());
        }
        if (workingDir != null) {
            args.add("--search-root");
            args.add(workingDir.toString());
            args.add("--working-dir");
            args.add(workingDir.toString());
        }
        ExecCapture cap = execInProcess(args);
        if (cap.code() != 0) return ToolCallResult.error(lastLines(cap.err(), STDERR_TAIL_LINES));
        return ToolCallResult.ok(cap.out());
    }

    /** {@code DoctorCommand.call()}이 정상 반환한 exit 1(FAIL 존재)은 정상 도구 결과다(isError 아님) —
     *  SP3 §3 불변식(진단은 예외로 죽지 않는다). 이 메서드가 예외를 던지는 경우만(어댑터/파싱 수준
     *  실패) 위 handleToolsCall의 catch에서 error로 수렴한다(design spec §2). */
    private static ToolCallResult callDoctor(JsonNode arguments) throws Exception {
        Path workingDir = resolveWorkingDir(arguments);
        List<String> args = new ArrayList<>();
        args.add("doctor");
        args.add("--format");
        args.add("json");
        if (workingDir != null) {
            args.add("--search-root");
            args.add(workingDir.toString());
            args.add("--working-dir");
            args.add(workingDir.toString());
        }
        ExecCapture cap = execInProcess(args);
        return ToolCallResult.ok(cap.out());
    }

    private static Path resolveWorkingDir(JsonNode arguments) {
        if (!arguments.has("working_dir")) return null;
        return Path.of(arguments.get("working_dir").asText()).toAbsolutePath().normalize();
    }

    /** 상대 경로면 working_dir(없으면 서버 프로세스 cwd) 기준으로 절대화한다 — 단일 JVM엔
     *  호출별 cwd가 없으므로 어댑터가 명시적으로 계약한다(design spec §2). */
    private static String absolutize(String maybeRelative, Path workingDir) {
        Path p = Path.of(maybeRelative);
        if (p.isAbsolute()) return p.toString();
        Path base = (workingDir != null) ? workingDir : Path.of("").toAbsolutePath();
        return base.resolve(p).normalize().toString();
    }

    /** DemoCommand의 캡처+finally-복원 관용구를 따르되 stdout도 함께 스왑한다(도구 결과 JSON은
     *  stdout 캡처가 본질 — DemoCommand는 err만 스왑). 응답 채널은 bootstrap에서 저장한 realOut
     *  참조로 별도 보호되므로 이 스왑·복원과 무관하게 안전하다(§3 불변식). */
    private static ExecCapture execInProcess(List<String> args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream bufOut = new ByteArrayOutputStream();
        ByteArrayOutputStream bufErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bufOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(bufErr, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = new CommandLine(new TiaCommand()).execute(args.toArray(new String[0]));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new ExecCapture(code, bufOut.toString(StandardCharsets.UTF_8), bufErr.toString(StandardCharsets.UTF_8));
    }

    private static String lastLines(String text, int max) {
        String[] lines = text.split("\n", -1);
        int from = Math.max(0, lines.length - max);
        return String.join("\n", Arrays.asList(lines).subList(from, lines.length));
    }

    // ---- JSON-RPC 응답 배선 ----

    private static void respond(PrintStream out, JsonNode id, ObjectNode result) {
        ObjectNode root = OM.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.set("id", id);
        root.set("result", result);
        writeLine(out, root);
    }

    private static void writeError(PrintStream out, JsonNode id, int code, String message) {
        ObjectNode root = OM.createObjectNode();
        root.put("jsonrpc", "2.0");
        if (id != null) root.set("id", id); else root.putNull("id");
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message);
        writeLine(out, root);
    }

    private static void writeLine(PrintStream out, ObjectNode root) {
        try {
            out.println(OM.writeValueAsString(root));   // compact(단일 줄) — pretty-print 금지(개행 구분 계약)
        } catch (JsonProcessingException e) {
            // ObjectNode 트리 직렬화 실패는 사실상 발생하지 않음 — 방어적 무시(응답 채널을 죽이지 않음)
        }
    }

    private record ToolCallResult(String text, boolean isError) {
        static ToolCallResult ok(String text) { return new ToolCallResult(text, false); }
        static ToolCallResult error(String text) { return new ToolCallResult(text, true); }
    }

    private record ExecCapture(int code, String out, String err) {}
}

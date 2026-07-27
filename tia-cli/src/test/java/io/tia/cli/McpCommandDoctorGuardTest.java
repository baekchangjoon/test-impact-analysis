package io.tia.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP4-REQ-006 최종 수정 웨이브 — {@code McpCommand.validateDoctorOutput}(callDoctor 침묵 실패 가드)의
 * 단위 테스트. package-private 접근으로 {@link McpCommand.ExecCapture}/{@link McpCommand.ToolCallResult}를
 * 직접 구성해, E2E 픽스처를 새로 만들지 않고도 가드 로직만 격리 검증한다.
 */
class McpCommandDoctorGuardTest {

    @Test
    @DisplayName("stdout이 유효한 JSON이면 ok(그대로) 반환")
    void validJsonPassesThrough() {
        McpCommand.ExecCapture cap = new McpCommand.ExecCapture(0, "{\"command\":\"doctor\"}", "");

        McpCommand.ToolCallResult result = McpCommand.validateDoctorOutput(cap);

        assertFalse(result.isError(), result.text());
        assertEquals("{\"command\":\"doctor\"}", result.text());
    }

    @Test
    @DisplayName("stdout이 비어있으면(exit 0이어도) isError=true + 진단 메시지")
    void blankStdoutIsError() {
        McpCommand.ExecCapture cap = new McpCommand.ExecCapture(0, "", "");

        McpCommand.ToolCallResult result = McpCommand.validateDoctorOutput(cap);

        assertTrue(result.isError(), result.text());
        assertFalse(result.text().isBlank());
        assertTrue(result.text().contains("no output"), result.text());
    }

    @Test
    @DisplayName("stdout이 공백뿐이면 isError=true + 진단 메시지")
    void blankWhitespaceStdoutIsError() {
        McpCommand.ExecCapture cap = new McpCommand.ExecCapture(0, "   \n  ", "");

        McpCommand.ToolCallResult result = McpCommand.validateDoctorOutput(cap);

        assertTrue(result.isError(), result.text());
    }

    @Test
    @DisplayName("stdout이 JSON으로 파싱 불가능하면 isError=true + stderr 꼬리 포함")
    void unparseableStdoutIsErrorWithStderrTail() {
        McpCommand.ExecCapture cap = new McpCommand.ExecCapture(1, "not json at all", "boom: something failed");

        McpCommand.ToolCallResult result = McpCommand.validateDoctorOutput(cap);

        assertTrue(result.isError(), result.text());
        assertTrue(result.text().contains("not valid JSON"), result.text());
        assertTrue(result.text().contains("boom: something failed"), result.text());
    }
}

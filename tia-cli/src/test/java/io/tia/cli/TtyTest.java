package io.tia.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtyTest {

    @Test
    @DisplayName("FU-REQ-001: JDK 17 런타임에서는 Console.isTerminal()이 없어 System.console() != null로 수렴")
    void fallbackOnJdk17() {
        // 이 스위트는 실제 JDK 17 툴체인에서 실행된다 — java.io.Console에 isTerminal()이 없으므로
        // 리플렉션이 항상 miss하고 기존 System.console() != null 폴백과 동치여야 한다.
        assertEquals(System.console() != null, Tty.interactive());
    }

    @Test
    @DisplayName("FU-REQ-001: consoleOrNull이 null이면 false(호출측 System.console() == null과 동치)")
    void nullConsoleIsNotInteractive() {
        assertFalse(Tty.interactive(null));
    }

    @Test
    @DisplayName("FU-REQ-001: isTerminal()을 가진 스텁이 있으면 그 값에 위임한다(JDK22+ 시뮬레이션)")
    void delegatesWhenIsTerminalPresent() {
        Object redirectedStub = new Object() {
            @SuppressWarnings("unused")
            public boolean isTerminal() { return false; }
        };
        Object terminalStub = new Object() {
            @SuppressWarnings("unused")
            public boolean isTerminal() { return true; }
        };

        assertFalse(Tty.interactive(redirectedStub),
                "isTerminal()=false인 스텁은 non-null이어도 interactive가 아니어야 함(JDK22+ 리다이렉트 방지)");
        assertTrue(Tty.interactive(terminalStub));
    }
}

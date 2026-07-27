package io.tia.cli;

import java.lang.reflect.Method;

/**
 * 대화형 TTY 판별 [FU-REQ-001]. JDK 22+에서 {@code System.console() != null}은 출력이 리다이렉트돼도
 * non-null일 수 있어(JLine 콘솔 통합) 더 이상 안전한 TTY 신호가 아니다 — 파이프에 ANSI가 새거나
 * 비대화형 프롬프트 게이트가 뚫릴 위험. {@code Console.isTerminal()}(JDK 22+ API)을 리플렉션으로 우선
 * 사용하고(컴파일은 JDK 17 유지), 그 메서드가 없거나(JDK 17-21) 호출이 실패하면 기존
 * {@code System.console() != null} 폴백으로 수렴한다(예외 전파 금지).
 */
final class Tty {
    private Tty() {}

    /** ImpactCommand/FlakyCommand/InitCommand의 실제 진입점. */
    static boolean interactive() {
        return interactive(System.console());
    }

    /**
     * 테스트 시임: {@code Console}(또는 {@code isTerminal()}을 갖는 임의의 스텁 객체)을 직접 주입한다.
     * consoleOrNull이 null이면 false. 아니면 리플렉션으로 {@code isTerminal()}을 찾아 위임하고,
     * 없거나(JDK 17-21) 호출이 실패하면 true로 수렴(consoleOrNull != null이 이미 확정된 상태이므로
     * 기존 {@code System.console() != null} 폴백과 동치).
     */
    static boolean interactive(Object consoleOrNull) {
        if (consoleOrNull == null) return false;
        try {
            Method isTerminal = consoleOrNull.getClass().getMethod("isTerminal");
            return (boolean) isTerminal.invoke(consoleOrNull);
        } catch (Exception e) {   // NoSuchMethodException(JDK 17-21) 등 — 폴백으로 수렴, 예외 전파 금지
            return true;
        }
    }
}

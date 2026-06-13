package io.tia.junit;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 각 테스트 전후로 teamscale 에이전트에 start/end를 신호한다.
 * 에이전트 주소: 시스템 프로퍼티 tia.agent.url (기본 http://localhost:8123).
 */
public class TeamscaleTestwiseExtension implements BeforeEachCallback, AfterEachCallback {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private final AgentClient client;

    public TeamscaleTestwiseExtension() {
        this(new HttpAgentClient(System.getProperty("tia.agent.url", "http://localhost:8123")));
    }

    TeamscaleTestwiseExtension(AgentClient client) { this.client = client; }

    @Override public void beforeEach(ExtensionContext ctx) {
        String id = uniformPath(ctx);
        CURRENT.set(id);                 // §5.1/D9: 클라이언트(RestAssured 필터)가 test.id Baggage로 전파할 현재 테스트 id
        client.testStart(id);
    }

    @Override public void afterEach(ExtensionContext ctx) {
        try {
            String result = ctx.getExecutionException().isPresent() ? "FAILED" : "PASSED";
            client.testEnd(uniformPath(ctx), result);
        } finally {
            CURRENT.remove();
        }
    }

    /** 현재 실행 중인 테스트의 uniformPath — RestAssured 필터가 모든 인입 요청에 test.id Baggage로 주입(§5.1). 없으면 null. */
    public static String currentTestId() { return CURRENT.get(); }

    private static String uniformPath(ExtensionContext ctx) {
        return ctx.getRequiredTestClass().getName().replace('.', '/')
                + "/" + ctx.getRequiredTestMethod().getName();
    }
}

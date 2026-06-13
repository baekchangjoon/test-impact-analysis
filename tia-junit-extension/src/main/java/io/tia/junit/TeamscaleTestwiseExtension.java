package io.tia.junit;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 각 테스트 전후로 teamscale 에이전트에 start/end를 신호한다.
 * 에이전트 주소: 시스템 프로퍼티 tia.agent.url (기본 http://localhost:8123).
 */
public class TeamscaleTestwiseExtension implements BeforeEachCallback, AfterEachCallback {
    private final AgentClient client;

    public TeamscaleTestwiseExtension() {
        this(new HttpAgentClient(System.getProperty("tia.agent.url", "http://localhost:8123")));
    }

    TeamscaleTestwiseExtension(AgentClient client) { this.client = client; }

    @Override public void beforeEach(ExtensionContext ctx) {
        client.testStart(uniformPath(ctx));
    }

    @Override public void afterEach(ExtensionContext ctx) {
        String result = ctx.getExecutionException().isPresent() ? "FAILED" : "PASSED";
        client.testEnd(uniformPath(ctx), result);
    }

    private static String uniformPath(ExtensionContext ctx) {
        return ctx.getRequiredTestClass().getName().replace('.', '/')
                + "/" + ctx.getRequiredTestMethod().getName();
    }
}

package io.tia.junit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpAgentClientTest {
    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            hits.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath());   // raw: %2F 유지(인코딩 검증)
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void postsStartAndEnd() {
        String base = "http://localhost:" + server.getAddress().getPort();
        AgentClient client = new HttpAgentClient(base);
        client.testStart("io/tia/fixture/ApiSmokeTest/testPrice");
        client.testEnd("io/tia/fixture/ApiSmokeTest/testPrice", "PASSED");
        // 슬래시는 %2F로 인코딩되어 단일 세그먼트로 전송돼야 한다(teamscale {testId} 요건).
        assertTrue(hits.contains("POST /test/start/io%2Ftia%2Ffixture%2FApiSmokeTest%2FtestPrice"), hits.toString());
        assertTrue(hits.contains("POST /test/end/io%2Ftia%2Ffixture%2FApiSmokeTest%2FtestPrice"), hits.toString());
    }
}

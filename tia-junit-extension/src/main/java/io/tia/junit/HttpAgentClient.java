package io.tia.junit;

import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** teamscale-jacoco-agent testwise 제어 엔드포인트 호출 (경로는 agent-api.md 검증값). Java 8 호환. */
public final class HttpAgentClient implements AgentClient {
    private final String baseUrl;

    public HttpAgentClient(String baseUrl) { this.baseUrl = stripTrailingSlash(baseUrl); }

    @Override public void testStart(String uniformPath) {
        post(baseUrl + "/test/start/" + encodeSegment(uniformPath), null);
    }

    @Override public void testEnd(String uniformPath, String result) {
        post(baseUrl + "/test/end/" + encodeSegment(uniformPath), "{\"result\":\"" + result + "\"}");
    }

    /**
     * uniformPath를 단일 path 세그먼트로 인코딩(슬래시 → %2F). teamscale 엔드포인트는 {testId}가
     * 단일 세그먼트라 슬래시를 인코딩하지 않으면 라우팅 실패한다(실측: raw 슬래시=HTTP 500, %2F=204).
     */
    private static String encodeSegment(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);   // UTF-8은 항상 존재
        }
    }

    private static void post(String url, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();   // 응답을 읽어 연결 완료
            if (code >= 400) throw new IllegalStateException("agent " + url + " → HTTP " + code);
        } catch (Exception e) {
            throw new RuntimeException("agent 호출 실패: " + url, e);
        } finally { if (c != null) c.disconnect(); }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

package io.tia.fixture;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** tia.run.out 로 지정된 파일에 {"results":{testId:passed}} 를 기록 (tia flaky 입력). */
public class RunResultWriter implements TestWatcher, AfterAllCallback {
    private final Map<String, Boolean> results = new ConcurrentHashMap<>();

    private String id(ExtensionContext ctx) {
        return ctx.getRequiredTestClass().getName().replace('.', '/')
                + "/" + ctx.getRequiredTestMethod().getName();
    }

    @Override public void testSuccessful(ExtensionContext ctx) { results.put(id(ctx), true); }

    @Override public void testFailed(ExtensionContext ctx, Throwable cause) { results.put(id(ctx), false); }

    @Override public void afterAll(ExtensionContext ctx) throws IOException {
        String out = System.getProperty("tia.run.out");
        if (out == null) return;
        StringBuilder sb = new StringBuilder("{\"results\":{");
        int i = 0;
        for (Map.Entry<String, Boolean> e : results.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append("}}");
        Files.write(Paths.get(out), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}

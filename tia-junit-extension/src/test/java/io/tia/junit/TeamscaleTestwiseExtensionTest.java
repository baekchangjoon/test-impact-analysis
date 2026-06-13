package io.tia.junit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamscaleTestwiseExtensionTest {
    static class RecordingClient implements AgentClient {
        final List<String> calls = new ArrayList<>();
        public void testStart(String p) { calls.add("start:" + p); }
        public void testEnd(String p, String r) { calls.add("end:" + p + ":" + r); }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void signalsStartThenEndWithUniformPath() throws Exception {
        RecordingClient client = new RecordingClient();
        TeamscaleTestwiseExtension ext = new TeamscaleTestwiseExtension(client);

        ExtensionContext ctx = mock(ExtensionContext.class);
        when(ctx.getRequiredTestClass()).thenReturn((Class) SampleTest.class);
        when(ctx.getRequiredTestMethod()).thenReturn(SampleTest.class.getMethod("testPrice"));
        when(ctx.getExecutionException()).thenReturn(Optional.empty());

        ext.beforeEach(ctx);
        ext.afterEach(ctx);

        assertEquals(List.of(
            "start:io/tia/junit/SampleTest/testPrice",
            "end:io/tia/junit/SampleTest/testPrice:PASSED"), client.calls);
    }
}

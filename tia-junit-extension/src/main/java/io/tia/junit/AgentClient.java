package io.tia.junit;

public interface AgentClient {
    void testStart(String uniformPath);
    void testEnd(String uniformPath, String result);
}

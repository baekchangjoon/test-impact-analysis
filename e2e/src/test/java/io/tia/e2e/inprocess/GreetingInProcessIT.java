package io.tia.e2e.inprocess;

import io.tia.fixture.GreetingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("inprocess-tester")
class GreetingInProcessIT extends InProcessTesterBase {
    private final GreetingService svc = new GreetingService();   // in-JVM 직접 호출(Spring 없음)
    @Test void greetAlice(TestInfo i) throws Throwable { probe(i, () -> assertEquals("hello alice", svc.greet("Alice"))); }
    @Test void greetBob(TestInfo i)   throws Throwable { probe(i, () -> assertEquals("hello bob",   svc.greet("Bob"))); }
    @Test void greetCarol(TestInfo i) throws Throwable { probe(i, () -> assertEquals("hello carol", svc.greet("Carol"))); }
    @Test void greetDave(TestInfo i)  throws Throwable { probe(i, () -> assertEquals("hello dave",  svc.greet("Dave"))); }
}

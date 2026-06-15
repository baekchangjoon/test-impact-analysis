package io.tia.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionProviderTest {

    @Test
    void readsVersionFromGeneratedResource() throws Exception {
        String[] v = new VersionProvider().getVersion();
        assertEquals(1, v.length);
        // generated /tia-version.properties drives this (D1 single source) — not the literal "unknown"
        assertTrue(v[0].startsWith("tia "), v[0]);
        assertTrue(v[0].matches("tia \\S+"), "version present: " + v[0]);
    }

    @Test
    void cliVersionOptionUsesProvider() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        int code = new CommandLine(new TiaCommand()).execute("--version");
        System.setOut(prev);
        assertEquals(0, code);
        assertTrue(out.toString().startsWith("tia "), out.toString());
    }
}

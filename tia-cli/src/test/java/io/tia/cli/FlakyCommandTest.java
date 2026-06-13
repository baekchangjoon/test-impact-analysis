package io.tia.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlakyCommandTest {
    @Test
    void reportsFlakyRatio(@TempDir Path dir) throws Exception {
        Path r1 = dir.resolve("run1.json"); Files.writeString(r1, "{\"results\":{\"T_ok\":true,\"T_flaky\":true}}");
        Path r2 = dir.resolve("run2.json"); Files.writeString(r2, "{\"results\":{\"T_ok\":true,\"T_flaky\":false}}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out; System.setOut(new PrintStream(out));
        int code = new CommandLine(new TiaCommand()).execute(
            "flaky", "--runs", r1.toString() + "," + r2.toString());
        System.setOut(prev);

        assertEquals(0, code);
        String printed = out.toString();
        assertTrue(printed.contains("T_flaky"), printed);
        assertTrue(printed.contains("0.5") || printed.contains("50"), printed);
    }
}

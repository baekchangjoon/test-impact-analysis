package io.tia.e2e.inprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverlapProbeAnalyzerTest {
    @Test void countsOverlappingPairs(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("ov.csv");
        // A[100,300], B[200,400] 겹침; C[500,600] 비겹침 → 겹치는 쌍 1
        Files.writeString(f, "A,START,100\nB,START,200\nA,END,300\nB,END,400\nC,START,500\nC,END,600\n");
        assertEquals(1, OverlapProbeAnalyzer.overlappingPairs(f));
    }
    @Test void noOverlapWhenSequential(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("ov.csv");
        Files.writeString(f, "A,START,100\nA,END,200\nB,START,200\nB,END,300\n");
        assertEquals(0, OverlapProbeAnalyzer.overlappingPairs(f));
    }
}

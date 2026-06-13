package io.tia.cli;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.store.CoverageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexCommandTest {
    @Test
    void indexesReportIntoStore(@TempDir Path dir) throws Exception {
        Path report = dir.resolve("r.json");
        Files.writeString(report, """
            {"tests":[{"uniformPath":"io/tia/fixture/ApiSmokeTest/testPrice","result":"PASSED",
              "paths":[{"path":"io/tia/fixture","files":[{"fileName":"PricingService.java","coveredLines":"8-10"}]}]}]}""");
        Path db = dir.resolve("tia.db");

        int code = new CommandLine(new TiaCommand()).execute(
            "index", "--report", report.toString(), "--repo", "fixture", "--commit", "c0", "--db", db.toString());
        assertEquals(0, code);

        try (CoverageStore store = new CoverageStore(db)) {
            CoverageSnapshot snap = store.load("c0");
            assertEquals(1, snap.tests().size());
            assertTrue(snap.tests().get(0).covers("io/tia/fixture/PricingService.java"));
        }
    }
}

package io.tia.cli;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.store.CoverageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @DisplayName("REQ-006/008: --db 생략 시 기본 경로 생성 + 기본 DB INFO(stderr)")
    void defaultDbWhenOmitted(@TempDir Path dir) throws Exception {
        Path report = dir.resolve("r.json");
        Files.writeString(report, """
            {"tests":[{"uniformPath":"io/tia/fixture/ApiSmokeTest/testPrice","result":"PASSED",
              "paths":[{"path":"io/tia/fixture","files":[{"fileName":"PricingService.java","coveredLines":"8-10"}]}]}]}""");
        Path expected = DbPaths.resolveDefault();        // 현재 JVM cwd 기준 실제 기본 경로
        boolean preexisting = Files.exists(expected);    // 사용자 실제 인덱스 보호 가드
        try {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream prevErr = System.err; System.setErr(new PrintStream(err));
            int code = new CommandLine(new TiaCommand()).execute(
                "index", "--report", report.toString(), "--repo", "fixture", "--commit", "cDEF");  // --db 없음
            System.setErr(prevErr);
            assertEquals(0, code);
            assertTrue(Files.exists(expected), "기본 DB 생성: " + expected);
            assertTrue(err.toString().contains("기본 인덱스 DB"), err.toString());
        } finally {
            // 테스트가 새로 만든 경우에만 정리(기존 인덱스가 있었다면 건드리지 않음).
            if (!preexisting) Files.deleteIfExists(expected);
        }
    }

    @Test
    @DisplayName("REQ-009: --db 명시 시 부모 디렉터리 자동 생성 + INFO 없음")
    void explicitDbCreatesParentNoInfo(@TempDir Path dir) throws Exception {
        Path report = dir.resolve("r.json");
        Files.writeString(report, """
            {"tests":[{"uniformPath":"io/tia/fixture/ApiSmokeTest/testPrice","result":"PASSED",
              "paths":[{"path":"io/tia/fixture","files":[{"fileName":"PricingService.java","coveredLines":"8-10"}]}]}]}""");
        Path db = dir.resolve("nested").resolve("tia.db");   // 부모 부재
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream prevErr = System.err; System.setErr(new PrintStream(err));
        int code = new CommandLine(new TiaCommand()).execute(
            "index", "--report", report.toString(), "--repo", "fixture", "--commit", "c0", "--db", db.toString());
        System.setErr(prevErr);
        assertEquals(0, code);
        assertTrue(Files.exists(db));
        assertFalse(err.toString().contains("기본 인덱스 DB"), err.toString());
    }
}

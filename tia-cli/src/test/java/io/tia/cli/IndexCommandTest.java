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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

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
        // 충돌 없는 고유 커밋 식별자 — 이 테스트만의 행을 cleanup에서 정확히 지우기 위해 사용
        final String TEST_COMMIT = "IDX_DEFAULT_REQ006";
        try {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream prevErr = System.err; System.setErr(new PrintStream(err));
            int code = new CommandLine(new TiaCommand()).execute(
                "index", "--report", report.toString(), "--repo", "fixture", "--commit", TEST_COMMIT);  // --db 없음
            System.setErr(prevErr);
            assertEquals(0, code);
            assertTrue(Files.exists(expected), "기본 DB 생성: " + expected);
            assertTrue(err.toString().contains("기본 인덱스 DB"), err.toString());
        } finally {
            if (preexisting) {
                // 기존 DB가 있었다면: 이 테스트가 삽입한 행만 정확히 삭제 → 실제 인덱스 오염 방지
                deleteTestCommitRows(expected, TEST_COMMIT);
            } else {
                // 이 테스트가 새로 만든 파일이라면: 파일 전체 삭제
                Files.deleteIfExists(expected);
            }
        }
    }

    /**
     * 기본 DB에서 특정 commit_sha의 coverage·builds 행만 삭제.
     * preexisting DB가 있을 때 테스트 오염 방지용.
     */
    private static void deleteTestCommitRows(Path dbPath, String commitSha) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement delCov = conn.prepareStatement(
                 "DELETE FROM coverage WHERE build_id IN (SELECT build_id FROM builds WHERE commit_sha=?)");
             PreparedStatement delBuild = conn.prepareStatement(
                 "DELETE FROM builds WHERE commit_sha=?")) {
            delCov.setString(1, commitSha);
            delCov.executeUpdate();
            delBuild.setString(1, commitSha);
            delBuild.executeUpdate();
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

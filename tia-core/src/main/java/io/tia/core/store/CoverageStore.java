package io.tia.core.store;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.TestCoverage;
import org.roaringbitmap.RoaringBitmap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 레포당 SQLite 스냅샷. 모든 레코드 키에 commit_sha 포함(설계 §6.1). */
public final class CoverageStore implements AutoCloseable {
    private final Connection conn;

    public CoverageStore(Path dbFile) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString());
            initSchema();
        } catch (SQLException | IOException e) { throw new RuntimeException(e); }
    }

    private void initSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS builds(" +
                "build_id INTEGER PRIMARY KEY AUTOINCREMENT, repo TEXT, commit_sha TEXT, indexed_at TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS coverage(" +
                "build_id INTEGER, test_id TEXT, result TEXT, file TEXT, line_bitmap BLOB)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cov_build ON coverage(build_id)");
        }
    }

    public long save(CoverageSnapshot snap) {
        try {
            long buildId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO builds(repo, commit_sha, indexed_at) VALUES(?,?,datetime('now'))",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, snap.repo());
                ps.setString(2, snap.commitSha());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); buildId = rs.getLong(1); }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO coverage(build_id, test_id, result, file, line_bitmap) VALUES(?,?,?,?,?)")) {
                for (TestCoverage t : snap.tests()) {
                    for (Map.Entry<String, RoaringBitmap> e : t.linesByFile().entrySet()) {
                        ps.setLong(1, buildId);
                        ps.setString(2, t.testId());
                        ps.setString(3, t.result());
                        ps.setString(4, e.getKey());
                        ps.setBytes(5, toBytes(e.getValue()));
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
            return buildId;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 해당 commit의 모든 build를 test_id별 최신-build-wins로 병합한 스냅샷. */
    public CoverageSnapshot load(String commitSha) {
        try {
            // 1단계: builds 열거(오름차순). repo는 마지막 행 = 최대 build_id의 값.
            List<Long> buildIds = new ArrayList<>();
            String repo = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT build_id, repo FROM builds WHERE commit_sha=? ORDER BY build_id ASC")) {
                ps.setString(1, commitSha);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { buildIds.add(rs.getLong(1)); repo = rs.getString(2); }
                }
            }
            if (buildIds.isEmpty()) return new CoverageSnapshot(null, commitSha, List.of());

            // 2단계: coverage를 build_id 오름차순으로 읽어 test_id별 최신 build로 병합.
            // buildIds는 자체 DB의 AUTOINCREMENT long이라 IN 절 인라인이 안전.
            String inClause = buildIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            Map<String, Map<String, RoaringBitmap>> byTest = new LinkedHashMap<>();
            Map<String, String> resultByTest = new LinkedHashMap<>();
            Map<String, Long> winningBuild = new HashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                    "SELECT build_id, test_id, result, file, line_bitmap FROM coverage " +
                    "WHERE build_id IN (" + inClause + ") ORDER BY build_id ASC")) {
                while (rs.next()) {
                    long bid = rs.getLong(1);
                    String tid = rs.getString(2);
                    long w = winningBuild.getOrDefault(tid, -1L);   // 첫 만남이면 -1L → 항상 reset
                    if (bid > w) {                                   // 더 높은 build → 기존 엔트리 리셋
                        winningBuild.put(tid, bid);
                        byTest.put(tid, new LinkedHashMap<>());
                        resultByTest.put(tid, rs.getString(3));
                    }
                    byTest.get(tid).put(rs.getString(4), fromBytes(rs.getBytes(5)));   // 같은 build면 누적
                }
            }
            List<TestCoverage> tests = new ArrayList<>();
            for (Map.Entry<String, Map<String, RoaringBitmap>> e : byTest.entrySet())
                tests.add(new TestCoverage(e.getKey(), resultByTest.get(e.getKey()), e.getValue()));
            return new CoverageSnapshot(repo, commitSha, tests);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 해당 commit의 distinct build 수(없으면 0). 상태 없는 쿼리 — thread-safe. */
    public int distinctBuildCount(String commitSha) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(DISTINCT build_id) FROM builds WHERE commit_sha=?")) {
            ps.setString(1, commitSha);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    static byte[] toBytes(RoaringBitmap b) {
        b.runOptimize();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try { b.serialize(new DataOutputStream(bos)); } catch (IOException e) { throw new UncheckedIOException(e); }
        return bos.toByteArray();
    }

    static RoaringBitmap fromBytes(byte[] data) {
        RoaringBitmap b = new RoaringBitmap();
        try { b.deserialize(new DataInputStream(new ByteArrayInputStream(data))); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        return b;
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException e) { throw new RuntimeException(e); }
    }
}

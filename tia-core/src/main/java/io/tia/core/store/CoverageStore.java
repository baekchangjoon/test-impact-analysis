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
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 레포당 SQLite 스냅샷. 모든 레코드 키에 commit_sha 포함(설계 §6.1). */
public final class CoverageStore implements AutoCloseable {
    private final Connection conn;

    public CoverageStore(Path dbFile) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString());
            initSchema();
        } catch (SQLException e) { throw new RuntimeException(e); }
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

    /** 해당 커밋의 가장 최근 빌드 스냅샷을 로드. */
    public CoverageSnapshot load(String commitSha) {
        try {
            long buildId = -1; String repo = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT build_id, repo FROM builds WHERE commit_sha=? ORDER BY build_id DESC LIMIT 1")) {
                ps.setString(1, commitSha);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return new CoverageSnapshot(null, commitSha, List.of());
                    buildId = rs.getLong(1); repo = rs.getString(2);
                }
            }
            Map<String, Map<String, RoaringBitmap>> byTest = new LinkedHashMap<>();
            Map<String, String> resultByTest = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT test_id, result, file, line_bitmap FROM coverage WHERE build_id=?")) {
                ps.setLong(1, buildId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tid = rs.getString(1);
                        resultByTest.put(tid, rs.getString(2));
                        byTest.computeIfAbsent(tid, k -> new LinkedHashMap<>())
                              .put(rs.getString(3), fromBytes(rs.getBytes(4)));
                    }
                }
            }
            List<TestCoverage> tests = new ArrayList<>();
            for (Map.Entry<String, Map<String, RoaringBitmap>> e : byTest.entrySet())
                tests.add(new TestCoverage(e.getKey(), resultByTest.get(e.getKey()), e.getValue()));
            return new CoverageSnapshot(repo, commitSha, tests);
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

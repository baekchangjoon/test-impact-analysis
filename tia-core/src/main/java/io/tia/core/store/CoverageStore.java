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

    /** 쓰기 오픈 — index 등 인덱싱 경로. busy_timeout=5000(커넥션 스코프) + journal_mode=WAL(파일
     *  헤더에 영속되는 전환, best-effort) 적용 [FU-REQ-003]. */
    public CoverageStore(Path dbFile) {
        this(dbFile, true);
    }

    /** 읽기 오픈 — doctor/impact 등 조회 전용 경로. busy_timeout=5000만 적용하고 journal_mode는
     *  절대 건드리지 않는다 — 기존 non-WAL DB를 WAL로 전환하면 doctor의 "진단 도구가 사용자 DB를
     *  변형하지 않는다"는 읽기 전용 불변식이 깨진다 [FU-REQ-003]. */
    public static CoverageStore openRead(Path dbFile) {
        return new CoverageStore(dbFile, false);
    }

    private CoverageStore(Path dbFile, boolean write) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString());
            applyBusyTimeout();
            if (write) applyWalBestEffort();
            initSchema();
        } catch (SQLException | IOException e) { throw new RuntimeException(e); }
    }

    /** 모든 오픈에 적용 — 동시 접근 시 즉시 SQLITE_BUSY 대신 최대 5초 대기(커넥션 스코프, 파일 무변형). */
    private void applyBusyTimeout() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
        }
    }

    /** WAL 전환은 DB 파일 헤더에 영속되는 쓰기라서 쓰기 오픈에만 적용한다. 실패는 무시하고
     *  busy_timeout만으로 계속 동작(best-effort — 예: 동시 오픈 중 전환 실패). */
    private void applyWalBestEffort() {
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
        } catch (SQLException ignored) {
            // best-effort — 무시
        }
    }

    /** 테스트 전용 시임(패키지 프라이빗): busy_timeout은 파일에 영속되지 않는 커넥션 스코프 설정이라
     *  이 커넥션 자체에서 조회해야 한다 [FU-REQ-003 테스트]. */
    int busyTimeoutMillis() {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("PRAGMA busy_timeout")) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 테스트 전용 시임(패키지 프라이빗): 현재 journal_mode 조회(파일에 영속된 실제 값을 반영). */
    String journalMode() {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
            return rs.next() ? rs.getString(1) : null;
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

    /** builds INSERT + coverage 배치 INSERT를 명시적 단일 트랜잭션으로 묶는다 — 동시 reader가
     *  coverage 없는 builds 행을 관측하는 원자성 공백을 없앤다. 도중 예외(SQL 오류 포함 임의 예외) 시
     *  롤백 후 재던짐(builds/coverage 모두 미반영) [FU-REQ-003]. */
    public long save(CoverageSnapshot snap) {
        long buildId;
        try {
            conn.setAutoCommit(false);
        } catch (SQLException e) { throw new RuntimeException(e); }
        try {
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
            conn.commit();
            return buildId;
        } catch (Exception e) {
            rollbackQuietly();
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) { /* best-effort 복원 */ }
        }
    }

    private void rollbackQuietly() {
        try { conn.rollback(); } catch (SQLException ignored) { /* 이미 실패한 트랜잭션 — 원 예외를 던짐 */ }
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

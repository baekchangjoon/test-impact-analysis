package io.tia.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DbPathsTest {

    @Test
    @DisplayName("REQ-006: git-common-dir이 있으면 <common>/tia/tia.db")
    void gitCommonDirPath(@TempDir Path common) {
        Path p = DbPaths.resolveDefault(k -> null, common);
        assertEquals(common.resolve("tia").resolve("tia.db"), p);
    }

    @Test
    @DisplayName("REQ-007: git-common-dir 없고 XDG_CACHE_HOME 설정 시 <xdg>/tia/tia.db")
    void xdgFallbackWhenEnvSet(@TempDir Path xdg) {
        Map<String, String> env = Map.of("XDG_CACHE_HOME", xdg.toString());
        Path p = DbPaths.resolveDefault(env::get, null);
        assertEquals(xdg.resolve("tia").resolve("tia.db"), p);
    }

    @Test
    @DisplayName("REQ-007: git-common-dir 없고 XDG 미설정 시 ~/.cache/tia/tia.db")
    void homeCacheFallbackWhenNoXdg() {
        Path p = DbPaths.resolveDefault(k -> null, null);
        Path expected = Path.of(System.getProperty("user.home"), ".cache", "tia", "tia.db");
        assertEquals(expected, p);
    }

    @Test
    @DisplayName("SP4-REQ-004: resolveDefault(workingDir) — git 레포면 그 레포의 common-dir 사용")
    void workingDirGitRepoUsesItsCommonDir(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");

        Path p = DbPaths.resolveDefault(repo);

        Path expectedCommonDir = repo.resolve(".git").toAbsolutePath().normalize();
        assertEquals(expectedCommonDir.resolve("tia").resolve("tia.db"), p);
    }

    @Test
    @DisplayName("SP4-REQ-004: resolveDefault(workingDir) — non-git이면 기존 XDG/home-cache 폴백 유지")
    void workingDirNonGitFallsBackToCacheHome(@TempDir Path nonGitDir) {
        Path p = DbPaths.resolveDefault(nonGitDir);

        assertFalse(p.startsWith(nonGitDir), "non-git workingDir는 그 디렉터리 밑에 DB를 두면 안 됨: " + p);
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}

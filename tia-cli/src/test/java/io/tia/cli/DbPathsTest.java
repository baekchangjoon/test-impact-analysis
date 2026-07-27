package io.tia.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

    @Test
    @DisplayName("FU-REQ-004: git worktree 토폴로지 — 링크드 워크트리에서도 메인 레포의 common-dir(.git) 사용")
    void worktreeResolvesToMainCommonDir(@TempDir Path parent) throws Exception {
        Path mainRepo = parent.resolve("main");
        Files.createDirectories(mainRepo);
        git(mainRepo, "init", "-q");
        // 결정적 `git worktree add`를 위해 최초 커밋 필요(커밋 0개 레포는 git 버전별 orphan
        // 추론에 의존해 비결정적) — 픽스처 로컬 user.name/email 설정.
        git(mainRepo, "config", "user.email", "tia-fixture@example.com");
        git(mainRepo, "config", "user.name", "tia-fixture");
        Files.writeString(mainRepo.resolve("README.md"), "fixture\n");
        git(mainRepo, "add", "README.md");
        git(mainRepo, "commit", "-q", "-m", "initial commit");

        Path worktree = parent.resolve("wt");
        git(mainRepo, "worktree", "add", worktree.toString());

        Path p = DbPaths.resolveDefault(worktree);

        // linked worktree에서 `git rev-parse --git-common-dir`은 절대(실)경로를 반환한다(macOS의
        // /var → /private/var 심링크 등) — toRealPath()로 맞춰 비교(git과 동일 기준).
        Path expectedCommonDir = mainRepo.toRealPath().resolve(".git");
        assertEquals(expectedCommonDir.resolve("tia").resolve("tia.db"), p,
                "worktree의 git-common-dir은 메인 레포의 .git이어야 함(모든 worktree가 DB를 공유)");
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

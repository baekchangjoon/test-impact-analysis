package io.tia.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * `--db` 미지정 시 기본 인덱스 DB 경로를 해석한다.
 * git-common-dir(모든 worktree 공유) 우선, 비 git이면 XDG_CACHE_HOME(없으면 ~/.cache) 폴백.
 * 디렉터리 생성은 하지 않는다 — 부모 생성은 CoverageStore 생성자가 담당.
 */
final class DbPaths {
    private DbPaths() {}

    static Path resolveDefault() {
        return resolveDefault(System::getenv);
    }

    static Path resolveDefault(Function<String, String> env) {
        return resolveDefault(env, gitCommonDir());
    }

    /** 테스트 seam: git-common-dir과 env를 모두 주입. */
    static Path resolveDefault(Function<String, String> env, Path gitCommonDirOrNull) {
        if (gitCommonDirOrNull != null) {
            return gitCommonDirOrNull.resolve("tia").resolve("tia.db");
        }
        String xdg = env.apply("XDG_CACHE_HOME");
        Path base = (xdg != null && !xdg.isBlank())
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("tia").resolve("tia.db");
    }

    /** `git rev-parse --git-common-dir` → 절대경로. 비 git/실패면 null. stderr는 폐기. */
    private static Path gitCommonDir() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--git-common-dir")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int code = p.waitFor();
            if (code != 0 || out.isEmpty()) return null;
            return Path.of(out).toAbsolutePath().normalize();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

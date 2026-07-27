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

    /**
     * working_dir 시임(SP4-REQ-004) — git-common-dir 조회를 workingDirOrNull 기준으로 실행한다.
     * null이면 프로세스 cwd 기준(기존 동작과 완전히 동일 — {@link #resolveDefault()}로 위임).
     */
    static Path resolveDefault(Path workingDirOrNull) {
        return resolveDefault(System::getenv, gitCommonDir(workingDirOrNull));
    }

    static Path resolveDefault(Function<String, String> env) {
        return resolveDefault(env, gitCommonDir(null));
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

    /**
     * `git rev-parse --git-common-dir` → 절대경로. 비 git/실패면 null. stderr는 폐기.
     * workingDirOrNull 지정 시 그 디렉터리에서 조회(.directory()) — null이면 프로세스 cwd(기존 동작).
     */
    private static Path gitCommonDir(Path workingDirOrNull) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--git-common-dir")
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            if (workingDirOrNull != null) pb.directory(workingDirOrNull.toFile());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int code = p.waitFor();
            if (code != 0 || out.isEmpty()) return null;
            Path resolved = Path.of(out);
            return (workingDirOrNull != null)
                ? workingDirOrNull.resolve(resolved).toAbsolutePath().normalize()
                : resolved.toAbsolutePath().normalize();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

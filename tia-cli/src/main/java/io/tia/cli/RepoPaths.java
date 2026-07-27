package io.tia.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SP3 공용 헬퍼 — TIA 레포 루트 감지 · git 루트 감지 · git HEAD 해석.
 * {@code init}/{@code doctor}/{@code demo}가 공유한다(설계 spec §1 "공통 인프라").
 */
public final class RepoPaths {
    private RepoPaths() {}

    /** doctor 체크 6·demo 전제검사가 쓰는 TIA 레포 마커. */
    private static final String TIA_MARKER_SCRIPT = "scripts/run-inprocess-e2e.sh";
    private static final String TIA_SETTINGS_GRADLE = "settings.gradle";
    private static final String TIA_ROOT_PROJECT_NAME = "rootProject.name = 'test-impact-analysis'";

    /**
     * start부터 상향으로 {@code scripts/run-inprocess-e2e.sh}가 존재하고 {@code settings.gradle}에
     * {@code rootProject.name = 'test-impact-analysis'} 문자열이 포함된 디렉터리를 찾는다.
     * 발견하면 그 디렉터리, 없으면(파일시스템 루트까지) {@code null}.
     */
    public static Path findTiaRepoRoot(Path start) {
        Path dir = start.toAbsolutePath().normalize();
        while (dir != null) {
            if (looksLikeTiaRepoRoot(dir)) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    private static boolean looksLikeTiaRepoRoot(Path dir) {
        Path marker = dir.resolve(TIA_MARKER_SCRIPT);
        Path settingsGradle = dir.resolve(TIA_SETTINGS_GRADLE);
        if (!Files.isRegularFile(marker) || !Files.isRegularFile(settingsGradle)) return false;
        try {
            return Files.readString(settingsGradle).contains(TIA_ROOT_PROJECT_NAME);
        } catch (IOException e) {
            return false;
        }
    }

    /** start부터 상향으로 {@code .git}(디렉터리 또는 worktree의 gitdir 파일)을 보유한 디렉터리(git 루트)를 찾는다.
     *  없으면(파일시스템 루트까지) {@code null}. */
    public static Path findGitRoot(Path start) {
        Path dir = start.toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * {@code git rev-parse HEAD} — workingDir 기준(ProcessBuilder.directory 명시, ImpactCommand.runGitDiff
     * 패턴). 비-git 디렉터리·프로세스 실패·인터럽트 등 어떤 이유로든 HEAD를 못 구하면 {@code null}
     * (예외를 던지지 않음 — doctor 체크5·demo가 SKIP/폴백 판단에 사용).
     */
    public static String gitHead(Path workingDir) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(workingDir.toFile())
                    .redirectErrorStream(false)
                    .start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.getErrorStream().readAllBytes();   // 자식 프로세스 파이프 버퍼 고갈 방지(폐기)
            int code = p.waitFor();
            return (code == 0 && !out.isEmpty()) ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

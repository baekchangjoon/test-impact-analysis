package io.tia.cli;

import io.tia.core.impact.ImpactAnalyzer;
import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.DiffSummary;
import io.tia.core.model.ImpactResult;
import io.tia.core.model.ImpactedTest;
import io.tia.core.parse.GitDiffParser;
import io.tia.core.store.CoverageStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "impact", description = "diff와 커버리지 매핑을 교차해 영향 테스트 선별")
public class ImpactCommand implements Callable<Integer> {
    @Option(names = "--db", required = true) Path db;
    @Option(names = "--commit", required = true) String commit;
    @Option(names = "--diff-file", description = "unified diff 파일 (미지정 시 --git-ref로 git diff 실행)") Path diffFile;
    @Option(names = "--git-ref", description = "diff 베이스 ref (미지정 시 --commit). 라인 공간 정렬 위해 인덱싱 커밋과 일치해야 함 [설계 §6.2 4-B]") String gitRef;

    @Override public Integer call() throws Exception {
        // 기본 베이스 = 인덱싱 커밋(--commit) → git diff <commit> 의 old-side가 커버리지와 같은 라인 공간 [§6.2 4-B].
        // Phase 0는 라인 재조정 미구현이므로 diff 베이스 ≠ 인덱싱 커밋이면 결과 무효(아래 가드).
        String base = (gitRef == null) ? commit : gitRef;
        String diffText = (diffFile != null)
            ? Files.readString(diffFile)
            : runGitDiff(base);

        DiffSummary diff = new GitDiffParser().parse(diffText);
        CoverageSnapshot snap;
        try (CoverageStore store = new CoverageStore(db)) { snap = store.load(commit); }

        ImpactResult r = new ImpactAnalyzer().select(snap, diff);
        System.out.println("# 매핑 기준 커밋: " + commit + "  (영향 테스트 " + r.impacted().size() + "개"
            + (r.conservativeSelectAll() ? ", 보수적 전체 선택" : "") + ")");
        for (ImpactedTest t : r.impacted())
            System.out.println(t.confidence() + "\t" + t.testId());
        for (String reason : r.reasons())
            System.out.println("# 주의: " + reason);
        return 0;
    }

    private static String runGitDiff(String ref) throws Exception {
        Process p = new ProcessBuilder("git", "diff", "--unified=0", ref).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }
}

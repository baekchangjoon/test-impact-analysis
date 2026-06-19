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
    @Option(names = "--db") Path db;
    @Option(names = "--commit", required = true) String commit;
    @Option(names = "--diff-file", description = "unified diff 파일 (미지정 시 --git-ref로 git diff 실행)") Path diffFile;
    @Option(names = "--git-ref", description = "diff 베이스 ref (미지정 시 --commit). 라인 공간 정렬 위해 인덱싱 커밋과 일치해야 함 [설계 §6.2 4-B]") String gitRef;
    @Option(names = "--strict", description = "인덱스에 이 커밋의 베이스라인이 없으면 0개 대신 실패(기본: 전체 실행 신호 후 성공)") boolean strict;

    /** 베이스라인 부재(no-baseline) 머신 마커 — CI/Action이 '전체 실행(보수적)'으로 처리(누락 위험 0). */
    static final String NO_BASELINE_MARKER = "# tia:no-baseline";

    @Override public Integer call() throws Exception {
        Path effectiveDb = (db != null) ? db : DbPaths.resolveDefault();
        if (db == null) System.err.println("INFO: 기본 인덱스 DB: " + effectiveDb);
        CoverageSnapshot snap;
        int buildCount;
        try (CoverageStore store = new CoverageStore(effectiveDb)) {
            buildCount = store.distinctBuildCount(commit);   // try 블록 안에서 캡처(store 스코프 제한)
            snap = store.load(commit);
        }
        if (buildCount > 1) {
            System.err.println("INFO: commit " + commit + "에 build " + buildCount
                + "개 → test_id별 최신 build 병합(멀티모듈/재인덱싱).");
        }

        // 인덱스에 이 커밋의 베이스라인이 없으면(DB에 해당 커밋 데이터 없음) 선별을 신뢰할 수 없다.
        // 빈 선별(=아무것도 안 돌림)은 누락 위험이 크므로, 기본은 '전체 실행' 신호를 내고 성공한다(보수적, 누락 0).
        // --strict 면 실패시켜 파이프라인이 명시적으로 처리하게 한다.
        if (snap.tests().isEmpty()) {
            System.out.println(NO_BASELINE_MARKER);
            System.err.println("WARN: '" + commit + "' 의 TIA 베이스라인이 " + effectiveDb
                + " 에 없음 → 전체 실행 권장(보수적, 누락 위험 0).");
            return strict ? 3 : 0;
        }

        // 기본 베이스 = 인덱싱 커밋(--commit) → git diff <commit> 의 old-side가 커버리지와 같은 라인 공간 [§6.2 4-B].
        // 현재 구현은 라인 재조정 미구현이므로 diff 베이스 ≠ 인덱싱 커밋이면 결과 무효(아래 가드).
        String base = (gitRef == null) ? commit : gitRef;
        String diffText = (diffFile != null)
            ? Files.readString(diffFile)
            : runGitDiff(base, null);   // null = 현재 작업 디렉터리(레포)에서 git diff

        DiffSummary diff = new GitDiffParser().parse(diffText);
        ImpactResult r = new ImpactAnalyzer().select(snap, diff);
        System.out.println("# 매핑 기준 커밋: " + commit + "  (영향 테스트 " + r.impacted().size() + "개"
            + (r.conservativeSelectAll() ? ", 보수적 전체 선택" : "") + ")");
        for (ImpactedTest t : r.impacted())
            System.out.println(t.confidence() + "\t" + t.testId());
        for (String reason : r.reasons())
            System.out.println("# 주의: " + reason);
        return 0;
    }

    /** two-dot `git diff --unified=0 <ref>` — old-side가 인덱싱 베이스라인 라인공간과 정렬(§6.2).
     *  workingDir=null 이면 현재 디렉터리(레포). 테스트는 임시 repo를 지정한다. */
    static String runGitDiff(String ref, java.io.File workingDir) throws Exception {
        Process p = new ProcessBuilder("git", "diff", "--unified=0", ref)
            .directory(workingDir).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }
}

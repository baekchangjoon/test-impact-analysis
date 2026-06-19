package io.tia.cli;

import io.tia.core.model.CoverageSnapshot;
import io.tia.core.model.TestCoverage;
import io.tia.core.parse.TestwiseReportParser;
import io.tia.core.store.CoverageStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "index", description = "testwise 리포트를 SQLite 스냅샷으로 인덱싱")
public class IndexCommand implements Callable<Integer> {
    @Option(names = "--report", required = true) Path report;
    @Option(names = "--repo", required = true) String repo;
    @Option(names = "--commit", required = true) String commit;
    @Option(names = "--db") Path db;

    @Override public Integer call() throws Exception {
        Path effectiveDb = (db != null) ? db : DbPaths.resolveDefault();
        if (db == null) System.err.println("INFO: 기본 인덱스 DB: " + effectiveDb);
        try (InputStream in = Files.newInputStream(report)) {
            List<TestCoverage> tests = new TestwiseReportParser().parse(in);
            try (CoverageStore store = new CoverageStore(effectiveDb)) {
                store.save(new CoverageSnapshot(repo, commit, tests));
            }
            System.out.println("indexed " + tests.size() + " tests @ " + commit);
            return 0;
        }
    }
}

package io.tia.cli;

import io.tia.core.report.ReportBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "report",
        description = "testwise/scenarios/flaky/prod → 자기완결형 인터랙티브 HTML 리포트")
public class ReportCommand implements Callable<Integer> {
    @Option(names = "--testwise", required = true, description = "per-test 커버리지 JSON") Path testwise;
    @Option(names = "--scenarios", description = "tia impact 시나리오 JSON ('-'/미지정 시 탭 비움)") Path scenarios;
    @Option(names = "--flaky", description = "flaky.json ('-'/미지정 시 탭 비움)") Path flaky;
    @Option(names = "--prod-files", description = "프로덕션 .java 목록(blind-spot 분모; 미지정 시 빈값)") Path prodFiles;
    @Option(names = "--commit", required = true, description = "인덱싱된 베이스라인 커밋") String commit;
    @Option(names = "--out", required = true, description = "출력 report.html 경로") Path out;
    @Option(names = "--sut-name", defaultValue = "SUT", description = "리포트 타이틀의 SUT 이름") String sut;
    @Option(names = "--jacoco-dir", defaultValue = "jacoco", description = "JaCoCo HTML 리포트 상대경로(딥링크 대상)") String jacoco;
    @Option(names = "--test-src-root", description = "테스트 소스 루트(file:// 로컬 열기 링크)") Path testSrcRoot;
    @Option(names = "--prefix-strip", defaultValue = "", description = "경로 축약 접두(예: org/.../petclinic/)") String prefixStrip;

    @Override public Integer call() throws Exception {
        String html = new ReportBuilder().render(new ReportBuilder.Inputs(
                testwise, nullIfDash(scenarios), nullIfDash(flaky), nullIfDash(prodFiles),
                commit, sut, jacoco, testSrcRoot, prefixStrip));
        Files.writeString(out, html);
        System.out.println("wrote " + out);
        return 0;
    }

    /** Treat the conventional "-" sentinel path as "omitted". */
    private static Path nullIfDash(Path p) {
        return (p == null || p.toString().equals("-")) ? null : p;
    }
}

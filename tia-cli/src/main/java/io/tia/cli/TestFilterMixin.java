package io.tia.cli;

import picocli.CommandLine.Option;

import java.util.List;

/** --include-test / --exclude-test. impact·flaky·report 전용. */
public class TestFilterMixin {
    @Option(names = "--include-test", description = "테스트 include 글로브(tia.yml의 목록을 대체)") List<String> includeTest;
    @Option(names = "--exclude-test", description = "테스트 exclude 글로브(tia.yml의 목록을 대체)") List<String> excludeTest;
}

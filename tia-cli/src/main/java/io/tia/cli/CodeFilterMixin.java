package io.tia.cli;

import picocli.CommandLine.Option;

import java.util.List;

/** --include-code / --exclude-code. impact·report 전용. */
public class CodeFilterMixin {
    @Option(names = "--include-code", description = "코드 include 글로브(tia.yml의 목록을 대체)") List<String> includeCode;
    @Option(names = "--exclude-code", description = "코드 exclude 글로브(tia.yml의 목록을 대체)") List<String> excludeCode;
}

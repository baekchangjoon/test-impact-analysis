package io.tia.cli;

import io.tia.core.convert.Testwise;
import io.tia.core.convert.TestwiseConverter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "convert",
        description = "per-test .exec 디렉터리 → testwise JSON (jacoco core, subprocess 없음)")
public class ConvertCommand implements Callable<Integer> {
    @Option(names = "--exec-dir", required = true, description = "<testId>.exec 들이 있는 디렉터리") Path execDir;
    @Option(names = "--classes", required = true, description = "SUT 컴파일 classfile 디렉터리") Path classes;
    @Option(names = "--out", required = true, description = "출력 testwise.json 경로") Path out;
    @Option(names = "--allow-incomplete",
            description = "in-process 손실(incompleteAttribution/droppedProbes) 시 실패 대신 경고만") boolean allowIncomplete;
    @Option(names = "--fail-on-empty",
            description = "0커버(paths 비어있음) 테스트가 있으면 실패(기본 off)") boolean failOnEmpty;

    @Override public Integer call() throws Exception {
        TestwiseConverter converter = new TestwiseConverter();
        Testwise.Document doc = converter.convert(execDir, classes);
        converter.write(doc, out);
        int rows = doc.tests().stream().flatMap(t -> t.paths().stream()).mapToInt(p -> p.files().size()).sum();
        System.out.println("wrote " + out + ": " + doc.tests().size() + " tests, " + rows + " (test,file) rows");

        boolean fail = false;
        // incomplete gate (default ON; --allow-incomplete -> warn)
        List<String> lost = doc.tests().stream().filter(TestwiseConverter::isLoss)
                .map(Testwise.Test::uniformPath).collect(Collectors.toList());
        if (!lost.isEmpty()) {
            String head = (allowIncomplete ? "[WARN] " : "[ERROR] ") + lost.size()
                    + " test(s) have incomplete in-process attribution (worker-thread coverage lost):";
            System.err.println(head);
            lost.forEach(id -> System.err.println("  incomplete  " + id));
            if (!allowIncomplete) {
                System.err.println("  → use the out-of-process baggage model for HTTP black-box tests, or --allow-incomplete to override.");
                fail = true;
            }
        }
        // empty gate (default OFF; --fail-on-empty -> fail). Independent of --allow-incomplete.
        if (failOnEmpty) {
            List<String> empty = doc.tests().stream().filter(t -> t.paths().isEmpty())
                    .map(Testwise.Test::uniformPath).collect(Collectors.toList());
            if (!empty.isEmpty()) {
                System.err.println("[ERROR] " + empty.size() + " test(s) covered 0 production files:");
                empty.forEach(id -> System.err.println("  empty       " + id));
                fail = true;
            }
        }
        return fail ? 1 : 0;
    }
}

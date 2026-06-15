package io.tia.cli;

import io.tia.core.convert.Testwise;
import io.tia.core.convert.TestwiseConverter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "convert",
        description = "per-test .exec 디렉터리 → testwise JSON (jacoco core, subprocess 없음)")
public class ConvertCommand implements Callable<Integer> {
    @Option(names = "--exec-dir", required = true, description = "<testId>.exec 들이 있는 디렉터리") Path execDir;
    @Option(names = "--classes", required = true, description = "SUT 컴파일 classfile 디렉터리") Path classes;
    @Option(names = "--out", required = true, description = "출력 testwise.json 경로") Path out;

    @Override public Integer call() throws Exception {
        TestwiseConverter converter = new TestwiseConverter();
        Testwise.Document doc = converter.convert(execDir, classes);
        converter.write(doc, out);
        int rows = doc.tests().stream()
                .flatMap(t -> t.paths().stream())
                .mapToInt(p -> p.files().size()).sum();
        System.out.println("wrote " + out + ": " + doc.tests().size() + " tests, " + rows + " (test,file) rows");
        return 0;
    }
}

package io.tia.core.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tia.core.filter.FilterSet;
import io.tia.core.model.Confidence;
import io.tia.core.model.ImpactedTest;

import java.util.List;
import java.util.Map;

/** impact 결과의 summary/json/markdown 렌더 [REQ-013/015/016]. text는 CLI 기존 경로가 담당(동결). */
public final class ImpactFormats {
    private ImpactFormats() {}
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String BOLD = "[1m", RESET = "[0m";

    public record Payload(String commit, List<ImpactedTest> tests, boolean conservative,
                          List<String> reasons, List<String> ignoredFiles,
                          Map<String, List<String>> testsByFile, FilterSet filters,
                          int totalTests) {}

    static String reasonOf(Confidence c) {   // 코어 모델 무변경 — 매핑으로 채움 [REQ-013]
        return switch (c) {
            case DETERMINISTIC -> "covered-line intersects diff";
            case CONSERVATIVE -> "conservative select-all";
            case LOW_CONFIDENCE -> "low-confidence";
        };
    }

    public static String json(Payload p) {
        ObjectNode root = OM.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("command", "impact");
        root.put("commit", p.commit());
        root.set("appliedFilters", filtersNode(p.filters()));
        ArrayNode tests = root.putArray("tests");
        for (ImpactedTest t : p.tests()) {
            ObjectNode n = tests.addObject();
            n.put("id", t.testId());
            n.put("confidence", t.confidence().name());
            n.put("reason", reasonOf(t.confidence()));
        }
        ArrayNode ignored = root.putArray("ignoredChangedFiles");
        p.ignoredFiles().forEach(ignored::add);
        ArrayNode warnings = root.putArray("warnings");
        p.ignoredFiles().forEach(f -> warnings.add("excluded change ignored: " + f));
        p.reasons().forEach(warnings::add);
        return root.toPrettyString();
    }

    public static String summary(Payload p, boolean ansiColor) {
        long det = p.tests().stream().filter(t -> t.confidence() == Confidence.DETERMINISTIC).count();
        long con = p.tests().stream().filter(t -> t.confidence() == Confidence.CONSERVATIVE).count();
        StringBuilder sb = new StringBuilder();
        String count = "영향 테스트 " + p.tests().size() + "/" + p.totalTests() + "개 선별 (DETERMINISTIC " + det
                + " · CONSERVATIVE " + con + ")   @ " + p.commit();
        sb.append(ansiColor ? BOLD + count + RESET : count).append('\n');
        if (!p.testsByFile().isEmpty()) {
            sb.append("파일별:\n");
            p.testsByFile().forEach((file, tests) -> sb.append("  ").append(file).append(" → ")
                    .append(tests.isEmpty() ? "(blind spot: 이 변경을 커버하는 테스트 없음)"
                                            : String.join(", ", tests)).append('\n'));
        }
        sb.append("필터로 무시된 변경 파일: ").append(p.ignoredFiles().size()).append("개\n");
        for (String reason : p.reasons())
            sb.append("경고: ").append(reason).append('\n');
        sb.append("다음: 선별된 테스트만 실행하세요. blind spot 파일은 테스트 보강을 검토하세요.\n");
        return sb.toString();
    }

    public static String markdown(Payload p) {
        long det = p.tests().stream().filter(t -> t.confidence() == Confidence.DETERMINISTIC).count();
        long con = p.tests().stream().filter(t -> t.confidence() == Confidence.CONSERVATIVE).count();
        StringBuilder sb = new StringBuilder();
        sb.append("| 선별 | DETERMINISTIC | CONSERVATIVE | 무시된 변경 |\n");
        sb.append("|---|---|---|---|\n");
        sb.append("| ").append(p.tests().size()).append(" | ").append(det).append(" | ")
          .append(con).append(" | ").append(p.ignoredFiles().size()).append(" |\n\n");
        sb.append("<details><summary>선별 목록</summary>\n\n");
        for (ImpactedTest t : p.tests())
            sb.append("- `").append(t.testId()).append("` — ").append(t.confidence()).append('\n');
        sb.append("\n</details>\n");
        if (!p.reasons().isEmpty()) {
            sb.append("\n### 경고\n");
            for (String reason : p.reasons())
                sb.append("- ").append(reason).append('\n');
        }
        return sb.toString();
    }

    private static ObjectNode filtersNode(FilterSet f) {
        ObjectNode n = OM.createObjectNode();
        ObjectNode code = n.putObject("code");
        code.set("include", OM.valueToTree(f.codeInclude()));
        code.set("exclude", OM.valueToTree(f.codeExclude()));
        ObjectNode test = n.putObject("test");
        test.set("include", OM.valueToTree(f.testInclude()));
        test.set("exclude", OM.valueToTree(f.testExclude()));
        return n;
    }
}

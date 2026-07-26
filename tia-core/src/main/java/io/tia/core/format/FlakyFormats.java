package io.tia.core.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tia.core.filter.FilterSet;
import io.tia.core.flaky.FlakyReport;

import java.util.List;

/** flaky 결과의 summary/json/markdown 렌더 [REQ-014]. commit 개념 없음 — 키 자체를 넣지 않는다. */
public final class FlakyFormats {
    private FlakyFormats() {}
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String BOLD = "[1m", RESET = "[0m";

    public static String json(FlakyReport r, FilterSet filters, List<String> warnings) {
        ObjectNode root = OM.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("command", "flaky");
        ObjectNode f = root.putObject("appliedFilters");
        ObjectNode test = f.putObject("test");
        test.set("include", OM.valueToTree(filters.testInclude()));
        test.set("exclude", OM.valueToTree(filters.testExclude()));
        root.put("ratio", r.ratio());
        root.put("totalTests", r.totalTests());
        ArrayNode flaky = root.putArray("flakyTests");
        r.flakyTests().forEach(flaky::add);
        ArrayNode w = root.putArray("warnings");
        warnings.forEach(w::add);
        return root.toPrettyString();
    }

    public static String summary(FlakyReport r, boolean ansiColor) {
        String head = String.format("flaky %d/%d개 (ratio %.3f)", r.flakyTests().size(), r.totalTests(), r.ratio());
        StringBuilder sb = new StringBuilder(ansiColor ? BOLD + head + RESET : head).append('\n');
        r.flakyTests().forEach(t -> sb.append("  FLAKY ").append(t).append('\n'));
        sb.append("다음: FLAKY 테스트는 원인 격리(재시도·인프라) 전까지 게이트에서 제외를 검토하세요.\n");
        return sb.toString();
    }

    public static String markdown(FlakyReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("| flaky | total | ratio |\n|---|---|---|\n");
        sb.append("| ").append(r.flakyTests().size()).append(" | ").append(r.totalTests())
          .append(" | ").append(String.format("%.3f", r.ratio())).append(" |\n\n");
        sb.append("<details><summary>flaky 목록</summary>\n\n");
        r.flakyTests().forEach(t -> sb.append("- `").append(t).append("`\n"));
        sb.append("\n</details>\n");
        return sb.toString();
    }
}

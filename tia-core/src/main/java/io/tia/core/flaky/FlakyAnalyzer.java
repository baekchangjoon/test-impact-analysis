package io.tia.core.flaky;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FlakyAnalyzer {
    public FlakyReport aggregate(List<RunResult> runs) {
        Map<String, Set<Boolean>> outcomes = new LinkedHashMap<>();
        for (RunResult run : runs)
            run.passedByTest().forEach((id, passed) ->
                outcomes.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(passed));

        List<String> flaky = new ArrayList<>();
        for (Map.Entry<String, Set<Boolean>> e : outcomes.entrySet())
            if (e.getValue().size() > 1) flaky.add(e.getKey());   // pass와 fail 모두 관측

        int total = outcomes.size();
        double ratio = total == 0 ? 0.0 : (double) flaky.size() / total;
        return new FlakyReport(ratio, flaky, total);
    }
}

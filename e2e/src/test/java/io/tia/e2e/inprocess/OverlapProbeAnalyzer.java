package io.tia.e2e.inprocess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 오버랩 프로브 로그(testId,PHASE,ms)를 파싱해 겹치는 [START,END] 구간 쌍 수를 센다. */
public final class OverlapProbeAnalyzer {
    private OverlapProbeAnalyzer() {}

    public static int overlappingPairs(Path file) throws IOException {
        Map<String, long[]> spans = new LinkedHashMap<>();   // testId → [start, end]
        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] p = line.split(",");
            if (p.length != 3) throw new IllegalStateException("파싱 불가 줄: " + raw);
            String id = p[0]; String phase = p[1]; long ms = Long.parseLong(p[2].trim());
            long[] s = spans.computeIfAbsent(id, k -> new long[]{Long.MIN_VALUE, Long.MIN_VALUE});
            if ("START".equals(phase)) s[0] = ms;
            else if ("END".equals(phase)) s[1] = ms;
            else throw new IllegalStateException("알 수 없는 phase: " + raw);
        }
        List<long[]> iv = new ArrayList<>();
        for (long[] s : spans.values()) if (s[0] != Long.MIN_VALUE && s[1] != Long.MIN_VALUE) iv.add(s);
        int pairs = 0;
        for (int i = 0; i < iv.size(); i++)
            for (int j = i + 1; j < iv.size(); j++)
                if (iv.get(i)[0] < iv.get(j)[1] && iv.get(j)[0] < iv.get(i)[1]) pairs++;
        return pairs;
    }
}

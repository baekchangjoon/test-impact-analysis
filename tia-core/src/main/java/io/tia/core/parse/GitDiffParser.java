package io.tia.core.parse;

import io.tia.core.model.DiffSummary;
import io.tia.core.path.PathNormalizer;
import org.roaringbitmap.RoaringBitmap;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitDiffParser {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+\\d+(?:,\\d+)? @@");

    public DiffSummary parse(String diff) {
        Map<String, RoaringBitmap> changedJava = new LinkedHashMap<>();
        Set<String> unmappable = new LinkedHashSet<>();
        Map<String, Boolean> hadOldChange = new LinkedHashMap<>();  // file -> '-'(삭제/수정) 존재 여부
        Map<String, Boolean> hadAnyChange = new LinkedHashMap<>();  // file -> 변경 존재 여부

        String file = null;
        int oldLine = 0;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git")) {
                file = null; oldLine = 0;
            } else if (line.startsWith("+++ ")) {
                String p = line.substring(4).trim();
                if (!p.equals("/dev/null")) file = PathNormalizer.canonical(stripPrefix(p));  // 'b/' 제거 + 정규화
            } else if (line.startsWith("--- ")) {
                if (file == null) {
                    String p = line.substring(4).trim();
                    if (!p.equals("/dev/null")) file = PathNormalizer.canonical(stripPrefix(p));
                }
            } else if (line.startsWith("@@")) {
                Matcher m = HUNK.matcher(line);
                if (m.find()) oldLine = Integer.parseInt(m.group(1));
            } else if (file != null) {
                if (line.startsWith("-") && !line.startsWith("---")) {
                    if (isJava(file)) changedJava.computeIfAbsent(file, k -> new RoaringBitmap()).add(oldLine);
                    hadOldChange.put(file, true); hadAnyChange.put(file, true);
                    oldLine++;
                } else if (line.startsWith("+") && !line.startsWith("+++")) {
                    hadAnyChange.put(file, true);            // new-side: oldLine 미증가
                } else {
                    oldLine++;                               // context
                }
            }
        }

        Set<String> additionOnly = new LinkedHashSet<>();
        for (String f : hadAnyChange.keySet()) {
            if (isJava(f)) {
                if (!hadOldChange.getOrDefault(f, false)) additionOnly.add(f);  // '+'만 존재
            } else {
                unmappable.add(f);
            }
        }
        return new DiffSummary(changedJava, additionOnly, unmappable);
    }

    private static boolean isJava(String f) { return f.endsWith(".java"); }

    private static String stripPrefix(String p) {
        return (p.startsWith("a/") || p.startsWith("b/")) ? p.substring(2) : p;
    }
}

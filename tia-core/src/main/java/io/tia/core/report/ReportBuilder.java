package io.tia.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * D0: ports {@code petclinic-demo/make_html.py} to Java. The interactive HTML/CSS/JS
 * <b>template is reused verbatim</b> (resource {@code /report-template.html}, extracted
 * from make_html.py) — only the {@code __DATA__} model computation is reimplemented here,
 * so the report is equivalent to the Python output (E2E-1 compares the {@code __DATA__}
 * JSON island canonically).
 *
 * <p>Optional inputs ({@code scenarios}/{@code flaky}/{@code prodFiles}) may be null (or a
 * missing path) → the corresponding tab degrades gracefully, same as the Python generator.
 */
public final class ReportBuilder {
    private static final String PREFIX_REPLACEMENT = "…/";   // "…/"
    private final ObjectMapper om = new ObjectMapper();

    public record Inputs(Path testwise, Path scenarios, Path flaky, Path prodFiles,
                         String commit, String sut, String jacoco, Path testSrcRoot,
                         String prefixStrip) {}

    /** Build the report HTML (template + injected model). */
    public String render(Inputs in) throws IOException {
        Map<String, Object> model = buildModel(in);
        String dataJs = om.writeValueAsString(model).replace("</", "<\\/");
        String sutTitle = in.sut().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return loadTemplate().replace("__SUT__", sutTitle).replace("__DATA__", dataJs);
    }

    /** The model injected as {@code const D = …} — mirrors make_html.py's `model` dict. */
    public Map<String, Object> buildModel(Inputs in) throws IOException {
        JsonNode root = om.readTree(in.testwise().toFile());
        Object scenarios = usable(in.scenarios(), "scenarios") ? om.readValue(in.scenarios().toFile(), Object.class) : new ArrayList<>();
        Object flaky = usable(in.flaky(), "flaky") ? om.readValue(in.flaky().toFile(), Object.class) : null;
        List<String> prod = usable(in.prodFiles(), "prod-files") ? readProd(in.prodFiles()) : new ArrayList<>();
        Map<String, String> testSrc = walkTestSrc(in.testSrcRoot());

        // per-test + reverse index (rev: full path → list of test ids, insertion order)
        Map<String, PerTest> perTest = new LinkedHashMap<>();
        Map<String, List<String>> rev = new LinkedHashMap<>();
        for (JsonNode t : root.path("tests")) {
            String id = t.path("uniformPath").asText();
            String result = t.path("result").asText("UNKNOWN");
            List<FileCov> files = new ArrayList<>();
            for (JsonNode p : t.path("paths")) {
                String pkg = p.path("path").asText("");
                for (JsonNode f : p.path("files")) {
                    String fname = f.path("fileName").asText();
                    String full = pkg.isEmpty() ? fname : pkg + "/" + fname;
                    String covered = f.path("coveredLines").asText("");
                    files.add(new FileCov(full, pkg.replace("/", "."), fname, linesOf(covered), firstLine(covered)));
                    rev.computeIfAbsent(full, k -> new ArrayList<>()).add(id);
                }
            }
            files.sort(Comparator.comparingInt((FileCov c) -> c.n).reversed());
            int total = files.stream().mapToInt(c -> c.n).sum();
            perTest.put(id, new PerTest(result, files, total));
        }

        // perTest model entries, sorted by id
        List<Map<String, Object>> perTestModel = new ArrayList<>();
        for (Map.Entry<String, PerTest> e : new TreeMap<>(perTest).entrySet()) {
            PerTest d = e.getValue();
            List<Map<String, Object>> fileEntries = new ArrayList<>();
            for (FileCov c : d.files) {
                fileEntries.add(ordered("f", shorten(c.full, in.prefixStrip()), "file", c.file,
                        "pkg", c.pkg, "n", c.n, "line", c.line));
            }
            perTestModel.add(ordered("id", e.getKey(), "result", d.result,
                    "nfiles", d.files.size(), "lines", d.total, "files", fileEntries));
        }

        // reverse entries, sorted by (-fan-in, full path)
        List<Map.Entry<String, List<String>>> revSorted = new ArrayList<>(rev.entrySet());
        revSorted.sort(Comparator.<Map.Entry<String, List<String>>>comparingInt(en -> -en.getValue().size())
                .thenComparing(Map.Entry::getKey));
        List<Map<String, Object>> reverseModel = new ArrayList<>();
        for (Map.Entry<String, List<String>> en : revSorted) {
            String full = en.getKey();
            String pkg = full.contains("/") ? full.substring(0, full.lastIndexOf('/')) : "";
            String fname = full.substring(full.lastIndexOf('/') + 1);
            List<String> tests = new ArrayList<>(en.getValue());
            tests.sort(Comparator.naturalOrder());
            reverseModel.add(ordered("file", shorten(full, in.prefixStrip()), "pkg", pkg.replace("/", "."),
                    "fname", fname, "n", en.getValue().size(), "tests", tests));
        }

        // blind spots = prod files no test covered
        List<String> blind = new ArrayList<>();
        for (String p : prod) {
            if (!rev.containsKey(p)) {
                blind.add(p);
            }
        }
        blind.sort(Comparator.naturalOrder());
        List<String> blindShort = blind.stream().map(p -> shorten(p, in.prefixStrip())).toList();

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("commit", in.commit());
        model.put("sut", in.sut());
        model.put("jacoco", in.jacoco());
        model.put("testSrc", testSrc);
        model.put("perTest", perTestModel);
        model.put("reverse", reverseModel);
        model.put("scenarios", scenarios);
        model.put("flaky", flaky);
        model.put("blind", blindShort);
        model.put("nProd", prod.size());
        model.put("nCovered", rev.size());
        model.put("nTests", perTest.size());
        model.put("totalPoints", perTest.values().stream().mapToInt(d -> d.total).sum());
        return model;
    }

    // ---- helpers ----

    private record FileCov(String full, String pkg, String file, int n, int line) {}

    private record PerTest(String result, List<FileCov> files, int total) {}

    /** A non-null path that doesn't exist is almost certainly a typo → warn but degrade. */
    private static boolean usable(Path p, String label) {
        if (p == null) {
            return false;
        }
        if (!Files.exists(p)) {
            System.err.println("warning: input " + p + " not found — '" + label + "' tab will be empty");
            return false;
        }
        return true;
    }

    private String shorten(String full, String prefixStrip) {
        return (prefixStrip == null || prefixStrip.isEmpty()) ? full
                : full.replace(prefixStrip, PREFIX_REPLACEMENT);
    }

    /** count lines in "a-b,c" (matches make_html.py lines_of). */
    static int linesOf(String covered) {
        int n = 0;
        for (String part : covered.split(",")) {
            if (part.isEmpty()) {
                continue;
            }
            int dash = part.indexOf('-');
            if (dash >= 0) {
                n += Integer.parseInt(part.substring(dash + 1)) - Integer.parseInt(part.substring(0, dash)) + 1;
            } else {
                n += 1;
            }
        }
        return n;
    }

    /** first line number in "a-b,c" (matches make_html.py first_line; default 1). */
    static int firstLine(String covered) {
        for (String part : covered.split(",")) {
            if (!part.isEmpty()) {
                int dash = part.indexOf('-');
                return Integer.parseInt(dash >= 0 ? part.substring(0, dash) : part);
            }
        }
        return 1;
    }

    private List<String> readProd(Path prodFiles) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(prodFiles)) {
            String s = line.strip();
            if (s.endsWith(".java")) {
                out.add(s);
            }
        }
        return out;
    }

    /** map simple test-class name → absolute .java path (first occurrence), for file:// links. */
    private Map<String, String> walkTestSrc(Path root) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (root == null || !Files.isDirectory(root)) {
            return map;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> {
                String fn = p.getFileName().toString();
                map.putIfAbsent(fn.substring(0, fn.length() - ".java".length()), p.toAbsolutePath().toString());
            });
        }
        return map;
    }

    private String loadTemplate() {
        try (InputStream in = ReportBuilder.class.getResourceAsStream("/report-template.html")) {
            if (in == null) {
                throw new IllegalStateException("report-template.html missing from classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}

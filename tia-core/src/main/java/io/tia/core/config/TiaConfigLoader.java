package io.tia.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** tia.yml 탐색·파싱·검증. 탐색 시작점은 명시 파라미터(JVM cwd 비의존 — e2e 병렬 안전). */
public final class TiaConfigLoader {
    private TiaConfigLoader() {}

    static final String FILE_NAME = "tia.yml";
    private static final Set<String> TOP_KEYS = Set.of("version", "sut-name", "db", "filters");
    private static final Set<String> FILTER_KEYS = Set.of("code", "test");
    private static final Set<String> LIST_KEYS = Set.of("include", "exclude");

    /** explicitOrNull이 있으면 그 파일만 로드(상향 탐색·다른 파일 파싱 안 함) [REQ-001].
     *  상대 explicit 경로는 JVM cwd가 아니라 searchStart 기준으로 해석(전역 상태 비의존). */
    public static Optional<TiaConfig> load(Path explicitOrNull, Path searchStart) {
        Path explicit = (explicitOrNull == null) ? null
                : explicitOrNull.isAbsolute() ? explicitOrNull
                : searchStart.resolve(explicitOrNull).normalize();
        Path file = (explicit != null) ? explicit : discover(searchStart);
        if (file == null) return Optional.empty();
        if (explicit != null && !Files.isRegularFile(file))
            throw new TiaConfigException("tia.yml not found: " + file);
        return Optional.of(parse(file));
    }

    /** searchStart부터 부모로 올라가며 tia.yml 탐색. `.git`이 있는 디렉터리(git 루트)까지 포함 후 중단. */
    private static Path discover(Path searchStart) {
        Path dir = searchStart.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(FILE_NAME);
            if (Files.isRegularFile(candidate)) return candidate;
            if (Files.exists(dir.resolve(".git"))) return null;  // git 루트까지 못 찾음
            dir = dir.getParent();
        }
        return null;
    }

    private static TiaConfig parse(Path file) {
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(file));
        } catch (Exception e) {
            throw new TiaConfigException("tia.yml 파싱 실패: " + file + " — " + e.getMessage(), e);
        }
        if (root == null || !root.isObject())
            throw new TiaConfigException("tia.yml 형식 오류(최상위가 객체가 아님): " + file);
        rejectUnknownKeys(root, TOP_KEYS, file, "최상위");
        JsonNode version = root.get("version");
        if (version == null || !version.canConvertToInt() || version.asInt() != 1)
            throw new TiaConfigException("미지원 version (지원: 1): " + file
                    + " — version=" + (version == null ? "(없음)" : version.asText()));
        String sutName = root.hasNonNull("sut-name") ? root.get("sut-name").asText() : null;
        Path db = root.hasNonNull("db")
                ? file.toAbsolutePath().getParent().resolve(root.get("db").asText()).normalize()  // yml 위치 기준 [REQ-023]
                : null;
        TiaConfig.FilterLists code = TiaConfig.FilterLists.empty();
        TiaConfig.FilterLists test = TiaConfig.FilterLists.empty();
        JsonNode filters = root.get("filters");
        if (filters != null) {
            rejectUnknownKeys(filters, FILTER_KEYS, file, "filters");
            code = filterLists(filters.get("code"), file);
            test = filterLists(filters.get("test"), file);
        }
        return new TiaConfig(1, sutName, db, code, test);
    }

    private static TiaConfig.FilterLists filterLists(JsonNode node, Path file) {
        if (node == null) return TiaConfig.FilterLists.empty();
        rejectUnknownKeys(node, LIST_KEYS, file, "filters.*");
        return new TiaConfig.FilterLists(strings(node.get("include")), strings(node.get("exclude")));
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) arr.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }

    private static void rejectUnknownKeys(JsonNode obj, Set<String> allowed, Path file, String where) {
        for (Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!allowed.contains(k))
                throw new TiaConfigException("알 수 없는 " + where + " 키 '" + k + "': " + file
                        + " (지원: " + allowed + ")");
        }
    }
}

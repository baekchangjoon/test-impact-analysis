package io.tia.core.path;

/** git diff(레포 상대)·testwise 리포트 경로를 공통 패키지 상대 정규형으로 통일. */
public final class PathNormalizer {
    private PathNormalizer() {}

    private static final String[] SOURCE_ROOTS = {
        "src/main/java/", "src/test/java/",
        "src/main/kotlin/", "src/test/kotlin/",
        "src/main/resources/", "src/test/resources/"
    };

    public static String canonical(String path) {
        if (path == null) return null;
        String p = path.replace('\\', '/');
        if (p.startsWith("./")) p = p.substring(2);
        for (String root : SOURCE_ROOTS) {
            int idx = p.indexOf(root);
            if (idx >= 0) return p.substring(idx + root.length());   // 소스 루트 뒤 = 패키지 상대
        }
        return p;   // 소스 루트 없음 → 이미 패키지 상대(리포트 경로)로 간주
    }
}

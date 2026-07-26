package io.tia.core.filter;

import io.tia.core.config.TiaConfigException;

import java.util.List;
import java.util.regex.Pattern;

/** `/` 구분 문자열 전용 글로브. 지원 어휘: `**`(경로 경계 포함 임의), `*`(`/` 제외 임의), `?`(`/` 제외 1자).
 *  그 밖의 글로브 메타문자([, ], {, }, 백슬래시)는 미지원 → TiaConfigException (OS 무관, FileSystem 비의존) [REQ-021]. */
public final class GlobMatcher {
    private final List<Pattern> patterns;

    private GlobMatcher(List<Pattern> patterns) { this.patterns = patterns; }

    public static GlobMatcher compile(List<String> globs) {
        return new GlobMatcher(globs.stream().map(GlobMatcher::toRegex).toList());
    }

    public boolean matchesAny(String slashPath) {
        return patterns.stream().anyMatch(p -> p.matcher(slashPath).matches());
    }

    /** 표준 글로브 의미론: `**​/` = 0개 이상 세그먼트(루트 포함), `/**` = 0개 이상 하위, `*`는 `/` 못 넘음. */
    private static Pattern toRegex(String glob) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                boolean atStart = (i == 0);
                boolean afterSlash = (i > 0 && glob.charAt(i - 1) == '/');
                if ((atStart || afterSlash) && i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                    re.append("(?:[^/]+/)*");   // `**/` (선두/세그먼트 시작) → 0+개 세그먼트
                    i += 3;
                    continue;
                }
                if (i + 2 == glob.length() && afterSlash) {
                    re.setLength(re.length() - Pattern.quote("/").length());   // 직전에 붙은 `/` 리터럴 제거
                    re.append("(?:/.*)?");      // 끝의 `/**` → 자기 자신 또는 하위 전부
                    i += 2;
                    continue;
                }
                re.append(".*");                // 그 밖의 `**`
                i += 2;
                continue;
            }
            switch (c) {
                case '*' -> re.append("[^/]*");
                case '?' -> re.append("[^/]");
                case '[', ']', '{', '}', '\\' -> throw new TiaConfigException(
                        "지원하지 않는 글로브 문법 '" + c + "' in \"" + glob + "\" (지원: ** * ?)");
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return Pattern.compile(re.toString());
    }
}

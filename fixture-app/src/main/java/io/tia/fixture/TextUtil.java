package io.tia.fixture;

public final class TextUtil {
    private TextUtil() {}

    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}

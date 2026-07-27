package io.tia.gradle;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SP2 §3: 경로 글로브(SP1 code 필터, {@code /} 구분·package-relative) → 에이전트 클래스 패턴
 * ({@code .} 구분, JaCoCo {@code *}(dot 경계 무제한)/{@code ?} 와일드카드) 변환.
 *
 * <p><b>일괄 과포함 규칙</b> — 각 단계가 매칭 집합을 확장만 하므로(치환 대상이 더 넓은 와일드카드로만
 * 바뀜) 원본 글로브가 매칭하던 소스는 결과 패턴이 반드시 매칭한다(과소포함 반례 계열 — 기본 패키지·
 * 중간 {@code **}·중간 {@code ?}·내부/람다 클래스 — 를 일괄 해소한다):
 * <ol>
 *   <li>{@code .java} 접미 제거</li>
 *   <li>{@code **}{@code /} → {@code *} (0-세그먼트 포함 — JaCoCo {@code *}는 빈 문자열도 매칭)</li>
 *   <li>잔여 {@code /} → {@code .}</li>
 *   <li>잔여 {@code **} → {@code *}</li>
 *   <li>결과가 {@code *}로 끝나지 않으면 {@code *} 접미(내부/익명/람다 {@code $} 클래스 포함;
 *       "정확-파일" 특수 판정 불요 — 규칙이 보편 적용)</li>
 * </ol>
 *
 * <p>수집 필터는 성능 최적화이므로 과포함은 안전, 과소포함이 위험 — 이 유니온 규칙은 과포함만
 * 허용한다(예: {@code *gen.*}는 {@code mygen.Foo}도, {@code *Dto*}는 {@code DtoFactory}도 매칭).
 * 변환 불능 패턴은 없다(어휘가 {@code **}/{@code *}/{@code ?}뿐 — SP1 로더가 fail-fast).
 */
final class GlobToClassPattern {
    private GlobToClassPattern() {}

    /** 경로 글로브 1건을 JaCoCo 클래스 패턴 1건으로 변환한다(spec §3의 5단계 규칙 그대로). */
    static String convertOne(String glob) {
        String result = glob;
        if (result.endsWith(".java")) {
            result = result.substring(0, result.length() - ".java".length());
        }
        result = result.replace("**/", "*");
        result = result.replace("/", ".");
        result = result.replace("**", "*");
        if (!result.endsWith("*")) {
            result = result + "*";
        }
        return result;
    }

    /**
     * 글로브 목록을 각각 {@link #convertOne} 변환 후 {@code :}로 결합한다(콤마 금지 —
     * {@code -javaagent} 옵션 파서가 콤마를 옵션 구분자로 쓴다). 빈/null 목록은 빈 문자열을
     * 반환하며, 호출자는 그 경우 {@code includes}/{@code excludes} 옵션 자체를 생략한다.
     */
    static String convertAll(List<String> globs) {
        if (globs == null || globs.isEmpty()) {
            return "";
        }
        return globs.stream().map(GlobToClassPattern::convertOne).collect(Collectors.joining(":"));
    }
}

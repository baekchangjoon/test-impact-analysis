# TIA SP1 (tia.yml 설정 + 소비 필터 + 출력 포맷) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레포 루트 `tia.yml` 하나로 필터·기본값을 선언하고, `impact`/`flaky`/`report`가 소비 시점에 include/exclude를 적용하며, `impact`/`flaky`에 `--format text|summary|json|markdown`을 추가한다.

**Architecture:** 순수 로직(설정 로더·글로브 매칭·Diff 필터·포맷터)은 `tia-core`에 TDD로 만들고, `tia-cli`는 picocli `@Mixin` 하나로 모든 커맨드에 배선한다. 아우터 루프는 e2e 모듈의 인프로세스 picocli E2E(기존 `SpecAcceptanceE2ETest` 패턴), 이너 루프는 tia-core 단위 테스트.

**Tech Stack:** Java 17, picocli, jackson-databind(+`jackson-dataformat-yaml` 신규), RoaringBitmap, JUnit 5.

**참조 문서:**
- design spec: `docs/superpowers/specs/2026-07-26-usability-foundation-design.md`
- 요구사항명세: `docs/superpowers/requirements/2026-07-26-usability-foundation-requirements.md` (REQ-001..025) — 구현 중 매트릭스 상태(🔴→🟡→🟢)를 직접 갱신할 것

## Global Constraints

- JDK 17, 기존 Gradle 멀티모듈 구조 유지. Gradle wrapper(`./gradlew`) 사용.
- **기본 `--format text` 출력은 기존과 바이트 단위 동일** — 기존 stdout 라인(`# 매핑 기준 커밋:`, `CONFIDENCE\ttestId`, `# 주의:`, `# tia:no-baseline`, `flaky ratio: …`, `FLAKY\t…`)을 절대 변경하지 않는다 [REQ-012].
- 신규 경고(WARN)는 전부 **stderr**, 포맷 데이터 출력은 전부 **stdout** [REQ-017].
- 글로브는 OS 무관 `/` 구분 문자열 매칭. 지원 어휘는 `**`/`*`/`?` 뿐 — 그 밖(짝 안 맞는 `[` 등)은 설정 오류로 exit 1 [REQ-003/021].
- tia.yml 오류는 즉시 exit 1 + 파일·원인 stderr 메시지. 침묵 무시 금지 [REQ-003].
- E2E는 e2e 모듈 인프로세스 picocli 패턴(`CommandLine(new TiaCommand()).execute(...)` + stdout/stderr 캡처). e2e 모듈은 JUnit 병렬 실행이므로 **JVM cwd·전역 상태에 의존하는 테스트 금지** — 탐색 시작점은 명시 파라미터/히든 옵션으로 주입.
- 각 E2E는 검증하는 REQ-ID를 `@DisplayName("REQ-0NN: …")`으로 참조.
- 커밋 메시지에 `[REQ-0NN]` 표기(기존 레포 관례).
- 구현 시작 전 `superpowers:using-git-worktrees`로 전용 워크트리+브랜치(`feat/sp1-usability-foundation`)를 만든다.

## File Structure

**tia-core (신규):**
| 파일 | 책임 |
|---|---|
| `io/tia/core/config/TiaConfig.java` | 파싱된 설정 값 객체(record) |
| `io/tia/core/config/TiaConfigException.java` | 설정 오류(파일·원인 메시지 포함) |
| `io/tia/core/config/TiaConfigLoader.java` | 탐색(명시>상향)·YAML 파싱·검증·상대경로 해석 |
| `io/tia/core/filter/GlobMatcher.java` | `/` 고정 글로브 컴파일·매칭 |
| `io/tia/core/filter/FilterSet.java` | code/test include·exclude 판정 (`#` 정규화 포함) |
| `io/tia/core/filter/DiffFilter.java` | DiffSummary 3필드 필터 + 무시 목록 |
| `io/tia/core/format/FileImpact.java` | 변경 파일→교차 테스트 매핑(blind spot 분모) |
| `io/tia/core/format/ImpactFormats.java` | impact summary/json/markdown 렌더 |
| `io/tia/core/format/FlakyFormats.java` | flaky summary/json/markdown 렌더 |

**tia-cli (신규/수정):** `ConfigMixin.java`(신규), `ImpactCommand.java`·`FlakyCommand.java`·`ReportCommand.java`·`IndexCommand.java`(수정), `tia-core/build.gradle`(YAML 의존성).

**tia-core 수정:** `report/ReportBuilder.java`(Inputs에 FilterSet 추가).

**테스트:** tia-core 단위(`config/`·`filter/`·`format/`), tia-cli `CliWiringTest`, e2e `ConfigE2ETest`·`FilterE2ETest`·`FlakyFilterE2ETest`·`ReportFilterE2ETest`·`FormatE2ETest`.

---

### Task 1: tia.yml 로더 (tia-core/config)

**REQ-IDs:** REQ-001, REQ-003, REQ-023(경로 해석 부분)

**Files:**
- Modify: `tia-core/build.gradle` (의존성 1줄)
- Create: `tia-core/src/main/java/io/tia/core/config/TiaConfig.java`
- Create: `tia-core/src/main/java/io/tia/core/config/TiaConfigException.java`
- Create: `tia-core/src/main/java/io/tia/core/config/TiaConfigLoader.java`
- Test: `tia-core/src/test/java/io/tia/core/config/TiaConfigLoaderTest.java`

**Interfaces:**
- Produces:
  - `record TiaConfig(int version, String sutName, Path db, FilterLists code, FilterLists test)` + `record FilterLists(List<String> include, List<String> exclude)` (둘 다 non-null, 기본 빈 리스트) + `static TiaConfig empty()`
  - `TiaConfigLoader.load(Path explicitOrNull, Path searchStart)` → `Optional<TiaConfig>`; 못 찾으면 `Optional.empty()`; 오류는 `TiaConfigException(메시지에 파일 경로+원인)`. **`explicitOrNull != null`이면 상향 탐색을 수행하지 않는다.** 상향 탐색은 `searchStart`부터 부모로 올라가며 `.git`(파일/디렉터리)이 있는 디렉터리까지(포함) `tia.yml`을 찾는다. `db` 상대 경로는 tia.yml 부모 디렉터리 기준으로 절대화해 담는다.
  - 검증: `version != 1` / 알 수 없는 최상위 키 / YAML 파싱 실패 → `TiaConfigException`. (글로브 문법 검증은 Task 2의 `FilterSet` 생성 시점.)

- [ ] **Step 1: 의존성 추가**

`tia-core/build.gradle` dependencies 블록에:

```groovy
    implementation 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2'   // tia.yml 로더 (SP1)
```

- [ ] **Step 2: 실패하는 단위 테스트 작성** — `TiaConfigLoaderTest.java`

```java
package io.tia.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TiaConfigLoaderTest {
    @TempDir Path root;

    private Path write(Path dir, String yaml) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve("tia.yml");
        Files.writeString(f, yaml);
        return f;
    }

    @Test void explicitConfigWins_andSkipsDiscoveryEntirely() throws Exception {
        // 탐색 경로에 '깨진' tia.yml — explicit이 있으면 절대 파싱되면 안 된다 [REQ-001]
        write(root, "version: [broken");
        Path sub = root.resolve("mod");
        Path explicit = write(sub.resolve("cfg"), "version: 1\nsut-name: svc\n");
        Optional<TiaConfig> c = TiaConfigLoader.load(explicit, root);
        assertEquals("svc", c.orElseThrow().sutName());
    }

    @Test void upwardDiscoveryStopsAtGitRoot() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        write(root, "version: 1\nsut-name: from-root\n");
        Path deep = root.resolve("a/b");
        Files.createDirectories(deep);
        assertEquals("from-root", TiaConfigLoader.load(null, deep).orElseThrow().sutName());
    }

    @Test void absentYmlReturnsEmpty() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        assertTrue(TiaConfigLoader.load(null, root).isEmpty());
    }

    @Test void relativeDbResolvedAgainstYmlDir() throws Exception {
        write(root, "version: 1\ndb: .tia/tia.db\n");
        TiaConfig c = TiaConfigLoader.load(root.resolve("tia.yml"), root).orElseThrow();
        assertEquals(root.resolve(".tia/tia.db").normalize(), c.db());  // cwd 아닌 yml 위치 기준 [REQ-023]
    }

    @Test void unsupportedVersionFails() throws Exception {
        Path f = write(root, "version: 99\n");
        TiaConfigException e = assertThrows(TiaConfigException.class,
                () -> TiaConfigLoader.load(f, root));
        assertTrue(e.getMessage().contains(f.toString()));
        assertTrue(e.getMessage().contains("version"));
    }

    @Test void unknownTopLevelKeyFails() throws Exception {
        Path f = write(root, "version: 1\nfiltres: {}\n");
        assertTrue(assertThrows(TiaConfigException.class,
                () -> TiaConfigLoader.load(f, root)).getMessage().contains("filtres"));
    }

    @Test void malformedYamlFails() throws Exception {
        Path f = write(root, "version: [broken");
        assertTrue(assertThrows(TiaConfigException.class,
                () -> TiaConfigLoader.load(f, root)).getMessage().contains(f.toString()));
    }

    @Test void filtersParsedIntoLists() throws Exception {
        Path f = write(root, """
                version: 1
                filters:
                  code:
                    include: ["com/acme/**"]
                    exclude: ["**/gen/**"]
                  test:
                    exclude: ["**/*Slow*"]
                """);
        TiaConfig c = TiaConfigLoader.load(f, root).orElseThrow();
        assertEquals(List.of("com/acme/**"), c.code().include());
        assertEquals(List.of("**/gen/**"), c.code().exclude());
        assertEquals(List.of(), c.test().include());
        assertEquals(List.of("**/*Slow*"), c.test().exclude());
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :tia-core:test --tests 'io.tia.core.config.*'`
Expected: 컴파일 실패(클래스 없음) — red 확인.

- [ ] **Step 4: 구현**

`TiaConfig.java`:

```java
package io.tia.core.config;

import java.nio.file.Path;
import java.util.List;

/** tia.yml 파싱 결과. 리스트는 항상 non-null(기본 빈 리스트). */
public record TiaConfig(int version, String sutName, Path db, FilterLists code, FilterLists test) {
    public record FilterLists(List<String> include, List<String> exclude) {
        public static FilterLists empty() { return new FilterLists(List.of(), List.of()); }
    }
    public static TiaConfig empty() {
        return new TiaConfig(1, null, null, FilterLists.empty(), FilterLists.empty());
    }
}
```

`TiaConfigException.java`:

```java
package io.tia.core.config;

/** tia.yml 로딩/검증 오류 — 메시지에 파일 경로와 원인을 담는다(fail-fast, exit 1 대상). */
public class TiaConfigException extends RuntimeException {
    public TiaConfigException(String message) { super(message); }
    public TiaConfigException(String message, Throwable cause) { super(message, cause); }
}
```

`TiaConfigLoader.java`:

```java
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

    /** explicitOrNull이 있으면 그 파일만 로드(상향 탐색·다른 파일 파싱 안 함) [REQ-001]. */
    public static Optional<TiaConfig> load(Path explicitOrNull, Path searchStart) {
        Path file = (explicitOrNull != null) ? explicitOrNull : discover(searchStart);
        if (file == null) return Optional.empty();
        if (explicitOrNull != null && !Files.isRegularFile(file))
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
```

- [ ] **Step 5: 통과 확인** — Run: `./gradlew :tia-core:test --tests 'io.tia.core.config.*'` → PASS
- [ ] **Step 6: Commit** — `git add tia-core && git commit -m "feat(core): tia.yml 로더 — 탐색 시임·검증 fail-fast·상대경로 해석 [REQ-001/003/023]"`

---

### Task 2: 글로브 매칭 + FilterSet (tia-core/filter)

**REQ-IDs:** REQ-004, REQ-005, REQ-006, REQ-021, REQ-003(글로브 문법 오류)

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/filter/GlobMatcher.java`
- Create: `tia-core/src/main/java/io/tia/core/filter/FilterSet.java`
- Test: `tia-core/src/test/java/io/tia/core/filter/GlobFilterTest.java`

**Interfaces:**
- Consumes: `TiaConfig.FilterLists`, `TiaConfigException` (Task 1)
- Produces:
  - `GlobMatcher.compile(List<String> globs)` → `GlobMatcher`; `boolean matchesAny(String slashPath)`; 지원 어휘 `**`/`*`/`?` 밖의 문법은 `TiaConfigException`. 매칭은 OS 무관 `/` 구분 정규식 변환(자체 컴파일, `FileSystem#getPathMatcher` 미사용) [REQ-021].
  - `FilterSet.of(List<String> codeInc, List<String> codeExc, List<String> testInc, List<String> testExc)` → `FilterSet`
  - `boolean acceptsCode(String canonicalPath)` — include(빈=전체)∧¬exclude
  - `boolean acceptsUnmappable(String path)` — **exclude만** 평가(include 우회) [REQ-007 의미론]
  - `boolean acceptsTest(String testId)` — `testId.replace('#','/')` 후 include/exclude [REQ-005]
  - `boolean hasCodeFilters()` / `boolean hasTestFilters()` / getter 4종(`codeInclude()` 등, JSON `appliedFilters`용)
  - `FilterSet.none()` — 전량 통과

- [ ] **Step 1: 실패하는 단위 테스트 작성** — `GlobFilterTest.java`

```java
package io.tia.core.filter;

import io.tia.core.config.TiaConfigException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GlobFilterTest {

    @Test void excludeWins() {   // [REQ-006]
        FilterSet f = FilterSet.of(List.of("com/acme/**"), List.of("com/acme/gen/**"), List.of(), List.of());
        assertTrue(f.acceptsCode("com/acme/Svc.java"));
        assertFalse(f.acceptsCode("com/acme/gen/Dto.java"));   // include에도 걸리지만 exclude 우선
    }

    @Test void emptyIncludeMeansAll() {   // [REQ-006]
        FilterSet f = FilterSet.of(List.of(), List.of("**/gen/**"), List.of(), List.of());
        assertTrue(f.acceptsCode("anything/At/All.java"));
        assertFalse(f.acceptsCode("x/gen/Y.java"));
    }

    @Test void codeGlobMatchesCanonicalPackageRelativePath() {   // [REQ-004]
        FilterSet f = FilterSet.of(List.of("com/acme/**"), List.of(), List.of(), List.of());
        assertTrue(f.acceptsCode("com/acme/pricing/PricingService.java"));
        assertFalse(f.acceptsCode("org/other/Thing.java"));
    }

    @Test void unmappableBypassesInclude() {   // [REQ-007] build.gradle은 include 미매칭이어도 생존
        FilterSet f = FilterSet.of(List.of("com/acme/**"), List.of(), List.of(), List.of());
        assertTrue(f.acceptsUnmappable("build.gradle"));
        FilterSet g = FilterSet.of(List.of("com/acme/**"), List.of("**/*.gradle"), List.of(), List.of());
        assertFalse(g.acceptsUnmappable("build.gradle"));      // 명시적 exclude만 제거
    }

    @Test void testIdHashNormalizedBeforeMatch() {   // [REQ-005]
        FilterSet f = FilterSet.of(List.of(), List.of(), List.of(), List.of("AuthApiBlackBoxIT/**"));
        assertFalse(f.acceptsTest("AuthApiBlackBoxIT#loginWithInvalidCredentialsReturns400"));
        assertTrue(f.acceptsTest("io/tia/fixture/ApiSmokeTest/testPrice"));
    }

    @Test void singleStarDoesNotCrossSlash_doubleStarDoes() {   // [REQ-021]
        FilterSet f = FilterSet.of(List.of(), List.of("com/*/Svc.java"), List.of(), List.of());
        assertFalse(f.acceptsCode("com/a/Svc.java"));
        assertTrue(f.acceptsCode("com/a/b/Svc.java"));          // `*`는 `/` 못 넘음
        FilterSet g = FilterSet.of(List.of(), List.of("com/**/Svc.java"), List.of(), List.of());
        assertFalse(g.acceptsCode("com/a/b/Svc.java"));          // `**`는 넘음
    }

    @Test void unsupportedGlobSyntaxFailsFast() {   // [REQ-003] 지원 어휘 밖
        assertThrows(TiaConfigException.class,
                () -> FilterSet.of(List.of("[unterminated"), List.of(), List.of(), List.of()));
        assertThrows(TiaConfigException.class,
                () -> FilterSet.of(List.of("{a,b}/**"), List.of(), List.of(), List.of()));
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :tia-core:test --tests 'io.tia.core.filter.*'` → 컴파일 실패(red)
- [ ] **Step 3: 구현**

`GlobMatcher.java`:

```java
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

    private static Pattern toRegex(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') { re.append(".*"); i++; }
                    else re.append("[^/]*");
                }
                case '?' -> re.append("[^/]");
                case '[', ']', '{', '}', '\\' -> throw new TiaConfigException(
                        "지원하지 않는 글로브 문법 '" + c + "' in \"" + glob + "\" (지원: ** * ?)");
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }
}
```

`FilterSet.java`:

```java
package io.tia.core.filter;

import java.util.List;

/** 해석 완료된 code/test include·exclude 필터. exclude가 include보다 우선, include 비면 전체 [REQ-006]. */
public final class FilterSet {
    private final List<String> codeInc, codeExc, testInc, testExc;
    private final GlobMatcher codeIncM, codeExcM, testIncM, testExcM;

    private FilterSet(List<String> ci, List<String> ce, List<String> ti, List<String> te) {
        this.codeInc = ci; this.codeExc = ce; this.testInc = ti; this.testExc = te;
        this.codeIncM = GlobMatcher.compile(ci); this.codeExcM = GlobMatcher.compile(ce);
        this.testIncM = GlobMatcher.compile(ti); this.testExcM = GlobMatcher.compile(te);
    }

    public static FilterSet of(List<String> codeInc, List<String> codeExc,
                               List<String> testInc, List<String> testExc) {
        return new FilterSet(List.copyOf(codeInc), List.copyOf(codeExc),
                List.copyOf(testInc), List.copyOf(testExc));
    }

    public static FilterSet none() { return of(List.of(), List.of(), List.of(), List.of()); }

    /** 매핑 가능한 .java 정규화 경로용: include(빈=전체) ∧ ¬exclude [REQ-004]. */
    public boolean acceptsCode(String canonicalPath) {
        if (codeExcM.matchesAny(canonicalPath)) return false;
        return codeInc.isEmpty() || codeIncM.matchesAny(canonicalPath);
    }

    /** 비-Java(unmappable)용: include 우회, 명시적 exclude만 평가 [REQ-007]. */
    public boolean acceptsUnmappable(String path) { return !codeExcM.matchesAny(path); }

    /** testId는 `#`→`/` 정규화 후 매칭 (out-of-process id 지원) [REQ-005]. */
    public boolean acceptsTest(String testId) {
        String norm = testId.replace('#', '/');
        if (testExcM.matchesAny(norm)) return false;
        return testInc.isEmpty() || testIncM.matchesAny(norm);
    }

    public boolean hasCodeFilters() { return !codeInc.isEmpty() || !codeExc.isEmpty(); }
    public boolean hasTestFilters() { return !testInc.isEmpty() || !testExc.isEmpty(); }
    public List<String> codeInclude() { return codeInc; }
    public List<String> codeExclude() { return codeExc; }
    public List<String> testInclude() { return testInc; }
    public List<String> testExclude() { return testExc; }
}
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :tia-core:test --tests 'io.tia.core.filter.*'` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(core): GlobMatcher+FilterSet — / 고정 글로브·#정규화·exclude 우선 [REQ-004/005/006/021]"`

---

### Task 3: DiffFilter — DiffSummary 3필드 필터 (tia-core/filter)

**REQ-IDs:** REQ-007, REQ-008(코어 절반)

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/filter/DiffFilter.java`
- Test: `tia-core/src/test/java/io/tia/core/filter/DiffSummaryFilterTest.java`

**Interfaces:**
- Consumes: `FilterSet` (Task 2), `DiffSummary` (기존: `Map<String,RoaringBitmap> changedOldLinesByJavaFile, Set<String> additionOnlyJavaFiles, Set<String> unmappableFiles`)
- Produces: `DiffFilter.apply(DiffSummary diff, FilterSet filters)` → `record Result(DiffSummary diff, List<String> ignoredFiles)` — `ignoredFiles`는 제거된 파일 경로(순서 안정, 세 필드 합산).

- [ ] **Step 1: 실패하는 단위 테스트 작성** — `DiffSummaryFilterTest.java`

```java
package io.tia.core.filter;

import io.tia.core.model.DiffSummary;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiffSummaryFilterTest {

    private DiffSummary diff() {
        return new DiffSummary(
                Map.of("com/acme/Svc.java", RoaringBitmap.bitmapOf(5),
                       "com/acme/gen/Dto.java", RoaringBitmap.bitmapOf(9)),
                Set.of("com/acme/gen/NewGen.java"),
                Set.of("build.gradle", "conf/app.yml"));
    }

    @Test void filtersAllThreeFields() {   // [REQ-007]
        FilterSet f = FilterSet.of(List.of(), List.of("**/gen/**", "conf/**"), List.of(), List.of());
        DiffFilter.Result r = DiffFilter.apply(diff(), f);
        assertEquals(Set.of("com/acme/Svc.java"), r.diff().changedOldLinesByJavaFile().keySet());
        assertTrue(r.diff().additionOnlyJavaFiles().isEmpty());          // 신규 파일도 제거
        assertEquals(Set.of("build.gradle"), r.diff().unmappableFiles()); // conf/app.yml 제거
        assertEquals(3, r.ignoredFiles().size());
    }

    @Test void includeDoesNotDropUnmappable() {   // [REQ-007] include만으로 build.gradle이 사라지면 안 됨
        FilterSet f = FilterSet.of(List.of("com/acme/**"), List.of(), List.of(), List.of());
        DiffFilter.Result r = DiffFilter.apply(diff(), f);
        assertEquals(Set.of("build.gradle", "conf/app.yml"), r.diff().unmappableFiles());
        assertTrue(r.ignoredFiles().isEmpty());
    }

    @Test void allExcludedYieldsEmptyDiff() {   // [REQ-008] 코어 전제
        FilterSet f = FilterSet.of(List.of(), List.of("**"), List.of(), List.of());
        DiffFilter.Result r = DiffFilter.apply(diff(), f);
        assertTrue(r.diff().changedOldLinesByJavaFile().isEmpty());
        assertTrue(r.diff().additionOnlyJavaFiles().isEmpty());
        assertTrue(r.diff().unmappableFiles().isEmpty());
        assertEquals(4, r.ignoredFiles().size());
    }

    @Test void noFiltersIsIdentity() {
        DiffFilter.Result r = DiffFilter.apply(diff(), FilterSet.none());
        assertEquals(diff(), r.diff());
        assertTrue(r.ignoredFiles().isEmpty());
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :tia-core:test --tests 'io.tia.core.filter.DiffSummaryFilterTest'` → red
- [ ] **Step 3: 구현** — `DiffFilter.java`

```java
package io.tia.core.filter;

import io.tia.core.model.DiffSummary;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** code 필터를 DiffSummary 세 필드 전부에 적용 [REQ-007].
 *  - 매핑 가능(.java 키): include ∧ ¬exclude
 *  - unmappable(비-Java): include 우회, exclude만 (CONSERVATIVE 안전망 보존) */
public final class DiffFilter {
    private DiffFilter() {}

    public record Result(DiffSummary diff, List<String> ignoredFiles) {}

    public static Result apply(DiffSummary diff, FilterSet filters) {
        List<String> ignored = new ArrayList<>();
        Map<String, RoaringBitmap> changed = new LinkedHashMap<>();
        diff.changedOldLinesByJavaFile().forEach((file, lines) -> {
            if (filters.acceptsCode(file)) changed.put(file, lines); else ignored.add(file);
        });
        Set<String> additions = new LinkedHashSet<>();
        for (String f : diff.additionOnlyJavaFiles()) {
            if (filters.acceptsCode(f)) additions.add(f); else ignored.add(f);
        }
        Set<String> unmappable = new LinkedHashSet<>();
        for (String f : diff.unmappableFiles()) {
            if (filters.acceptsUnmappable(f)) unmappable.add(f); else ignored.add(f);
        }
        return new Result(new DiffSummary(changed, additions, unmappable), List.copyOf(ignored));
    }
}
```

- [ ] **Step 4: 통과 확인** — PASS 후
- [ ] **Step 5: Commit** — `git commit -m "feat(core): DiffFilter — DiffSummary 3필드 필터·unmappable include 우회 [REQ-007/008]"`

---

### Task 4: E2E 아우터 루프 작성 (전부 red)

**REQ-IDs:** REQ-001, REQ-002, REQ-003, REQ-004, REQ-005, REQ-007, REQ-008, REQ-009, REQ-010, REQ-011, REQ-013, REQ-014, REQ-015, REQ-016, REQ-017, REQ-018, REQ-022, REQ-023, REQ-024

**Files:**
- Create: `e2e/src/test/java/io/tia/e2e/config/ConfigE2ETest.java`
- Create: `e2e/src/test/java/io/tia/e2e/filter/FilterE2ETest.java`
- Create: `e2e/src/test/java/io/tia/e2e/filter/FlakyFilterE2ETest.java`
- Create: `e2e/src/test/java/io/tia/e2e/filter/ReportFilterE2ETest.java`
- Create: `e2e/src/test/java/io/tia/e2e/format/FormatE2ETest.java`
- Create: `tia-cli/src/test/java/io/tia/cli/CliWiringTest.java`

**Interfaces:**
- Consumes: 기존 `TiaCommand`, e2e 리소스 `/spec-testwise.json`(testId `io/tia/fixture/ApiSmokeTest/testPrice`·`testGreeting`; PricingService{6,7,8}, GreetingService{6,7}, TextUtil{6}), `SpecAcceptanceE2ETest`의 run/캡처 패턴.
- Produces: 이후 Task 5~8의 완료 기준(green 목표). **이 태스크의 테스트는 작성 시점에 실패(red)가 정상 — 약화·주석처리 금지.**

- [ ] **Step 1: 공통 헬퍼 포함 E2E 작성.** 각 클래스는 `SpecAcceptanceE2ETest`와 같은 패턴: `@TempDir Path work;` + 아래 헬퍼를 클래스마다 복사(테스트 독립성 우선, e2e 병렬 안전).

```java
    /** stdout/stderr 캡처 실행 헬퍼 — SpecAcceptanceE2ETest 패턴. */
    record Exec(int code, String out, String err) {}
    static Exec run(String... args) {
        PrintStream oo = System.out, oe = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new TiaCommand()).execute(args);
            return new Exec(code, bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(oo); System.setErr(oe); }
    }
```

핵심 케이스(각 테스트는 `@DisplayName("REQ-0NN: …")` 필수). 대표 예 — `FilterE2ETest`:

```java
    // 준비: index --report spec-testwise.json --repo fixture --commit C0 --db work/tia.db
    // tia.yml은 work/ 아래 작성, --config로 명시 주입(경로 독립).

    @Test @DisplayName("REQ-009: 제외 테스트는 DETERMINISTIC이어도 출력되지 않는다")
    void excludedTestNeverOutput() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  test:
                    exclude: ["**/testPrice"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiff("PricingService.java", 8).toString(),
                "--config", yml.toString());
        assertEquals(0, r.code());
        assertFalse(r.out().contains("testPrice"), r.out());   // DETERMINISTIC 히트였을 테스트
    }

    @Test @DisplayName("REQ-008: 전부 제외된 diff → 0건 + stderr WARN + exit 0")
    void allExcludedDiffZeroSelection() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    exclude: ["com/acme/gen/**"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", modifyDiffFor("src/main/java/com/acme/gen/G.java", 3).toString(),
                "--config", yml.toString());
        assertEquals(0, r.code());
        assertTrue(r.out().contains("영향 테스트 0개"), r.out());
        assertTrue(r.err().contains("# WARN: excluded change ignored: com/acme/gen/G.java"), r.err());
    }

    @Test @DisplayName("REQ-007: 좁은 include여도 build.gradle 변경은 CONSERVATIVE 발동")
    void unmappableBypassesInclude() throws Exception {
        Path yml = writeYml("""
                version: 1
                filters:
                  code:
                    include: ["com/acme/**"]
                """);
        Exec r = run("impact", "--db", db.toString(), "--commit", "C0",
                "--diff-file", unmappableDiff("build.gradle").toString(),
                "--config", yml.toString());
        assertTrue(r.out().contains("보수적 전체 선택"), r.out());
    }
```

`ConfigE2ETest` 케이스: `configFlagPrecedence`(REQ-001: 탐색 경로에 깨진 tia.yml + `--config` 유효 파일 → 에러 없음), `invalidYmlFailsFast`(REQ-003: 4변형 — 깨진 YAML·version 99·`filtres:`·`[unterminated` 글로브 각각 exit 1 + stderr에 파일·원인), `flagReplacesListNotMerge`(REQ-002: yml의 test.exclude를 `--exclude-test`로 대체 → yml 목록 무효), `ymlDbDefaultRelativeToYml`/`dbFlagBeatsYml`/`noDbKeepsCommonDirDefault`(REQ-023), `ymlSutNameDefault`/`sutNameFlagBeatsYml`(REQ-024: report 출력 HTML에 sut-name 반영), `absentYmlUnchanged`(REQ-001: tia.yml 없이 기존 출력 동일 — `SpecAcceptanceE2ETest`와 같은 시나리오 1개 재실행 대조). 상향 탐색은 `--config` 없이 `work/` 안에 `.git` 디렉터리 + `tia.yml` + 하위 디렉터리를 만들고 **히든 옵션 `--search-root <dir>`**(Task 5에서 배선)로 시작점을 주입해 검증한다(JVM cwd 비의존).

`FilterE2ETest` 추가 케이스: `codeGlobMatchesCanonicalPath`(REQ-004: diff 헤더는 `src/main/java/...` 경로, 글로브는 `com/...` — 매칭됨), `hashTestIdNormalization`(REQ-005: `Class#method` testId 픽스처를 별도 testwise JSON으로 인덱싱 후 exclude 글로브 적용), `excludedNewFileNoConservative`+`warnPerIgnoredFile`(REQ-007), `excludedFromConservativeSet`(REQ-009), `nonMatchingFilterKeepsConservative`(REQ-007).

`FlakyFilterE2ETest`: `excludedBeforeAggregation`(REQ-010: run-result 2개, flaky 1개가 exclude 대상 → 목록 부재 + `flaky ratio: 0.000 (0/1)` 형태로 분모 재계산), `allExcludedRatioZero`(REQ-010: 전체 제외 → `0.000 (0/0)` + stderr 경고 + exit 0).

`ReportFilterE2ETest`: `filteredAxesNotRendered`(REQ-011: testwise+prod-files에 제외 대상 포함 → 출력 HTML 문자열에 제외 testId·제외 파일 부재, 입력 파일 내용 불변).

`FormatE2ETest`: `impactJsonSchema`(REQ-013: jackson으로 파싱, `schemaVersion==1`·`command=="impact"`·`tests[0].id/confidence` 존재), `flakyJsonSchema`(REQ-014: `ratio`·`totalTests`·`flakyTests` 존재, `commit` 키 부재), `impactSummaryPipedNoAnsi`(REQ-015: blind spot·무시 수·다음 행동 문구 존재, `[` 부재), `impactMarkdownTableAndDetails`(REQ-016: `|` 테이블 행 + `<details>` + 무시 수), `stderrWarnStdoutData`(REQ-017: json을 stdout만으로 파싱 + text 포맷에서 WARN은 stderr·`# 주의:`는 stdout), `exitCodeFormatIndependent_impact`/`_flaky`(REQ-018: 4포맷 exit 동일).

`CliWiringTest`(tia-cli): picocli `CommandSpec`으로 커맨드×옵션 표 검증(REQ-022) —

```java
    @Test void optionsPerCommandTable() {
        CommandLine tia = new CommandLine(new TiaCommand());
        assertTrue(hasOption(tia, "impact", "--config"));
        assertTrue(hasOption(tia, "impact", "--format"));
        assertTrue(hasOption(tia, "flaky", "--format"));
        assertTrue(hasOption(tia, "report", "--config"));
        assertTrue(hasOption(tia, "index", "--config"));
        assertFalse(hasOption(tia, "convert", "--config"));   // 소비할 값 없음
        assertFalse(hasOption(tia, "report", "--format"));    // HTML 전용
        assertFalse(hasOption(tia, "flaky", "--include-code"));
    }
    static boolean hasOption(CommandLine tia, String sub, String opt) {
        return tia.getSubcommands().get(sub).getCommandSpec().optionsMap().containsKey(opt);
    }
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :e2e:test --tests 'io.tia.e2e.config.*' --tests 'io.tia.e2e.filter.*' --tests 'io.tia.e2e.format.*' ; ./gradlew :tia-cli:test --tests io.tia.cli.CliWiringTest`
Expected: 전부 FAIL(미지원 옵션 → picocli usage exit 2 등). **컴파일은 성공해야 한다**(문자열 인자만 사용).
- [ ] **Step 3: Commit** — `git commit -m "test(e2e): SP1 아우터 루프 E2E (red) — config/filter/format/wiring [REQ-001..025]"`
- [ ] **Step 4: 매트릭스 갱신** — 요구사항명세 추적 매트릭스에서 해당 REQ를 🔴→🟡로 갱신하고 같은 커밋 또는 후속 docs 커밋에 포함.

---

### Task 5: ConfigMixin + ImpactCommand 배선 (text 경로)

**REQ-IDs:** REQ-001, REQ-002, REQ-003, REQ-004, REQ-005, REQ-007, REQ-008, REQ-009, REQ-012, REQ-017(text 부분), REQ-022, REQ-023

**Files:**
- Create: `tia-cli/src/main/java/io/tia/cli/ConfigMixin.java`
- Modify: `tia-cli/src/main/java/io/tia/cli/ImpactCommand.java`
- Modify: `tia-cli/src/main/java/io/tia/cli/IndexCommand.java` (`--config` + db 기본값)
- Test: green 대상 — `ConfigE2ETest`(REQ-024 제외), `FilterE2ETest`, `CliWiringTest`(report/flaky 항목 제외)

**Interfaces:**
- Consumes: `TiaConfigLoader.load`, `FilterSet.of`, `DiffFilter.apply` (Task 1~3)
- Produces (Task 6~8이 사용):

```java
public class ConfigMixin {
    @Option(names = "--config", description = "tia.yml 경로 (미지정 시 상향 탐색)") Path config;
    @Option(names = "--search-root", hidden = true,
            description = "탐색 시작 디렉터리(테스트 시임; 기본 cwd)") Path searchRoot;
    @Option(names = "--include-code") List<String> includeCode;
    @Option(names = "--exclude-code") List<String> excludeCode;
    @Option(names = "--include-test") List<String> includeTest;
    @Option(names = "--exclude-test") List<String> excludeTest;

    public record Resolved(TiaConfig config, FilterSet filters) {}

    /** 플래그는 tia.yml의 해당 '목록'을 대체(병합 아님) [REQ-002]. TiaConfigException은 호출측이 잡아 exit 1. */
    public Resolved resolve() {
        Path start = (searchRoot != null) ? searchRoot : Path.of("").toAbsolutePath();
        TiaConfig cfg = TiaConfigLoader.load(config, start).orElse(TiaConfig.empty());
        FilterSet f = FilterSet.of(
                includeCode != null ? includeCode : cfg.code().include(),
                excludeCode != null ? excludeCode : cfg.code().exclude(),
                includeTest != null ? includeTest : cfg.test().include(),
                excludeTest != null ? excludeTest : cfg.test().exclude());
        return new Resolved(cfg, f);
    }
}
```

- [ ] **Step 1: ConfigMixin 구현** (위 코드 그대로 + import).
- [ ] **Step 2: ImpactCommand 수정.** 기존 흐름을 유지하되:

```java
    @Mixin ConfigMixin configMixin;

    @Override public Integer call() throws Exception {
        ConfigMixin.Resolved resolved;
        try { resolved = configMixin.resolve(); }
        catch (TiaConfigException e) { System.err.println("ERROR: " + e.getMessage()); return 1; }  // [REQ-003]
        FilterSet filters = resolved.filters();
        Path effectiveDb = (db != null) ? db
                : (resolved.config().db() != null) ? resolved.config().db()      // [REQ-023]
                : DbPaths.resolveDefault();
        // …기존 로드/no-baseline 로직 그대로 (출력 문자열 불변 [REQ-012])…
        DiffSummary rawDiff = new GitDiffParser().parse(diffText);
        DiffFilter.Result filtered = DiffFilter.apply(rawDiff, filters);
        for (String f : filtered.ignoredFiles())
            System.err.println("# WARN: excluded change ignored: " + f);          // stderr [REQ-017]
        ImpactResult r = new ImpactAnalyzer().select(snap, filtered.diff());
        List<ImpactedTest> visible = r.impacted().stream()
                .filter(t -> filters.acceptsTest(t.testId())).toList();           // [REQ-009]
        // 기존 3종 stdout 라인은 visible 기준으로 그대로 출력 (count = visible.size())
    }
```

주의: **필터 미사용 시(빈 FilterSet + tia.yml 없음) 출력이 기존과 바이트 동일**해야 한다 — `visible`은 이 경우 `r.impacted()`와 동일 리스트가 되고 WARN 0줄.
- [ ] **Step 3: IndexCommand 수정** — `@Mixin ConfigMixin`(필터는 미사용, db 기본값만: `db != null ? db : cfg.db() != null ? cfg.db() : DbPaths.resolveDefault()`) + 같은 `TiaConfigException` 처리.
- [ ] **Step 4: green 확인** — Run: `./gradlew :e2e:test --tests 'io.tia.e2e.config.ConfigE2ETest' --tests 'io.tia.e2e.filter.FilterE2ETest' :tia-cli:test`
Expected: REQ-024(sut-name)·format 관련 외 전부 PASS. 기존 `SpecAcceptanceE2ETest`·`ImpactCommandTest`·`IndexCommandTest`도 무변경 PASS [REQ-012 1차 확인].
- [ ] **Step 5: Commit + 매트릭스 🟡→🟢 갱신** — `git commit -m "feat(cli): ConfigMixin + impact/index 배선 — tia.yml·필터·db 기본값 [REQ-001..009/023]"`

---

### Task 6: 출력 포맷터 + impact `--format`

**REQ-IDs:** REQ-013, REQ-015, REQ-016, REQ-017, REQ-018(impact)

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/format/FileImpact.java`
- Create: `tia-core/src/main/java/io/tia/core/format/ImpactFormats.java`
- Modify: `tia-cli/src/main/java/io/tia/cli/ImpactCommand.java` (`--format` 옵션)
- Test: `tia-core/src/test/java/io/tia/core/format/ImpactFormatsTest.java` (골든 검증) + `FormatE2ETest`(impact 절반)

**Interfaces:**
- Consumes: `ImpactResult`, `CoverageSnapshot`, `DiffSummary`, `FilterSet`
- Produces:

```java
public final class FileImpact {
    /** 변경 파일별 교차 테스트 목록(빈 리스트 = blind spot). 키는 changedOldLinesByJavaFile 순서. */
    public static Map<String, List<String>> testsByChangedFile(CoverageSnapshot snap, DiffSummary diff)
}
public final class ImpactFormats {
    public record Payload(String commit, List<ImpactedTest> tests, boolean conservative,
                          List<String> reasons, List<String> ignoredFiles,
                          Map<String, List<String>> testsByFile, FilterSet filters) {}
    public static String json(Payload p)        // §4 스키마: schemaVersion=1, command="impact" [REQ-013]
    public static String summary(Payload p, boolean ansiColor)   // [REQ-015]
    public static String markdown(Payload p)    // [REQ-016]
}
```

- [ ] **Step 1: 실패하는 단위 테스트** — `ImpactFormatsTest`: ① `json()` 결과를 jackson 파싱해 `schemaVersion/command/commit/appliedFilters/tests[].{id,confidence,reason}/ignoredChangedFiles/warnings` 필드 검증 ② `summary(p,false)`에 카운트 라인·`blind spot`·`무시` 문구·`다음` 안내·ANSI 부재 ③ `markdown()`에 `|` 테이블(선별·Confidence별·무시 수)·`<details>` 포함. 테스트 데이터는 ImpactedTest 2개(DETERMINISTIC/CONSERVATIVE)+ignored 1개+blind spot 파일 1개.
- [ ] **Step 2: red 확인** — `./gradlew :tia-core:test --tests 'io.tia.core.format.*'`
- [ ] **Step 3: 구현.** `json()`은 `ObjectMapper.createObjectNode()`로 §4 스키마 그대로(`warnings`는 `"excluded change ignored: <path>"` 문자열 목록). `summary()` 레이아웃(무색 기준):

```
영향 테스트 3/12개 선별 (DETERMINISTIC 2 · CONSERVATIVE 1)   @ <commit>
파일별:
  com/acme/PricingService.java → testPrice, testBulk
  com/acme/Unused.java → (blind spot: 이 변경을 커버하는 테스트 없음)
필터로 무시된 변경 파일: 1개
다음: 선별된 테스트만 실행하세요. blind spot 파일은 테스트 보강을 검토하세요.
```

`ansiColor=true`면 카운트에 `[1m` 등 적용; CLI에서 `System.console() != null && System.getenv("NO_COLOR") == null`일 때만 true [REQ-015]. `markdown()`: 헤더 테이블(`| 선별 | DETERMINISTIC | CONSERVATIVE | 무시된 변경 |`) + `<details><summary>선별 목록</summary>` 내 테스트별 줄.
- [ ] **Step 4: ImpactCommand에 `--format` 배선**

```java
    enum Format { text, summary, json, markdown }
    @Option(names = "--format", defaultValue = "text",
            description = "출력 형식: ${COMPLETION-CANDIDATES} (기본 text = 기존 출력)") Format format;
```

`format == text`면 **기존 출력 경로 그대로**(바이트 동일 [REQ-012]); 그 외엔 Payload를 만들어 해당 포맷터의 문자열만 stdout으로 출력. no-baseline(`# tia:no-baseline`) 경로는 text 전용 마커이므로, 비-text 포맷에선 `warnings`에 `"no-baseline"`을 담은 스키마 출력 + 동일 exit code [REQ-018].
- [ ] **Step 5: green 확인** — `./gradlew :tia-core:test :e2e:test --tests 'io.tia.e2e.format.FormatE2ETest'` → impact 관련 케이스 PASS
- [ ] **Step 6: Commit + 매트릭스 갱신** — `git commit -m "feat(format): impact summary/json/markdown + --format [REQ-013/015/016/017/018]"`

---

### Task 7: flaky 필터 + `--format`

**REQ-IDs:** REQ-010, REQ-014, REQ-018(flaky)

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/format/FlakyFormats.java`
- Modify: `tia-cli/src/main/java/io/tia/cli/FlakyCommand.java`
- Test: `tia-core/src/test/java/io/tia/core/format/FlakyFormatsTest.java` + `FlakyFilterE2ETest`·`FormatE2ETest`(flaky 절반)

**Interfaces:**
- Consumes: `FlakyAnalyzer.aggregate(List<RunResult>)`, `RunResult(Map<String,Boolean> passedByTest)`, `FilterSet.acceptsTest`
- Produces: `FlakyFormats.json/summary/markdown(FlakyReport r, FilterSet filters)` — json은 §4 flaky 스키마(`ratio`·`totalTests`·`flakyTests[]`, **`commit` 없음**) [REQ-014].

- [ ] **Step 1: 실패하는 단위 테스트** — `FlakyFormatsTest`: json 파싱해 스키마 필드 존재+`commit` 부재 확인.
- [ ] **Step 2: FlakyCommand 수정**

```java
    @Mixin ConfigMixin configMixin;
    @Option(names = "--format", defaultValue = "text", ...) Format format;   // ImpactCommand와 동일 enum 별도 선언

    // resolve() → TiaConfigException catch → exit 1
    // 집계 '전' 필터 [REQ-010]:
    List<RunResult> filteredRuns = parsed.stream()
            .map(r -> new RunResult(r.passedByTest().entrySet().stream()
                    .filter(e -> filters.acceptsTest(e.getKey()))
                    .collect(LinkedHashMap::new, (m,e) -> m.put(e.getKey(), e.getValue()), Map::putAll)))
            .toList();
    FlakyReport r = new FlakyAnalyzer().aggregate(filteredRuns);
    if (r.totalTests() == 0 && filters.hasTestFilters())
        System.err.println("WARN: 필터 적용 후 테스트가 0개 — ratio 0.0");   // [REQ-010]
    // format==text → 기존 printf/FLAKY 라인 그대로 [REQ-012]
```

(참고: `FlakyAnalyzer`는 이미 `total==0 → ratio 0.0`이라 0-나눗셈 없음 — 경고만 추가.)
- [ ] **Step 3: green 확인** — `./gradlew :tia-core:test :tia-cli:test :e2e:test --tests 'io.tia.e2e.filter.FlakyFilterE2ETest' --tests 'io.tia.e2e.format.FormatE2ETest'` → flaky 케이스 PASS, 기존 `FlakyCommandTest` 무변경 PASS
- [ ] **Step 4: Commit + 매트릭스 갱신** — `git commit -m "feat(flaky): 집계 전 test 필터 + summary/json/markdown [REQ-010/014/018]"`

---

### Task 8: report 필터 + sut-name 기본값

**REQ-IDs:** REQ-011, REQ-024

**Files:**
- Modify: `tia-core/src/main/java/io/tia/core/report/ReportBuilder.java` (Inputs에 `FilterSet filters` 추가 — null 허용, null이면 무필터)
- Modify: `tia-cli/src/main/java/io/tia/cli/ReportCommand.java`
- Test: `tia-core/src/test/java/io/tia/core/report/ReportBuilderTest.java`(케이스 추가) + `ReportFilterE2ETest`

- [ ] **Step 1: 실패 테스트** — `ReportBuilderTest`에 추가: 제외 testId·제외 prod 파일이 렌더 HTML에 부재. `ReportFilterE2ETest` red 재확인.
- [ ] **Step 2: 구현.** `ReportBuilder.Inputs`에 `FilterSet filters` 필드 추가(기존 생성 호출부는 `FilterSet.none()` 전달로 컴파일 유지). testwise 파싱 직후 `filters.acceptsTest(testId)`로 테스트 행 제거, prod-files 목록에 `filters.acceptsCode(path)` 적용, flaky 목록에도 `acceptsTest` 적용. `ReportCommand`:

```java
    @Mixin ConfigMixin configMixin;
    @Option(names = "--sut-name", description = "리포트 타이틀의 SUT 이름 (기본: tia.yml sut-name, 없으면 SUT)") String sut;  // defaultValue 제거
    // call(): resolve() catch → 1;
    String effectiveSut = (sut != null) ? sut
            : (resolved.config().sutName() != null) ? resolved.config().sutName() : "SUT";   // [REQ-024]
```

- [ ] **Step 3: green 확인** — `./gradlew :tia-core:test :e2e:test --tests 'io.tia.e2e.filter.ReportFilterE2ETest' --tests 'io.tia.e2e.config.ConfigE2ETest'` → REQ-011·REQ-024 PASS
- [ ] **Step 4: Commit + 매트릭스 갱신** — `git commit -m "feat(report): 인프로세스 필터 + tia.yml sut-name 기본값 [REQ-011/024]"`

---

### Task 9: 하위호환 회귀 + 문서·고지 동기화

**REQ-IDs:** REQ-012, REQ-019, REQ-020, REQ-022, REQ-025

**Files:**
- Modify: `GETTING-STARTED.md`(tia.yml 섹션 신설: 스키마·플래그 대체 규칙·글로브 공간 함정·exclude 리스크), `README.md`(CLI 사용법에 `--format`·`--config` 1줄씩), `THIRD-PARTY-NOTICES.md`+`licenses/`(jackson-dataformat-yaml·snakeyaml 추가), `skills/tia/SKILL.md`(`--format json` 언급 1줄)
- Test: 전체 스위트

- [ ] **Step 1: 전체 회귀** — Run: `./gradlew test` (tia-core·tia-cli·e2e 전체) → 전부 PASS. 특히 `SpecAcceptanceE2ETest` **무변경** PASS [REQ-012/019].
- [ ] **Step 2: 수집 경로 E2E** — Run: `bash scripts/setup-pjacoco.sh && bash scripts/run-inprocess-e2e.sh` → `✅ E2E PASS` 확인 [REQ-019]. (Docker 가능 환경이면 컨테이너 E2E도. 불가하면 사유를 결과 보고에 명시 — 침묵 스킵 금지.)
- [ ] **Step 3: 문서 갱신** — GETTING-STARTED에 "## tia.yml 설정" 섹션: 스키마 예시(§2와 동일), 3항목 필수 기재 ① 플래그는 목록 단위 **대체**(병합 아님) ② code 글로브는 `src/main/java/` 접두어 없는 package-relative 공간 매칭(함정 명시) ③ exclude는 "TIA 범위 밖 선언" — 제외 경로 회귀는 TIA가 못 잡음 [REQ-025]. THIRD-PARTY-NOTICES.md에 두 라이브러리(Apache-2.0) 추가, `licenses/`에 라이선스 텍스트, SBOM은 릴리스 워크플로가 runtimeClasspath로 자동 반영됨을 확인 [REQ-020].
- [ ] **Step 4: 매트릭스 최종 대조** — 요구사항명세 추적 매트릭스 25행 전부 🟢인지, 각 🟢가 실제 통과 테스트명과 대응하는지 테스트 리포트로 대조. REQ-020·REQ-025는 이 태스크의 문서 diff를 근거로 🟢 처리.
- [ ] **Step 5: Commit** — `git commit -m "docs: tia.yml 가이드·NOTICES·매트릭스 100% green [REQ-012/019/020/025]"`

---

## 완료 정의 (Definition of Done)

1. 추적 매트릭스 25/25 green (Must 24 + Should 1) — 각 REQ가 실제 통과 테스트와 대응.
2. 기존 스위트(`./gradlew test`, `scripts/run-inprocess-e2e.sh`, 가능 시 컨테이너 E2E) 무변경 green.
3. PR 게이트: spec-compliance 리뷰 → code-quality 리뷰(`pr-review-toolkit:code-reviewer`) 순으로 실행, 소견 전건 triage. 문서 동기화 커밋 포함.

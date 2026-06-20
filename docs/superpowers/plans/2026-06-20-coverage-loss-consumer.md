# Coverage-Loss Consumer (phase 2, TIA) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** TIA가 phase 1의 in-process 손실 신호(sidecar `incompleteAttribution`/`droppedProbes`)를 소비해 거짓 baseline 생성을 차단하고, 사용자/에이전트가 스레드 토폴로지 축으로 올바른 수집 모델을 고르게 안내한다.

**Architecture:** `tia convert`가 sidecar를 1회 읽어(`sidecarOf` DTO) 손실 플래그를 testwise.json에 전파(@JsonInclude NON_NULL)하고, 두 독립 게이트(incomplete 기본 ON / empty opt-in)로 손실 시 exit 1. 문서는 토폴로지 결정 트리 + fail-fast 레시피를 추가하고 콘텐츠 테스트로 고정.

**Tech Stack:** Java 17, Jackson 2.17, picocli, JUnit 5, Gradle 멀티모듈(tia-core, tia-cli).

## Global Constraints
- 출처: design `docs/superpowers/specs/2026-06-20-coverage-loss-consumer-design.md`, 요구사항 `docs/superpowers/requirements/2026-06-20-coverage-loss-consumer-requirements.md`.
- REQ-ID `CLC-REQ-00X`; 수용 테스트 `@DisplayName("CLC-REQ-00X: …")`.
- 손실 정의: per-test가 `incompleteAttribution==true` OR `droppedProbes>0`. "0커버" = `Test.paths().isEmpty()`.
- 게이트: incomplete 기본 ON(opt-out `--allow-incomplete`), empty 기본 OFF(opt-in `--fail-on-empty`); 독립. 실패 exit **1**(picocli 2/ImpactCommand 3 회피). 실패해도 testwise.json은 작성.
- 후방호환: 손실 0 testwise.json은 기존과 byte 동일(@JsonInclude NON_NULL); `Number.longValue()`로 숫자 읽기; `droppedProbes` 0/≤0 → null 정규화; 기존 `ExecWriter`/CLI 호출 무변.
- malformed/누락 sidecar = no-loss(파싱 실패 swallow).
- 커밋 메시지 말미:
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01LXH8qJRxzMv4Jhewi4tyks
  ```

---

### Task 1: sidecar DTO + Testwise.Test 손실 필드 + 전파(NON_NULL)

**REQ-IDs:** CLC-REQ-004 (+ CLC-REQ-001 손실 판정 헬퍼 기반)

**Files:**
- Modify: `tia-core/src/main/java/io/tia/core/convert/Testwise.java`
- Modify: `tia-core/src/main/java/io/tia/core/convert/TestwiseConverter.java`
- Test: `tia-core/src/test/java/io/tia/core/convert/TestwiseConverterLossTest.java` (Create)

**Interfaces:**
- Produces: `Testwise.Test(uniformPath, result, paths, Boolean incompleteAttribution, Long droppedProbes)` with `@JsonInclude(NON_NULL)`; `TestwiseConverter.Sidecar` record + `sidecarOf(Path)`; static `boolean TestwiseConverter.isLoss(Testwise.Test)`.

- [ ] **Step 1: 실패 테스트 — TestwiseConverterLossTest** (CLC-REQ-004 + 판정):

```java
package io.tia.core.convert;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestwiseConverterLossTest {
    private final ObjectMapper M = new ObjectMapper();

    private void exec(Path dir, String id) throws Exception {
        // minimal valid .exec via the converter's own writer is overkill; write empty file is enough for paths analysis (0 covered)
        Files.write(dir.resolve(id + ".exec"), new byte[0]);
    }
    private void sidecar(Path dir, String id, String json) throws Exception {
        Files.writeString(dir.resolve(id + ".json"), json);
    }

    @Test @DisplayName("CLC-REQ-004: incompleteAttribution+droppedProbes propagate to testwise")
    void propagatesFlags(@TempDir Path dir) throws Exception {
        exec(dir, "T1");
        sidecar(dir, "T1", "{\"result\":\"passed\",\"incompleteAttribution\":true,\"droppedProbes\":3}");
        var doc = new TestwiseConverter().convert(dir, dir /*no classes -> 0 covered, fine*/);
        var t = doc.tests().get(0);
        assertEquals(Boolean.TRUE, t.incompleteAttribution());
        assertEquals(3L, t.droppedProbes());
    }

    @Test @DisplayName("CLC-REQ-004: droppedProbes-only propagates, incompleteAttribution absent")
    void propagatesDroppedProbesOnly(@TempDir Path dir) throws Exception {
        exec(dir, "T2");
        sidecar(dir, "T2", "{\"result\":\"passed\",\"droppedProbes\":5}");
        var t = new TestwiseConverter().convert(dir, dir).tests().get(0);
        assertNull(t.incompleteAttribution());
        assertEquals(5L, t.droppedProbes());
    }

    @Test @DisplayName("CLC-REQ-004: no loss -> fields omitted from JSON (NON_NULL)")
    void omitsWhenNoLoss(@TempDir Path dir) throws Exception {
        exec(dir, "T3");
        sidecar(dir, "T3", "{\"result\":\"passed\",\"droppedProbes\":0}");
        var conv = new TestwiseConverter();
        var doc = conv.convert(dir, dir);
        Path out = dir.resolve("tw.json");
        conv.write(doc, out);
        String json = Files.readString(out);
        assertFalse(json.contains("incompleteAttribution"), json);
        assertFalse(json.contains("droppedProbes"), json);
    }

    @Test @DisplayName("CLC-REQ-001: isLoss true on flag or droppedProbes>0, false otherwise")
    void detectsLoss(@TempDir Path dir) throws Exception {
        exec(dir, "L"); sidecar(dir, "L", "{\"incompleteAttribution\":true}");
        exec(dir, "D"); sidecar(dir, "D", "{\"droppedProbes\":2}");
        exec(dir, "OK"); sidecar(dir, "OK", "{\"result\":\"passed\"}");
        var doc = new TestwiseConverter().convert(dir, dir);
        java.util.Map<String,Boolean> loss = new java.util.HashMap<>();
        for (var t : doc.tests()) loss.put(t.uniformPath(), TestwiseConverter.isLoss(t));
        assertTrue(loss.get("L")); assertTrue(loss.get("D")); assertFalse(loss.get("OK"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :tia-core:test --tests 'io.tia.core.convert.TestwiseConverterLossTest' -i`
Expected: 컴파일 실패(record 5-arg·`incompleteAttribution()`·`isLoss`·`Sidecar` 없음).

- [ ] **Step 3: Testwise.Test 필드 + NON_NULL** — `Testwise.java`:

import 추가:
```java
import com.fasterxml.jackson.annotation.JsonInclude;
```
record 교체:
```java
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Test(String uniformPath, String result, List<PathCov> paths,
                       Boolean incompleteAttribution, Long droppedProbes) {}
```

- [ ] **Step 4: TestwiseConverter — Sidecar DTO·sidecarOf·isLoss·전파** — `TestwiseConverter.java`:

`resultFor`를 다음으로 대체(이름 `sidecarOf`, 1회 읽기):
```java
    /** Parsed companion {@code <testId>.json}: pass/fail result + phase-1 loss signals. */
    public record Sidecar(String result, boolean incompleteAttribution, Long droppedProbes) {}

    private Sidecar sidecarOf(Path execFile) {
        String s = execFile.toString();
        Path companion = Path.of(s.substring(0, s.length() - ".exec".length()) + ".json");
        String result = "UNKNOWN";
        boolean incomplete = false;
        Long dropped = null;
        if (Files.exists(companion)) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String,Object> m = mapper.readValue(companion.toFile(), java.util.Map.class);
                Object r = m.get("result");
                if (r != null) result = r.toString().toUpperCase();
                incomplete = Boolean.TRUE.equals(m.get("incompleteAttribution"));
                Object d = m.get("droppedProbes");
                if (d instanceof Number n && n.longValue() > 0) dropped = n.longValue();   // 0/≤0 -> null
            } catch (IOException ignored) { /* malformed -> no-loss */ }
        }
        return new Sidecar(result, incomplete, dropped);
    }

    /** A test is "lost" if the producer flagged incomplete attribution or any dropped probes. */
    public static boolean isLoss(Testwise.Test t) {
        return Boolean.TRUE.equals(t.incompleteAttribution())
                || (t.droppedProbes() != null && t.droppedProbes() > 0);
    }
```
`convert`의 Test 생성부 교체(기존 `new Testwise.Test(testId, resultFor(exec), analyze(exec, classesDir))`):
```java
                Sidecar sc = sidecarOf(exec);
                tests.add(new Testwise.Test(testId, sc.result(), analyze(exec, classesDir),
                        sc.incompleteAttribution() ? Boolean.TRUE : null, sc.droppedProbes()));
```
(기존 `resultFor` 메서드는 제거.)

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :tia-core:test --tests 'io.tia.core.convert.TestwiseConverterLossTest'`
Expected: PASS (4 케이스).

- [ ] **Step 6: tia-core 기존 회귀**

Run: `./gradlew :tia-core:test`
Expected: PASS (기존 `TestwiseConverterTest` 포함 — 손실 없는 fixture라 신규 필드 없음·byte 동일).

- [ ] **Step 7: 커밋**

```bash
git add tia-core/src/main/java/io/tia/core/convert/Testwise.java tia-core/src/main/java/io/tia/core/convert/TestwiseConverter.java tia-core/src/test/java/io/tia/core/convert/TestwiseConverterLossTest.java
git commit -m "feat(convert): read+propagate loss signals to testwise (NON_NULL), isLoss helper [CLC-REQ-004/001]"
```

---

### Task 2: ConvertCommand 두 독립 게이트

**REQ-IDs:** CLC-REQ-001, CLC-REQ-002, CLC-REQ-003

**Files:**
- Modify: `tia-cli/src/main/java/io/tia/cli/ConvertCommand.java`
- Test: `tia-cli/src/test/java/io/tia/cli/ConvertGateTest.java` (Create)

**Interfaces:**
- Consumes: `TestwiseConverter.isLoss(Test)`, `Test.paths()` (Task 1).
- Produces: `tia convert` exit 1 on gate fire; options `--allow-incomplete`, `--fail-on-empty`.

- [ ] **Step 1: 실패 테스트 — ConvertGateTest** (CLI black-box, picocli `execute`):

```java
package io.tia.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ConvertGateTest {
    private static void exec(Path d, String id) throws Exception { Files.write(d.resolve(id+".exec"), new byte[0]); }
    private static void side(Path d, String id, String j) throws Exception { Files.writeString(d.resolve(id+".json"), j); }
    private static int run(String... args) { return new CommandLine(new ConvertCommand()).execute(args); }

    @Test @DisplayName("CLC-REQ-001: incompleteAttribution sidecar fails convert by default")
    void incompleteSidecar_failsByDefault(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"incompleteAttribution\":true}");
        int code = run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString());
        assertEquals(1, code);
        assertTrue(Files.exists(d.resolve("o.json")));   // testwise still written
    }

    @Test @DisplayName("CLC-REQ-001: droppedProbes-only sidecar fails by default")
    void droppedProbesOnly_failsByDefault(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"droppedProbes\":4}");
        assertEquals(1, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString()));
    }

    @Test @DisplayName("CLC-REQ-001: no loss -> exit 0")
    void noLoss_exit0(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"result\":\"passed\"}");
        assertEquals(0, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString()));
    }

    @Test @DisplayName("CLC-REQ-002: --allow-incomplete downgrades to warn, exit 0")
    void allowIncomplete_warnsExit0(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"incompleteAttribution\":true}");
        assertEquals(0, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString(), "--allow-incomplete"));
    }

    @Test @DisplayName("CLC-REQ-003: empty coverage passes by default")
    void empty_passesByDefault(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"result\":\"passed\"}");   // 0 covered (no classes), no loss flag
        assertEquals(0, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString()));
    }

    @Test @DisplayName("CLC-REQ-003: --fail-on-empty fails on 0-covered")
    void failOnEmpty_fails(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"result\":\"passed\"}");
        assertEquals(1, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString(), "--fail-on-empty"));
    }

    @Test @DisplayName("CLC-REQ-003: --allow-incomplete does not silence --fail-on-empty")
    void allowIncompleteWithFailOnEmpty_emptyStillFires(@TempDir Path d) throws Exception {
        exec(d,"T"); side(d,"T","{\"incompleteAttribution\":true}");   // also 0-covered
        assertEquals(1, run("--exec-dir", d.toString(), "--classes", d.toString(), "--out", d.resolve("o.json").toString(), "--allow-incomplete", "--fail-on-empty"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :tia-cli:test --tests 'io.tia.cli.ConvertGateTest' -i`
Expected: 실패(현재 convert는 항상 0, 옵션 없음).

- [ ] **Step 3: 구현 — ConvertCommand** — 전체 교체:

```java
package io.tia.cli;

import io.tia.core.convert.Testwise;
import io.tia.core.convert.TestwiseConverter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "convert",
        description = "per-test .exec 디렉터리 → testwise JSON (jacoco core, subprocess 없음)")
public class ConvertCommand implements Callable<Integer> {
    @Option(names = "--exec-dir", required = true, description = "<testId>.exec 들이 있는 디렉터리") Path execDir;
    @Option(names = "--classes", required = true, description = "SUT 컴파일 classfile 디렉터리") Path classes;
    @Option(names = "--out", required = true, description = "출력 testwise.json 경로") Path out;
    @Option(names = "--allow-incomplete",
            description = "in-process 손실(incompleteAttribution/droppedProbes) 시 실패 대신 경고만") boolean allowIncomplete;
    @Option(names = "--fail-on-empty",
            description = "0커버(paths 비어있음) 테스트가 있으면 실패(기본 off)") boolean failOnEmpty;

    @Override public Integer call() throws Exception {
        TestwiseConverter converter = new TestwiseConverter();
        Testwise.Document doc = converter.convert(execDir, classes);
        converter.write(doc, out);
        int rows = doc.tests().stream().flatMap(t -> t.paths().stream()).mapToInt(p -> p.files().size()).sum();
        System.out.println("wrote " + out + ": " + doc.tests().size() + " tests, " + rows + " (test,file) rows");

        boolean fail = false;
        // incomplete gate (default ON; --allow-incomplete -> warn)
        List<String> lost = doc.tests().stream().filter(TestwiseConverter::isLoss)
                .map(Testwise.Test::uniformPath).collect(Collectors.toList());
        if (!lost.isEmpty()) {
            String head = (allowIncomplete ? "[WARN] " : "[ERROR] ") + lost.size()
                    + " test(s) have incomplete in-process attribution (worker-thread coverage lost):";
            System.err.println(head);
            lost.forEach(id -> System.err.println("  incomplete  " + id));
            if (!allowIncomplete) {
                System.err.println("  → use the out-of-process baggage model for HTTP black-box tests, or --allow-incomplete to override.");
                fail = true;
            }
        }
        // empty gate (default OFF; --fail-on-empty -> fail). Independent of --allow-incomplete.
        if (failOnEmpty) {
            List<String> empty = doc.tests().stream().filter(t -> t.paths().isEmpty())
                    .map(Testwise.Test::uniformPath).collect(Collectors.toList());
            if (!empty.isEmpty()) {
                System.err.println("[ERROR] " + empty.size() + " test(s) covered 0 production files:");
                empty.forEach(id -> System.err.println("  empty       " + id));
                fail = true;
            }
        }
        return fail ? 1 : 0;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :tia-cli:test --tests 'io.tia.cli.ConvertGateTest'`
Expected: PASS (7 케이스).

- [ ] **Step 5: tia-cli 기존 회귀**

Run: `./gradlew :tia-cli:test`
Expected: PASS (기존 `D0AcceptanceTest` 포함 — fixture에 손실 sidecar 없음·`--fail-on-empty` 미사용이라 exit 0 유지).

- [ ] **Step 6: 커밋**

```bash
git add tia-cli/src/main/java/io/tia/cli/ConvertCommand.java tia-cli/src/test/java/io/tia/cli/ConvertGateTest.java
git commit -m "feat(cli): convert incomplete gate (default) + empty gate (opt-in), exit 1 [CLC-REQ-001/002/003]"
```

---

### Task 3: index 파서 후방호환 테스트

**REQ-IDs:** CLC-REQ-005

**Files:**
- Test: `tia-core/src/test/java/io/tia/core/parse/TestwiseReportParserCompatTest.java` (Create)

**Interfaces:** Consumes: `TestwiseReportParser.parse(InputStream)` (기존, 변경 없음 — JsonNode 트리라 미지 필드 무시).

- [ ] **Step 1: 검증 테스트 작성**

```java
package io.tia.core.parse;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import io.tia.core.model.TestCoverage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestwiseReportParserCompatTest {
    @Test @DisplayName("CLC-REQ-005: parser ignores new loss fields, parses existing fields")
    void ignoresNewFields() {
        String json = "{\"tests\":[{\"uniformPath\":\"T1\",\"result\":\"passed\","
                + "\"incompleteAttribution\":true,\"droppedProbes\":3,"
                + "\"paths\":[{\"path\":\"io/x\",\"files\":[{\"fileName\":\"A.java\",\"coveredLines\":\"1-3\"}]}]}]}";
        List<TestCoverage> out = new TestwiseReportParser()
                .parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, out.size());
        assertEquals("T1", out.get(0).testId());
        assertEquals("passed", out.get(0).result());
    }
}
```
(주: `TestCoverage`의 접근자 이름이 `testId()`/`result()`가 아니면 실제 이름으로 맞춘다 — 구현자 확인.)

- [ ] **Step 2: 통과 확인**

Run: `./gradlew :tia-core:test --tests 'io.tia.core.parse.TestwiseReportParserCompatTest'`
Expected: PASS (코드 변경 없이 — JsonNode 트리가 미지 필드 무시).

- [ ] **Step 3: 커밋**

```bash
git add tia-core/src/test/java/io/tia/core/parse/TestwiseReportParserCompatTest.java
git commit -m "test(parse): lock TestwiseReportParser backward-compat with loss fields [CLC-REQ-005]"
```

---

### Task 4: 문서 — 토폴로지 결정 체크리스트 + fail-fast 레시피 + README, 콘텐츠 테스트

**REQ-IDs:** CLC-REQ-006

**Files:**
- Modify: `GETTING-STARTED.md` (§1 분류 축 재정의 + 결정 트리 + ③-b 레시피)
- Modify: `skills/tia/SKILL.md` (Scope에 결정 체크리스트 포인터/요지)
- Modify: `README.md` (convert 플래그 한 줄)
- Test: `tia-cli/src/test/java/io/tia/cli/DecisionChecklistDocTest.java` (Create)

**Interfaces:** none (docs + content test).

- [ ] **Step 1: 실패 테스트 — DecisionChecklistDocTest** (repo-root 경로해석):

```java
package io.tia.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DecisionChecklistDocTest {
    /** Resolve repo root by walking up until settings.gradle is found (Gradle runs tests with the subproject CWD). */
    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle")) && !Files.exists(p.resolve("settings.gradle.kts"))) {
            p = p.getParent();
        }
        assertNotNull(p, "repo root (settings.gradle) not found");
        return p;
    }
    private static String read(String rel) throws Exception { return Files.readString(repoRoot().resolve(rel)); }

    @Test @DisplayName("CLC-REQ-006: docs carry the thread-topology decision tree and gate flags")
    void docsHaveTopologyTreeAndFlags() throws Exception {
        String gs = read("GETTING-STARTED.md");
        assertTrue(gs.contains("RANDOM_PORT"), "GETTING-STARTED must mention RANDOM_PORT");
        assertTrue(gs.contains("MockMvc"), "GETTING-STARTED must mention MockMvc");
        assertTrue(gs.contains("tia.inprocess.failOnWebServer"), "GETTING-STARTED must document the fail-fast property");
        String skill = read("skills/tia/SKILL.md");
        assertTrue(skill.contains("RANDOM_PORT") || skill.contains("토폴로지") || skill.contains("topology"),
                "SKILL.md must point to the topology decision checklist");
        String readme = read("README.md");
        assertTrue(readme.contains("--allow-incomplete"), "README must document --allow-incomplete");
        assertTrue(readme.contains("--fail-on-empty"), "README must document --fail-on-empty");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :tia-cli:test --tests 'io.tia.cli.DecisionChecklistDocTest' -i`
Expected: FAIL(문구 부재).

- [ ] **Step 3: GETTING-STARTED.md — §1에 토폴로지 결정 트리 추가**

`## 1. per-test 커버리지 수집` 섹션 안, in-process/out-of-process 설명 바로 뒤에 추가:

```markdown
> **수집 모델 결정(스레드 토폴로지 축).** "같은 JVM이냐"가 아니라 **"프로덕션 코드가 어느 스레드에서
> 실행되나"**로 고른다:
>
> | 테스트가 프로덕션 코드를 실행하는 방식 | 실행 스레드 | 수집 모델 |
> |---|---|---|
> | 직접 호출 / MockMvc / `@WebMvcTest` / `@SpringBootTest(webEnvironment=MOCK)` / WebTestClient(bindToApplicationContext) | 테스트 스레드 | **in-process** OK |
> | `@SpringBootTest(RANDOM_PORT\|DEFINED_PORT)` + RestAssured/TestRestTemplate/WebTestClient(bindToServer) · WebSocket/STOMP · `@Async` | 워커/다른 스레드 | **out-of-process baggage 필수** |
>
> in-process로 HTTP 블랙박스(RANDOM_PORT)를 수집하면 프로덕션 코드가 Tomcat 워커 스레드에서 돌아
> 커버리지가 **침묵 손실**된다. 이 경우 pjacoco 에이전트가 WARN/카운터를 내고, sidecar에
> `incompleteAttribution`이 찍히며, `tia convert`가 기본으로 **exit 1**(`--allow-incomplete`로 우회).
>
> **수집 fail-fast 레시피(소비자 하니스).** in-process 수집 중 SUT가 임베디드 웹서버를 띄우면 토폴로지
> 미스매치다. 작은 리스너로 즉시 실패시킨다(opt-out `tia.inprocess.failOnWebServer=false`):
> ```java
> // src/test, testRuntimeOnly 로 등록
> @org.springframework.context.event.EventListener
> void onWebServer(org.springframework.boot.web.context.WebServerInitializedEvent e) {
>     if (Boolean.parseBoolean(System.getProperty("tia.inprocess.failOnWebServer", "true")))
>         throw new IllegalStateException("Embedded web server booted during in-process collection — "
>             + "use the out-of-process baggage model for this module.");
> }
> ```
> (TIA의 `scripts/run-inprocess-e2e.sh`는 서버를 띄우지 않는 올바른 토폴로지라 이 가드 대상이 아니다.)
```

- [ ] **Step 4: skills/tia/SKILL.md — Scope에 포인터 추가**

SKILL.md의 Scope(또는 "does not collect coverage" 안내) 부근에 한 줄:

```markdown
> **수집 모델 선택**: 인덱스를 만들 때 in/out-of-process는 "프로세스"가 아니라 **스레드 토폴로지**로
> 고른다 — 프로덕션 코드가 테스트 스레드에서 돌면 in-process, 워커 스레드(`@SpringBootTest(RANDOM_PORT)`
> +RestAssured/TestRestTemplate, WebSocket, `@Async`)면 out-of-process baggage. 잘못 고르면 커버리지가
> 침묵 손실되고 `tia convert`가 막는다. 결정 트리: GETTING-STARTED.md §1.
```

- [ ] **Step 5: README.md — convert 플래그 문서화**

README의 convert 사용 예시(또는 산출물 설명) 부근에 한 줄:

```markdown
> `tia convert`는 in-process 손실 신호(sidecar `incompleteAttribution`/`droppedProbes`)가 있으면 기본
> `exit 1`로 막는다(`--allow-incomplete`로 경고만). 0커버 테스트도 막으려면 `--fail-on-empty`(기본 off).
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :tia-cli:test --tests 'io.tia.cli.DecisionChecklistDocTest'`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add GETTING-STARTED.md skills/tia/SKILL.md README.md tia-cli/src/test/java/io/tia/cli/DecisionChecklistDocTest.java
git commit -m "docs: thread-topology decision checklist + fail-fast recipe + convert gate flags [CLC-REQ-006]"
```

---

### Task 5: 전체 회귀 + 매트릭스 갱신

**REQ-IDs:** CLC-REQ-001..006 (검증)

**Files:** Modify: `docs/superpowers/requirements/2026-06-20-coverage-loss-consumer-requirements.md`

- [ ] **Step 1: 전체 빌드/테스트**

Run: `./gradlew :tia-core:test :tia-cli:test`
Expected: PASS — 신규 4 테스트 클래스 + 기존 회귀(TestwiseConverterTest, D0AcceptanceTest 등) green.

- [ ] **Step 2: 매트릭스 100% green** — 요구사항명세 Status를 실제 통과와 대조해 🟢, Coverage `6/6 green (100%)`.

- [ ] **Step 3: 커밋**

```bash
git add docs/superpowers/requirements/2026-06-20-coverage-loss-consumer-requirements.md
git commit -m "docs(req): coverage-loss-consumer 매트릭스 6/6 green [CLC-REQ-001..006]"
```

---

## Self-Review
**1. Spec coverage:** CLC-REQ-001~006 전부 task 매핑 — 001→T1(헬퍼)+T2(게이트), 002→T2, 003→T2, 004→T1, 005→T3, 006→T4; 회귀→T5. design §4(③-c-1/③-c-2/③-a/③-b)·§6(README 포함) 반영. ✅
**2. Placeholder scan:** 모든 step 실제 코드/명령/기대. `TestCoverage` 접근자명만 구현자 확인 주석(Step T3-1). ✅
**3. Type consistency:** `Sidecar(result, boolean incompleteAttribution, Long droppedProbes)`·`isLoss(Test)`·`Test(…, Boolean, Long)`(T1) ↔ `ConvertCommand`(T2) ↔ 테스트(T1/T2) 일치. exit 1 일관. ✅

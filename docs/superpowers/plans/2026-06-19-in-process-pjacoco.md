# in-process pjacoco 전환 + teamscale 은퇴 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 출처: design spec `docs/superpowers/specs/2026-06-19-in-process-pjacoco-design.md`,
> 요구사항명세 `docs/superpowers/requirements/2026-06-19-in-process-pjacoco-requirements.md`

**Goal:** in-JVM per-test 커버리지 수집을 pjacoco `PjacocoInProcessExtension`로 제공하고 teamscale를 완전히 은퇴시킨다(컨테이너 E2E는 pjacoco로 이전).

**Architecture:** 테스터 JVM(=SUT)에 pjacoco 에이전트를 `-javaagent`로 부착하고, `@ExtendWith(PjacocoInProcessExtension)`로 감싼 in-JVM 테스트가 fixture-app 서비스를 직접 호출 → 테스트별 `.exec` → `tia convert` → 직렬과 per-test 비교. 동시성은 공유파일 오버랩 프로브로 in-JVM 모드를 하드 게이트.

**Tech Stack:** Java 17, JUnit 5, pjacoco(agent + testkit-junit5, mavenLocal 소스빌드), tia-cli/tia-core, Gradle, fixture-app(Spring Boot, 서비스만 in-JVM 사용).

## Global Constraints
(모든 task에 암묵 포함 — spec/요구사항명세에서 인용)
- in-process 에이전트는 **테스터 JVM에 `-javaagent`** 부착: `-javaagent:<repo>/tools/pjacoco/jacocoagent-parallel.jar=destfile=<cov-mode-dir>,aggregate=false,includes=io.tia.fixture.*,port=0`. (테스터 JVM이 곧 SUT. agent jar은 `setup-pjacoco.sh` 산출물. 없으면 CoverageControl no-op → REQ-004 non-empty가 방어.)
- 확장은 **명시적 `@ExtendWith(PjacocoInProcessExtension.class)`** (자동등록 금지 — 충돌 회피). testId = pjacoco **FQN#method**.
- e2e는 **`testImplementation project(':fixture-app')`**; 서비스는 `new`로 직접 인스턴스화(Spring 부팅 금지).
- in-JVM 테스터 ≥8 결정적 케이스를 **≥2 클래스로 분할**(forks 병렬 실현). `/flaky` 등 비결정 없음.
- 모드별 격리 exec 디렉터리(cov-serial/forks/injvm), 직렬/병렬 비교는 동일 `--classes`(fixture-app main classes).
- 비교기 `TestwiseNormalizer`(지난 phase) 재사용 → `Map<testId, Map<fileKey, RoaringBitmap>>` equals.
- 오버랩 프로브: 원자적 줄 기록(`Files.write` CREATE+APPEND + `synchronized`). **in-JVM 겹침≥1쌍=하드 게이트, 직렬=0; forks 겹침은 기록만**(CI 코어 의존 플랩 방지).
- teamscale 완전 제거; grep 게이트 범위 = 활성 산출물(`*.java`/`*.gradle`/`*.yml`/`*.sh`, README/GETTING-STARTED/THIRD-PARTY-NOTICES/plugin README), `docs/superpowers/` 제외.
- pjacoco 미게시 → `setup-pjacoco.sh`로 mavenLocal 해소; CI 해소 실패는 **fail**(skip 아님).
- 각 수용 테스트는 `@DisplayName`에 REQ-ID 참조.

---

## File Structure
- `e2e/build.gradle` (Modify) — fixture-app 의존, `inProcessTesterTest`/`inProcessE2ETest` 태스크(에이전트 jvmArg/모드), pitest 제외 갱신.
- `e2e/src/test/java/io/tia/e2e/parallel/TestwiseNormalizer.java` (Modify) — package-private → `public`(in-process 패키지에서 재사용).
- `e2e/src/test/java/io/tia/e2e/inprocess/OverlapProbe.java` (Create) — 공유파일 원자 기록 + around 헬퍼.
- `e2e/src/test/java/io/tia/e2e/inprocess/OverlapProbeAnalyzer.java` (Create) + `OverlapProbeAnalyzerTest.java` — 겹침 쌍 분석.
- `e2e/src/test/java/io/tia/e2e/inprocess/InProcessTesterBase.java` (Create) — testId 계산 + 프로브 래핑.
- `e2e/src/test/java/io/tia/e2e/inprocess/GreetingInProcessIT.java`, `PriceInProcessIT.java` (Create) — ≥8 in-JVM 케이스.
- `e2e/src/test/java/io/tia/e2e/inprocess/InProcessCollectionE2E.java` (Create) — 수용 비교(REQ-001~005).
- `scripts/run-inprocess-e2e.sh` (Create) — 오케스트레이터.
- 제거/마이그레이션: `tia-junit-extension/`(모듈), `settings.gradle`, `coverage/build.gradle`, `fixture-app/build.gradle`, `fixture-app/.../ApiSmokeTest.java`+`RunResultWriter.java`, `tia-gradle-plugin/`(attachTeamscaleAgent/TiaArgs/README/test), `scripts/download-agent.sh`·`run-poc.sh`·`measure-flaky.sh`, `docker-compose.e2e.yml`, `scripts/docker-e2e-tester.sh`, `.github/workflows/ci.yml`, `README.md`/`GETTING-STARTED.md`/`THIRD-PARTY-NOTICES.md`, `TestwiseConverter.java`/`LineRangeParser.java`(주석).

권장 실행 순서: 1 → 2 → 3 → 4(여기서 REQ-001~005 green) → 5 → 6 → 7.

---

## Task 1: e2e fixture-app 의존 + in-JVM 테스터 + 오버랩 프로브
**REQ-IDs:** REQ-001, REQ-005

**Files:**
- Modify: `e2e/build.gradle`
- Modify: `e2e/src/test/java/io/tia/e2e/parallel/TestwiseNormalizer.java` (public 화)
- Create: `e2e/src/test/java/io/tia/e2e/inprocess/OverlapProbe.java`
- Create: `e2e/src/test/java/io/tia/e2e/inprocess/InProcessTesterBase.java`
- Create: `e2e/src/test/java/io/tia/e2e/inprocess/GreetingInProcessIT.java`
- Create: `e2e/src/test/java/io/tia/e2e/inprocess/PriceInProcessIT.java`

**Interfaces:**
- Produces: `@Tag("inprocess-tester")` 케이스들(testId=FQN#method)이 pjacoco 에이전트 하에서 per-test `.exec` 생성; `OverlapProbe.record/around`. Gradle `:e2e:inProcessTesterTest`(-Pinprocess.mode, -Dtia.inprocess.covDir, -Dtia.inprocess.overlapFile, -Dtia.inprocess.agentJar).

- [ ] **Step 1: e2e/build.gradle 수정**

`dependencies`에 추가:
```gradle
    testImplementation project(':fixture-app')
```
`TestwiseNormalizer` 가시성 변경(Step 2). 그리고 in-process 전용 태스크 등록(파일 끝, pitest 블록 앞):
```gradle
// in-process per-test 테스터 — 테스터 JVM(=SUT)에 pjacoco 에이전트를 -javaagent로 부착.
tasks.register('inProcessTesterTest', Test) {
    useJUnitPlatform { includeTags 'inprocess-tester' }
    def repo = rootProject.projectDir
    def agentJar = (project.findProperty('inprocess.agentJar') ?: "${repo}/tools/pjacoco/jacocoagent-parallel.jar").toString()
    def covDir = (project.findProperty('inprocess.covDir') ?: "${repo}/build/inprocess-e2e/cov-serial").toString()
    jvmArgs "-javaagent:${agentJar}=destfile=${covDir},aggregate=false,includes=io.tia.fixture.*,port=0"
    systemProperties System.getProperties().findAll { it.key.toString().startsWith('tia.inprocess.') }
    def mode = (project.findProperty('inprocess.mode') ?: 'serial').toString()
    if (mode == 'forks') {
        maxParallelForks = 4
    } else if (mode == 'injvm') {
        maxParallelForks = 1
        systemProperty 'junit.jupiter.execution.parallel.enabled', 'true'
        systemProperty 'junit.jupiter.execution.parallel.mode.default', 'concurrent'
        systemProperty 'junit.jupiter.execution.parallel.config.strategy', 'fixed'
        systemProperty 'junit.jupiter.execution.parallel.config.fixed.parallelism', '4'
    } else {
        maxParallelForks = 1
    }
    outputs.upToDateWhen { false }
}
```
그리고 일반 `test` 제외 태그에 `inprocess-tester`,`inprocess-e2e` 추가, pitest `excludedTestClasses`에 `io.tia.e2e.inprocess.*` 추가:
```gradle
tasks.named('test') { useJUnitPlatform { excludeTags 'parallel-tester', 'parallel-e2e', 'inprocess-tester', 'inprocess-e2e' } }
```
pitest 블록의 `excludedTestClasses = ['io.tia.e2e.parallel.*']` → `['io.tia.e2e.parallel.*', 'io.tia.e2e.inprocess.*']`.

- [ ] **Step 2: TestwiseNormalizer public 화**

`e2e/src/test/java/io/tia/e2e/parallel/TestwiseNormalizer.java`: `final class TestwiseNormalizer` → `public final class TestwiseNormalizer`, `static ... normalize` → `public static ... normalize`. (in-process 패키지에서 재사용.)

- [ ] **Step 3: OverlapProbe 작성**

```java
package io.tia.e2e.inprocess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static java.nio.charset.StandardCharsets.UTF_8;

/** 공유 파일에 testId의 START/END(epochMillis)를 원자적으로 기록 — in-process 동시성 실증용.
 *  경로는 시스템 프로퍼티 tia.inprocess.overlapFile; 미설정 시 no-op. POSIX(O_APPEND) 원자 + in-JVM은 synchronized. */
final class OverlapProbe {
    private static final Object LOCK = new Object();
    private OverlapProbe() {}

    interface Body { void run() throws Throwable; }

    static void record(String testId, String phase) {
        String path = System.getProperty("tia.inprocess.overlapFile");
        if (path == null || path.isBlank()) return;
        String line = testId + "," + phase + "," + System.currentTimeMillis() + "\n";
        try {
            synchronized (LOCK) {
                Files.write(Path.of(path), line.getBytes(UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) { /* best-effort */ }
    }

    /** START 기록 → 지연(동시성 창 확보) → 본문 → END 기록(finally). */
    static void around(String testId, long sleepMs, Body body) throws Throwable {
        record(testId, "START");
        try {
            Thread.sleep(sleepMs);
            body.run();
        } finally {
            record(testId, "END");
        }
    }
}
```

- [ ] **Step 4: InProcessTesterBase 작성**

```java
package io.tia.e2e.inprocess;

import io.pjacoco.testkit.junit5.PjacocoInProcessExtension;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/** in-JVM per-test 테스터 베이스: PjacocoInProcessExtension로 각 테스트를 감싸고,
 *  본문을 OverlapProbe로 래핑한다. testId는 pjacoco와 동일한 FQN#method. */
@ExtendWith(PjacocoInProcessExtension.class)
abstract class InProcessTesterBase {
    /** 본문을 동시성 프로브로 감싸 실행. sleep 150ms로 동시 실행 창 확보(커버 라인 불변 — 타이밍만). */
    protected void probe(TestInfo info, OverlapProbe.Body body) throws Throwable {
        String testId = info.getTestClass().orElseThrow().getName()
                + "#" + info.getTestMethod().orElseThrow().getName();
        OverlapProbe.around(testId, 150, body);
    }
}
```

- [ ] **Step 5: GreetingInProcessIT / PriceInProcessIT 작성 (≥8 케이스, 2 클래스)**

```java
package io.tia.e2e.inprocess;

import io.tia.fixture.GreetingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("inprocess-tester")
class GreetingInProcessIT extends InProcessTesterBase {
    private final GreetingService svc = new GreetingService();   // in-JVM 직접 호출(Spring 없음)
    @Test void greetAlice(TestInfo i) throws Throwable { probe(i, () -> assertEquals("hello alice", svc.greet("Alice"))); }
    @Test void greetBob(TestInfo i)   throws Throwable { probe(i, () -> assertEquals("hello bob",   svc.greet("Bob"))); }
    @Test void greetCarol(TestInfo i) throws Throwable { probe(i, () -> assertEquals("hello carol", svc.greet("Carol"))); }
    @Test void greetDave(TestInfo i)  throws Throwable { probe(i, () -> assertEquals("hello dave",  svc.greet("Dave"))); }
}
```
```java
package io.tia.e2e.inprocess;

import io.tia.fixture.PricingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("inprocess-tester")
class PriceInProcessIT extends InProcessTesterBase {
    private final PricingService svc = new PricingService();
    @Test void priceAbc(TestInfo i)  throws Throwable { probe(i, () -> assertEquals(300, svc.priceOf("ABC"))); }
    @Test void priceDe(TestInfo i)   throws Throwable { probe(i, () -> assertEquals(200, svc.priceOf("DE"))); }
    @Test void priceF(TestInfo i)    throws Throwable { probe(i, () -> assertEquals(100, svc.priceOf("F"))); }
    @Test void priceWxyz(TestInfo i) throws Throwable { probe(i, () -> assertEquals(400, svc.priceOf("WXYZ"))); }
}
```
> 값 근거: `greet(n)="hello "+normalize(n)`(소문자), `priceOf(sku)=normalize(sku).length()*100`.

- [ ] **Step 6: 컴파일 + 에이전트 없이 안전 확인**

먼저 에이전트 jar 준비: `bash scripts/setup-pjacoco.sh` (tools/pjacoco/jacocoagent-parallel.jar 생성).
Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :e2e:inProcessTesterTest -Pinprocess.mode=serial -Dtia.inprocess.covDir="$PWD/build/inprocess-e2e/cov-serial" --no-daemon`
Expected: 8개 테스트 통과(에이전트 부착되어 본문 실행). `build/inprocess-e2e/cov-serial`에 `.exec` 생성. (overlapFile 미설정이라 프로브는 no-op.)
또 `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :e2e:test --no-daemon` 으로 기존 회귀 무손상 확인.

- [ ] **Step 7: Commit**
```bash
git add e2e/build.gradle e2e/src/test/java/io/tia/e2e/parallel/TestwiseNormalizer.java e2e/src/test/java/io/tia/e2e/inprocess/
git commit -m "feat(e2e): in-JVM 테스터(서비스 직접 호출) + 오버랩 프로브 + fixture-app 의존 [REQ-001/005]"
```

---

## Task 2: OverlapProbeAnalyzer + 단위 테스트 (TDD)
**REQ-IDs:** REQ-005

**Files:**
- Create: `e2e/src/test/java/io/tia/e2e/inprocess/OverlapProbeAnalyzer.java`
- Test: `e2e/src/test/java/io/tia/e2e/inprocess/OverlapProbeAnalyzerTest.java`

**Interfaces:**
- Produces: `public static int OverlapProbeAnalyzer.overlappingPairs(Path file)` — 겹치는 [START,END] 구간 쌍 수. 파싱 불가 줄은 예외.

- [ ] **Step 1: 실패 테스트 작성**
```java
package io.tia.e2e.inprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverlapProbeAnalyzerTest {
    @Test void countsOverlappingPairs(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("ov.csv");
        // A[100,300], B[200,400] 겹침; C[500,600] 비겹침 → 겹치는 쌍 1
        Files.writeString(f, "A,START,100\nB,START,200\nA,END,300\nB,END,400\nC,START,500\nC,END,600\n");
        assertEquals(1, OverlapProbeAnalyzer.overlappingPairs(f));
    }
    @Test void noOverlapWhenSequential(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("ov.csv");
        Files.writeString(f, "A,START,100\nA,END,200\nB,START,200\nB,END,300\n");
        assertEquals(0, OverlapProbeAnalyzer.overlappingPairs(f));
    }
}
```

- [ ] **Step 2: 실패 확인**
Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :e2e:test --tests 'io.tia.e2e.inprocess.OverlapProbeAnalyzerTest' --no-daemon`
Expected: FAIL (OverlapProbeAnalyzer 미존재 — 컴파일 에러). (참고: 이 테스트는 inprocess 태그가 없어 일반 test에서 실행됨.)

- [ ] **Step 3: 구현**
```java
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
```

- [ ] **Step 4: 통과 확인**
Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :e2e:test --tests 'io.tia.e2e.inprocess.OverlapProbeAnalyzerTest' --no-daemon`
Expected: PASS (2개).

- [ ] **Step 5: Commit**
```bash
git add e2e/src/test/java/io/tia/e2e/inprocess/OverlapProbeAnalyzer.java e2e/src/test/java/io/tia/e2e/inprocess/OverlapProbeAnalyzerTest.java
git commit -m "feat(e2e): 오버랩 프로브 분석기(겹침 쌍) + 단위테스트 [REQ-005]"
```

---

## Task 3: in-process 수용 비교 테스트 (red)
**REQ-IDs:** REQ-001, REQ-002, REQ-003, REQ-004, REQ-005

**Files:**
- Create: `e2e/src/test/java/io/tia/e2e/inprocess/InProcessCollectionE2E.java`

**Interfaces:**
- Consumes: 시스템 프로퍼티 `tia.inprocess.artifacts`(= testwise_{serial,forks,injvm}.json + overlap_{serial,injvm}.csv 디렉터리), `TestwiseNormalizer`(public), `OverlapProbeAnalyzer`.
- 게이트: `@Tag("inprocess-e2e")` + artifacts 없으면 skip.

- [ ] **Step 1: 수용 테스트 작성 (먼저 red)**
```java
package io.tia.e2e.inprocess;

import io.tia.e2e.parallel.TestwiseNormalizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** in-process per-test 수집이 정확하고 병렬==직렬임을 검증. 산출물은 run-inprocess-e2e.sh가 생성. */
@Tag("inprocess-e2e")
class InProcessCollectionE2E {
    static Path dir;
    @BeforeAll static void locate() {
        String d = System.getProperty("tia.inprocess.artifacts");
        assumeTrue(d != null && Files.isDirectory(Path.of(d)),
                "tia.inprocess.artifacts 미설정 — scripts/run-inprocess-e2e.sh 로 산출 후 실행");
        dir = Path.of(d);
    }
    private static Map<String, Map<String, RoaringBitmap>> tw(String n) throws Exception {
        return TestwiseNormalizer.normalize(dir.resolve(n));
    }

    @Test @DisplayName("REQ-001: in-JVM per-test 귀속 — greeting/price 테스트가 각자 서비스 파일을 커버")
    void serialPerTestAttribution() throws Exception {
        Map<String, Map<String, RoaringBitmap>> m = tw("testwise_serial.json");
        assertEquals(8, m.size(), "8개 테스트");
        m.forEach((id, files) -> {
            String joined = String.join(",", files.keySet());
            if (id.contains("GreetingInProcessIT")) assertTrue(joined.contains("GreetingService.java"), id + " → " + joined);
            if (id.contains("PriceInProcessIT")) assertTrue(joined.contains("PricingService.java"), id + " → " + joined);
        });
    }

    @Test @DisplayName("REQ-002: forks 병렬 수집이 직렬과 per-test 동일")
    void forksMatchesSerial() throws Exception { assertEquals(tw("testwise_serial.json"), tw("testwise_forks.json")); }

    @Test @DisplayName("REQ-003: in-JVM 병렬 수집이 직렬과 per-test 동일")
    void inJvmMatchesSerial() throws Exception { assertEquals(tw("testwise_serial.json"), tw("testwise_injvm.json")); }

    @Test @DisplayName("REQ-004: 모든 병렬 산출 테스트의 커버리지가 non-empty")
    void everyTestHasNonEmptyCoverage() throws Exception {
        for (String f : new String[]{"testwise_forks.json", "testwise_injvm.json"}) {
            Map<String, Map<String, RoaringBitmap>> m = tw(f);
            assertFalse(m.isEmpty(), f);
            m.forEach((id, files) -> assertTrue(
                    files.values().stream().mapToLong(RoaringBitmap::getLongCardinality).sum() > 0,
                    f + " / " + id + ": 커버 라인 0"));
        }
    }

    @Test @DisplayName("REQ-005: in-JVM 모드가 실제 동시 실행됨(겹침≥1) + 직렬은 겹침 0")
    void inJvmRunsConcurrently() throws Exception {
        assertTrue(OverlapProbeAnalyzer.overlappingPairs(dir.resolve("overlap_injvm.csv")) >= 1, "in-JVM 동시 실행 미관측");
        assertEquals(0, OverlapProbeAnalyzer.overlappingPairs(dir.resolve("overlap_serial.csv")), "직렬인데 겹침 발생");
    }
}
```

- [ ] **Step 2: red 확인 (artifacts 없음 → skip)**
Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :e2e:test --tests 'io.tia.e2e.inprocess.InProcessCollectionE2E' --no-daemon`
Expected: 컴파일 성공, 5개 skip(assumeTrue). (`test`가 inprocess-e2e 태그를 제외하므로 `--tests` 직접 지정.)

- [ ] **Step 3: Commit**
```bash
git add e2e/src/test/java/io/tia/e2e/inprocess/InProcessCollectionE2E.java
git commit -m "feat(e2e): in-process 병렬==직렬 수용 비교 테스트 [REQ-001~005]"
```

---

## Task 4: 오케스트레이터 — 산출물 생성 + 수용 green
**REQ-IDs:** REQ-001, REQ-002, REQ-003, REQ-004, REQ-005

**Files:**
- Create: `scripts/run-inprocess-e2e.sh`

**Interfaces:**
- Consumes: Task 1~3. Produces: `build/inprocess-e2e/{testwise_serial,testwise_forks,testwise_injvm}.json`, `overlap_{serial,injvm}.csv`. 마지막에 `InProcessCollectionE2E` green.

- [ ] **Step 1: 오케스트레이터 작성**
```bash
#!/usr/bin/env bash
set -euo pipefail
# in-JVM per-test 수집을 직렬/forks/in-JVM 3모드로 구동(테스터 JVM에 pjacoco 에이전트 -javaagent),
# 모드별 격리 디렉터리로 .exec 수집 → tia convert → 수용 비교(InProcessCollectionE2E) green.
# JAVA_HOME 이식성: 이미 설정·유효하면 사용, 아니면 macOS java_home, 아니면 PATH java.
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q 'version "17'; then
  if [ -x /usr/libexec/java_home ]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
fi
AGENT_JAR="$(bash scripts/setup-pjacoco.sh | sed -n 's/^PJACOCO_AGENT_JAR=//p')"
[ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 미해소" >&2; exit 1; }

CLASSES="$PWD/fixture-app/build/classes/java/main"
OUT="$PWD/build/inprocess-e2e"; rm -rf "$OUT"; mkdir -p "$OUT"
./gradlew --no-daemon :fixture-app:classes :tia-cli:installDist >&2
CLI="$PWD/tia-cli/build/install/tia/bin/tia"

run_mode() {  # $1 = serial|forks|injvm
  local mode="$1" cov="$OUT/cov-$mode" ov="$OUT/overlap-$mode.csv"
  rm -rf "$cov"; mkdir -p "$cov"; rm -f "$ov"
  ./gradlew --no-daemon :e2e:inProcessTesterTest -Pinprocess.mode="$mode" \
      -Pinprocess.agentJar="$AGENT_JAR" -Pinprocess.covDir="$cov" \
      "-Dtia.inprocess.overlapFile=$ov" >&2
  "$CLI" convert --exec-dir "$cov" --classes "$CLASSES" --out "$OUT/testwise_$mode.json" >&2
  # 수용 테스트가 기대하는 파일명으로 정리(overlap_<mode>.csv)
  cp "$ov" "$OUT/overlap_$mode.csv" 2>/dev/null || true
}
run_mode serial; run_mode forks; run_mode injvm

./gradlew --no-daemon :e2e:test --tests 'io.tia.e2e.inprocess.InProcessCollectionE2E' \
    "-Dtia.inprocess.artifacts=$OUT" >&2
echo "✅ inprocess-e2e PASS"
```
> 주: `:e2e:test --tests`는 태그 제외에도 `--tests`로 직접 지정하면 실행된다. 그래도 안 되면 별도
> `inProcessE2ETest`(Test, includeTags 'inprocess-e2e', outputs.upToDateWhen{false}) 태스크를 추가해 사용.

- [ ] **Step 2: 실행 권한 + 구동 (red→green)**
Run: `chmod +x scripts/run-inprocess-e2e.sh && bash scripts/run-inprocess-e2e.sh`
Expected: 세 모드 수집, `InProcessCollectionE2E` 5/5 PASS, `✅ inprocess-e2e PASS`. overlap_injvm.csv에 겹침 존재, overlap_serial.csv 겹침 0.
디버그: in-JVM 겹침이 0이면 parallelism/슬립을 확인(태스크 설정). forks 산출이 직렬과 다르면 testId 충돌/격리 디렉터리 확인. 단언 약화 금지.

- [ ] **Step 3: Commit**
```bash
git add scripts/run-inprocess-e2e.sh
git commit -m "feat(e2e): in-process 수집 오케스트레이터(3모드→convert→수용비교) [REQ-001~005]"
```

---

## Task 5: teamscale 완전 은퇴
**REQ-IDs:** REQ-006

**Files (제거/수정):**
- Remove dir: `tia-junit-extension/`
- Modify: `settings.gradle`, `coverage/build.gradle`, `fixture-app/build.gradle`
- Remove: `fixture-app/src/test/java/io/tia/fixture/ApiSmokeTest.java`, `fixture-app/src/test/java/io/tia/fixture/RunResultWriter.java`
- Modify: `tia-gradle-plugin/src/main/java/io/tia/gradle/TiaPlugin.java`, `TiaArgs.java`, `tia-gradle-plugin/src/test/java/io/tia/gradle/TiaPluginTest.java`
- Remove: `scripts/download-agent.sh`, `scripts/run-poc.sh`, `scripts/measure-flaky.sh`
- Modify: `tia-core/src/main/java/io/tia/core/convert/TestwiseConverter.java`, `LineRangeParser.java` (주석만)

- [ ] **Step 1: 사용처 확인 + 모듈/파일 제거**
```bash
git grep -l "currentTestId\|TeamscaleTestwise\|HttpAgentClient" -- '*.java' | grep -v tia-junit-extension
# 결과가 fixture-app/ApiSmokeTest 뿐임을 확인(그 외 있으면 STOP·보고).
git rm -r tia-junit-extension
git rm fixture-app/src/test/java/io/tia/fixture/ApiSmokeTest.java fixture-app/src/test/java/io/tia/fixture/RunResultWriter.java
git rm scripts/download-agent.sh scripts/run-poc.sh scripts/measure-flaky.sh
```

- [ ] **Step 2: 빌드 와이어링 정리**
- `settings.gradle`: `include` 목록에서 `'tia-junit-extension'` 제거.
- `coverage/build.gradle`: `jacocoAggregation project(':tia-junit-extension')` 줄 제거.
- `fixture-app/build.gradle`: `testImplementation project(':tia-junit-extension')` 줄 제거.

- [ ] **Step 3: 플러그인에서 teamscale 헬퍼 제거**
- `TiaPlugin.java`: `attachTeamscaleAgent(...)` 메서드 전체 제거(Javadoc 포함).
- `TiaArgs.java`: `teamscaleAgentJvmArg(...)` 메서드 제거.
- `TiaPluginTest.java`: teamscale 관련 테스트(`attachTeamscaleAgent`/`teamscaleAgentJvmArg` 단언) 제거. `attachCoverageAgent`(out-of-process) 테스트는 유지.

- [ ] **Step 4: 주석 정리**
`TestwiseConverter.java`/`LineRangeParser.java`에서 "teamscale" 단어를 포함한 주석을 일반 표현(pjacoco/testwise 포맷)으로 수정(코드 동작 무관).

- [ ] **Step 5: 빌드/회귀 + grep 게이트**
Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --no-daemon`
Expected: BUILD SUCCESSFUL(전 모듈; tia-junit-extension 부재로 인한 깨짐 없음).
Run: `git grep -li teamscale -- '*.java' '*.gradle' '*.yml' '*.sh' README.md GETTING-STARTED.md THIRD-PARTY-NOTICES.md 'tia-gradle-plugin/README.md' ':!docs/superpowers/'`
Expected: (Task 6/7에서 docker/문서 정리 후) 최종 0. 이 task 종료 시점엔 docker-compose.e2e.yml/docker-e2e-tester.sh/ci.yml/문서가 남아 있으므로, **이 task의 grep은 `*.java`/`*.gradle`만** 0을 확인(나머지는 Task 6/7에서 0으로).

- [ ] **Step 6: Commit**
```bash
git add -A
git commit -m "refactor: teamscale 완전 은퇴 — 모듈/헬퍼/스크립트/와이어링 제거 [REQ-006]"
```

---

## Task 6: 컨테이너 out-of-process E2E를 pjacoco로 마이그레이션
**REQ-IDs:** REQ-007

**Files:**
- Modify: `docker-compose.e2e.yml`, `scripts/docker-e2e-tester.sh`, `.github/workflows/ci.yml`
- (재사용) e2e의 out-of-process 테스터(`io.tia.e2e.parallel.CoverageTesterIT` 패턴: `PjacocoExtension`+`PjacocoRestAssured`)

- [ ] **Step 1: docker-compose.e2e.yml — pjacoco 에이전트로 교체**
sut 서비스의 teamscale 마운트/옵션을 pjacoco로:
```yaml
    volumes:
      - ./fixture-app/build/libs:/libs:ro
      - ./tools/pjacoco:/agent:ro
      - cov:/cov
    command:
      - java
      - "-javaagent:/agent/jacocoagent-parallel.jar=destfile=/cov,port=6310,aggregate=false,includes=io.tia.fixture.*"
      - -jar
      - /libs/fixture-app.jar
      - --server.port=8080
    expose: ["8080", "6310"]
```
(class-dir 마운트 제거 — pjacoco는 불요. cov 볼륨 유지.)

- [ ] **Step 2: scripts/docker-e2e-tester.sh — tia convert + pjacoco 테스터**
- teamscale `convert` 호출 제거 → `tia convert --exec-dir /cov --classes <fixture main classes> --out /work/build/inprocess-... testwise.json` (정확 경로는 스크립트 맥락에 맞춤).
- 실행 테스트를 `io.tia.e2e.parallel.CoverageTesterIT`(또는 동등 pjacoco 블랙박스)로 교체, `-Dfixture.baseUrl=http://sut:8080 -Dpjacoco.control-url=http://sut:6310`.
- impact/index 검증의 testId를 pjacoco `ClassName#method`로 갱신.

- [ ] **Step 3: ci.yml docker-e2e job — setup-pjacoco + 이름 갱신**
- job name `Container E2E (real teamscale agent)` → `Container E2E (pjacoco)`.
- 단계 `teamscale 에이전트 다운로드` / `bash scripts/download-agent.sh` → `pjacoco 빌드` / `bash scripts/setup-pjacoco.sh`.
- 워밍 단계의 `:fixture-app:testClasses`(ApiSmokeTest 제거됨) 의존 제거/조정.

- [ ] **Step 4: 컨테이너 E2E 구동 확인**
Run: `docker compose -f docker-compose.e2e.yml up --abort-on-container-exit --exit-code-from tester`
Expected: per-test 수집→`tia convert`→index→impact green. (docker 불가 환경이면 명시적으로 skip 보고하고 yaml/스크립트 정합만 확인.)

- [ ] **Step 5: Commit**
```bash
git add docker-compose.e2e.yml scripts/docker-e2e-tester.sh .github/workflows/ci.yml
git commit -m "ci: 컨테이너 out-of-process E2E를 pjacoco로 마이그레이션 [REQ-007]"
```

---

## Task 7: 문서 갱신 + in-process CI job + 최종 grep 게이트
**REQ-IDs:** REQ-008, REQ-006(최종)

**Files:**
- Modify: `README.md`, `GETTING-STARTED.md`, `tia-gradle-plugin/README.md`, `THIRD-PARTY-NOTICES.md`, `.github/workflows/ci.yml`

- [ ] **Step 1: 문서에서 teamscale in-process → pjacoco in-process**
- `GETTING-STARTED.md` §1 in-process 항목: teamscale 에이전트/확장 서술 → pjacoco in-process(테스터 JVM에 `-javaagent`, `@ExtendWith(PjacocoInProcessExtension)`, `aggregate=false`, `port=0`, 서비스 직접 호출). 병렬 자유 명시.
- `README.md`: teamscale 언급 제거/pjacoco로. in-process 수집을 pjacoco로 일원화 서술.
- `tia-gradle-plugin/README.md`: §(b) in-process(teamscale) 섹션을 pjacoco in-process로 교체.
- `THIRD-PARTY-NOTICES.md`: teamscale가 런타임 의존이 아니므로 해당 고지 섹션 제거(pjacoco/jacoco 고지는 유지/추가).

- [ ] **Step 2: ci.yml — in-process E2E job 추가**
`jobs:`에 추가:
```yaml
  inprocess-e2e:
    name: In-process collection E2E (pjacoco)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - name: Set up Gradle (cache)
        uses: gradle/actions/setup-gradle@v4
      - name: in-process E2E (pjacoco 소스 해소, 실패 시 fail)
        env: { PJACOCO_REF: main }
        run: bash scripts/run-inprocess-e2e.sh
```

- [ ] **Step 3: 최종 grep 게이트 (REQ-006 완료)**
Run: `git grep -li teamscale -- '*.java' '*.gradle' '*.yml' '*.sh' README.md GETTING-STARTED.md THIRD-PARTY-NOTICES.md 'tia-gradle-plugin/README.md' ':!docs/superpowers/'`
Expected: **0**.
Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --no-daemon` → BUILD SUCCESSFUL.
Run: `bash scripts/run-inprocess-e2e.sh` → ✅ PASS. (선택) `bash scripts/run-parallel-e2e.sh` → ✅ PASS(REQ-009 회귀).

- [ ] **Step 4: Commit**
```bash
git add -A
git commit -m "docs+ci: 문서 pjacoco in-process 반영 + inprocess-e2e job + teamscale 잔존 0 [REQ-006/008]"
```

---

## 최종 검증 (PR 전)
- [ ] `./gradlew test` green; `run-inprocess-e2e.sh` ✅; `run-parallel-e2e.sh` ✅(REQ-009); grep 게이트 0(REQ-006).
- [ ] 요구사항 매트릭스 100% green 갱신, 각 green REQ ↔ 통과 테스트(@DisplayName) 대조.

---

## Self-Review (작성자 점검)

**1. Spec coverage (REQ ↔ task):**
- REQ-001 in-JVM 수집 → Task 1(테스터)+4(오케)+3(`serialPerTestAttribution`).
- REQ-002 forks==직렬 → Task 1(forks 모드)+4+3(`forksMatchesSerial`).
- REQ-003 in-JVM==직렬 → Task 1(injvm 모드)+4+3(`inJvmMatchesSerial`).
- REQ-004 non-empty → Task 3(`everyTestHasNonEmptyCoverage`).
- REQ-005 동시성 실증 → Task 1(프로브)+2(분석기)+4+3(`inJvmRunsConcurrently`).
- REQ-006 teamscale 은퇴 → Task 5 + 최종 grep Task 7.
- REQ-007 컨테이너 마이그레이션 → Task 6.
- REQ-008 문서 → Task 7.
- REQ-009 out-of-process 회귀 → 최종 검증(run-parallel-e2e.sh).
모든 REQ에 task 존재.

**2. Placeholder scan:** 모든 step에 실제 코드/명령/기대값. "적절히" 류 없음(Task 6의 경로 일부는 스크립트 맥락 의존이라 "정확 경로는 맥락에 맞춤"으로 명시 — 구현자가 docker-e2e-tester.sh 기존 구조를 보고 확정).

**3. Type consistency:** `OverlapProbe.around(String,long,Body)`/`record(String,String)`, `OverlapProbeAnalyzer.overlappingPairs(Path)->int`, `TestwiseNormalizer.normalize(Path)`(public 화), testId=FQN#method, 시스템 프로퍼티 `tia.inprocess.{overlapFile,covDir,artifacts}`·`-Pinprocess.{mode,agentJar,covDir}` 명칭이 Task 1/3/4에서 일치. 에이전트 옵션 `destfile/aggregate=false/includes/port=0` 일관.

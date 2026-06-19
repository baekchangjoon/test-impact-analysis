# 병렬 테스트 수집 지원 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 출처: design spec `docs/superpowers/specs/2026-06-19-tia-parallel-collection-design.md`,
> 요구사항명세 `docs/superpowers/requirements/2026-06-19-tia-parallel-collection-requirements.md`

**Goal:** out-of-process(pjacoco) per-test 커버리지 수집을 병렬(forks·in-JVM)로 가능하게 하고, 병렬 산출물이 직렬과 동일함을 E2E로 잠근다.

**Architecture:** pjacoco 에이전트를 **단일 SUT(fixture-app)** 에 부착하고, 별도 테스터를 직렬/병렬로 실행. 테스트별 `<testId>.exec`를 `tia convert`로 testwise로 바꿔 직렬 산출물과 정규화 비교. 병렬 기계 장치(ThreadLocal 격리·baggage)는 pjacoco가 소유 — TIA는 토폴로지 확립·소비·검증·문서만.

**Tech Stack:** Java 17(빌드), Spring Boot 3.3.4(fixture-app SUT), JUnit 5, RestAssured 5.5, pjacoco(agent/testkit-junit5/testkit-restassured, mavenLocal 소스빌드), tia-cli/tia-core, Gradle.

## Global Constraints
(모든 task의 요구에 암묵 포함 — spec/요구사항명세에서 그대로 인용)
- 병렬 토폴로지: **에이전트는 단일 SUT에만** 부착. 테스터를 병렬화(forks/in-JVM). 내장 `attachCoverageAgent`(에이전트=Test JVM)는 **`maxParallelForks=1` 유지**(forks>1 포트 충돌 방지).
- pjacoco 에이전트 옵션 **`aggregate=false`** (per-test만 소비; `aggregate.exec` 방지).
- 직렬/병렬 비교는 **동일 `--classes` 디렉터리**(fixture-app main classes)로 convert.
- testId 키는 양쪽 모두 pjacoco **`ClassName#method`**. teamscale 키(`FQN/method`)와 한 인덱스에 혼용 금지.
- E2E 테스트 케이스: **결정적 케이스 ≥8개**, 비결정 엔드포인트 **`/flaky` 제외**.
- 실행별 **격리 exec 디렉터리**, 각 수집 전 디렉터리 비움.
- speedup은 **정보용 기록만**(하드 게이트 아님 — CI 플레이크 방지).
- 동기 HTTP 호출 기준 지원; 자식 스레드/스레드풀 위임은 한계(문서화).
- CI는 pjacoco 의존을 **결정적으로 해소**(소스 빌드 → mavenLocal); 해소 실패는 **skip 아닌 fail**.
- 각 E2E/수용 테스트는 검증 REQ-ID를 `@DisplayName`으로 참조.

---

## File Structure
- `scripts/setup-pjacoco.sh` (Create) — pjacoco 소스 빌드 → 에이전트 jar 경로 + testkit/plugin을 mavenLocal에. 미해소 시 비0 종료.
- `scripts/run-parallel-e2e.sh` (Create) — SUT 기동(모드별 격리 destfile) → 테스터 3모드 실행 → `tia convert` → `testwise_<mode>.json` + `timings.json` 산출. 오케스트레이터.
- `e2e/build.gradle` (Modify) — mavenLocal + RestAssured + pjacoco testkit 의존, `parallelTesterTest` 전용 Test 태스크(모드별 fork/in-JVM 구성).
- `e2e/src/test/java/io/tia/e2e/parallel/CoverageTesterIT.java` (Create) — 단일 SUT를 때리는 ≥8 결정적 케이스(pjacoco 확장+baggage). 오케스트레이터가 3모드로 구동.
- `e2e/src/test/java/io/tia/e2e/parallel/TestwiseNormalizer.java` (Create) — testwise JSON → `Map<testId, Map<fileKey, RoaringBitmap>>` 정규화기(공유 유틸).
- `e2e/src/test/java/io/tia/e2e/parallel/ParallelCollectionE2E.java` (Create) — REQ-001~004,009 수용 단언(산출 artifacts 소비).
- `tia-gradle-plugin/src/main/java/io/tia/gradle/TiaPlugin.java` (Modify) — `attachCoverageAgent` Javadoc 교정.
- `tia-gradle-plugin/src/main/java/io/tia/gradle/TiaArgs.java` (Modify) — `coverageAgentJvmArg` 주석 교정.
- `tia-gradle-plugin/src/test/java/io/tia/gradle/TiaPluginTest.java` (Modify) — `coverageHelperPinsSingleFork` 명시 단언.
- `GETTING-STARTED.md`, `tia-gradle-plugin/README.md` (Modify) — 병렬 와이어링·한계·aggregate 두 경로·키 주의(REQ-006/007).
- `.github/workflows/ci.yml` (Modify) — `parallel-e2e` job(REQ-008).

---

## Task 1: pjacoco 의존 해소 스크립트 (REQ-008 일부)

**Files:**
- Create: `scripts/setup-pjacoco.sh`

**Interfaces:**
- Produces: `tools/pjacoco/jacocoagent-parallel.jar`(에이전트 jar 절대경로를 stdout `PJACOCO_AGENT_JAR=...`로), mavenLocal에 `io.pjacoco:*` 게시.

- [ ] **Step 1: 스크립트 작성**

```bash
#!/usr/bin/env bash
set -euo pipefail
# pjacoco(미게시)를 소스에서 빌드해 (1) 에이전트 jar 확보 (2) testkit/plugin을 mavenLocal에 게시.
# 해소 실패 시 비0 종료 — 호출측(E2E/CI)이 skip 아닌 fail로 다룬다.
PJACOCO_SRC="${PJACOCO_SRC:-$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage}"
PJACOCO_REPO="${PJACOCO_REPO:-https://github.com/baekchangjoon/parallel-per-test-coverage.git}"
PJACOCO_REF="${PJACOCO_REF:-main}"

if [ ! -d "$PJACOCO_SRC" ]; then
  echo "pjacoco 소스 없음 → clone: $PJACOCO_REPO@$PJACOCO_REF"
  git clone --depth 1 --branch "$PJACOCO_REF" "$PJACOCO_REPO" "$PJACOCO_SRC"
fi

( cd "$PJACOCO_SRC" && ./gradlew --no-daemon :agent:shadowJar publishToMavenLocal )

AGENT_JAR="$(find "$PJACOCO_SRC/agent/build/libs" -name 'jacocoagent-parallel*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
[ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 jar 빌드 실패"; exit 1; }
mkdir -p tools/pjacoco
cp "$AGENT_JAR" tools/pjacoco/jacocoagent-parallel.jar
echo "PJACOCO_AGENT_JAR=$PWD/tools/pjacoco/jacocoagent-parallel.jar"
```

- [ ] **Step 2: 실행 권한 + 구동 검증**

Run: `chmod +x scripts/setup-pjacoco.sh && bash scripts/setup-pjacoco.sh`
Expected: 마지막 줄 `PJACOCO_AGENT_JAR=<repo>/tools/pjacoco/jacocoagent-parallel.jar`, 파일 존재. mavenLocal에 `~/.m2/repository/io/pjacoco/pjacoco-testkit-junit5/` 생성.

- [ ] **Step 3: Commit**

```bash
git add scripts/setup-pjacoco.sh
git commit -m "feat(e2e): pjacoco 의존 해소 스크립트 (소스 빌드→agent jar+mavenLocal) [REQ-008]"
```

---

## Task 2: 정규화기 + 단위 테스트 (비교 기반, REQ-001/002 토대)

**Files:**
- Create: `e2e/src/test/java/io/tia/e2e/parallel/TestwiseNormalizer.java`
- Test: `e2e/src/test/java/io/tia/e2e/parallel/TestwiseNormalizerTest.java`
- Modify: `e2e/build.gradle` (RoaringBitmap·tia-core는 이미 의존; 신규 의존은 Task 3에서)

**Interfaces:**
- Produces: `TestwiseNormalizer.normalize(Path testwiseJson) -> Map<String, Map<String, RoaringBitmap>>` (testId → fileKey → covered lines). `RoaringBitmap`/`Map`의 `equals`로 직렬==병렬 비교.

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.tia.e2e.parallel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestwiseNormalizerTest {
    @Test
    void normalizesTestwiseToTestFileLineMap(@TempDir Path tmp) throws Exception {
        Path j = tmp.resolve("tw.json");
        Files.writeString(j, "{\"tests\":[{\"uniformPath\":\"ApiIT#a\",\"result\":\"PASSED\","
            + "\"paths\":[{\"path\":\"io/tia/fixture\",\"files\":[{\"fileName\":\"PricingService.java\",\"coveredLines\":\"6-8\"}]}]}]}");
        Map<String, Map<String, RoaringBitmap>> m = TestwiseNormalizer.normalize(j);
        RoaringBitmap expected = RoaringBitmap.bitmapOf(6, 7, 8);
        assertEquals(Map.of("ApiIT#a", Map.of("io/tia/fixture/PricingService.java", expected)), m);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :e2e:test --tests 'io.tia.e2e.parallel.TestwiseNormalizerTest' --no-daemon`
Expected: FAIL (`TestwiseNormalizer` 미존재 → 컴파일 에러).

- [ ] **Step 3: 정규화기 구현**

```java
package io.tia.e2e.parallel;

import io.tia.core.model.TestCoverage;
import io.tia.core.parse.TestwiseReportParser;
import org.roaringbitmap.RoaringBitmap;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** testwise JSON → {@code Map<testId, Map<fileKey, RoaringBitmap>>}. 직렬/병렬 산출물 동등 비교용 공유 유틸. */
final class TestwiseNormalizer {
    private TestwiseNormalizer() {}

    static Map<String, Map<String, RoaringBitmap>> normalize(Path testwiseJson) throws Exception {
        try (InputStream in = Files.newInputStream(testwiseJson)) {
            List<TestCoverage> tests = new TestwiseReportParser().parse(in);
            Map<String, Map<String, RoaringBitmap>> out = new LinkedHashMap<>();
            for (TestCoverage t : tests) {
                Map<String, RoaringBitmap> byFile = new LinkedHashMap<>(t.linesByFile());
                out.put(t.testId(), byFile);
            }
            return out;
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :e2e:test --tests 'io.tia.e2e.parallel.TestwiseNormalizerTest' --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add e2e/src/test/java/io/tia/e2e/parallel/TestwiseNormalizer.java e2e/src/test/java/io/tia/e2e/parallel/TestwiseNormalizerTest.java
git commit -m "feat(e2e): testwise 정규화기(testId→file→lines) + 단위테스트 [REQ-001/002]"
```

---

## Task 3: e2e 모듈 의존/태스크 + 병렬 테스터 스위트 (CoverageTesterIT)

**Files:**
- Modify: `e2e/build.gradle`
- Create: `e2e/src/test/java/io/tia/e2e/parallel/CoverageTesterIT.java`

**Interfaces:**
- Consumes: 시스템 프로퍼티 `fixture.baseUrl`(SUT 주소), `pjacoco.control-url`(에이전트 제어). `@Tag("parallel-tester")`로 일반 `:e2e:test`에서 제외.
- Produces: SUT에 baggage `test.id=CoverageTesterIT#<method>`로 요청 → 에이전트가 `<testId>.exec` 산출. Gradle 태스크 `:e2e:parallelTesterTest`(프로퍼티 `parallel.mode`로 serial/forks/injvm 구성).

- [ ] **Step 1: e2e/build.gradle 수정 (의존 + 전용 태스크)**

`e2e/build.gradle`에 추가:

```gradle
repositories { mavenLocal(); mavenCentral() }   // pjacoco testkit(mavenLocal) 해소

dependencies {
    testImplementation 'io.rest-assured:rest-assured:5.5.0'
    testImplementation 'io.pjacoco:pjacoco-testkit-junit5:1.1.0'
    testImplementation 'io.pjacoco:pjacoco-testkit-restassured:1.1.0'
}

// 일반 :e2e:test 에서 병렬 테스터/수용 비교 테스트는 제외(오케스트레이터가 별도 구동).
tasks.named('test') { useJUnitPlatform { excludeTags 'parallel-tester', 'parallel-e2e' } }

// 병렬 테스터 전용 태스크 — -Pparallel.mode 로 직렬/포크/in-JVM 구성. SUT는 외부 기동(스크립트).
tasks.register('parallelTesterTest', Test) {
    useJUnitPlatform { includeTags 'parallel-tester' }
    systemProperties System.getProperties().findAll { it.key.toString().startsWith('fixture.') || it.key.toString() == 'pjacoco.control-url' }
    def mode = (project.findProperty('parallel.mode') ?: 'serial').toString()
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
}
```

- [ ] **Step 2: 테스터 스위트 작성 (≥8 결정적 케이스, /flaky 제외)**

```java
package io.tia.e2e.parallel;

import io.pjacoco.testkit.junit5.PjacocoExtension;
import io.pjacoco.testkit.restassured.PjacocoRestAssured;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 단일 pjacoco SUT(fixture-app)를 때리는 결정적 블랙박스 케이스. 오케스트레이터가 직렬/forks/in-JVM 3모드로 구동.
 *  /flaky(비결정) 제외. testId = CoverageTesterIT#<method> (pjacoco 키). */
@Tag("parallel-tester")
@ExtendWith(PjacocoExtension.class)
class CoverageTesterIT {
    @BeforeAll
    static void wire() {
        String base = System.getProperty("fixture.baseUrl");
        assumeTrue(base != null, "fixture.baseUrl 미설정 — 오케스트레이터에서만 실행");
        RestAssured.baseURI = base;
        PjacocoRestAssured.enable();   // 모든 요청에 baggage: test.id=<현재testId>
    }

    @Test void greetingAlice() { given().get("/greeting/Alice").then().statusCode(200).body(equalTo("hello alice")); }
    @Test void greetingBob()   { given().get("/greeting/Bob").then().statusCode(200).body(equalTo("hello bob")); }
    @Test void greetingCarol() { given().get("/greeting/Carol").then().statusCode(200).body(equalTo("hello carol")); }
    @Test void greetingDave()  { given().get("/greeting/Dave").then().statusCode(200).body(equalTo("hello dave")); }
    @Test void priceAbc() { given().get("/price/ABC").then().statusCode(200).body(equalTo("300")); }
    @Test void priceDe()  { given().get("/price/DE").then().statusCode(200).body(equalTo("200")); }
    @Test void priceF()   { given().get("/price/F").then().statusCode(200).body(equalTo("100")); }
    @Test void priceWxyz(){ given().get("/price/WXYZ").then().statusCode(200).body(equalTo("400")); }
}
```

> 기대값 근거: `priceOf(sku)=normalize(sku).length()*100`(소문자화는 길이 불변). 본문값까지 검증해 결정성 확보.

- [ ] **Step 3: 컴파일 확인 (SUT 없이 assume-skip)**

Run: `./gradlew :e2e:parallelTesterTest --no-daemon`
Expected: 컴파일 성공. `fixture.baseUrl` 미설정이라 `@BeforeAll` assumeTrue로 전부 skip(=정상; 실제 실행은 Task 5 오케스트레이터). 컴파일 에러 없으면 통과로 간주.

- [ ] **Step 4: Commit**

```bash
git add e2e/build.gradle e2e/src/test/java/io/tia/e2e/parallel/CoverageTesterIT.java
git commit -m "feat(e2e): 병렬 테스터 스위트(≥8, /flaky 제외) + parallelTesterTest 태스크 [REQ-001/002/007]"
```

---

## Task 4: 수용 비교 테스트 (ParallelCollectionE2E, REQ-001~004/009)

**Files:**
- Create: `e2e/src/test/java/io/tia/e2e/parallel/ParallelCollectionE2E.java`

**Interfaces:**
- Consumes: 시스템 프로퍼티 `tia.parallel.artifacts`(= testwise_serial.json/testwise_forks.json/testwise_injvm.json/timings.json 가 있는 디렉터리). `TestwiseNormalizer`(Task 2).
- 게이트: `@Tag("parallel-e2e")` + artifacts 디렉터리 없으면 skip(메시지). 오케스트레이터/CI가 산출 후 명시 구동.

- [ ] **Step 1: 수용 테스트 작성 (먼저 red)**

```java
package io.tia.e2e.parallel;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** out-of-process 병렬 수집이 직렬과 동일함을 검증. 산출물(testwise_*.json/timings.json)은 오케스트레이터가 생성. */
@Tag("parallel-e2e")
class ParallelCollectionE2E {
    static Path dir;
    @BeforeAll static void locate() {
        String d = System.getProperty("tia.parallel.artifacts");
        assumeTrue(d != null && Files.isDirectory(Path.of(d)),
                "tia.parallel.artifacts 미설정 — scripts/run-parallel-e2e.sh 로 산출 후 실행");
        dir = Path.of(d);
    }

    private static Map<String, Map<String, RoaringBitmap>> tw(String name) throws Exception {
        return TestwiseNormalizer.normalize(dir.resolve(name));
    }

    @Test @DisplayName("REQ-001: forks 병렬 수집이 직렬과 per-test 동일")
    void forksParallelMatchesSerial() throws Exception {
        assertEquals(tw("testwise_serial.json"), tw("testwise_forks.json"));
    }

    @Test @DisplayName("REQ-002: in-JVM 병렬 수집이 직렬과 per-test 동일")
    void inJvmParallelMatchesSerial() throws Exception {
        assertEquals(tw("testwise_serial.json"), tw("testwise_injvm.json"));
    }

    @Test @DisplayName("REQ-003: 모든 병렬 산출 테스트의 커버리지가 non-empty")
    void everyTestHasNonEmptyCoverage() throws Exception {
        for (String f : new String[]{"testwise_forks.json", "testwise_injvm.json"}) {
            Map<String, Map<String, RoaringBitmap>> m = tw(f);
            assertFalse(m.isEmpty(), f + ": 테스트 0건");
            m.forEach((id, files) -> {
                long covered = files.values().stream().mapToLong(RoaringBitmap::getLongCardinality).sum();
                assertTrue(covered > 0, f + " / " + id + ": 커버 라인 0 (baggage 전파 실패 의심)");
            });
        }
    }

    @Test @DisplayName("REQ-004/009: 세 모드 벽시계 기록 존재 + 동일 테스트 집합")
    void recordsWallClockPerMode() throws Exception {
        String timings = Files.readString(dir.resolve("timings.json"));
        for (String mode : new String[]{"serial", "forks", "injvm"}) {
            assertTrue(timings.contains("\"" + mode + "\""), "timings.json 에 " + mode + " 누락");
        }
        assertEquals(tw("testwise_serial.json").keySet(), tw("testwise_forks.json").keySet());
        assertEquals(tw("testwise_serial.json").keySet(), tw("testwise_injvm.json").keySet());
    }
}
```

- [ ] **Step 2: red 확인 (artifacts 없음 → skip)**

Run: `./gradlew :e2e:test --tests 'io.tia.e2e.parallel.ParallelCollectionE2E' -PincludeParallelE2E --no-daemon`
(노트: 일반 test는 parallel-e2e 태그를 제외하므로, 이 단계는 `--tests` 직접 지정. artifacts 미설정 → 4건 skip = 의도된 red 전 상태.)
Expected: 4 tests skipped (assumeTrue). 컴파일 성공.

- [ ] **Step 3: Commit**

```bash
git add e2e/src/test/java/io/tia/e2e/parallel/ParallelCollectionE2E.java
git commit -m "feat(e2e): 병렬==직렬 수용 비교 테스트 [REQ-001~004/009]"
```

---

## Task 5: 오케스트레이터 — 산출물 생성 + 수용 테스트 green (REQ-001~004/009 달성)

**Files:**
- Create: `scripts/run-parallel-e2e.sh`

**Interfaces:**
- Consumes: Task 1(에이전트 jar), Task 3(`parallelTesterTest`), Task 4(`ParallelCollectionE2E`).
- Produces: `build/parallel-e2e/{testwise_serial,testwise_forks,testwise_injvm}.json`, `timings.json`. 마지막에 `ParallelCollectionE2E` green.

- [ ] **Step 1: 오케스트레이터 작성**

```bash
#!/usr/bin/env bash
set -euo pipefail
# 단일 pjacoco SUT(fixture-app)에 대해 테스터를 직렬/forks/in-JVM 3모드로 구동, 각 모드 격리 destfile로 수집,
# tia convert로 testwise 생성, 마지막에 수용 비교 테스트(ParallelCollectionE2E) green 확인.
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
AGENT_JAR="$(bash scripts/setup-pjacoco.sh | sed -n 's/^PJACOCO_AGENT_JAR=//p')"
[ -n "$AGENT_JAR" ] || { echo "❌ pjacoco 에이전트 미해소"; exit 1; }

CLASSES="$PWD/fixture-app/build/classes/java/main"
OUT="$PWD/build/parallel-e2e"; rm -rf "$OUT"; mkdir -p "$OUT"
CTRL=6310; PORT=8080
./gradlew --no-daemon :fixture-app:bootJar :fixture-app:classes :tia-cli:installDist
CLI="$PWD/tia-cli/build/install/tia/bin/tia"
JAR="$(find fixture-app/build/libs -name 'fixture-app*.jar' -print -quit)"

run_mode() {  # $1 = serial|forks|injvm
  local mode="$1" cov="$OUT/cov-$mode"
  rm -rf "$cov"; mkdir -p "$cov"           # 실행별 격리 디렉터리 [REQ-009]
  # 스케줄러 노이즈 끔(@Profile 'noise' 비활성 — Task 6에서 NoiseScheduler를 프로필 게이트).
  "$JAVA_BIN" "-javaagent:$AGENT_JAR=destfile=$cov,port=$CTRL,aggregate=false,includes=io.tia.fixture.*" \
      -Dspring.profiles.active=e2e -jar "$JAR" --server.port=$PORT > "$OUT/sut-$mode.log" 2>&1 &
  local pid=$!
  for i in $(seq 1 40); do curl -sf "localhost:$PORT/price/F" >/dev/null 2>&1 && break || sleep 1; done
  local t0=$(date +%s%3N)
  ./gradlew --no-daemon :e2e:parallelTesterTest -Pparallel.mode="$mode" \
      "-Dfixture.baseUrl=http://localhost:$PORT" "-Dpjacoco.control-url=http://127.0.0.1:$CTRL"
  local t1=$(date +%s%3N)
  kill $pid 2>/dev/null || true; sleep 1
  "$CLI" convert --exec-dir "$cov" --classes "$CLASSES" --out "$OUT/testwise_$mode.json"
  echo "$mode:$((t1 - t0))"
}

declare -a T
T+=("$(run_mode serial)"); T+=("$(run_mode forks)"); T+=("$(run_mode injvm)")
# timings.json [REQ-004]
{ echo "{"; first=1; for kv in "${T[@]}"; do k="${kv%%:*}"; v="${kv##*:}";
    [ $first -eq 1 ] && first=0 || echo ","; printf '  "%s": %s' "$k" "$v"; done; echo; echo "}"; } > "$OUT/timings.json"
cat "$OUT/timings.json"

# 수용 비교 green 확인 [REQ-001~004/009]
./gradlew --no-daemon :e2e:test --tests 'io.tia.e2e.parallel.ParallelCollectionE2E' \
    "-Dtia.parallel.artifacts=$OUT"
echo "✅ parallel-e2e PASS"
```

- [ ] **Step 2: 실행 권한 + 전체 구동 (red→green)**

Run: `chmod +x scripts/run-parallel-e2e.sh && bash scripts/run-parallel-e2e.sh`
Expected: 세 모드 수집 완료, `timings.json` 출력, 마지막 `ParallelCollectionE2E` 4건 PASS, `✅ parallel-e2e PASS`.
(주: Task 6의 NoiseScheduler 프로필 게이트가 선행되어야 `-Dspring.profiles.active=e2e`로 노이즈가 꺼진다 — Task 6를 이 단계 전에 적용하거나, 임시로 includes에 의해 무해함을 확인.)

- [ ] **Step 3: Commit**

```bash
git add scripts/run-parallel-e2e.sh
git commit -m "feat(e2e): 병렬 수집 오케스트레이터(3모드→convert→수용비교) [REQ-001~004/009]"
```

---

## Task 6: fixture-app 노이즈 프로필 게이트 + 사용자 문서 (REQ-006/007)

**Files:**
- Modify: `fixture-app/src/main/java/io/tia/fixture/NoiseScheduler.java`
- Modify: `GETTING-STARTED.md`, `tia-gradle-plugin/README.md`

- [ ] **Step 1: NoiseScheduler를 프로필로 게이트 (E2E 결정성)**

```java
package io.tia.fixture;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")   // E2E(-Dspring.profiles.active=e2e)에서는 백그라운드 노이즈 비활성 — 직렬/병렬 잡음 차이 배제
public class NoiseScheduler {
    @Scheduled(fixedRate = 1000)
    public void tick() { TextUtil.normalize("background-noise"); }
}
```

- [ ] **Step 2: fixture-app 기존 테스트 회귀 확인**

Run: `./gradlew :fixture-app:test --no-daemon`
Expected: PASS (프로필 기본값에선 스케줄러 동작 유지; E2E에서만 비활성).

- [ ] **Step 3: GETTING-STARTED §1 out-of-process 갱신 (병렬 토폴로지·한계·aggregate·키)**

`GETTING-STARTED.md` out-of-process 항목 아래에 추가:

```markdown
> **병렬 수집(out-of-process).** pjacoco 에이전트를 **단일 SUT**에 부착하고 테스터를 병렬화하면
> per-test 수집을 병렬로 할 수 있다 — 테스터 포크/스레드가 모두 같은 SUT control 포트로 향하므로
> 충돌이 없고, pjacoco가 baggage `test.id`로 분리한다. 두 방식 모두 지원: Gradle `maxParallelForks>1`,
> JUnit 5 in-JVM 병렬(`junit.jupiter.execution.parallel.enabled=true`). **동기 HTTP 호출 기준**이며,
> 테스트가 작업을 자식 스레드/스레드풀로 위임하면 그 커버리지는 해당 test로 귀속되지 않을 수 있다.
> `aggregate=false`를 둔다(per-test만 소비) — pjacoco 플러그인은 `pjacoco { aggregate.set(false) }`,
> TIA 내장 헬퍼는 jvmarg `aggregate=false`. testId 키는 한 인덱스 안에서 한 형식만 쓴다
> (pjacoco `ClassName#method`).
> (TIA 내장 `attachCoverageAgent`는 에이전트를 Test JVM에 붙이는 **직렬** 브리지다 — 병렬은 위
> 단일-SUT 토폴로지를 쓴다.)
```

- [ ] **Step 4: plugin README §(a) 내장 헬퍼 문단 토폴로지 교정**

`tia-gradle-plugin/README.md` §(a)의 "대안 — TIA 내장 헬퍼" 문단을 교정: 이 헬퍼는 에이전트가 **Test JVM**에 붙는 **직렬·in-JVM 부착 브리지**(`maxParallelForks=1`)이며, 진짜 out-of-process **병렬**은 pjacoco 에이전트를 **단일 SUT**에 붙이고 테스터를 병렬화하는 토폴로지임을 명시. 고정 포트→단일 fork 설명은 "Test JVM 부착 시"로 한정.

- [ ] **Step 5: Commit**

```bash
git add fixture-app/src/main/java/io/tia/fixture/NoiseScheduler.java GETTING-STARTED.md tia-gradle-plugin/README.md
git commit -m "docs+fix: 병렬 수집 문서(토폴로지/한계/aggregate/키) + 노이즈 프로필 게이트 [REQ-006/007]"
```

---

## Task 7: 플러그인 Javadoc/주석 교정 + 단위 단언 (REQ-005)

**Files:**
- Modify: `tia-gradle-plugin/src/main/java/io/tia/gradle/TiaPlugin.java:76-88`
- Modify: `tia-gradle-plugin/src/main/java/io/tia/gradle/TiaArgs.java:46-54`
- Test: `tia-gradle-plugin/src/test/java/io/tia/gradle/TiaPluginTest.java`

**Interfaces:**
- Produces: 동작 변화 없음(핀 유지). Javadoc/주석 정합 + 명시 단언 `coverageHelperPinsSingleFork`.

- [ ] **Step 1: 실패(또는 명시) 단언 추가**

`TiaPluginTest`에 추가(기존 attach 헬퍼 테스트와 동일 패턴):

```java
@Test
void coverageHelperPinsSingleFork() {
    Project p = ProjectBuilder.builder().build();
    Test t = p.getTasks().create("itTest", Test.class);
    TiaPlugin.attachCoverageAgent(t, new File("/opt/agent.jar"), new File("/tmp/cov"), 6310, "com.acme.*");
    assertEquals(1, t.getMaxParallelForks(),
            "내장 헬퍼는 에이전트를 Test JVM에 붙이므로 직렬 유지(병렬은 단일-SUT 토폴로지)");
    assertEquals("http://127.0.0.1:6310", t.getSystemProperties().get("pjacoco.control-url"));
}
```

- [ ] **Step 2: 실행 (이미 통과해야 함 — 핀 유지)**

Run: `./gradlew :tia-gradle-plugin:test --tests 'io.tia.gradle.TiaPluginTest.coverageHelperPinsSingleFork' --no-daemon`
Expected: PASS (행동 변화 없음을 잠금).

- [ ] **Step 3: Javadoc/주석 교정 (행동 무변경)**

`TiaPlugin.java` `attachCoverageAgent` Javadoc: "(in-process model)" → "(out-of-process; agent on the Test JVM — serial bridge)". "control port is FIXED ... pins maxParallelForks=1" 설명은 유지하되 "이 헬퍼(Test JVM 부착) 한정이며, 병렬은 pjacoco 에이전트를 단일 SUT에 붙이는 토폴로지를 쓴다"를 한 줄 덧붙인다.
`TiaArgs.java` `coverageAgentJvmArg` 주석의 "test JVM with maxParallelForks=1" 설명에 동일 단서(병렬=단일 SUT 토폴로지) 추가.

- [ ] **Step 4: 플러그인 회귀**

Run: `./gradlew :tia-gradle-plugin:test --no-daemon`
Expected: PASS (전체).

- [ ] **Step 5: Commit**

```bash
git add tia-gradle-plugin/src/main/java/io/tia/gradle/TiaPlugin.java tia-gradle-plugin/src/main/java/io/tia/gradle/TiaArgs.java tia-gradle-plugin/src/test/java/io/tia/gradle/TiaPluginTest.java
git commit -m "docs+test: 내장 헬퍼 Javadoc 토폴로지 교정 + 직렬 핀 명시 단언 [REQ-005]"
```

---

## Task 8: CI job — parallel-e2e (REQ-008)

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: job 추가 (fail-not-skip)**

`ci.yml` `jobs:` 아래 추가:

```yaml
  parallel-e2e:
    name: Parallel collection E2E (pjacoco)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - name: Set up Gradle (cache)
        uses: gradle/actions/setup-gradle@v4
      - name: pjacoco 해소 + 병렬 E2E (실패 시 fail — skip 아님)
        env:
          PJACOCO_REF: main
        run: bash scripts/run-parallel-e2e.sh
```

- [ ] **Step 2: 로컬에서 스크립트 경로 동등 구동 확인**

Run: `bash scripts/run-parallel-e2e.sh`
Expected: `✅ parallel-e2e PASS` (CI job이 실행할 동일 경로).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: 병렬 수집 E2E job(pjacoco 소스 해소, fail-not-skip) [REQ-008]"
```

---

## 최종 검증 (PR 전)
- [ ] 전체 회귀: `./gradlew test --no-daemon` (기존 스위트 green; 병렬 태그는 제외됨).
- [ ] 병렬 E2E: `bash scripts/run-parallel-e2e.sh` → `✅ parallel-e2e PASS`.
- [ ] 요구사항 매트릭스 100% green 갱신(REQ-001~009), 각 green REQ ↔ 실제 통과 테스트(@DisplayName) 대조.
- [ ] 문서 동기화(GETTING-STARTED/plugin README) 포함.

---

## Self-Review (작성자 점검)

**1. Spec coverage (REQ ↔ task):**
- REQ-001 forks==직렬 → Task 2(정규화기)+3(테스터)+5(오케)+4(단언 `forksParallelMatchesSerial`).
- REQ-002 in-JVM==직렬 → Task 3(injvm 모드)+5+4(`inJvmParallelMatchesSerial`).
- REQ-003 non-empty → Task 4(`everyTestHasNonEmptyCoverage`).
- REQ-004 벽시계 기록 → Task 5(timings.json)+4(`recordsWallClockPerMode`).
- REQ-005 헬퍼 직렬 유지 → Task 7(`coverageHelperPinsSingleFork`).
- REQ-006 동기-호출 한계 문서 → Task 6 §3/§4.
- REQ-007 병렬 와이어링 문서 → Task 6 §3/§4.
- REQ-008 CI 의존 해소 fail-not-skip → Task 1 + Task 8.
- REQ-009 격리 출력·동일 classes → Task 5(cov-<mode> 격리, 동일 CLASSES)+4(`recordsWallClockPerMode` 키셋 동일).
모든 REQ에 대응 task 존재.

**2. Placeholder scan:** 모든 step에 실제 코드/명령/기대값 기재. "적절히 처리" 류 없음.

**3. Type consistency:** `TestwiseNormalizer.normalize(Path)->Map<String,Map<String,RoaringBitmap>>`가 Task 2 정의 ↔ Task 4 소비 일치. `parallelTesterTest`/`-Pparallel.mode`/`tia.parallel.artifacts`/`fixture.baseUrl`/`pjacoco.control-url` 명칭이 Task 3/4/5에서 일치. 에이전트 옵션 `destfile/port/aggregate=false/includes`가 spec/TiaArgs 계약과 일치.

**의존 순서:** 1 → 2 → 3 → 4 → 5(여기서 REQ-001~004/009 green) ; 6은 5 이전 적용 권장(노이즈 게이트) ; 7·8은 독립(8은 1·5 선행). 권장 실행 순서: 1, 2, 3, 6, 4, 5, 7, 8.

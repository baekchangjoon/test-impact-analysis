# TIA PoC Implementation Plan

> ✅ **상태: PoC 완료 (2026-06-13).** 전 모듈 구현 + 단위/수용 테스트 33건 GREEN + 컨테이너 out-of-process E2E GREEN(실제 teamscale 에이전트 36.5.2 수집→convert→index→impact, `testPrice` DETERMINISTIC 선별·`testGreeting` 제외) + PIT mutation testing 적용 + GitHub Actions CI 구성. **미실행 1건**: Task 18 Step 2(플레이키 라이브 N회 측정 — 호스트 샌드박스 네트워크 제약으로 deferred, 로직은 단위 테스트로 검증). 외부 실제 레포 스모크(Task 19)는 런북 작성 완료, 실행은 외부 환경 대상. 상세는 문서 말미 **Self-Review(4차)** 참조.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 단일 레포에서 teamscale-jacoco-agent로 per-test 커버리지를 수집하고, git diff 변경 라인과 교차해 **영향 테스트를 선별**하며, 스위트의 **플레이키 비율을 측정**하는 동작하는 PoC를 만든다.

**Architecture:** Java 17 + Gradle 멀티모듈. 순수 로직(`tia-core`: 파싱·교차·저장·플레이키)은 결정론적 단위 테스트로 TDD, CLI(`tia-cli`)가 이를 묶고, 테스트 측 신호용 JUnit5 확장(`tia-junit-extension`, Java 8 호환)이 에이전트를 제어한다. in-repo 최소 Spring Boot 앱(`fixture-app`)으로 파이프라인 전체를 E2E 검증하고, 외부 실제 레포는 스모크 런북으로 확인한다. 설계 결정 **1-A(비코드 변경 보수적 폴백)**, **1-B(신규 코드 저신뢰)**, **D11(라인 정밀도)**를 코드로 구현·검증한다.

**Tech Stack:** Java 17(확장 모듈 main만 Java 8 타깃, 테스트는 17), Gradle 8.x, JaCoCo/teamscale-jacoco-agent(testwise), JUnit5, RestAssured, picocli, Jackson, sqlite-jdbc, RoaringBitmap, Spring Boot 3.x.

**참조 설계 문서:** `docs/superpowers/specs/2026-06-13-test-impact-analysis-design.md` (§1.3, §3.0, §3.1, §6.2, §9, D11, D12)

**의도적 defer (설계 대비):** 아래는 설계엔 있으나 현재 PoC 범위에서 의식적으로 미룬 항목이다.
- **D8 `CoverageCollector` 드롭인 seam**: 현재 PoC는 직렬 전용이라 인터페이스를 도입하지 않는다. 단 산출물을 정규화된 `(test_id, file, line_bitmap)` 계약 형태로 내보내므로, 병렬 에이전트(`jacocoagent-parallel.jar`)는 이 계약만 구현하면 교체된다.
- **§6.2 [4-B] 라인 재조정**: 현재 PoC는 "diff old-side 라인 공간 == 인덱싱 커밋 공간"을 **불변식**으로 두고 라인 재조정을 생략한다. `tia impact`의 diff 베이스 기본값이 `--commit`(인덱싱 커밋)이라 자동 정렬되며, 베이스가 달라지면 비트 AND가 무효(Task 13 기본값·런북 step 6 주의).
- **§6.5 staleness 저신뢰**: "매핑 기준 커밋 ↔ HEAD 변경분 저신뢰" 플래그는 이후 확장. 현재 신뢰도 enum은 `DETERMINISTIC`/`CONSERVATIVE`만 사용하고 **`LOW_CONFIDENCE`는 이후 staleness용으로 예약**(현재 미사용). 신규 .java 파일은 과거 커버리지·정적 그래프가 모두 없어 `CONSERVATIVE`(보수적 전체 선택)로 처리한다(§1.3 [1-B] 안전판).
- **§3.4 노이즈 베이스라인 차감(D2)**: 현재 PoC는 차감을 구현하지 않고 **관찰만** 한다(Task 19 체크리스트의 1-C). 픽스처 `@Scheduled`→`TextUtil` 노이즈로 `TextUtil.java` 커버가 실행마다 흔들릴 수 있으나, E2E 어서션은 `PricingService` 대상이라 영향 없음.
- **§3.3 D1 watchdog/'오염' 플래그**: 확장의 경계 보장은 `beforeEach`→`/test/start`(reset 우선)로 충족하나, afterEach try/finally·max-duration watchdog·오염 플래그는 현재 미구현(부분).
- **§7.1 PR 코멘트 이원화(D5)**: 현재 PoC는 CLI 텍스트 출력. PR Action은 이후 확장. MCP provenance 어휘(§7.2)도 이후 매핑(`LOW_CONFIDENCE→INFERRED`, staleness→`STALE`).

---

## File Structure

```
test-impact-analysis/
├── settings.gradle                  # 모듈 선언
├── build.gradle                     # 공통 java/test 설정
├── .gitignore
├── tia-core/                        # 순수 로직 + SQLite (외부 의존 없는 결정론 단위)
│   ├── build.gradle
│   └── src/main/java/io/tia/core/
│   │   ├── model/TestCoverage.java
│   │   ├── model/CoverageSnapshot.java
│   │   ├── model/DiffSummary.java
│   │   ├── model/Confidence.java
│   │   ├── model/ImpactedTest.java
│   │   ├── model/ImpactResult.java
│   │   ├── path/PathNormalizer.java
│   │   ├── parse/LineRangeParser.java
│   │   ├── parse/TestwiseReportParser.java
│   │   ├── parse/GitDiffParser.java
│   │   ├── impact/ImpactAnalyzer.java
│   │   ├── flaky/RunResult.java
│   │   ├── flaky/FlakyReport.java
│   │   ├── flaky/FlakyAnalyzer.java
│   │   └── store/CoverageStore.java
│   └── src/test/java/io/tia/core/...  (+ src/test/resources/sample-testwise.json)
├── tia-cli/                         # picocli CLI: index / impact / flaky
│   ├── build.gradle
│   └── src/main/java/io/tia/cli/{TiaCommand,IndexCommand,ImpactCommand,FlakyCommand,Main}.java
│   └── src/test/java/io/tia/cli/...
├── tia-junit-extension/             # 테스트 JVM에서 에이전트 제어 (Java 8 호환)
│   ├── build.gradle
│   └── src/main/java/io/tia/junit/{AgentClient,HttpAgentClient,TeamscaleTestwiseExtension}.java
│   └── src/test/java/io/tia/junit/...
├── fixture-app/                     # in-repo 검증 대상 Spring Boot 앱 + RestAssured 스위트
│   ├── build.gradle
│   ├── src/main/java/io/tia/fixture/{FixtureApplication,TextUtil,GreetingService,PricingService,ApiController,NoiseScheduler}.java
│   └── src/test/java/io/tia/fixture/{ApiSmokeTest,RunResultWriter}.java
├── scripts/{run-poc.sh,measure-flaky.sh,download-agent.sh}
├── tools/   # 다운로드된 teamscale-jacoco-agent jar (gitignore)
└── docs/superpowers/plans/2026-06-13-tia-phase0-poc.md
```

**모듈 경계 근거:** `tia-core`는 외부 프로세스/네트워크 의존이 전혀 없어 빠르고 결정론적인 TDD가 가능(파싱·교차·저장·플레이키). `tia-junit-extension`은 레거시 테스트 스위트(Java 8)에도 붙어야 하므로 별도 모듈로 분리하고 Java 8 바이트코드를 타깃한다. `fixture-app`은 멀티프로세스 E2E의 대상이며 단위 테스트 모듈과 섞이지 않는다.

---

## Task 1: Gradle 멀티모듈 스캐폴드

**Files:**
- Create: `settings.gradle`, `build.gradle`, `.gitignore`
- Create: `tia-core/build.gradle`, `tia-cli/build.gradle`, `tia-junit-extension/build.gradle`, `fixture-app/build.gradle`

- [x] **Step 1: settings.gradle 작성**

`settings.gradle`:
```groovy
rootProject.name = 'test-impact-analysis'
include 'tia-core', 'tia-cli', 'tia-junit-extension', 'fixture-app', 'e2e'
```

- [x] **Step 2: 루트 build.gradle 작성**

`build.gradle`:
```groovy
subprojects {
    apply plugin: 'java'
    group = 'io.tia'
    version = '0.1.0'
    repositories { mavenCentral() }
    java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
    tasks.withType(Test).configureEach { useJUnitPlatform() }
}
```

- [x] **Step 3: .gitignore 작성**

`.gitignore`:
```
.gradle/
build/
*.db
tools/
/poc-out/
*.exec
.idea/
```

- [x] **Step 4: 모듈 build.gradle 4개 작성**

`tia-core/build.gradle`:
```groovy
dependencies {
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.2'
    implementation 'org.roaringbitmap:RoaringBitmap:1.2.1'
    implementation 'org.xerial:sqlite-jdbc:3.46.1.3'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'
}
```

`tia-cli/build.gradle`:
```groovy
plugins { id 'application' }
dependencies {
    implementation project(':tia-core')
    implementation 'info.picocli:picocli:4.7.6'
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.2'   // FlakyCommand가 직접 사용 (tia-core는 implementation이라 미전파)
    annotationProcessor 'info.picocli:picocli-codegen:4.7.6'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'
    testImplementation 'org.roaringbitmap:RoaringBitmap:1.2.1'   // ImpactCommandTest가 직접 사용
}
application { mainClass = 'io.tia.cli.Main' }
```

`tia-junit-extension/build.gradle`:
```groovy
// 레거시(Java 8) 호환은 '배포되는 main 코드'에만 필요 — 테스트는 toolchain 17 유지.
// (전체 JavaCompile에 걸면 테스트의 List.of 등 Java 9+ API가 --release 8에서 컴파일 실패.)
tasks.named('compileJava') { options.release = 8 }
dependencies {
    compileOnly 'org.junit.jupiter:junit-jupiter-api:5.11.0'   // 소비 스위트가 JUnit 런타임 제공
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'
    testImplementation 'org.mockito:mockito-core:5.12.0'
}
```

`fixture-app/build.gradle`:
```groovy
plugins { id 'org.springframework.boot' version '3.3.4'; id 'io.spring.dependency-management' version '1.1.6' }
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    testImplementation project(':tia-junit-extension')
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'io.rest-assured:rest-assured:5.5.0'
    testImplementation 'com.fasterxml.jackson.core:jackson-databind:2.17.2'
}
tasks.named('test') { systemProperties System.getProperties().findAll { it.key.toString().startsWith('tia.') || it.key.toString().startsWith('fixture.') } }
```

- [x] **Step 5: Gradle wrapper 생성 + 빌드 확인**

Run: `gradle wrapper --gradle-version 8.10 && ./gradlew projects`
Expected: 루트 아래 `:tia-core`, `:tia-cli`, `:tia-junit-extension`, `:fixture-app` 4개 프로젝트가 출력됨.
(전제: 래퍼 부트스트랩에 시스템 `gradle`가 필요. 없으면 `brew install gradle`(macOS) 후 진행하거나, 기존 래퍼가 있는 프로젝트의 `gradle/wrapper/` + `gradlew`를 복사해 `./gradlew`로 부트스트랩.)

- [x] **Step 6: Commit**

```bash
git add settings.gradle build.gradle .gitignore gradlew gradlew.bat gradle tia-core/build.gradle tia-cli/build.gradle tia-junit-extension/build.gradle fixture-app/build.gradle
git commit -m "build: Gradle 멀티모듈 스캐폴드 (tia-core/cli/junit-extension/fixture-app)"
```

---

## Task 2: teamscale-jacoco-agent 다운로드 + REST API 검증

이 태스크는 **외부 도구의 정확한 엔드포인트/리포트 산출 방식을 사용 버전에 맞게 핀다.** 이후 `AgentClient`와 스크립트가 여기서 확정한 값을 참조한다.

**Files:**
- Create: `scripts/download-agent.sh`
- Create: `docs/superpowers/plans/notes/agent-api.md` (검증 결과 기록)

- [x] **Step 1: 다운로드 스크립트 작성**

`scripts/download-agent.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
VERSION="${TEAMSCALE_AGENT_VERSION:-36.5.2}"   # 검증 2026-06-13: 레포 이전 → cqse/teamscale-java-profiler
DEST="tools"
mkdir -p "$DEST"
ZIP="$DEST/teamscale-jacoco-agent-${VERSION}.zip"
if [ ! -f "$ZIP" ]; then
  curl -fsSL -o "$ZIP" \
    "https://github.com/cqse/teamscale-java-profiler/releases/download/v${VERSION}/teamscale-jacoco-agent.zip"   # 자산명 버전 무관, jar는 .../lib/teamscale-jacoco-agent.jar
fi
unzip -o "$ZIP" -d "$DEST/teamscale-${VERSION}" >/dev/null
AGENT_JAR="$(find "$DEST/teamscale-${VERSION}" -name 'teamscale-jacoco-agent.jar' -print -quit)"
echo "AGENT_JAR=$AGENT_JAR"
```

- [x] **Step 2: 다운로드 실행 + 에이전트 위치 확인**

Run: `bash scripts/download-agent.sh`
Expected: `AGENT_JAR=tools/teamscale-.../teamscale-jacoco-agent.jar` 출력. 파일 존재.
(릴리스 URL/zip 내부 경로가 다르면 버전을 조정하고 `find tools -name '*.jar'`로 실제 jar 경로를 확인한다.)

- [x] **Step 3: REST API + 리포트 산출 방식 검증**

임의의 작은 자바 프로그램(또는 다음 Task의 fixture jar)을 에이전트와 함께 띄워 testwise 제어를 확인한다. 우선 에이전트 옵션 도움말로 모드/포트/출력 옵션을 확인:

Run: `java -jar tools/teamscale-*/teamscale-jacoco-agent.jar --help 2>&1 | head -50` (도움말이 없으면 README/docs 참조)

확인할 항목(기록 대상):
1. testwise 모드 활성 옵션 (예: `mode=testwise`)
2. HTTP 제어 포트 옵션 (예: `http-server-port=8123`)
3. 테스트 시작/종료 엔드포인트 verb·경로 (예: `POST /test/start/{uniformPath}`, `POST /test/end/{uniformPath}`)
4. testwise 커버리지 리포트 산출 위치/방식 (out 디렉터리 파일 vs `GET /testwise-coverage` vs `/dump`)

- [x] **Step 4: 검증 결과 기록**

`docs/superpowers/plans/notes/agent-api.md`에 위 4항목의 **실제 값**을 적는다. 예시(검증 후 실제값으로 대체):
```
AGENT_VERSION=34.1.5
TESTWISE_OPTS=mode=testwise,includes=io.tia.fixture.*,http-server-port=8123,out=poc-out/coverage
START=POST http://localhost:8123/test/start/{uniformPath}
END  =POST http://localhost:8123/test/end/{uniformPath}   (body: {"result":"PASSED|FAILED"})
REPORT=poc-out/coverage/testwise-coverage-*.json   (또는 GET /testwise-coverage)
```

- [x] **Step 5: Commit**

```bash
git add scripts/download-agent.sh docs/superpowers/plans/notes/agent-api.md
git commit -m "build: teamscale-jacoco-agent 다운로드 스크립트 + REST API 검증 기록"
```

---

## Task 3: fixture-app — 최소 Spring Boot 대상 앱

공유 유틸(`TextUtil`)을 여러 서비스와 `@Scheduled` 노이즈가 함께 사용하도록 설계해, 추후 노이즈 한계(설계 1-C)를 관찰할 수 있게 한다. `/flaky`는 의도적으로 비결정적이다(플레이키 측정 검증용).

**Files:**
- Create: `fixture-app/src/main/java/io/tia/fixture/FixtureApplication.java`
- Create: `fixture-app/src/main/java/io/tia/fixture/TextUtil.java`
- Create: `fixture-app/src/main/java/io/tia/fixture/GreetingService.java`
- Create: `fixture-app/src/main/java/io/tia/fixture/PricingService.java`
- Create: `fixture-app/src/main/java/io/tia/fixture/ApiController.java`
- Create: `fixture-app/src/main/java/io/tia/fixture/NoiseScheduler.java`

- [x] **Step 1: 앱 클래스 6개 작성**

`FixtureApplication.java`:
```java
package io.tia.fixture;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FixtureApplication {
    public static void main(String[] args) { SpringApplication.run(FixtureApplication.class, args); }
}
```

`TextUtil.java` (공유 유틸 — 여러 서비스 + 스케줄러가 사용):
```java
package io.tia.fixture;

public final class TextUtil {
    private TextUtil() {}
    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}
```

`GreetingService.java`:
```java
package io.tia.fixture;
import org.springframework.stereotype.Service;

@Service
public class GreetingService {
    public String greet(String name) {
        return "hello " + TextUtil.normalize(name);
    }
}
```

`PricingService.java`:
```java
package io.tia.fixture;
import org.springframework.stereotype.Service;

@Service
public class PricingService {
    public int priceOf(String sku) {
        String key = TextUtil.normalize(sku);
        return key.length() * 100;   // 결정론적 더미 가격
    }
}
```

`ApiController.java`:
```java
package io.tia.fixture;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class ApiController {
    private final GreetingService greeting;
    private final PricingService pricing;

    public ApiController(GreetingService greeting, PricingService pricing) {
        this.greeting = greeting;
        this.pricing = pricing;
    }

    @GetMapping("/greeting/{name}")
    public String greeting(@PathVariable String name) { return greeting.greet(name); }

    @GetMapping("/price/{sku}")
    public int price(@PathVariable String sku) { return pricing.priceOf(sku); }

    @GetMapping("/flaky")
    public ResponseEntity<String> flaky() {                 // 의도적 비결정 — 플레이키 측정용
        boolean ok = ThreadLocalRandom.current().nextBoolean();
        return ok ? ResponseEntity.ok("ok") : ResponseEntity.status(500).body("boom");
    }
}
```

`NoiseScheduler.java` (백그라운드 노이즈 — 공유 유틸을 건드림):
```java
package io.tia.fixture;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NoiseScheduler {
    @Scheduled(fixedRate = 1000)
    public void tick() { TextUtil.normalize("background-noise"); }
}
```

- [x] **Step 2: 앱 부팅 확인**

Run: `./gradlew :fixture-app:bootJar && java -jar fixture-app/build/libs/fixture-app-0.1.0.jar --server.port=8080 &` 후 `sleep 8 && curl -s localhost:8080/greeting/Alice`
Expected: `hello alice` 출력. (확인 후 `kill %1`)

- [x] **Step 3: Commit**

```bash
git add fixture-app/src/main/java/io/tia/fixture
git commit -m "feat(fixture): 최소 Spring Boot 대상 앱 (공유 TextUtil + 노이즈 스케줄러 + /flaky)"
```

---

## Task 4: 실제 testwise 리포트 캡처 → 테스트 리소스화

파서를 **실제 리포트 스키마**에 맞춰 TDD하기 위해, 에이전트가 산출한 진짜 리포트를 캡처해 고정한다.

**Files:**
- Create: `tia-core/src/test/resources/sample-testwise.json`

- [x] **Step 1: 에이전트 부착 후 수동 testwise 사이클 1회 실행**

Task 2에서 기록한 옵션/엔드포인트를 사용한다. 예시(agent-api.md 실제값으로 대체):
```bash
AGENT_JAR=$(find tools -name teamscale-jacoco-agent.jar -print -quit)
mkdir -p poc-out/coverage
java "-javaagent:$AGENT_JAR=mode=testwise,includes=io.tia.fixture.*,http-server-port=8123,out=poc-out/coverage" \
  -jar fixture-app/build/libs/fixture-app-0.1.0.jar --server.port=8080 &
sleep 8
# uniformPath는 %2F 인코딩 (teamscale {testId} 단일 세그먼트 — 실측: raw 슬래시=HTTP 500, %2F=204).
curl -s -X POST localhost:8123/test/start/io%2Ftia%2Ffixture%2FApiSmokeTest%2FtestPrice
curl -s localhost:8080/price/ABC
curl -s -X POST localhost:8123/test/end/io%2Ftia%2Ffixture%2FApiSmokeTest%2FtestPrice -H 'Content-Type: application/json' -d '{"uniformPath":"io/tia/fixture/ApiSmokeTest/testPrice","durationMillis":5,"result":"PASSED"}'
# out= 산출물은 .exec + test-execution.json — testwise JSON은 convert로 변환:
kill %1; sleep 1
bin/convert --class-dir <classes> --in <out-dir> --testwise-coverage -o report.json   # 출력은 report-1.json
kill %1
```

- [x] **Step 2: 리포트를 테스트 리소스로 복사 + 형태 확인**

Run: `cp "$(ls -t poc-out/coverage/*.json | head -1)" tia-core/src/test/resources/sample-testwise.json && python3 -m json.tool tia-core/src/test/resources/sample-testwise.json | head -40`
Expected: `tests[].uniformPath`, `tests[].paths[].path`, `tests[].paths[].files[].fileName`, `tests[].paths[].files[].coveredLines` 형태 확인. `io/tia/fixture/PricingService.java`와 `io/tia/fixture/TextUtil.java`가 covered로 등장.
(실제 키 이름이 다르면 이 파일이 진실의 원천 — Task 7 파서를 이 파일에 맞춘다.)

- [x] **Step 3: Commit**

```bash
git add tia-core/src/test/resources/sample-testwise.json
git commit -m "test(fixture): 실제 teamscale testwise 리포트 캡처본을 파서 테스트 리소스로 고정"
```

---

## Task 5: tia-core 모델 타입

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/model/{TestCoverage,CoverageSnapshot,DiffSummary,Confidence,ImpactedTest,ImpactResult}.java`
- Test: `tia-core/src/test/java/io/tia/core/model/TestCoverageTest.java`

- [x] **Step 1: 실패 테스트 작성**

`tia-core/src/test/java/io/tia/core/model/TestCoverageTest.java`:
```java
package io.tia.core.model;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TestCoverageTest {
    @Test
    void linesFor_returnsBitmap_andEmptyWhenAbsent() {
        RoaringBitmap b = RoaringBitmap.bitmapOf(10, 11, 12);
        TestCoverage tc = new TestCoverage("T1", "PASSED", Map.of("A.java", b));
        assertTrue(tc.linesFor("A.java").contains(11));
        assertTrue(tc.covers("A.java"));
        assertFalse(tc.covers("B.java"));
        assertTrue(tc.linesFor("B.java").isEmpty());
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.model.TestCoverageTest`
Expected: FAIL — `TestCoverage` 클래스 없음(컴파일 에러).

- [x] **Step 3: 모델 6개 구현**

`TestCoverage.java`:
```java
package io.tia.core.model;
import org.roaringbitmap.RoaringBitmap;
import java.util.Map;

public record TestCoverage(String testId, String result, Map<String, RoaringBitmap> linesByFile) {
    public RoaringBitmap linesFor(String file) {
        return linesByFile.getOrDefault(file, new RoaringBitmap());
    }
    public boolean covers(String file) {
        RoaringBitmap b = linesByFile.get(file);
        return b != null && !b.isEmpty();
    }
}
```

`CoverageSnapshot.java`:
```java
package io.tia.core.model;
import java.util.List;

public record CoverageSnapshot(String repo, String commitSha, List<TestCoverage> tests) {}
```

`DiffSummary.java`:
```java
package io.tia.core.model;
import org.roaringbitmap.RoaringBitmap;
import java.util.Map;
import java.util.Set;

/** git diff 분석 결과. 라인은 모두 매핑 기준 커밋(old-side) 번호 공간. */
public record DiffSummary(
        Map<String, RoaringBitmap> changedOldLinesByJavaFile,  // 수정/삭제된 .java old-side 라인
        Set<String> additionOnlyJavaFiles,                     // 추가만 있는 .java (신규 코드)
        Set<String> unmappableFiles                            // 비-.java 변경(yml/sql/gradle 등)
) {}
```

`Confidence.java`:
```java
package io.tia.core.model;

public enum Confidence { DETERMINISTIC, LOW_CONFIDENCE, CONSERVATIVE }
```

`ImpactedTest.java`:
```java
package io.tia.core.model;

public record ImpactedTest(String testId, Confidence confidence) {}
```

`ImpactResult.java`:
```java
package io.tia.core.model;
import java.util.List;

public record ImpactResult(List<ImpactedTest> impacted, boolean conservativeSelectAll, List<String> reasons) {}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.model.TestCoverageTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-core/src/main/java/io/tia/core/model tia-core/src/test/java/io/tia/core/model
git commit -m "feat(core): 도메인 모델 (TestCoverage/CoverageSnapshot/DiffSummary/ImpactResult)"
```

---

## Task 6: LineRangeParser ("10-12,20" ↔ RoaringBitmap)

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/parse/LineRangeParser.java`
- Test: `tia-core/src/test/java/io/tia/core/parse/LineRangeParserTest.java`

- [x] **Step 1: 실패 테스트 작성**

`LineRangeParserTest.java`:
```java
package io.tia.core.parse;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;
import static org.junit.jupiter.api.Assertions.*;

class LineRangeParserTest {
    @Test
    void parsesRangesAndSingletons() {
        RoaringBitmap b = LineRangeParser.parse("10-12, 20 ,25-25");
        assertEquals(RoaringBitmap.bitmapOf(10, 11, 12, 20, 25), b);
    }
    @Test
    void blankYieldsEmpty() {
        assertTrue(LineRangeParser.parse("").isEmpty());
        assertTrue(LineRangeParser.parse(null).isEmpty());
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.parse.LineRangeParserTest`
Expected: FAIL — `LineRangeParser` 없음.

- [x] **Step 3: 구현**

`LineRangeParser.java`:
```java
package io.tia.core.parse;
import org.roaringbitmap.RoaringBitmap;

public final class LineRangeParser {
    private LineRangeParser() {}

    /** "10-12,20" 형태(teamscale coveredLines)를 RoaringBitmap으로. */
    public static RoaringBitmap parse(String ranges) {
        RoaringBitmap b = new RoaringBitmap();
        if (ranges == null || ranges.trim().isEmpty()) return b;
        for (String part : ranges.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int dash = p.indexOf('-');
            if (dash < 0) {
                b.add(Integer.parseInt(p));
            } else {
                int lo = Integer.parseInt(p.substring(0, dash).trim());
                int hi = Integer.parseInt(p.substring(dash + 1).trim());
                b.add((long) lo, (long) hi + 1);   // add(start,end)은 [start,end)
            }
        }
        return b;
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.parse.LineRangeParserTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-core/src/main/java/io/tia/core/parse/LineRangeParser.java tia-core/src/test/java/io/tia/core/parse/LineRangeParserTest.java
git commit -m "feat(core): LineRangeParser (coveredLines 범위 문자열 → RoaringBitmap)"
```

---

## Task 6B: PathNormalizer (경로 네임스페이스 정규화)

git diff는 **레포 상대 경로**(`fixture-app/src/main/java/io/tia/fixture/Foo.java`)를, testwise 리포트는 **패키지 상대 경로**(`io/tia/fixture/Foo.java`)를 내놓는다. `ImpactAnalyzer`는 파일 키를 **정확 일치**로 교차하므로, 두 경로를 **공통 정규형**으로 변환하지 않으면 실제 git diff와 커버리지가 절대 교차하지 않는다(단위 테스트는 키를 손으로 맞춰 이 갭을 가린다 — 그래서 E2E에서만 터진다). PathNormalizer가 **두 파서의 출력 키를 패키지 상대형으로 통일**한다.

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/path/PathNormalizer.java`
- Test: `tia-core/src/test/java/io/tia/core/path/PathNormalizerTest.java`

- [x] **Step 1: 실패 테스트 작성**

`PathNormalizerTest.java`:
```java
package io.tia.core.path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathNormalizerTest {
    @Test void stripsModuleAndSourceRoot() {   // 레포 상대(실제 git diff) → 패키지 상대(커버리지 키)
        assertEquals("io/tia/fixture/PricingService.java",
            PathNormalizer.canonical("fixture-app/src/main/java/io/tia/fixture/PricingService.java"));
    }
    @Test void leavesAlreadyCanonicalUnchanged() {   // testwise 리포트는 이미 패키지 상대 → 무변경
        assertEquals("io/tia/fixture/PricingService.java",
            PathNormalizer.canonical("io/tia/fixture/PricingService.java"));
    }
    @Test void stripsTestRootAndResources() {
        assertEquals("io/tia/fixture/ApiSmokeTest.java",
            PathNormalizer.canonical("src/test/java/io/tia/fixture/ApiSmokeTest.java"));
        assertEquals("application.yml",
            PathNormalizer.canonical("module/src/main/resources/application.yml"));
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.path.PathNormalizerTest`
Expected: FAIL — `PathNormalizer` 없음.

- [x] **Step 3: 구현**

`PathNormalizer.java`:
```java
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
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.path.PathNormalizerTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-core/src/main/java/io/tia/core/path/PathNormalizer.java tia-core/src/test/java/io/tia/core/path/PathNormalizerTest.java
git commit -m "feat(core): PathNormalizer (git diff↔커버리지 경로 네임스페이스 정규화)"
```

---

## Task 7: TestwiseReportParser (실제 캡처본 기반)

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/parse/TestwiseReportParser.java`
- Test: `tia-core/src/test/java/io/tia/core/parse/TestwiseReportParserTest.java`

- [x] **Step 1: 실패 테스트 작성 (캡처본 + 인라인 최소 샘플)**

`TestwiseReportParserTest.java`:
```java
package io.tia.core.parse;
import io.tia.core.model.TestCoverage;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestwiseReportParserTest {
    private static final String SAMPLE = """
        {"tests":[
          {"uniformPath":"io/tia/fixture/ApiSmokeTest/testPrice","result":"PASSED",
           "paths":[{"path":"io/tia/fixture","files":[
             {"fileName":"PricingService.java","coveredLines":"8-10"},
             {"fileName":"TextUtil.java","coveredLines":"6"}]}]}
        ]}""";

    @Test
    void parsesTestsFilesAndLines() {
        InputStream in = new ByteArrayInputStream(SAMPLE.getBytes(StandardCharsets.UTF_8));
        List<TestCoverage> tests = new TestwiseReportParser().parse(in);
        assertEquals(1, tests.size());
        TestCoverage t = tests.get(0);
        assertEquals("io/tia/fixture/ApiSmokeTest/testPrice", t.testId());
        assertTrue(t.linesFor("io/tia/fixture/PricingService.java").contains(9));
        assertTrue(t.covers("io/tia/fixture/TextUtil.java"));
    }

    @Test
    void parsesRealCapturedReport() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/sample-testwise.json")) {
            assertNotNull(in, "Task 4에서 캡처한 sample-testwise.json 필요");
            List<TestCoverage> tests = new TestwiseReportParser().parse(in);
            assertFalse(tests.isEmpty(), "캡처본에 최소 1개 테스트가 있어야 함");
        }
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.parse.TestwiseReportParserTest`
Expected: FAIL — `TestwiseReportParser` 없음.

- [x] **Step 3: 구현**

`TestwiseReportParser.java`:
```java
package io.tia.core.parse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tia.core.model.TestCoverage;
import io.tia.core.path.PathNormalizer;
import org.roaringbitmap.RoaringBitmap;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.*;

public final class TestwiseReportParser {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<TestCoverage> parse(InputStream json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<TestCoverage> out = new ArrayList<>();
            for (JsonNode test : root.path("tests")) {
                String id = test.path("uniformPath").asText();
                String result = test.path("result").asText("UNKNOWN");
                Map<String, RoaringBitmap> byFile = new LinkedHashMap<>();
                for (JsonNode path : test.path("paths")) {
                    String dir = path.path("path").asText("");
                    for (JsonNode file : path.path("files")) {
                        String fn = file.path("fileName").asText();
                        String full = PathNormalizer.canonical(dir.isEmpty() ? fn : dir + "/" + fn);  // 정규형 키
                        RoaringBitmap lines = LineRangeParser.parse(file.path("coveredLines").asText(""));
                        byFile.merge(full, lines, (a, b) -> { a.or(b); return a; });
                    }
                }
                out.add(new TestCoverage(id, result, byFile));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```
(캡처본의 실제 키가 `path`/`fileName`/`coveredLines`와 다르면 여기 4개 키 이름을 맞춘다.)

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.parse.TestwiseReportParserTest`
Expected: PASS (두 테스트 모두).

- [x] **Step 5: Commit**

```bash
git add tia-core/src/main/java/io/tia/core/parse/TestwiseReportParser.java tia-core/src/test/java/io/tia/core/parse/TestwiseReportParserTest.java
git commit -m "feat(core): TestwiseReportParser (teamscale testwise JSON → TestCoverage)"
```

---

## Task 8: GitDiffParser (unified diff → DiffSummary)

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/parse/GitDiffParser.java`
- Test: `tia-core/src/test/java/io/tia/core/parse/GitDiffParserTest.java`

- [x] **Step 1: 실패 테스트 작성**

`GitDiffParserTest.java`:
```java
package io.tia.core.parse;
import io.tia.core.model.DiffSummary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitDiffParserTest {
    // 실제 git diff처럼 '레포 상대' 경로 사용 → PathNormalizer가 패키지 상대로 정규화됨을 함께 검증.
    // .java 수정(old 9,10 변경), .yml 변경(매핑 불가), .java 순수 추가
    private static final String DIFF = """
        diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
        --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
        +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
        @@ -8,3 +8,3 @@ public class PricingService {
         public int priceOf(String sku) {
        -    String key = TextUtil.normalize(sku);
        -    return key.length() * 100;
        +    String key = TextUtil.normalize(sku);
        +    return key.length() * 200;
        diff --git a/fixture-app/src/main/resources/application.yml b/fixture-app/src/main/resources/application.yml
        --- a/fixture-app/src/main/resources/application.yml
        +++ b/fixture-app/src/main/resources/application.yml
        @@ -1,2 +1,2 @@
        -server.port: 8080
        +server.port: 9090
        diff --git a/fixture-app/src/main/java/io/tia/fixture/NewFeature.java b/fixture-app/src/main/java/io/tia/fixture/NewFeature.java
        --- /dev/null
        +++ b/fixture-app/src/main/java/io/tia/fixture/NewFeature.java
        @@ -0,0 +1,2 @@
        +package io.tia.fixture;
        +public class NewFeature {}
        """;

    @Test
    void normalizesRepoRelativePathsAndClassifies() {
        DiffSummary d = new GitDiffParser().parse(DIFF);

        // 레포 상대 → 패키지 상대 정규형 키(커버리지 키와 동일 공간), old-side 9,10 라인
        var changed = d.changedOldLinesByJavaFile().get("io/tia/fixture/PricingService.java");
        assertNotNull(changed, "정규화 후 패키지 상대 키여야 함");
        assertTrue(changed.contains(9) && changed.contains(10));

        // 비-.java → unmappable (1-A)
        assertTrue(d.unmappableFiles().contains("application.yml"));

        // 순수 추가 .java → additionOnly (1-B), changed map엔 없음
        assertTrue(d.additionOnlyJavaFiles().contains("io/tia/fixture/NewFeature.java"));
        assertFalse(d.changedOldLinesByJavaFile().containsKey("io/tia/fixture/NewFeature.java"));
    }
}
```
**주의(텍스트 블록 취약성, [D4]):** diff의 context 라인(` public int priceOf...`)은 **선행 공백 1칸**이 의미를 가진다(unified diff의 context 마커). Java 텍스트 블록의 incidental-whitespace 제거가 이 공백을 지우지 않도록, 닫는 `"""`를 content 본문과 **같은 들여쓰기**에 두고 `diff`/`---`/`+++`/`@@`/`-`/`+` 라인을 context 라인보다 한 칸 더 들여쓰지 말 것. 재포맷 시 파싱이 깨진다.

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.parse.GitDiffParserTest`
Expected: FAIL — `GitDiffParser` 없음.

- [x] **Step 3: 구현**

`GitDiffParser.java`:
```java
package io.tia.core.parse;
import io.tia.core.model.DiffSummary;
import io.tia.core.path.PathNormalizer;
import org.roaringbitmap.RoaringBitmap;
import java.util.*;
import java.util.regex.*;

public final class GitDiffParser {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+\\d+(?:,\\d+)? @@");

    public DiffSummary parse(String diff) {
        Map<String, RoaringBitmap> changedJava = new LinkedHashMap<>();
        Set<String> unmappable = new LinkedHashSet<>();
        Map<String, Boolean> hadOldChange = new LinkedHashMap<>();  // file -> '-'(삭제/수정) 존재 여부
        Map<String, Boolean> hadAnyChange = new LinkedHashMap<>();  // file -> 변경 존재 여부

        String file = null;
        int oldLine = 0;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git")) {
                file = null; oldLine = 0;
            } else if (line.startsWith("+++ ")) {
                String p = line.substring(4).trim();
                if (!p.equals("/dev/null")) file = PathNormalizer.canonical(stripPrefix(p));  // 'b/' 제거 + 정규화
            } else if (line.startsWith("--- ")) {
                if (file == null) {
                    String p = line.substring(4).trim();
                    if (!p.equals("/dev/null")) file = PathNormalizer.canonical(stripPrefix(p));
                }
            } else if (line.startsWith("@@")) {
                Matcher m = HUNK.matcher(line);
                if (m.find()) oldLine = Integer.parseInt(m.group(1));
            } else if (file != null) {
                if (line.startsWith("-") && !line.startsWith("---")) {
                    if (isJava(file)) changedJava.computeIfAbsent(file, k -> new RoaringBitmap()).add(oldLine);
                    hadOldChange.put(file, true); hadAnyChange.put(file, true);
                    oldLine++;
                } else if (line.startsWith("+") && !line.startsWith("+++")) {
                    hadAnyChange.put(file, true);            // new-side: oldLine 미증가
                } else {
                    oldLine++;                               // context
                }
            }
        }

        Set<String> additionOnly = new LinkedHashSet<>();
        for (String f : hadAnyChange.keySet()) {
            if (isJava(f)) {
                if (!hadOldChange.getOrDefault(f, false)) additionOnly.add(f);  // '+'만 존재
            } else {
                unmappable.add(f);
            }
        }
        return new DiffSummary(changedJava, additionOnly, unmappable);
    }

    private static boolean isJava(String f) { return f.endsWith(".java"); }
    private static String stripPrefix(String p) {
        return (p.startsWith("a/") || p.startsWith("b/")) ? p.substring(2) : p;
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.parse.GitDiffParserTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-core/src/main/java/io/tia/core/parse/GitDiffParser.java tia-core/src/test/java/io/tia/core/parse/GitDiffParserTest.java
git commit -m "feat(core): GitDiffParser (.java 수정/추가/비코드 분류, old-side 라인 추출)"
```

---

## Task 9: ImpactAnalyzer (교차 + 1-A/1-B 규칙)

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/impact/ImpactAnalyzer.java`
- Test: `tia-core/src/test/java/io/tia/core/impact/ImpactAnalyzerTest.java`

- [x] **Step 1: 실패 테스트 작성 (네 시나리오)**

`ImpactAnalyzerTest.java`:
```java
package io.tia.core.impact;
import io.tia.core.model.*;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImpactAnalyzerTest {
    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();

    private CoverageSnapshot snapshot() {   // 실제 PricingService 커버 라인 ~{6,7,8}
        TestCoverage price = new TestCoverage("T_price", "PASSED",
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(6, 7, 8)));
        TestCoverage greet = new TestCoverage("T_greet", "PASSED",
            Map.of("io/tia/fixture/GreetingService.java", RoaringBitmap.bitmapOf(6, 7)));
        return new CoverageSnapshot("fixture", "c0", List.of(price, greet));
    }

    @Test
    void deterministic_selectsOnlyTestsHittingChangedLines() {
        DiffSummary diff = new DiffSummary(
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8)),
            Set.of(), Set.of());
        ImpactResult r = analyzer.select(snapshot(), diff);
        assertFalse(r.conservativeSelectAll());
        assertEquals(List.of("T_price"), ids(r));
        assertEquals(Confidence.DETERMINISTIC, r.impacted().get(0).confidence());
    }

    @Test
    void unmappableChange_forcesConservativeAll() {     // 1-A
        DiffSummary diff = new DiffSummary(Map.of(), Set.of(),
            Set.of("application.yml"));
        ImpactResult r = analyzer.select(snapshot(), diff);
        assertTrue(r.conservativeSelectAll());
        assertEquals(Set.of("T_price", "T_greet"), new HashSet<>(ids(r)));
        assertEquals(Confidence.CONSERVATIVE, r.impacted().get(0).confidence());
    }

    @Test
    void newJavaFile_forcesConservativeAll_sinceNoCoverageNorStaticGraph() {   // 1-B
        DiffSummary diff = new DiffSummary(Map.of(),
            Set.of("io/tia/fixture/NewFeature.java"), Set.of());   // 과거 커버리지 없는 신규 파일
        ImpactResult r = analyzer.select(snapshot(), diff);
        assertTrue(r.conservativeSelectAll());
        assertEquals(Set.of("T_price", "T_greet"), new HashSet<>(ids(r)));
    }

    @Test
    void deterministicHitPlusNewFile_selectsAllButKeepsDeterministicLabel() {
        DiffSummary diff = new DiffSummary(
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8)),
            Set.of("io/tia/fixture/NewFeature.java"), Set.of());
        ImpactResult r = analyzer.select(snapshot(), diff);
        assertTrue(r.conservativeSelectAll());
        Map<String, Confidence> byId = new HashMap<>();
        for (ImpactedTest t : r.impacted()) byId.put(t.testId(), t.confidence());
        assertEquals(Confidence.DETERMINISTIC, byId.get("T_price"));   // 정밀 히트는 유지
        assertEquals(Confidence.CONSERVATIVE, byId.get("T_greet"));
    }

    private static List<String> ids(ImpactResult r) {
        List<String> out = new ArrayList<>();
        for (ImpactedTest t : r.impacted()) out.add(t.testId());
        return out;
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.impact.ImpactAnalyzerTest`
Expected: FAIL — `ImpactAnalyzer` 없음.

- [x] **Step 3: 구현**

`ImpactAnalyzer.java`:
```java
package io.tia.core.impact;
import io.tia.core.model.*;
import org.roaringbitmap.RoaringBitmap;
import java.util.*;

public final class ImpactAnalyzer {

    public ImpactResult select(CoverageSnapshot snapshot, DiffSummary diff) {
        List<String> reasons = new ArrayList<>();

        // 1) DETERMINISTIC: 수정/삭제된 old-side 라인과 커버 라인 교차 (비트 AND)
        Map<String, Confidence> hit = new LinkedHashMap<>();
        for (TestCoverage t : snapshot.tests()) {
            for (Map.Entry<String, RoaringBitmap> e : diff.changedOldLinesByJavaFile().entrySet()) {
                if (!RoaringBitmap.and(t.linesFor(e.getKey()), e.getValue()).isEmpty()) {
                    hit.put(t.testId(), Confidence.DETERMINISTIC);
                }
            }
        }

        // 2) 현재 구현에서 해결 불가한 변경 → 보수적 전체 선택(안전). skip 기본 OFF이라 과선택 허용.
        //    - 비코드(매핑 불가) [1-A]
        //    - 신규 .java 파일: 과거 커버리지 없음 + 호출자 탐색(정적 그래프)은 이후 확장 → 영향 불명 [1-B]
        //      (주의: 신규 파일은 어떤 기존 테스트도 커버하지 못하므로 'covers()' 기반 선택은 항상 0 → 보수적이 유일한 안전책)
        boolean conservative = !diff.unmappableFiles().isEmpty() || !diff.additionOnlyJavaFiles().isEmpty();
        if (!diff.unmappableFiles().isEmpty())
            reasons.add("커버리지 매핑 불가 변경 " + diff.unmappableFiles()
                    + " → 보수적 전체 선택 (목적1) / triage UNKNOWN (목적2)");
        if (!diff.additionOnlyJavaFiles().isEmpty())
            reasons.add("신규 .java " + diff.additionOnlyJavaFiles()
                    + " → 과거 커버리지 없음, 정적 폴백은 이후 확장 → 보수적 전체 선택");

        if (conservative) {
            List<ImpactedTest> all = new ArrayList<>();
            for (TestCoverage t : snapshot.tests())   // 정밀 히트는 DETERMINISTIC 유지, 나머지는 CONSERVATIVE
                all.add(new ImpactedTest(t.testId(), hit.getOrDefault(t.testId(), Confidence.CONSERVATIVE)));
            return new ImpactResult(all, true, reasons);
        }

        List<ImpactedTest> impacted = new ArrayList<>();
        for (Map.Entry<String, Confidence> e : hit.entrySet())
            impacted.add(new ImpactedTest(e.getKey(), e.getValue()));
        return new ImpactResult(impacted, false, reasons);
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.impact.ImpactAnalyzerTest`
Expected: PASS (네 시나리오).

- [x] **Step 5: Commit**

```bash
git add tia-core/src/main/java/io/tia/core/impact/ImpactAnalyzer.java tia-core/src/test/java/io/tia/core/impact/ImpactAnalyzerTest.java
git commit -m "feat(core): ImpactAnalyzer (비트 AND 교차 + 1-A 보수적폴백 + 1-B 저신뢰)"
```

---

## Task 10: FlakyAnalyzer (N회 실행 집계)

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/flaky/{RunResult,FlakyReport,FlakyAnalyzer}.java`
- Test: `tia-core/src/test/java/io/tia/core/flaky/FlakyAnalyzerTest.java`

- [x] **Step 1: 실패 테스트 작성**

`FlakyAnalyzerTest.java`:
```java
package io.tia.core.flaky;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlakyAnalyzerTest {
    @Test
    void detectsTestWithBothPassAndFail() {
        List<RunResult> runs = List.of(
            new RunResult(Map.of("T_ok", true,  "T_flaky", true)),
            new RunResult(Map.of("T_ok", true,  "T_flaky", false)),
            new RunResult(Map.of("T_ok", true,  "T_flaky", true)));
        FlakyReport r = new FlakyAnalyzer().aggregate(runs);
        assertEquals(List.of("T_flaky"), r.flakyTests());
        assertEquals(2, r.totalTests());
        assertEquals(0.5, r.ratio(), 1e-9);   // 2개 중 1개
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.flaky.FlakyAnalyzerTest`
Expected: FAIL — 클래스 없음.

- [x] **Step 3: 구현 3개**

`RunResult.java`:
```java
package io.tia.core.flaky;
import java.util.Map;

public record RunResult(Map<String, Boolean> passedByTest) {}
```

`FlakyReport.java`:
```java
package io.tia.core.flaky;
import java.util.List;

public record FlakyReport(double ratio, List<String> flakyTests, int totalTests) {}
```

`FlakyAnalyzer.java`:
```java
package io.tia.core.flaky;
import java.util.*;

public final class FlakyAnalyzer {
    public FlakyReport aggregate(List<RunResult> runs) {
        Map<String, Set<Boolean>> outcomes = new LinkedHashMap<>();
        for (RunResult run : runs)
            run.passedByTest().forEach((id, passed) ->
                outcomes.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(passed));

        List<String> flaky = new ArrayList<>();
        for (Map.Entry<String, Set<Boolean>> e : outcomes.entrySet())
            if (e.getValue().size() > 1) flaky.add(e.getKey());   // pass와 fail 모두 관측

        int total = outcomes.size();
        double ratio = total == 0 ? 0.0 : (double) flaky.size() / total;
        return new FlakyReport(ratio, flaky, total);
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.flaky.FlakyAnalyzerTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-core/src/main/java/io/tia/core/flaky tia-core/src/test/java/io/tia/core/flaky
git commit -m "feat(core): FlakyAnalyzer (N회 실행에서 pass/fail 혼재 테스트 집계)"
```

---

## Task 11: CoverageStore (SQLite + Roaring 블롭, 커밋 키)

**스키마 divergence 메모 [C1]:** 설계 §6.2는 `coverage(... service, class_fqn, method_sig ...)`이나, 현재 PoC는 단일 레포라 `service`를 생략하고 키를 **`file`(정규형 패키지 상대 경로 — Task 6B)**로 둔다. git diff가 파일+라인 단위이므로 `file+line_bitmap`이 교차에 직접 맞고, `class_fqn/method_sig`(메소드 그래프용)는 이후 정적 경로에서 도입한다. `file`은 반드시 PathNormalizer 정규형이어야 diff 키와 교차된다.

**Files:**
- Create: `tia-core/src/main/java/io/tia/core/store/CoverageStore.java`
- Test: `tia-core/src/test/java/io/tia/core/store/CoverageStoreTest.java`

- [x] **Step 1: 실패 테스트 작성**

`CoverageStoreTest.java`:
```java
package io.tia.core.store;
import io.tia.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoverageStoreTest {
    @Test
    void savesAndLoadsSnapshotByCommit(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        TestCoverage t = new TestCoverage("T1", "PASSED",
            Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10)));
        CoverageSnapshot snap = new CoverageSnapshot("fixture", "abc123", List.of(t));

        try (CoverageStore store = new CoverageStore(db)) {
            store.save(snap);
            CoverageSnapshot loaded = store.load("abc123");
            assertEquals("fixture", loaded.repo());
            assertEquals(1, loaded.tests().size());
            assertEquals(RoaringBitmap.bitmapOf(8, 9, 10),
                loaded.tests().get(0).linesFor("io/tia/fixture/PricingService.java"));
        }
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.store.CoverageStoreTest`
Expected: FAIL — `CoverageStore` 없음.

- [x] **Step 3: 구현**

`CoverageStore.java`:
```java
package io.tia.core.store;
import io.tia.core.model.*;
import org.roaringbitmap.RoaringBitmap;
import java.io.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** 레포당 SQLite 스냅샷. 모든 레코드 키에 commit_sha 포함(설계 §6.1). */
public final class CoverageStore implements AutoCloseable {
    private final Connection conn;

    public CoverageStore(Path dbFile) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString());
            initSchema();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private void initSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS builds(" +
                "build_id INTEGER PRIMARY KEY AUTOINCREMENT, repo TEXT, commit_sha TEXT, indexed_at TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS coverage(" +
                "build_id INTEGER, test_id TEXT, result TEXT, file TEXT, line_bitmap BLOB)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cov_build ON coverage(build_id)");
        }
    }

    public long save(CoverageSnapshot snap) {
        try {
            long buildId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO builds(repo, commit_sha, indexed_at) VALUES(?,?,datetime('now'))",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, snap.repo());
                ps.setString(2, snap.commitSha());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); buildId = rs.getLong(1); }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO coverage(build_id, test_id, result, file, line_bitmap) VALUES(?,?,?,?,?)")) {
                for (TestCoverage t : snap.tests()) {
                    for (Map.Entry<String, RoaringBitmap> e : t.linesByFile().entrySet()) {
                        ps.setLong(1, buildId);
                        ps.setString(2, t.testId());
                        ps.setString(3, t.result());
                        ps.setString(4, e.getKey());
                        ps.setBytes(5, toBytes(e.getValue()));
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
            return buildId;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 해당 커밋의 가장 최근 빌드 스냅샷을 로드. */
    public CoverageSnapshot load(String commitSha) {
        try {
            long buildId = -1; String repo = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT build_id, repo FROM builds WHERE commit_sha=? ORDER BY build_id DESC LIMIT 1")) {
                ps.setString(1, commitSha);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return new CoverageSnapshot(null, commitSha, List.of());
                    buildId = rs.getLong(1); repo = rs.getString(2);
                }
            }
            Map<String, Map<String, RoaringBitmap>> byTest = new LinkedHashMap<>();
            Map<String, String> resultByTest = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT test_id, result, file, line_bitmap FROM coverage WHERE build_id=?")) {
                ps.setLong(1, buildId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tid = rs.getString(1);
                        resultByTest.put(tid, rs.getString(2));
                        byTest.computeIfAbsent(tid, k -> new LinkedHashMap<>())
                              .put(rs.getString(3), fromBytes(rs.getBytes(4)));
                    }
                }
            }
            List<TestCoverage> tests = new ArrayList<>();
            for (Map.Entry<String, Map<String, RoaringBitmap>> e : byTest.entrySet())
                tests.add(new TestCoverage(e.getKey(), resultByTest.get(e.getKey()), e.getValue()));
            return new CoverageSnapshot(repo, commitSha, tests);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    static byte[] toBytes(RoaringBitmap b) {
        b.runOptimize();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try { b.serialize(new DataOutputStream(bos)); } catch (IOException e) { throw new UncheckedIOException(e); }
        return bos.toByteArray();
    }

    static RoaringBitmap fromBytes(byte[] data) {
        RoaringBitmap b = new RoaringBitmap();
        try { b.deserialize(new DataInputStream(new ByteArrayInputStream(data))); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        return b;
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-core:test --tests io.tia.core.store.CoverageStoreTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-core/src/main/java/io/tia/core/store/CoverageStore.java tia-core/src/test/java/io/tia/core/store/CoverageStoreTest.java
git commit -m "feat(core): CoverageStore (SQLite, Roaring 블롭, commit_sha 키)"
```

---

## Task 12: CLI — `tia index`

**Files:**
- Create: `tia-cli/src/main/java/io/tia/cli/{TiaCommand,IndexCommand,Main}.java`
- Test: `tia-cli/src/test/java/io/tia/cli/IndexCommandTest.java`

- [x] **Step 1: 실패 테스트 작성**

`IndexCommandTest.java`:
```java
package io.tia.cli;
import io.tia.core.model.CoverageSnapshot;
import io.tia.core.store.CoverageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexCommandTest {
    @Test
    void indexesReportIntoStore(@TempDir Path dir) throws Exception {
        Path report = dir.resolve("r.json");
        Files.writeString(report, """
            {"tests":[{"uniformPath":"io/tia/fixture/ApiSmokeTest/testPrice","result":"PASSED",
              "paths":[{"path":"io/tia/fixture","files":[{"fileName":"PricingService.java","coveredLines":"8-10"}]}]}]}""");
        Path db = dir.resolve("tia.db");

        int code = new CommandLine(new TiaCommand()).execute(
            "index", "--report", report.toString(), "--repo", "fixture", "--commit", "c0", "--db", db.toString());
        assertEquals(0, code);

        try (CoverageStore store = new CoverageStore(db)) {
            CoverageSnapshot snap = store.load("c0");
            assertEquals(1, snap.tests().size());
            assertTrue(snap.tests().get(0).covers("io/tia/fixture/PricingService.java"));
        }
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-cli:test --tests io.tia.cli.IndexCommandTest`
Expected: FAIL — `TiaCommand`/`IndexCommand` 없음.

- [x] **Step 3: 구현 3개**

`TiaCommand.java`:
```java
package io.tia.cli;
import picocli.CommandLine.Command;

@Command(name = "tia", mixinStandardHelpOptions = true, version = "tia 0.1.0",
        subcommands = { IndexCommand.class, ImpactCommand.class, FlakyCommand.class })
public class TiaCommand implements Runnable {
    @Override public void run() { System.out.println("Usage: tia [index|impact|flaky] --help"); }
}
```

`IndexCommand.java`:
```java
package io.tia.cli;
import io.tia.core.model.*;
import io.tia.core.parse.TestwiseReportParser;
import io.tia.core.store.CoverageStore;
import picocli.CommandLine.*;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "index", description = "testwise 리포트를 SQLite 스냅샷으로 인덱싱")
public class IndexCommand implements Callable<Integer> {
    @Option(names = "--report", required = true) Path report;
    @Option(names = "--repo", required = true) String repo;
    @Option(names = "--commit", required = true) String commit;
    @Option(names = "--db", required = true) Path db;

    @Override public Integer call() throws Exception {
        try (InputStream in = Files.newInputStream(report)) {
            List<TestCoverage> tests = new TestwiseReportParser().parse(in);
            try (CoverageStore store = new CoverageStore(db)) {
                store.save(new CoverageSnapshot(repo, commit, tests));
            }
            System.out.println("indexed " + tests.size() + " tests @ " + commit);
            return 0;
        }
    }
}
```

`Main.java`:
```java
package io.tia.cli;
import picocli.CommandLine;

public final class Main {
    public static void main(String[] args) {
        System.exit(new CommandLine(new TiaCommand()).execute(args));
    }
}
```

(주의: `ImpactCommand`/`FlakyCommand`는 다음 태스크에서 만든다. 이 태스크에서 컴파일되도록 두 클래스의 **빈 스텁**을 먼저 둔다 — 아래 Step 3b.)

- [x] **Step 3b: 컴파일용 임시 스텁 작성**

`ImpactCommand.java` (임시):
```java
package io.tia.cli;
import picocli.CommandLine.Command;
import java.util.concurrent.Callable;
@Command(name = "impact") public class ImpactCommand implements Callable<Integer> {
    @Override public Integer call() { return 0; }
}
```
`FlakyCommand.java` (임시):
```java
package io.tia.cli;
import picocli.CommandLine.Command;
import java.util.concurrent.Callable;
@Command(name = "flaky") public class FlakyCommand implements Callable<Integer> {
    @Override public Integer call() { return 0; }
}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-cli:test --tests io.tia.cli.IndexCommandTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-cli/src/main/java/io/tia/cli tia-cli/src/test/java/io/tia/cli/IndexCommandTest.java
git commit -m "feat(cli): tia index (testwise 리포트 → SQLite 인덱싱) + 서브커맨드 스캐폴드"
```

---

## Task 13: CLI — `tia impact`

**Files:**
- Modify: `tia-cli/src/main/java/io/tia/cli/ImpactCommand.java` (스텁 → 실구현)
- Test: `tia-cli/src/test/java/io/tia/cli/ImpactCommandTest.java`

- [x] **Step 1: 실패 테스트 작성**

`ImpactCommandTest.java`:
```java
package io.tia.cli;
import io.tia.core.model.*;
import io.tia.core.store.CoverageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImpactCommandTest {
    @Test
    void printsOnlyImpactedTests(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("tia.db");
        try (CoverageStore store = new CoverageStore(db)) {
            store.save(new CoverageSnapshot("fixture", "c0", List.of(
                new TestCoverage("T_price", "PASSED",
                    Map.of("io/tia/fixture/PricingService.java", RoaringBitmap.bitmapOf(8, 9, 10))),
                new TestCoverage("T_greet", "PASSED",
                    Map.of("io/tia/fixture/GreetingService.java", RoaringBitmap.bitmapOf(6, 7))))));
        }
        Path diff = dir.resolve("d.diff");
        Files.writeString(diff, """
            diff --git a/fixture-app/src/main/java/io/tia/fixture/PricingService.java b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
            --- a/fixture-app/src/main/java/io/tia/fixture/PricingService.java
            +++ b/fixture-app/src/main/java/io/tia/fixture/PricingService.java
            @@ -8,1 +8,1 @@
            -    return key.length() * 100;
            +    return key.length() * 200;
            """);   // 레포 상대 경로 → PathNormalizer가 커버리지 키(io/tia/fixture/...)와 교차되게 정규화

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out; System.setOut(new PrintStream(out));
        int code = new CommandLine(new TiaCommand()).execute(
            "impact", "--db", db.toString(), "--commit", "c0", "--diff-file", diff.toString());
        System.setOut(prev);

        assertEquals(0, code);
        String printed = out.toString();
        assertTrue(printed.contains("T_price"), printed);
        assertFalse(printed.contains("T_greet"), printed);
        assertTrue(printed.contains("DETERMINISTIC"), printed);
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-cli:test --tests io.tia.cli.ImpactCommandTest`
Expected: FAIL — 스텁이 아무것도 출력하지 않음.

- [x] **Step 3: ImpactCommand 실구현**

`ImpactCommand.java` (전체 교체):
```java
package io.tia.cli;
import io.tia.core.impact.ImpactAnalyzer;
import io.tia.core.model.*;
import io.tia.core.parse.GitDiffParser;
import io.tia.core.store.CoverageStore;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

@Command(name = "impact", description = "diff와 커버리지 매핑을 교차해 영향 테스트 선별")
public class ImpactCommand implements Callable<Integer> {
    @Option(names = "--db", required = true) Path db;
    @Option(names = "--commit", required = true) String commit;
    @Option(names = "--diff-file", description = "unified diff 파일 (미지정 시 --git-ref로 git diff 실행)") Path diffFile;
    @Option(names = "--git-ref", description = "diff 베이스 ref (미지정 시 --commit). 라인 공간 정렬 위해 인덱싱 커밋과 일치해야 함 [설계 §6.2 4-B]") String gitRef;

    @Override public Integer call() throws Exception {
        // 기본 베이스 = 인덱싱 커밋(--commit) → git diff <commit> 의 old-side가 커버리지와 같은 라인 공간 [§6.2 4-B].
        // 현재 구현은 라인 재조정 미구현이므로 diff 베이스 ≠ 인덱싱 커밋이면 결과 무효(아래 가드).
        String base = (gitRef == null) ? commit : gitRef;
        String diffText = (diffFile != null)
            ? Files.readString(diffFile)
            : runGitDiff(base);

        DiffSummary diff = new GitDiffParser().parse(diffText);
        CoverageSnapshot snap;
        try (CoverageStore store = new CoverageStore(db)) { snap = store.load(commit); }

        ImpactResult r = new ImpactAnalyzer().select(snap, diff);
        System.out.println("# 매핑 기준 커밋: " + commit + "  (영향 테스트 " + r.impacted().size() + "개"
            + (r.conservativeSelectAll() ? ", 보수적 전체 선택" : "") + ")");
        for (ImpactedTest t : r.impacted())
            System.out.println(t.confidence() + "\t" + t.testId());
        for (String reason : r.reasons())
            System.out.println("# 주의: " + reason);
        return 0;
    }

    private static String runGitDiff(String ref) throws Exception {
        Process p = new ProcessBuilder("git", "diff", "--unified=0", ref).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-cli:test --tests io.tia.cli.ImpactCommandTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-cli/src/main/java/io/tia/cli/ImpactCommand.java tia-cli/src/test/java/io/tia/cli/ImpactCommandTest.java
git commit -m "feat(cli): tia impact (diff∩커버 → 영향 테스트 + 신뢰도 출력)"
```

---

## Task 14: CLI — `tia flaky`

**Files:**
- Modify: `tia-cli/src/main/java/io/tia/cli/FlakyCommand.java` (스텁 → 실구현)
- Test: `tia-cli/src/test/java/io/tia/cli/FlakyCommandTest.java`

run-result JSON 형식: `{"results":{"<testId>":true,...}}` (Task 17의 RunResultWriter가 같은 형식 산출).

- [x] **Step 1: 실패 테스트 작성**

`FlakyCommandTest.java`:
```java
package io.tia.cli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FlakyCommandTest {
    @Test
    void reportsFlakyRatio(@TempDir Path dir) throws Exception {
        Path r1 = dir.resolve("run1.json"); Files.writeString(r1, "{\"results\":{\"T_ok\":true,\"T_flaky\":true}}");
        Path r2 = dir.resolve("run2.json"); Files.writeString(r2, "{\"results\":{\"T_ok\":true,\"T_flaky\":false}}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out; System.setOut(new PrintStream(out));
        int code = new CommandLine(new TiaCommand()).execute(
            "flaky", "--runs", r1.toString() + "," + r2.toString());
        System.setOut(prev);

        assertEquals(0, code);
        String printed = out.toString();
        assertTrue(printed.contains("T_flaky"), printed);
        assertTrue(printed.contains("0.5") || printed.contains("50"), printed);
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-cli:test --tests io.tia.cli.FlakyCommandTest`
Expected: FAIL — 스텁이 출력 없음.

- [x] **Step 3: FlakyCommand 실구현**

`FlakyCommand.java` (전체 교체):
```java
package io.tia.cli;
import com.fasterxml.jackson.databind.*;
import io.tia.core.flaky.*;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "flaky", description = "N회 실행 결과(run-result JSON)로 플레이키 비율 측정")
public class FlakyCommand implements Callable<Integer> {
    @Option(names = "--runs", required = true, split = ",",
            description = "run-result JSON 파일들 (쉼표 구분)") List<Path> runs;

    @Override public Integer call() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<RunResult> parsed = new ArrayList<>();
        for (Path p : runs) {
            JsonNode results = mapper.readTree(Files.readAllBytes(p)).path("results");
            Map<String, Boolean> m = new LinkedHashMap<>();
            results.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asBoolean()));
            parsed.add(new RunResult(m));
        }
        FlakyReport r = new FlakyAnalyzer().aggregate(parsed);
        System.out.printf("flaky ratio: %.3f (%d/%d)%n", r.ratio(), r.flakyTests().size(), r.totalTests());
        for (String t : r.flakyTests()) System.out.println("FLAKY\t" + t);
        return 0;
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-cli:test --tests io.tia.cli.FlakyCommandTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-cli/src/main/java/io/tia/cli/FlakyCommand.java tia-cli/src/test/java/io/tia/cli/FlakyCommandTest.java
git commit -m "feat(cli): tia flaky (run-result JSON 집계 → 플레이키 비율)"
```

---

## Task 15: 확장 — AgentClient (HttpURLConnection, Java 8)

**Files:**
- Create: `tia-junit-extension/src/main/java/io/tia/junit/{AgentClient,HttpAgentClient}.java`
- Test: `tia-junit-extension/src/test/java/io/tia/junit/HttpAgentClientTest.java`

- [x] **Step 1: 실패 테스트 작성 (in-JVM HTTP 스텁)**

`HttpAgentClientTest.java`:
```java
package io.tia.junit;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpAgentClientTest {
    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> { hits.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath());  // raw: %2F 유지
            ex.sendResponseHeaders(200, -1); ex.close(); });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void postsStartAndEnd() {
        String base = "http://localhost:" + server.getAddress().getPort();
        AgentClient client = new HttpAgentClient(base);
        client.testStart("io/tia/fixture/ApiSmokeTest/testPrice");
        client.testEnd("io/tia/fixture/ApiSmokeTest/testPrice", "PASSED");
        assertTrue(hits.contains("POST /test/start/io%2Ftia%2Ffixture%2FApiSmokeTest%2FtestPrice"), hits.toString());
        assertTrue(hits.contains("POST /test/end/io%2Ftia%2Ffixture%2FApiSmokeTest%2FtestPrice"), hits.toString());
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-junit-extension:test --tests io.tia.junit.HttpAgentClientTest`
Expected: FAIL — `AgentClient`/`HttpAgentClient` 없음.

- [x] **Step 3: 구현 2개**

`AgentClient.java`:
```java
package io.tia.junit;

public interface AgentClient {
    void testStart(String uniformPath);
    void testEnd(String uniformPath, String result);
}
```

`HttpAgentClient.java` (Java 8 호환 — HttpURLConnection):
```java
package io.tia.junit;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** teamscale-jacoco-agent testwise 제어 엔드포인트 호출 (경로는 agent-api.md 검증값). */
public final class HttpAgentClient implements AgentClient {
    private final String baseUrl;
    public HttpAgentClient(String baseUrl) { this.baseUrl = stripTrailingSlash(baseUrl); }

    @Override public void testStart(String uniformPath) {
        post(baseUrl + "/test/start/" + encodeSegment(uniformPath), null);
    }
    @Override public void testEnd(String uniformPath, String result) {
        post(baseUrl + "/test/end/" + encodeSegment(uniformPath), "{\"result\":\"" + result + "\"}");
    }

    // teamscale {testId}는 단일 path 세그먼트 → 슬래시를 %2F로 인코딩(실측: raw=HTTP 500, %2F=204). Java 8 호환.
    private static String encodeSegment(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException e) { throw new RuntimeException(e); }
    }

    private static void post(String url, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();   // 응답을 읽어 연결 완료
            if (code >= 400) throw new IllegalStateException("agent " + url + " → HTTP " + code);
        } catch (Exception e) {
            throw new RuntimeException("agent 호출 실패: " + url, e);
        } finally { if (c != null) c.disconnect(); }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
```
(주의: uniformPath는 **`%2F` 인코딩**해 단일 path 세그먼트로 보낸다 — teamscale `{testId}`가 단일 세그먼트라 필수. **실측: 원시 슬래시=HTTP 500, %2F=204**(agent-api.md). HttpServer 스텁은 `%2F`를 디코딩하므로 테스트는 `getRawPath()`로 인코딩을 검증한다.)

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-junit-extension:test --tests io.tia.junit.HttpAgentClientTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-junit-extension/src/main/java/io/tia/junit/AgentClient.java tia-junit-extension/src/main/java/io/tia/junit/HttpAgentClient.java tia-junit-extension/src/test/java/io/tia/junit/HttpAgentClientTest.java
git commit -m "feat(junit-ext): AgentClient + HttpAgentClient (Java 8, testwise 제어)"
```

---

## Task 16: 확장 — TeamscaleTestwiseExtension

**Files:**
- Create: `tia-junit-extension/src/main/java/io/tia/junit/TeamscaleTestwiseExtension.java`
- Test: `tia-junit-extension/src/test/java/io/tia/junit/TeamscaleTestwiseExtensionTest.java`
- Test: `tia-junit-extension/src/test/java/io/tia/junit/SampleTest.java` (최상위 더미 — nested면 `getName()`이 `$`-바이너리명)

- [x] **Step 1: 실패 테스트 작성 (fake client + Mockito ExtensionContext)**

`TeamscaleTestwiseExtensionTest.java`:
```java
package io.tia.junit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtensionContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamscaleTestwiseExtensionTest {
    static class RecordingClient implements AgentClient {
        final List<String> calls = new ArrayList<>();
        public void testStart(String p) { calls.add("start:" + p); }
        public void testEnd(String p, String r) { calls.add("end:" + p + ":" + r); }
    }

    @Test
    void signalsStartThenEndWithUniformPath() throws Exception {
        RecordingClient client = new RecordingClient();
        TeamscaleTestwiseExtension ext = new TeamscaleTestwiseExtension(client);

        ExtensionContext ctx = mock(ExtensionContext.class);
        when(ctx.getRequiredTestClass()).thenReturn((Class) SampleTest.class);
        when(ctx.getRequiredTestMethod()).thenReturn(SampleTest.class.getMethod("testPrice"));
        when(ctx.getExecutionException()).thenReturn(Optional.empty());

        ext.beforeEach(ctx);
        ext.afterEach(ctx);

        assertEquals(List.of(
            "start:io/tia/junit/SampleTest/testPrice",
            "end:io/tia/junit/SampleTest/testPrice:PASSED"), client.calls);
    }
}
```

`SampleTest.java` (별도 최상위 클래스 — nested로 두면 `Class.getName()`이 `io.tia.junit.TeamscaleTestwiseExtensionTest$SampleTest`가 되어 assertion과 불일치):
```java
package io.tia.junit;

public class SampleTest {
    public void testPrice() {}
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :tia-junit-extension:test --tests io.tia.junit.TeamscaleTestwiseExtensionTest`
Expected: FAIL — `TeamscaleTestwiseExtension` 없음.

- [x] **Step 3: 구현**

`TeamscaleTestwiseExtension.java`:
```java
package io.tia.junit;
import org.junit.jupiter.api.extension.*;

/**
 * 각 테스트 전후로 teamscale 에이전트에 start/end를 신호한다.
 * 에이전트 주소: 시스템 프로퍼티 tia.agent.url (기본 http://localhost:8123).
 */
public class TeamscaleTestwiseExtension implements BeforeEachCallback, AfterEachCallback {
    private final AgentClient client;

    public TeamscaleTestwiseExtension() {
        this(new HttpAgentClient(System.getProperty("tia.agent.url", "http://localhost:8123")));
    }
    TeamscaleTestwiseExtension(AgentClient client) { this.client = client; }

    @Override public void beforeEach(ExtensionContext ctx) {
        client.testStart(uniformPath(ctx));
    }
    @Override public void afterEach(ExtensionContext ctx) {
        String result = ctx.getExecutionException().isPresent() ? "FAILED" : "PASSED";
        client.testEnd(uniformPath(ctx), result);
    }

    private static String uniformPath(ExtensionContext ctx) {
        return ctx.getRequiredTestClass().getName().replace('.', '/')
                + "/" + ctx.getRequiredTestMethod().getName();
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :tia-junit-extension:test --tests io.tia.junit.TeamscaleTestwiseExtensionTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add tia-junit-extension/src/main/java/io/tia/junit/TeamscaleTestwiseExtension.java tia-junit-extension/src/test/java/io/tia/junit/TeamscaleTestwiseExtensionTest.java
git commit -m "feat(junit-ext): TeamscaleTestwiseExtension (beforeEach/afterEach → start/end 신호)"
```

---

## Task 17: fixture RestAssured 스위트 + RunResultWriter + E2E 스크립트

여기서 모든 부품이 멀티프로세스로 연결된다(앱+에이전트 별도 프로세스, 테스트가 HTTP로 호출하며 확장이 에이전트를 제어).

**Files:**
- Create: `fixture-app/src/test/java/io/tia/fixture/ApiSmokeTest.java`
- Create: `fixture-app/src/test/java/io/tia/fixture/RunResultWriter.java`
- Create: `scripts/run-poc.sh`

- [x] **Step 1: RunResultWriter 작성 (테스트 결과 → run-result JSON 누적)**

`RunResultWriter.java`:
```java
package io.tia.fixture;
import org.junit.jupiter.api.extension.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** tia.run.out 로 지정된 파일에 {"results":{testId:passed}} 를 기록 (tia flaky 입력). */
public class RunResultWriter implements TestWatcher, AfterAllCallback {
    private final Map<String, Boolean> results = new ConcurrentHashMap<>();

    private String id(ExtensionContext ctx) {
        return ctx.getRequiredTestClass().getName().replace('.', '/')
                + "/" + ctx.getRequiredTestMethod().getName();
    }
    @Override public void testSuccessful(ExtensionContext ctx) { results.put(id(ctx), true); }
    @Override public void testFailed(ExtensionContext ctx, Throwable cause) { results.put(id(ctx), false); }

    @Override public void afterAll(ExtensionContext ctx) throws IOException {
        String out = System.getProperty("tia.run.out");
        if (out == null) return;
        StringBuilder sb = new StringBuilder("{\"results\":{");
        int i = 0;
        for (Map.Entry<String, Boolean> e : results.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append("}}");
        Files.write(Paths.get(out), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
```

- [x] **Step 2: ApiSmokeTest 작성 (확장 2개 부착)**

`ApiSmokeTest.java`:
```java
package io.tia.fixture;
import io.restassured.RestAssured;
import io.tia.junit.TeamscaleTestwiseExtension;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith({ TeamscaleTestwiseExtension.class, RunResultWriter.class })
class ApiSmokeTest {
    @BeforeAll static void setup() {
        String base = System.getProperty("fixture.baseUrl");
        assumeTrue(base != null, "fixture.baseUrl 미설정 — E2E 스크립트에서만 실행");
        RestAssured.baseURI = base;
        // §5.1/D9: 모든 인입 요청에 test.id Baggage 주입 (병렬 에이전트 입력 와이어 사전 연결).
        RestAssured.filters((req, resp, ctx) -> {
            String tid = TeamscaleTestwiseExtension.currentTestId();
            if (tid != null) req.header("baggage", "test.id=" + tid);
            return ctx.next(req, resp);
        });
    }

    // 상태코드뿐 아니라 본문값까지 검증(200만 보지 않음).
    @Test void testGreeting() {   // greet("Alice")="hello alice"
        given().when().get("/greeting/Alice").then().statusCode(200).body(equalTo("hello alice"));
    }
    @Test void testPrice() {       // priceOf("ABC")="abc"(3)*100=300
        given().when().get("/price/ABC").then().statusCode(200).body(equalTo("300"));
    }
    @Test void testFlaky() {        // 의도적 플레이키 — 200일 때 body "ok" (~50% 500)
        given().when().get("/flaky").then().statusCode(200).body(equalTo("ok"));
    }
}
```

- [x] **Step 3: run-poc.sh 작성 (E2E)**

`scripts/run-poc.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
# agent-api.md 검증값 사용
AGENT_JAR="$(find tools -name teamscale-jacoco-agent.jar -print -quit)"   # -print -quit: pipefail 하 SIGPIPE 회피
[ -n "$AGENT_JAR" ] || { echo "에이전트 없음 — scripts/download-agent.sh 먼저"; exit 1; }
TESTWISE_OPTS="mode=testwise,includes=io.tia.fixture.*,http-server-port=8123,out=poc-out/coverage"
COMMIT="$(git rev-parse HEAD)"
SRC="fixture-app/src/main/java/io/tia/fixture/PricingService.java"
mkdir -p poc-out/coverage

./gradlew :fixture-app:bootJar :tia-cli:installDist
APP_JAR="fixture-app/build/libs/fixture-app-0.1.0.jar"

java "-javaagent:$AGENT_JAR=$TESTWISE_OPTS" -jar "$APP_JAR" --server.port=8080 &   # 인용: opts의 '*' glob/word-split 방지
APP_PID=$!
# 종료 시 앱 정리 + (중간 실패 대비) PricingService 원복
trap 'kill $APP_PID 2>/dev/null || true; [ -f "$SRC.bak" ] && mv "$SRC.bak" "$SRC" || true' EXIT
for i in $(seq 1 30); do curl -sf localhost:8080/greeting/x >/dev/null 2>&1 && break || sleep 1; done

./gradlew :fixture-app:test \
  -Dfixture.baseUrl=http://localhost:8080 \
  -Dtia.agent.url=http://localhost:8123 \
  -Dtia.run.out="$PWD/poc-out/run-e2e.json" || true

curl -s -X POST localhost:8123/dump || true          # 파일 산출형이면 flush
REPORT="$(ls -t poc-out/coverage/*.json | head -1)"

CLI="tia-cli/build/install/tia-cli/bin/tia-cli"
"$CLI" index --report "$REPORT" --repo fixture --commit "$COMMIT" --db poc-out/tia.db

# 실제 git diff로 변경 생성 → 라인 번호가 소스와 절대 어긋나지 않음(손으로 쓴 헤더의 drift 방지).
# 커버리지는 HEAD(=$COMMIT) 코드 기준, 이 diff의 old-side도 HEAD라 라인 공간 정렬됨(설계 §6.2 [4-B]).
perl -i.bak -pe 's/\* 100;/\* 200;/' "$SRC"
git diff --unified=0 -- "$SRC" > poc-out/sample.diff
mv "$SRC.bak" "$SRC"                                  # 원복 — 커버리지 측정 대상과 동일 상태 유지

echo "===== tia impact ====="
"$CLI" impact --db poc-out/tia.db --commit "$COMMIT" --diff-file poc-out/sample.diff
```
(diff는 **실제 `git diff`로 생성**하므로 라인 번호가 소스와 항상 일치하고 old-side가 HEAD(=인덱싱 커밋)라 커버리지와 라인 공간이 정렬된다. 경로는 레포 상대지만 PathNormalizer(Task 6B)가 커버리지 키와 동일 정규형으로 맞춘다. 0개가 나오면: ① 리포트 생성 여부 ② 변경 라인이 testPrice 커버 라인(예: 8)과 겹치는지 ③ agent-api.md start/end 경로 일치.)

- [x] **Step 4: E2E 실행 + 결과 확인**

Run: `bash scripts/run-poc.sh`
Expected: 마지막 `tia impact` 출력에 **`DETERMINISTIC  io/tia/fixture/ApiSmokeTest/testPrice`가 포함**되고, `testGreeting`은 **미포함**. (PricingService 변경이 testPrice만 친다.)
실패 시 디버깅 순서: ① 리포트가 생성됐는지(`ls poc-out/coverage`) ② 리포트 경로가 PathNormalizer로 패키지 상대 정규화되는지(`io/tia/fixture/...`) ③ 샘플 diff의 라인 번호가 리포트 커버 라인과 겹치는지 ④ `agent-api.md`의 start/end 경로가 실제와 일치하는지.

- [x] **Step 5: Commit**

```bash
git add fixture-app/src/test/java/io/tia/fixture scripts/run-poc.sh
git commit -m "feat(e2e): RestAssured 스위트 + RunResultWriter + run-poc.sh (수집→인덱스→영향 선별 전체 검증)"
```

---

## Task 18: 플레이키 비율 측정 (N회 실행)

**Files:**
- Create: `scripts/measure-flaky.sh`

- [x] **Step 1: measure-flaky.sh 작성**

`scripts/measure-flaky.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
N="${1:-10}"
mkdir -p poc-out/flaky

# 앱+에이전트를 이 스크립트가 직접 기동 (run-poc.sh와 독립 실행 가능) [D1]
AGENT_JAR="$(find tools -name teamscale-jacoco-agent.jar -print -quit)"
[ -n "$AGENT_JAR" ] || { echo "에이전트 없음 — scripts/download-agent.sh 먼저"; exit 1; }
./gradlew :fixture-app:bootJar :tia-cli:installDist
java "-javaagent:$AGENT_JAR=mode=testwise,includes=io.tia.fixture.*,http-server-port=8123,out=poc-out/coverage" \
  -jar fixture-app/build/libs/fixture-app-0.1.0.jar --server.port=8080 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT
for i in $(seq 1 30); do curl -sf localhost:8080/greeting/x >/dev/null 2>&1 && break || sleep 1; done

RUNS=""
for i in $(seq 1 "$N"); do
  OUT="poc-out/flaky/run-$i.json"
  ./gradlew :fixture-app:test --tests io.tia.fixture.ApiSmokeTest \
    -Dfixture.baseUrl=http://localhost:8080 -Dtia.agent.url=http://localhost:8123 \
    -Dtia.run.out="$PWD/$OUT" --rerun-tasks || true
  RUNS="${RUNS:+$RUNS,}$OUT"
done
CLI="tia-cli/build/install/tia-cli/bin/tia-cli"
echo "===== tia flaky (N=$N) ====="
"$CLI" flaky --runs "$RUNS"
```

- [ ] **Step 2: 앱 기동 후 측정 실행** — ⏸ **라이브 N회 미실행**: 호스트 샌드박스가 gradle 테스트 워커(포크 JVM) 아웃바운드를 차단(컨테이너 E2E를 만든 그 제약). 플레이키 로직은 `FlakyAnalyzerTest`/`FlakyCommandTest`로 검증됨, 라이브 수집 경로는 컨테이너 E2E(GREEN)로 증명됨. 외부/CI 환경에서 `bash scripts/measure-flaky.sh 10`으로 실측 가능.

Run: `bash scripts/download-agent.sh && bash scripts/measure-flaky.sh 10`  (스크립트가 앱+에이전트를 직접 기동)
Expected: `flaky ratio: 0.xxx (1/3)` 형태로 출력되고 `FLAKY  io/tia/fixture/ApiSmokeTest/testFlaky` 가 표시됨(10회 중 일부 실패하므로 플레이키로 검출). `testGreeting`/`testPrice`는 비플레이키.
(아주 드물게 10회 모두 같은 결과면 N을 늘린다 — `/flaky`는 50% 확률이라 10회 중 전부 동일할 확률은 약 0.2%.)

- [x] **Step 3: Commit**

```bash
git add scripts/measure-flaky.sh
git commit -m "feat(e2e): measure-flaky.sh (N회 실행 → tia flaky 비율 측정)"
```

---

## Task 19: 외부 실제 레포 스모크 런북

외부 레포는 결정론적 TDD 대상이 아니므로, **절차 + 기대 출력 형태**를 문서화한 체크리스트로 검증한다.

**Files:**
- Create: `docs/superpowers/plans/notes/phase0-external-smoke.md`

- [x] **Step 1: 런북 작성**

`docs/superpowers/plans/notes/phase0-external-smoke.md`:
```markdown
# 외부 레포 스모크 런북

전제: 대상은 단일 Spring Boot 레포, RestAssured(또는 임의 JUnit5) 스위트 보유.

## 절차
0. **직렬 실행 보장(설계 §3.3)**: 대상 스위트의 JUnit 병렬 실행을 끈다 — `junit.jupiter.execution.parallel.enabled=false`(기본값 유지). 병렬이면 testwise 커버리지가 섞여 매핑이 오염된다.
1. 대상 테스트 모듈 의존성에 `io.tia:tia-junit-extension:0.1.0` 추가.
2. 테스트 클래스(또는 전역)에 `@ExtendWith(io.tia.junit.TeamscaleTestwiseExtension.class)` 부착.
   (전역 적용: `src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension` +
    `junit.jupiter.extensions.autodetection.enabled=true`)
3. 대상 앱을 teamscale 에이전트와 함께 기동:
   `-javaagent:teamscale-jacoco-agent.jar=mode=testwise,includes=<대상패키지>.*,http-server-port=8123,out=<dir>`
4. 스위트 실행: `-Dfixture.baseUrl=<앱주소> -Dtia.agent.url=http://localhost:8123` (대상의 baseURI 주입 방식에 맞게)
5. 리포트 dump 후:
   `tia index --report <리포트> --repo <레포명> --commit $(git rev-parse HEAD) --db tia-<레포>.db`
6. 실제 변경에 대해: `tia impact --db tia-<레포>.db --commit <인덱싱커밋>` (--git-ref 미지정 시 베이스=인덱싱커밋 → `git diff <인덱싱커밋>`로 라인 공간 정렬 [§6.2 4-B]. **diff 베이스가 인덱싱 커밋과 달라지면 비트 AND가 무효** — 현재 구현은 라인 재조정 미구현)

## 체크리스트 (성공 기준)
- [ ] 리포트에 대상 패키지 클래스들이 covered로 등장 (includes 필터 정확)
- [ ] 코드 한 줄 수정 → 그 줄을 커버하는 테스트가 DETERMINISTIC으로 선별됨
- [ ] 무관한 클래스 수정 → 해당 테스트 미선별
- [ ] application.yml/SQL 변경 → "보수적 전체 선택" + 1-A 주의 메시지 출력 (목적1 안전망 동작)
- [ ] 신규 .java 추가 → "보수적 전체 선택" + 주의 메시지(영향 불명, 정적 폴백 이후 확장)
- [ ] 직렬 수행 오버헤드(스위트 +시간) 기록 — 병렬화 트리거(설계 §8) 판단 근거
- [ ] @Scheduled 등 백그라운드가 있는 레포에서 공유 유틸 과다 커버 관찰 여부 기록 (설계 1-C)

## 알려진 한계 (설계 문서 연결)
- 비코드 변경은 매핑 불가 → 보수적 폴백으로만 방어 (§1.3)
- 커버리지는 직전 인덱싱 커밋 기준 → staleness는 응답의 "매핑 기준 커밋"으로 노출 (§6.5)
```

- [x] **Step 2: Commit**

```bash
git add docs/superpowers/plans/notes/phase0-external-smoke.md
git commit -m "docs(e2e): 외부 레포 스모크 런북 + 성공 체크리스트"
```

---

## Task 20: 전체 빌드 + 회귀 확인

**Files:** 없음 (검증 전용)

- [x] **Step 1: 전체 단위 테스트**

Run: `./gradlew clean test`
Expected: `tia-core`, `tia-cli`, `tia-junit-extension` 전 모듈 BUILD SUCCESSFUL. (`fixture-app:test`는 `fixture.baseUrl` 미설정 시 assumeTrue로 스킵.)

- [x] **Step 2: E2E 재확인**

Run: `bash scripts/download-agent.sh && bash scripts/run-poc.sh`
Expected: `tia impact`가 `DETERMINISTIC testPrice` 포함, `testGreeting` 미포함.

- [x] **Step 3: 최종 커밋(있으면)**

```bash
git add -A && git commit -m "chore: PoC 전체 빌드/E2E 그린" || echo "변경 없음"
```

---

## Self-Review (작성자 체크 결과)

**1. Spec 커버리지 (설계 §9 PoC 요구 → 태스크 매핑)**
- "teamscale-jacoco-agent per-test 매핑" → Task 2,3,4,15,16,17 ✅
- "git diff 교차 → 영향 테스트 선별" → Task 6B(경로 정규화),8,9,13,17 ✅
- 경로 네임스페이스 정규화(git diff 레포상대 ↔ 커버리지 패키지상대) → Task 6B (A1 결함 수정; 단위테스트가 레포상대 입력으로 정규화까지 검증) ✅
- "플레이키 비율 측정 포함" → Task 10,14,18 ✅
- "SQLite 저장(커밋 1급, Roaring)" → Task 11 ✅
- "CLI 조회" → Task 12,13,14 ✅
- 설계 1-A(비코드 보수적 폴백) → Task 8,9 (테스트로 검증) ✅
- 설계 1-B(신규 코드 저신뢰) → Task 8,9 ✅
- 설계 D11(라인 정밀도) → Task 6,7,11 (라인 비트맵 전 구간) ✅
- "in-repo 픽스처 + 외부 스모크" → Task 3,17 / Task 19 ✅

**2. 플레이스홀더 스캔:** 모든 코드 스텝에 완전한 코드 포함. 외부 도구(teamscale 에이전트) 의존부는 "agent-api.md 검증값" 참조로 명시 — 이는 placeholder가 아니라 Task 2에서 실측해 고정하는 외부 계약. ✅

**3. 타입 일관성:** `TestCoverage.linesFor/covers`, `DiffSummary(changedOldLinesByJavaFile/additionOnlyJavaFiles/unmappableFiles)`, `ImpactAnalyzer.select`, `CoverageStore.save/load`, `AgentClient.testStart/testEnd`, run-result JSON `{"results":{id:bool}}` 형식이 생산처(Task 17 RunResultWriter)와 소비처(Task 14 FlakyCommand)에서 동일. uniformPath 생성 규칙(`클래스명 '.'→'/' + '/' + 메서드`)이 확장(Task 16)·결과기록기(Task 17)에서 동일. ✅

**알려진 외부 의존 리스크(플랜 결함 아님):** teamscale 에이전트의 정확한 start/end verb·경로·리포트 산출 방식은 Task 2에서 핀다(uniformPath는 원시 슬래시 규약으로 Task 4·15 통일). testwise JSON 스키마는 Task 4 실제 캡처본이 진실의 원천이며 Task 7 파서가 거기에 맞춰진다. git diff↔커버리지 경로 네임스페이스 불일치는 PathNormalizer(Task 6B)로 결정론적으로 해소된다(레포상대 입력을 받는 단위테스트가 가드). 이 두 지점(에이전트 API·리포트 스키마)만 환경 의존이고 나머지는 결정론적.

**리뷰 반영 이력(2차):** A1 경로 정규화(Task 6B 신규), A2 `--release 8`을 main 한정, B1 uniformPath 원시 슬래시 통일, C1 스키마 divergence 메모, C2/C3 defer 명시, D1 measure-flaky 자체 기동, D2 직렬 실행 경고, D3 wrapper 부트스트랩 전제, D4 텍스트블록 취약성 경고, D5 junit-api compileOnly.

**구현·실측 검증 이력(3차, 2026-06-13):** 계획대로 구현하여 전체 30 테스트 GREEN(스펙 수용 E2E 7 + 단위 23), 실제 teamscale 에이전트로 수집→convert→index→impact 전체 E2E도 GREEN. 실측으로 정정한 사항: ① 에이전트 레포 이전(`cqse/teamscale-java-profiler` v36.5.2, jar `.../lib/`), ② **B1 반전 — uniformPath는 원시 슬래시가 아니라 `%2F` 인코딩 필수**(raw=HTTP 500, %2F=204), ③ `out=`는 `.exec`+`test-execution.json` 산출 → `convert -t`로 testwise JSON 변환(출력 `-N` 접미사), ④ 실제 testwise JSON 형식이 `TestwiseReportParser`와 정확히 일치(캡처본을 리소스로 고정). 검증 상세는 `notes/agent-api.md`, 동작 스크립트는 `scripts/run-poc.sh`. 환경 주의: 일부 샌드박스에서 gradle 테스트 워커(포크 JVM) 아웃바운드 차단 → run-poc는 확장과 동일 신호를 curl로 보냄(구현 자체는 정상).

**구현·문서 일치 검증 이력(4차, 2026-06-13):** 구현↔문서 일치 감사.
- **§5.1/D9 `test.id` Baggage 미구현 → 원래 의도대로 구현**: 확장이 `TeamscaleTestwiseExtension.currentTestId()`(ThreadLocal)로 현재 test.id를 노출하고, `ApiSmokeTest`의 RestAssured 필터가 모든 인입 요청에 `baggage: test.id=<id>`를 주입. 직렬 모드는 dump 라벨 보조, 병렬 에이전트(Baggage로 per-test 컨텍스트 활성화) 드롭인 시 입력 와이어가 이미 연결(재작업 0). SUT 측 소비는 이후 확장/병렬 에이전트 몫.
- **문서 동기화(구현이 정답, 문서가 stale했던 것)**: 스펙 상태 라인(구현 완료), settings의 `e2e` 모듈, Task 17 ApiSmokeTest 본문값 검증 스니펫.
- **계획 외 추가 산출물(구현 중 도입, 문서화)**: `e2e` 모듈(`SpecAcceptanceE2ETest` — ATDD 스펙 수용 E2E, 출력 구조 파싱 정확 검증), `docker-compose.e2e.yml`+`scripts/docker-e2e-tester.sh`(컨테이너 간 out-of-process 블랙박스 E2E), tia-core/e2e의 PIT mutation testing 설정.
- 전체 33 테스트 + Docker 컨테이너 E2E + PIT 모두 GREEN.

# Getting started — 내 프로젝트에 TIA 적용하기

목표: **코드 변경 → 영향받는 테스트만 선별**(실행 시간↓) + **인터랙티브 리포트**.

이 문서는 두 부로 나뉜다.

- **1부(아래) — 첫 결과까지.** 클론에서 시작해 `tia demo`로 동작을 직접 보고, 그다음 자신의 프로젝트에
  적용하는 흐름을 5분 안에 훑는다. 막히면 `tia doctor`.
- **2부 — 레퍼런스.** 수집 모델 결정 트리, 인덱싱/선별/리포트 옵션, CI 연동, `tia.yml` 스키마, 플레이키
  측정 등 상세 절. 1부 각 단계에서 필요한 절로 바로 링크한다.

> 실제 블랙박스 스위트 예제가 궁금하면 → [petclinic-demo](petclinic-demo/README.md).

---

## 1부 — 첫 결과까지

### ① 클론 + 빌드

```bash
git clone https://github.com/baekchangjoon/test-impact-analysis.git
cd test-impact-analysis
./gradlew :tia-cli:installDist
```

이후 예시는 CLI 별칭을 쓴다(매번 전체 경로를 치지 않아도 되게):

```bash
CLI=tia-cli/build/install/tia/bin/tia
# 또는 PATH에 등록: export PATH="$PWD/tia-cli/build/install/tia/bin:$PATH"
"$CLI" --help
```

(fat-jar·Docker·Gradle 플러그인 등 다른 설치 방식은 2부 [§0 설치](#0-설치-택1) 참조.)

### ② `tia demo` 로 전 과정 체험

TIA 레포를 체크아웃한 상태에서, fixture-app을 **실제로** 수집→인덱싱→diff→impact→리포트까지 한 번에
구동하며 단계마다 해설한다(내장 리소스로 흉내 내는 게 아니라 진짜 커버리지를 수집한다).

```bash
"$CLI" demo
```

**첫 실행은 1~3분 걸릴 수 있다**(pjacoco 에이전트 해소 + Gradle 테스트 빌드 워밍업 — 이후는 캐시로
수십 초). 실제 실행 예(발췌, 6단계 마커 `=== [N/6] ... ===`가 안정적으로 찍힌다):

```
=== [1/6] pjacoco 에이전트 해소 ===
in-process 커버리지 수집에 쓰는 pjacoco 에이전트 jar를 내려받거나 재사용합니다(보통 수 초).

=== [2/6] fixture-app 실수집(serial) ===
fixture-app 테스트를 pjacoco in-process 에이전트로 실제 실행해 커버리지를 모읍니다.
첫 실행은 1~3분 걸릴 수 있습니다(의존성/데몬 워밍업).
...
✅ demo-collect PASS -> .../build/inprocess-e2e/demo/testwise_serial.json

=== [3/6] 인덱싱 — testwise 결과를 demo 전용 DB에 저장 ===
커밋 <sha> 기준으로 .../testwise_serial.json 를 .../demo-tia.db 에 인덱싱합니다.
indexed 8 tests @ <sha>

=== [4/6] diff 동적 생성 — 커버된 라인 하나를 골라 최소 변경 만들기 ===
변경 대상: io/tia/fixture/GreetingService.java 라인 8 (실제 파일은 건드리지 않는 diff 텍스트만 생성)

=== [5/6] 영향 테스트 선별 — diff와 커버리지 교차 ===
방금 만든 diff와 커버리지 매핑을 교차해, 라인 매핑이 확정된(DETERMINISTIC) 영향 테스트를 선별합니다.
# 매핑 기준 커밋: <sha>  (영향 테스트 4개)
DETERMINISTIC	io.tia.e2e.inprocess.GreetingInProcessIT#greetAlice
DETERMINISTIC	io.tia.e2e.inprocess.GreetingInProcessIT#greetBob
DETERMINISTIC	io.tia.e2e.inprocess.GreetingInProcessIT#greetCarol
DETERMINISTIC	io.tia.e2e.inprocess.GreetingInProcessIT#greetDave

=== [6/6] 인터랙티브 HTML 리포트 생성 ===

데모 완료 — 방금 한 일:
  1) fixture-app 테스트를 실제로 실행해 커버리지를 수집했습니다: .../testwise_serial.json
  2) 커밋 <sha> 기준으로 인덱싱했습니다: .../demo-tia.db
  3) io/tia/fixture/GreetingService.java 라인 8 변경에 영향받는 테스트를 선별했습니다.
  4) 인터랙티브 리포트를 생성했습니다(브라우저로 여세요): .../report.html
내 프로젝트에 적용하려면: tia init
```

선별된 테스트 개수·이름은 fixture-app의 실제 커버리지 형상에 따라 달라질 수 있다(위 4개는 한 예시).
산출물은 `build/inprocess-e2e/demo/`(`demo-tia.db`·`demo.diff`·`report.html`) 아래에 격리되므로,
2부 [§2 인덱싱](#2-인덱싱-베이스라인-스냅샷)에서 만드는 실제 개발용 인덱스와 섞이지 않는다.
레포 밖(체크아웃하지 않은 상태)에서 실행하면 exit 1과 `git clone` 안내가 뜬다.

### ③ 내 프로젝트에 적용하기

1. **`tia init`** — 프로젝트를 감지해 `tia.yml`을 만드는 마법사. 수집 토폴로지(스레드 기준 — "같은
   JVM이냐"가 아니라 "프로덕션 코드가 어느 스레드에서 실행되나")를 묻는다:

   ```bash
   "$CLI" init --topology in-process        # 또는 out-of-process
   ```

   어느 쪽을 골라야 하는지는 2부 [§1 수집 모델 결정](#1-per-test-커버리지-수집--testwisejson)의 표를
   본다. 비대화형(CI 등)에서 `--topology`를 생략하면 exit 2로 막고 플래그를 안내하고, TTY에서 실행하면
   대화형으로 물어본다. 이미 상위 디렉터리에 `tia.yml`이 있으면 그 경로를 알려주며 exit 1(덮어쓰려면
   `--force`). 생성이 끝나면 다음 단계 명령을 그대로 출력해 준다.

2. **토폴로지별 최소 수집** — 자세한 절차·경고는 2부 §1(및 기존 레포에 전역 적용하려면
   [§1.1](#11-기존-레포에-적용할-때-in-process)):
   - **in-process**: pjacoco in-process 에이전트(`-javaagent`)를 테스트 JVM에 붙여 실행 → `.exec`.
   - **out-of-process**: pjacoco out-of-process(parallel-per-test-coverage) 에이전트를 SUT에 부착 →
     `.exec`.

3. **`tia index` / `tia impact`**:

   ```bash
   "$CLI" convert --exec-dir <execDir> --classes <classesDir> --out testwise.json
   "$CLI" index --report testwise.json --repo <name> --commit "$(git rev-parse HEAD)"
   "$CLI" impact --commit <baseline-sha>
   ```

   `--db`를 생략하면 git-common-dir 아래 공유 경로가 기본값이다 — 자세한 옵션·기본 DB 경로는 2부
   [§2](#2-인덱싱-베이스라인-스냅샷)/[§3](#3-변경-영향-테스트-선별). 인터랙티브 리포트는
   [§4](#4-선택-인터랙티브-리포트), CI/에이전트 연동은 [§5](#5-ci--에이전트-통합).

### ④ 막히면 `tia doctor`

환경·설정·인덱스 상태를 6개 항목(JDK 17+ · git 레포 여부 · `tia.yml` 존재·유효성 · 인덱스 DB 존재 ·
DB 베이스라인↔HEAD 정렬 · pjacoco 에이전트 jar)으로 진단하고, 항목마다 PASS/WARN/FAIL/SKIP과 한 줄
처방을 낸다:

```bash
"$CLI" doctor
```

```
[PASS] JDK 17+ — java.version=17.0.15
[PASS] git 레포 여부 — git 루트: /path/to/repo
[WARN] tia.yml 존재·유효성 — tia.yml 없음 — 기본값으로 동작 (tia init 으로 tia.yml을 생성하세요)
[PASS] 인덱스 DB 존재 — DB 파일 존재: /path/to/repo/.git/tia/tia.db
[WARN] DB 베이스라인 ↔ HEAD 정렬 — HEAD(<sha>) 베이스라인 없음 (tia index --commit <sha> 로 재인덱싱하세요)
[PASS] pjacoco 에이전트 jar — 존재: /path/to/repo/tools/pjacoco/jacocoagent-parallel.jar
요약: PASS 4, WARN 2, FAIL 0, SKIP 0
```

FAIL이 하나라도 있으면 exit 1(그 외 WARN/SKIP은 0). 진단은 읽기 전용이다 — 인덱스 DB가 없으면 그
검사도 DB 파일을 만들지 않는다. 스크립트/에이전트가 소비하려면 `--format json`
(`{schemaVersion, command, checks[], summary}`).

---

## 2부 — 레퍼런스

> 아래는 1부에서 링크한 상세 절이다. 처음 적용한다면 위 1부부터 따라가는 편이 빠르다.

### 0. 설치 (택1)

| 방식 | 명령 / 이름 |
|---|---|
| fat-jar | `./gradlew :tia-cli:shadowJar` → `java -jar tia-cli/build/libs/tia.jar` |
| installDist 런처 | `./gradlew :tia-cli:installDist` → `tia-cli/build/install/tia/bin/tia` |
| 라이브러리/CLI(GitHub Packages) | `io.tia:tia-cli:<ver>` · `io.tia:tia-core:<ver>` |
| Docker | `docker run ghcr.io/baekchangjoon/tia:<ver> --help` |
| Gradle 플러그인 | `plugins { id 'io.tia' version '<ver>' }` ([가이드](tia-gradle-plugin/README.md)) |
| Agent Skill (Claude·Kiro·Antigravity) | `skills/tia/` 설치 ([SKILL.md](skills/tia/SKILL.md)) |

아래 예시는 `CLI=tia-cli/build/install/tia/bin/tia` 기준(또는 `java -jar … tia.jar`).

### 1. per-test 커버리지 수집 → `testwise.json`

테스트별로 어떤 프로덕션 라인을 실행했는지 수집한다. **두 모델** 중 프로젝트에 맞는 쪽:

- **in-process** (단위·통합 테스트가 코드와 같은 JVM): **pjacoco in-process** 에이전트(`-javaagent`)를
  테스트 JVM에 붙이고 `@ExtendWith(PjacocoInProcessExtension)`이 테스트마다 start/stop 신호.
  `aggregate=false`·`port=0`(시스템 할당)으로 구성하며, 서비스는 테스트에서 직접 호출(HTTP 불필요).
  수집 결과(`.exec`) → `tia convert` → `testwise.json`. 병렬 실행 제약이 없다(포트 충돌 없음).
  워크된 예: [`scripts/run-inprocess-e2e.sh`](scripts/run-inprocess-e2e.sh) (또는 `tia demo` — 1부 ②).
- **out-of-process** (HTTP 블랙박스, SUT 별도 프로세스): **parallel-per-test-coverage**(pjacoco) 에이전트를
  SUT에 붙이고 요청 `test.id` baggage로 per-test `.exec` 수집 → **`tia convert`**:
  ```bash
  "$CLI" convert --exec-dir <execDir> --classes build/classes/java/main --out testwise.json
  ```
  와이어링은 pjacoco 자체 Gradle 플러그인 + 테스트킷이 권장 경로다(공개 배포 후) — [플러그인 가이드 §(a)](tia-gradle-plugin/README.md).
  per-test만 소비하므로 에이전트의 `aggregate`는 끈다(`aggregate=false`; 기본 ON이면 전체-실행 `aggregate.exec`가 함께 떨어진다).
  워크된 예: [petclinic-demo](petclinic-demo/README.md).

> **수집 모델 결정(스레드 토폴로지 축).** "같은 JVM이냐"가 아니라 **"프로덕션 코드가 어느 스레드에서
> 실행되나"**로 고른다:
>
> | 테스트가 프로덕션 코드를 실행하는 방식 | 실행 스레드 | 수집 모델 |
> |---|---|---|
> | 직접 호출 / MockMvc / `@WebMvcTest` / `@SpringBootTest(webEnvironment=MOCK)` / WebTestClient(bindToApplicationContext) | 테스트 스레드 | **in-process** OK |
> | `@SpringBootTest(RANDOM_PORT` 또는 `DEFINED_PORT)` + RestAssured/TestRestTemplate/WebTestClient(bindToServer) · WebSocket/STOMP · `@Async` | 워커/다른 스레드 | **out-of-process baggage 필수** |
>
> in-process로 HTTP 블랙박스(RANDOM_PORT)를 수집하면 프로덕션 코드가 Tomcat 워커 스레드에서 돌아
> 커버리지가 **침묵 손실**된다. 이 경우 pjacoco 에이전트가 WARN/카운터를 내고, sidecar에
> `incompleteAttribution`이 찍히며, `tia convert`가 기본으로 **exit 1**(`--allow-incomplete`로 우회).
> `tia init`(1부 ③)의 토폴로지 질문은 이 표와 같은 기준으로 결정 트리를 안내한다. 프로덕션 코드가
> in-process/out-of-process 둘 다에서 실행되는(단위 테스트 + RANDOM_PORT 통합 테스트 혼재) 프로젝트라면
> 토폴로지는 테스트 단위 속성이다 — 두 모델을 병용하면 된다.
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

> 이미 `testwise.json`(또는 다른 도구의 동등 산출물)이 있으면 1단계는 건너뛴다 — 형식은
> [petclinic-demo/README §testwise.json 형식](petclinic-demo/README.md#testwisejson-형식).

#### 1.1 기존 레포에 적용할 때 (in-process)

수백 개의 테스트 클래스에 `@ExtendWith`를 일일이 추가하는 것은 비현실적이다. 대신 JUnit 5
자동 등록으로 확장을 전역 적용한다(테스트 코드 무수정):

1. 확장 자동 감지를 켠다 — `junit-platform.properties` 또는 시스템 프로퍼티:
   `junit.jupiter.extensions.autodetection.enabled=true`
2. 서비스 파일로 확장을 등록한다 —
   `META-INF/services/org.junit.jupiter.api.extension.Extension`에 한 줄:
   `io.pjacoco.testkit.junit5.PjacocoInProcessExtension`
3. 에이전트(`-javaagent:pjacoco-agent.jar=aggregate=false,port=0,includes=<패키지>`)는
   Gradle init script로 모든 `test` 태스크에 주입한다(빌드 스크립트 수정 없이).

> **확장/서비스 jar을 테스트 클래스패스에 추가할 때 `test.classpath`를 재할당하지 않는다.**
> `test.classpath = test.classpath + files(...)`는 설정 시점에 조기 평가되어 런타임 클래스패스가
> 깨지고, JUnit 엔진이 누락되어 모든 테스트가 `Cannot create Launcher without any TestEngine`으로
> 실패한다. 대신 `testRuntimeOnly(files(...))`로 추가한다.

> **`includes`는 프로덕션 패키지만 지정한다.** `includes=com.acme.*`처럼 테스트까지 포함하면
> 테스트 클래스도 계측되어, `class-dir`(main만)에 없는 클래스에 대해 매 테스트마다
> `Found coverage for class not provided` 경고가 쌓인다. 프로덕션 패키지로 좁히거나 `*Test`를 제외한다.

#### 1.2 정상 경고 (수집/convert)

다음 경고는 산출물에 per-test 커버리지가 정상적으로 들어 있어도 출력된다 — 실패가 아니다:

- `No test details found …` — details는 TIA 메타데이터로, 커버리지 산출과는 무관하다.
- `Session with empty name detected, possibly indicating intermediate coverage`
- `Found coverage for N classes that were not provided …` — 위 `includes` 항목 참고.

첫 수집에서 `0 Details` / `N Results`는 실패로 오해하기 쉽지만, `testwise.json`에 per-test
커버리지가 들어 있으면 정상이다.

### 2. 인덱싱 (베이스라인 스냅샷)

```bash
# --db 생략 → git-common-dir 기본 경로 자동 사용 (아래 박스 참조)
"$CLI" index --report testwise.json --repo my-service --commit "$(git rev-parse HEAD)"
```

> **인덱스 저장 위치 (`tia.db`).** `tia.db`는 **git에 커밋하지 않는다** — 바이너리 파일이라 diff가 의미없고,
> 워크트리마다 충돌을 일으키며 레포를 비대하게 만든다. `.gitignore`에 `tia.db`를 추가해 둘 것.
>
> 권장 위치: **git common dir** 아래의 비트래킹 경로.
> ```bash
> DB="$(git rev-parse --git-common-dir)/tia/tia.db"
> mkdir -p "$(dirname "$DB")"
> "$CLI" index ... --db "$DB"
> "$CLI" impact --db "$DB" ...
> ```
> 이렇게 하면 메인 체크아웃과 모든 워크트리가 같은 DB를 공유한다. 인덱스는 `commit_sha`를 키로
> 저장하므로 어느 워크트리에서 조회해도 동일한 스냅샷이 반환된다. `$XDG_CACHE_HOME/tia/` 또는
> 팀 공유 경로도 같은 방식으로 쓸 수 있다.
>
> **`--db` 기본값.** `--db`를 생략하면 git 레포에선 `<git-common-dir>/tia/tia.db`(예: `.git/tia/tia.db`,
> 모든 worktree 공유), 비 git 환경에선 `${XDG_CACHE_HOME:-~/.cache}/tia/tia.db`를 자동 사용한다.
> 기본 경로를 쓸 때 각 커맨드는 stderr에 `INFO: 기본 인덱스 DB: <path>`를 한 줄 안내한다.
> CI/컨테이너처럼 cwd가 불확실하거나 git이 없는 환경에선 `--db`로 명시 전달을 권장한다.

### 3. 변경 영향 테스트 선별

`index`와 같은 레포에서 실행하면 `--db` 없이도 같은 기본 경로(git-common-dir)로 수렴한다.

```bash
# 워킹트리/브랜치 변경을 인덱싱 커밋과 교차 (two-dot git diff)
"$CLI" impact --commit <baseline-sha>
# 또는 미리 만든 diff 파일로:
"$CLI" impact --commit <baseline-sha> --diff-file change.diff
```
출력: `DETERMINISTIC|CONSERVATIVE  <testId>` — 이 목록만 실행하면 된다. 베이스라인이 없으면
`# tia:no-baseline`(→ 전체 실행 권장; `--strict`면 실패).

> **출력 형식.** 기본값(`text`, 위 예시)은 기존 스크립트가 파싱하는 형식 그대로 동결돼 있다.
> 사람이 읽기 좋은 요약은 `--format summary`, PR 코멘트용은 `--format markdown`, 에이전트/스크립트
> 소비용은 `--format json`을 쓴다(`flaky`도 동일 옵션 지원). 아래 [tia.yml 설정](#tiayml-설정)의
> code/test 필터도 `impact`·`flaky`·`report`에 함께 적용된다.

### 4. (선택) 인터랙티브 리포트

```bash
"$CLI" report --testwise testwise.json --commit <sha> --out report.html --sut-name my-service \
  [--scenarios -] [--flaky -] [--prod-files -] [--jacoco-dir jacoco] [--test-src-root src/test/java]
```
탭 해설: [REPORT-GUIDE.md](petclinic-demo/REPORT-GUIDE.md). 옵셔널 입력은 `-`로 생략(탭이 graceful하게 비워짐).
생성된 `report.html`에는 각 탭 `<h2>` 아래 "이 탭 읽는 법" 접이식 요약이 내장돼 있어, 별도 문서 없이도
탭별 읽는 법을 바로 확인할 수 있다(REPORT-GUIDE.md는 더 상세한 버전).

### 5. CI / 에이전트 통합

- **GitHub Action**(PR에서 선별): [`action.yml`](action.yml) — `db`/`commit`/`diff-file` 입력 →
  Job Summary + `selected`/`run-all` 출력. `pr-comment: 'true'`(옵션, 기본 `'false'`)로 결과를 PR
  코멘트로도 게시할 수 있다(`permissions: pull-requests: write` 필요). [docker/README](docker/README.md).
- **Gradle 플러그인**: `tiaIndex`/`tiaImpact`/`tiaReport` 태스크 + 에이전트 와이어링. [가이드](tia-gradle-plugin/README.md).
- **Agent Skill**: Claude·Kiro·Antigravity 등에서 "이 변경에 영향받는 테스트?"를 자연어로. [SKILL.md](skills/tia/SKILL.md).

### tia.yml 설정

레포 루트에 `tia.yml` 하나를 두면 `impact`/`flaky`/`report`/`index`가 `--config <path>` 없이도
git 루트까지 상향 탐색으로 찾아 읽는다(`--config`를 명시하면 그 파일만 쓰고 상향 탐색은
완전히 건너뛴다). **`tia.yml`이 없으면 아무 것도 바뀌지 않는다** — 기존 사용자는 이 절을
몰라도 지금까지와 동일하게 동작한다. `tia init`(1부 ③)이 이 파일을 함정 경고 주석과 함께
생성해 준다.

```yaml
# tia.yml — 레포 루트
version: 1                  # 필수. 미지원 값이면 exit 1
sut-name: my-service        # report --sut-name 기본값 (선택)
# db: /shared/tia.db        # --db 기본값 (선택). 미선언 시 기존 git-common-dir 기본값 사용 —
                             # 워크트리-상대 경로는 워크트리 간 DB 분열을 일으키므로 권장하지 않음
                             # (위 "인덱스 저장 위치" 박스 참조)
filters:
  code:                     # 프로덕션 코드
    include: ["com/acme/**"]
    exclude: ["**/generated/**", "**/*Dto.java"]
  test:                     # 테스트
    include: []             # 비면 전체
    exclude: ["**/*Slow*"]
```

YAML 파싱 실패·미지원 `version`·알 수 없는 최상위 키·글로브 문법 오류는 모두 **즉시 exit 1**과
파일·위치·원인 메시지를 낸다 — 침묵 무시하지 않는다.

**반드시 알아야 할 세 가지:**

1. **CLI 플래그는 tia.yml 목록을 "대체"한다 — 병합이 아니다.** `--include-code`/`--exclude-code`/
   `--include-test`/`--exclude-test`(반복 가능)를 하나라도 주면, 그 축의 목록 전체가 플래그 값으로
   바뀐다. 예: tia.yml에 `code.include`와 `code.exclude`가 둘 다 있어도 `--exclude-code`만 주면
   `code.exclude`만 대체되고 `code.include`는 tia.yml 값이 그대로 유지된다 — 두 목록을 합치는
   게 아니다. "일부만 추가하려고" 플래그를 줬는데 나머지 목록이 사라진 것처럼 보인다면 이 규칙
   때문이다.
2. **code 글로브는 `src/main/java/` 같은 소스 경로가 아니라 package-relative 정규화 경로에
   매칭한다.** 예를 들어 실제 파일이 `src/main/java/com/acme/pricing/PricingService.java`여도
   글로브는 `com/acme/pricing/PricingService.java`(정규화된 키)에 매칭해야 한다.
   `src/main/java/com/acme/**` 같은 글로브는 **아무 것도 매칭하지 않는다** — 가장 흔한 함정이다
   (`tia init`이 생성하는 `tia.yml` 주석도 이 함정을 경고한다).
3. **`exclude`는 "이 경로/테스트는 TIA 판정 범위 밖"이라는 선언이다.** 제외한 경로의 변경은
   TIA가 영향 분석에서 아예 빼버리므로, **그 경로의 회귀는 TIA가 잡아주지 못한다.** 생성 코드나
   DTO 노이즈를 줄이려는 의도라도, exclude 범위가 넓을수록 회귀 누출 위험이 커진다 — 필터가
   실제로 변경을 무시할 때마다 `impact`가 stderr에 `# WARN: excluded change ignored: <path>`를
   출력하니 그 경고를 무시하지 말 것.

### 플레이키(부가)

```bash
"$CLI" flaky --runs run1.json,run2.json,run3.json   # 결과(P/F) 흔들림 비율
```

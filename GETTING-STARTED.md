# Getting started — 내 프로젝트에 TIA 적용하기

목표: **코드 변경 → 영향받는 테스트만 선별**(실행 시간↓) + **인터랙티브 리포트**. 5분 길잡이.

> 빠르게 동작부터 보고 싶으면 → **[빠른 시작(fixture 1줄 E2E)](README.md#빠른-시작-1줄-e2e)** 또는
> 실제 블랙박스 스위트 예제 **[petclinic-demo](petclinic-demo/README.md)**. 아래는 *당신의 JVM
> 프로젝트*에 적용하는 일반 절차다.

## 0. 설치 (택1)

| 방식 | 명령 / 이름 |
|---|---|
| fat-jar | `./gradlew :tia-cli:shadowJar` → `java -jar tia-cli/build/libs/tia.jar` |
| installDist 런처 | `./gradlew :tia-cli:installDist` → `tia-cli/build/install/tia/bin/tia` |
| 라이브러리/CLI(GitHub Packages) | `io.tia:tia-cli:<ver>` · `io.tia:tia-core:<ver>` |
| Docker | `docker run ghcr.io/baekchangjoon/tia:<ver> --help` |
| Gradle 플러그인 | `plugins { id 'io.tia' version '<ver>' }` ([가이드](tia-gradle-plugin/README.md)) |
| Agent Skill (Claude·Kiro·Antigravity) | `skills/tia/` 설치 ([SKILL.md](skills/tia/SKILL.md)) |

아래 예시는 `CLI=tia-cli/build/install/tia/bin/tia` 기준(또는 `java -jar … tia.jar`).

## 1. per-test 커버리지 수집 → `testwise.json`

테스트별로 어떤 프로덕션 라인을 실행했는지 수집한다. **두 모델** 중 프로젝트에 맞는 쪽:

- **in-process** (단위·통합 테스트가 코드와 같은 JVM): **pjacoco in-process** 에이전트(`-javaagent`)를
  테스트 JVM에 붙이고 `@ExtendWith(PjacocoInProcessExtension)`이 테스트마다 start/stop 신호.
  `aggregate=false`·`port=0`(시스템 할당)으로 구성하며, 서비스는 테스트에서 직접 호출(HTTP 불필요).
  수집 결과(`.exec`) → `tia convert` → `testwise.json`. 병렬 실행 제약이 없다(포트 충돌 없음).
  워크된 예: [`scripts/run-inprocess-e2e.sh`](scripts/run-inprocess-e2e.sh).
- **out-of-process** (HTTP 블랙박스, SUT 별도 프로세스): **parallel-per-test-coverage**(pjacoco) 에이전트를
  SUT에 붙이고 요청 `test.id` baggage로 per-test `.exec` 수집 → **`tia convert`**:
  ```bash
  "$CLI" convert --exec-dir <execDir> --classes build/classes/java/main --out testwise.json
  ```
  와이어링은 pjacoco 자체 Gradle 플러그인 + 테스트킷이 권장 경로다(공개 배포 후) — [플러그인 가이드 §(a)](tia-gradle-plugin/README.md).
  per-test만 소비하므로 에이전트의 `aggregate`는 끈다(`aggregate=false`; 기본 ON이면 전체-실행 `aggregate.exec`가 함께 떨어진다).
  워크된 예: [petclinic-demo](petclinic-demo/README.md).

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

### 1.1 기존 레포에 적용할 때 (in-process)

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

### 1.2 정상 경고 (수집/convert)

다음 경고는 산출물에 per-test 커버리지가 정상적으로 들어 있어도 출력된다 — 실패가 아니다:

- `No test details found …` — details는 TIA 메타데이터로, 커버리지 산출과는 무관하다.
- `Session with empty name detected, possibly indicating intermediate coverage`
- `Found coverage for N classes that were not provided …` — 위 `includes` 항목 참고.

첫 수집에서 `0 Details` / `N Results`는 실패로 오해하기 쉽지만, `testwise.json`에 per-test
커버리지가 들어 있으면 정상이다.

## 2. 인덱싱 (베이스라인 스냅샷)

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

## 3. 변경 영향 테스트 선별

`index`와 같은 레포에서 실행하면 `--db` 없이도 같은 기본 경로(git-common-dir)로 수렴한다.

```bash
# 워킹트리/브랜치 변경을 인덱싱 커밋과 교차 (two-dot git diff)
"$CLI" impact --commit <baseline-sha>
# 또는 미리 만든 diff 파일로:
"$CLI" impact --commit <baseline-sha> --diff-file change.diff
```
출력: `DETERMINISTIC|CONSERVATIVE  <testId>` — 이 목록만 실행하면 된다. 베이스라인이 없으면
`# tia:no-baseline`(→ 전체 실행 권장; `--strict`면 실패).

## 4. (선택) 인터랙티브 리포트

```bash
"$CLI" report --testwise testwise.json --commit <sha> --out report.html --sut-name my-service \
  [--scenarios -] [--flaky -] [--prod-files -] [--jacoco-dir jacoco] [--test-src-root src/test/java]
```
탭 해설: [REPORT-GUIDE.md](petclinic-demo/REPORT-GUIDE.md). 옵셔널 입력은 `-`로 생략(탭이 graceful하게 비워짐).

## 5. CI / 에이전트 통합

- **GitHub Action**(PR에서 선별): [`action.yml`](action.yml) — `db`/`commit`/`diff-file` 입력 →
  Job Summary + `selected`/`run-all` 출력. [docker/README](docker/README.md).
- **Gradle 플러그인**: `tiaIndex`/`tiaImpact`/`tiaReport` 태스크 + 에이전트 와이어링. [가이드](tia-gradle-plugin/README.md).
- **Agent Skill**: Claude·Kiro·Antigravity 등에서 "이 변경에 영향받는 테스트?"를 자연어로. [SKILL.md](skills/tia/SKILL.md).

## 플레이키(부가)

```bash
"$CLI" flaky --runs run1.json,run2.json,run3.json   # 결과(P/F) 흔들림 비율
```

# test-impact-analysis (TIA)

[![CI](https://github.com/baekchangjoon/test-impact-analysis/actions/workflows/ci.yml/badge.svg)](https://github.com/baekchangjoon/test-impact-analysis/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17-blue)
![Build](https://img.shields.io/badge/build-Gradle-green)
![Status](https://img.shields.io/badge/status-PoC%20complete-success)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**테스트 영향 분석(Test Impact Analysis).** `git diff`로 바뀐 코드 라인과 **테스트별(per-test) 커버리지**를 교차해서 두 가지 질문에 결정론적으로 답합니다.

1. **이 변경으로 어떤 테스트를 돌려야 하나?** — `테스트가 커버한 라인 ∩ diff 라인 ≠ ∅` 이면 영향 있음 → 선별 실행(RTS/TIA)
2. **이 테스트 실패가 코드 때문인가, 인프라/플레이키인가?** — `실패 테스트의 커버집합 ∩ diff = ∅` 이면 코드 무관 → 인프라·플레이키 의심

> 비트 연산 자체는 결정론적입니다. 다만 그 신뢰도는 커버리지 매핑 품질에 종속되므로, 커버리지가 닿지 않는 변경(설정/SQL/의존성)과 신규 코드는 **보수적으로 전체 선택**합니다(거짓 "인프라" 면죄부·회귀 누출 방지).

이 저장소는 위 설계의 **PoC** 구현체입니다. 단일 레포에서 [teamscale-jacoco-agent](https://github.com/cqse/teamscale-java-profiler)로 per-test 커버리지를 수집 → `git diff`와 교차 → **영향 테스트 선별** + **플레이키 비율 측정**까지 동작합니다.

> 🚀 **내 프로젝트에 적용하려면 → [GETTING-STARTED.md](GETTING-STARTED.md).** 바로 동작을 보려면 아래 [빠른 시작](#빠른-시작-1줄-e2e). 배포 형태(CLI·Docker·Gradle 플러그인·Agent Skill)는 [사용 형태](#사용-형태-배포-표면).

---

## 아이디어

- **동적 우선.** Spring 프록시/AOP/리플렉션/비동기는 정적 호출그래프를 흔듭니다. 그래서 "테스트 T가 실제로 실행하며 밟은 라인"을 동적으로 수집해 **판정의 주(主) 근거**로 삼습니다. 정적 분석은 보조(이후 확장 구상).
- **out-of-process 블랙박스.** 측정 대상 앱에 JaCoCo 에이전트를 붙여 띄우고, 테스트는 HTTP로만 호출합니다. 테스트 시작/종료를 에이전트에 신호(`/test/start`·`/test/end`)해 per-test로 커버리지를 분리합니다.
- **커밋이 기준점.** 커버리지 매핑은 특정 빌드 스냅샷이라, 인덱싱 커밋과 diff의 라인 공간이 일치할 때만 비트 AND가 유효합니다. 이 구현은 "diff 베이스 = 인덱싱 커밋"을 불변식으로 둡니다.

전체 설계와 근거는 **[설계 문서](docs/superpowers/specs/2026-06-13-test-impact-analysis-design.md)**, 구현 계획은 **[PoC 계획](docs/superpowers/plans/2026-06-13-tia-phase0-poc.md)** 참조.

---

## 구성

Gradle 멀티모듈입니다.

```
test-impact-analysis/
├── tia-core/             # 순수 로직: diff·testwise 파싱, 교차 판정, SQLite 저장, 플레이키 집계 (결정론적, TDD)
├── tia-cli/              # CLI: index / impact / flaky (picocli)
├── tia-junit-extension/  # JUnit5 확장: 테스트별로 에이전트에 시작/종료 신호 (Java 8 호환)
├── fixture-app/          # 수집 대상 최소 Spring Boot 앱 + RestAssured 스위트
├── e2e/                  # 스펙 수용(acceptance) E2E
├── coverage/             # 집계 JaCoCo 커버리지 리포트
├── scripts/              # run-poc.sh, measure-flaky.sh, download-agent.sh …
├── docker-compose.e2e.yml# 컨테이너 간 블랙박스 E2E
└── docs/superpowers/     # 설계 문서 + 구현 계획
```

| 모듈 | 역할 |
|------|------|
| `tia-core` | diff·커버리지 파싱, `라인 ∩ diff` 교차 판정, SQLite 스냅샷 저장, 플레이키 집계 |
| `tia-cli` | `tia index` / `tia impact` / `tia flaky` |
| `tia-junit-extension` | `@BeforeEach`/`@AfterEach`로 에이전트에 per-test 경계 신호 |
| `fixture-app` | 파이프라인을 끝까지 검증하기 위한 in-repo Spring Boot 앱 |

---

## 사전 요구사항

- **JDK 17** (코드가 Java 17 바이트코드)
- **Git**, **bash**, **curl**, **unzip**
- 컨테이너 E2E를 돌릴 경우 **Docker** (`docker compose`)
- Gradle은 포함된 wrapper(`./gradlew`) 사용 — 별도 설치 불필요

> macOS는 `/usr/libexec/java_home -v 17`로 JDK 17을 자동 선택합니다. 다른 OS는 `JAVA_HOME`을 JDK 17로 지정하세요.

---

## 빠른 시작 (1줄 E2E)

커버리지 에이전트를 받고, 전체 파이프라인을 한 번에 돌립니다.

```bash
# 1) teamscale-jacoco-agent 다운로드 (tools/ 에 압축 해제)
bash scripts/download-agent.sh

# 2) 수집 → 인덱싱 → diff 교차 → 영향 테스트 선별까지 전체 E2E
bash scripts/run-poc.sh
```

`run-poc.sh`는 다음을 자동으로 수행합니다.

1. `fixture-app`을 에이전트와 함께 기동
2. 엔드포인트 응답값 검증 (`greeting='hello alice'`, `price=300`)
3. 3개 테스트(`testGreeting`·`testPrice`·`testFlaky`)를 per-test로 커버리지 수집
4. testwise JSON 으로 변환 후 `tia index`
5. `PricingService.java` 한 줄을 실제로 바꿔 `git diff` 생성 → `tia impact`

**기대 출력 (마지막 줄):**

```
===== tia impact (PricingService 변경 → testPrice 선별 기대) =====
# 매핑 기준 커밋: <sha>  (영향 테스트 1개)
DETERMINISTIC	io/tia/fixture/ApiSmokeTest/testPrice
✅ E2E PASS: testPrice DETERMINISTIC 선별, testGreeting 제외
```

→ `PricingService`만 바꿨으니 그 라인을 밟은 `testPrice`만 선별되고, `testGreeting`은 제외됩니다.

---

## 설치

세 가지 방법 중 하나:

```bash
# (a) installDist 런처
./gradlew :tia-cli:installDist
CLI=tia-cli/build/install/tia/bin/tia

# (b) 단일 실행 fat-jar
./gradlew :tia-cli:shadowJar
java -jar tia-cli/build/libs/tia.jar --help     # → tia <ver>

# (c) 라이브러리 의존(임베딩) — GitHub Packages (태그 릴리스 시 게시; .github/workflows/release.yml)
#   io.tia:tia-core:<ver>  ·  io.tia:tia-cli:<ver>   (repo: maven.pkg.github.com/baekchangjoon/test-impact-analysis)
```

## CLI 사용법

```bash
$CLI --help          # convert | index | impact | flaky | report
```

### 0. `convert` — per-test `.exec` → testwise JSON (jacoco core, subprocess 없음)

```bash
$CLI convert --exec-dir <execDir> --classes build/classes/java/main --out testwise.json
```

parallel-per-test-coverage(out-of-process) 수집물을 인덱싱 입력으로 변환. teamscale(in-process)은
자체 `convert`로 testwise JSON을 만든다(→ [GETTING-STARTED](GETTING-STARTED.md) §1).

### 1. `index` — testwise 리포트를 SQLite 스냅샷으로 인덱싱

```bash
$CLI index \
  --report poc-out/testwise.json \   # teamscale testwise coverage JSON
  --repo   fixture \
  --commit "$(git rev-parse HEAD)" \  # 이 스냅샷의 기준 커밋
  --db     poc-out/tia.db
# → indexed 3 tests @ <sha>
```

### 2. `impact` — diff와 커버리지를 교차해 영향 테스트 선별

```bash
# diff 파일을 직접 주거나
$CLI impact --db poc-out/tia.db --commit "$SHA" --diff-file my.diff

# git ref로 diff를 즉석 생성 (기본 베이스 = --commit = 인덱싱 커밋)
$CLI impact --db poc-out/tia.db --commit "$SHA"
```

출력은 신뢰도 태그와 테스트 ID 목록입니다.

```
# 매핑 기준 커밋: <sha>  (영향 테스트 1개)
DETERMINISTIC	io/tia/fixture/ApiSmokeTest/testPrice
```

| 태그 | 의미 |
|------|------|
| `DETERMINISTIC` | 커버 라인 ∩ diff ≠ ∅ — 확실히 영향 있는 테스트 |
| `CONSERVATIVE` | 커버리지가 닿지 않는 변경(설정/SQL/의존성·신규 `.java`) → 안전을 위해 전체 선택 |

> ⚠️ **라인 공간 정렬:** diff의 old-side 라인 번호는 인덱싱 커밋과 같은 공간이어야 비트 AND가 유효합니다. 그래서 `--git-ref` 기본값이 `--commit`입니다. 베이스를 다르게 주면 결과가 무효가 될 수 있습니다(설계 §6.2 [4-B]).

### 3. `flaky` — N회 실행 결과로 플레이키 비율 측정

```bash
$CLI flaky --runs poc-out/flaky/run-1.json,poc-out/flaky/run-2.json,...
# → flaky ratio: 0.333 (1/3)
#   FLAKY  io/tia/fixture/ApiSmokeTest/testFlaky
```

스위트를 N회 돌려 run-result JSON을 만들고 한 번에 집계하려면:

```bash
bash scripts/measure-flaky.sh 10   # 10회 실행 후 tia flaky 집계
```

### 4. `report` — 인터랙티브 HTML 리포트

```bash
$CLI report --testwise testwise.json --commit "$SHA" --out report.html --sut-name my-service
# 옵셔널 입력(scenarios/flaky/prod-files)은 '-' 로 생략 → 해당 탭 graceful
```

5개 탭(per-test 영향범위·역인덱스·tia impact·flaky·blind spots) 해설: [REPORT-GUIDE](petclinic-demo/REPORT-GUIDE.md).

---

## 사용 형태 (배포 표면)

엔진 하나(`tia-core`/CLI)를 여러 표면으로 감싼다 — 자세한 적용은 **[GETTING-STARTED.md](GETTING-STARTED.md)**.

| 표면 | 용도 | 문서 |
|---|---|---|
| **CLI**(fat-jar·installDist·GitHub Packages) | 로컬·스크립트·CI | 위 [CLI 사용법](#cli-사용법) |
| **Docker 이미지 + GitHub Action** | PR에서 영향 테스트 선별 | [docker/README](docker/README.md) |
| **Gradle 플러그인** `io.tia` | 빌드 네이티브(`tiaIndex/Impact/Report` + 에이전트 와이어링) | [tia-gradle-plugin](tia-gradle-plugin/README.md) |
| **Agent Skill** | Claude·Kiro·Antigravity 등에서 자연어 질의 | [skills/tia/SKILL.md](skills/tia/SKILL.md) |
| **HTML 리포트** | 영향범위·flaky·blind spot 시각화 | [REPORT-GUIDE](petclinic-demo/REPORT-GUIDE.md) |

릴리스(`v*` 태그)마다 GitHub Packages 게시 + GitHub Release(`tia.jar`·SBOM·고지) + ghcr 이미지가 자동 배포된다.

---

## 동작 원리

```
fixture-app (+ JaCoCo agent, TESTWISE)
        ▲  HTTP 호출
        │
RestAssured 스위트 ── JUnit5 확장 ──▶ /test/start·/test/end (per-test 경계)
        │
        ▼  testwise coverage JSON  (테스트별 covered lines)
   tia index ──▶ SQLite 스냅샷 (test_id, file, line_bitmap @ commit)
        │
   git diff --unified=0 ──▶ 변경 라인 집합
        │
   tia impact ──▶  커버 라인 ∩ diff 라인 ≠ ∅  →  DETERMINISTIC 선별
                   커버리지 매핑 불가/신규     →  CONSERVATIVE 전체 선택
```

핵심은 **라인 단위 교차**입니다. testwise JSON은 테스트마다 covered lines를 담고, diff는 바뀐 라인을 담습니다. 두 집합의 교집합이 비면 "이 변경은 그 테스트와 무관"으로 판정합니다.

---

## 컨테이너 E2E (가장 충실한 검증)

호스트에 따라 Gradle 테스트 워커(포크 JVM)의 아웃바운드가 막혀 외부 에이전트(:8123)에 못 닿는 경우가 있습니다. 컨테이너는 자체 네트워크라 이 제약을 우회하며, 설계의 out-of-process 토폴로지(`sut` ↔ `tester`)와 정확히 일치합니다.

```bash
bash scripts/download-agent.sh
./gradlew :fixture-app:bootJar :fixture-app:classes :fixture-app:testClasses :tia-cli:installDist
docker compose -f docker-compose.e2e.yml up --abort-on-container-exit --exit-code-from tester
```

- `sut` 컨테이너: `fixture-app` + teamscale 에이전트(TESTWISE)
- `tester` 컨테이너: RestAssured 스위트 + JUnit5 확장이 `sut`를 네트워크로 호출

---

## 테스트 / CI

```bash
./gradlew test testCodeCoverageReport   # 전체 단위·수용 테스트 + 집계 커버리지
```

GitHub Actions([`.github/workflows/ci.yml`](.github/workflows/ci.yml))가 PR·main 푸시마다 ① 전체 테스트 + 커버리지 ② 컨테이너 간 블랙박스 E2E(실제 teamscale 에이전트)를 돌립니다.

---

## 현재 범위 & 한계

이 PoC는 **단일 레포 직렬 수집**까지 커버합니다. 설계에는 있으나 의식적으로 이후로 미룬 항목:

- **크로스 레포/API 경계 매핑·Kafka 귀속** — 이후 확장
- **정적 호출그래프 보강**(커버리지 사각지대) — 이후 확장
- **실패 분류기 → qe-rca-action DETERMINISTIC 신호 주입** — 이후 확장
- **PR 코멘트 이원화·자체 MCP 서버** — 이후 확장 (현재는 CLI 텍스트 출력)
- **staleness 저신뢰 플래그·라인 재조정** — 현재는 "diff 베이스 = 인덱싱 커밋" 불변식으로 우회
- **병렬 수집**(`jacocoagent-parallel.jar` 드롭인) — 직렬 스위트가 나이틀리 윈도우를 초과할 때 전환

알려진 환경 제약: 일부 샌드박스에서 Gradle 테스트 워커의 아웃바운드가 막힙니다 → `run-poc.sh`는 확장과 동일한 HTTP 신호를 `curl`로 대체하고, 가장 충실한 검증은 위 **컨테이너 E2E**로 합니다.

---

## 문서

- **[GETTING-STARTED.md](GETTING-STARTED.md)** — 내 프로젝트에 적용하는 5분 길잡이(수집→index→impact→report)
- [petclinic-demo](petclinic-demo/README.md) — 실제 블랙박스 스위트 end-to-end 예제 + "이미 커버리지가 있을 때"
- [REPORT-GUIDE](petclinic-demo/REPORT-GUIDE.md) — HTML 리포트 5개 탭 해설
- [배포 형태] [Gradle 플러그인](tia-gradle-plugin/README.md) · [Docker/Action](docker/README.md) · [Agent Skill](skills/tia/SKILL.md)
- [TIA 시스템 설계](docs/superpowers/specs/2026-06-13-test-impact-analysis-design.md) · [배포 설계 (D0~D4)](docs/superpowers/specs/2026-06-15-tia-distribution-design.md)
- [PoC 구현 계획](docs/superpowers/plans/2026-06-13-tia-phase0-poc.md)
- [THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES.md) — 번들 서드파티 라이선스 고지

## 라이선스

[MIT](LICENSE) © 2026 baekchangjoon

배포 산출물(fat-jar `tia.jar`·Docker 이미지·GitHub Packages)에는 서드파티가 번들된다 — Jackson·
picocli·RoaringBitmap·SQLite JDBC(Apache-2.0), ASM(BSD-3-Clause), **JaCoCo core/report(EPL-2.0)**.
모두 MIT와 양립하며 EPL-2.0은 파일 단위 약copyleft라 TIA 코드에 전염되지 않는다. 전체 고지·라이선스
전문은 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) + [`licenses/`](licenses) 참조(fat-jar
`META-INF/`·이미지 `/licenses`·릴리스 자산에도 동봉).

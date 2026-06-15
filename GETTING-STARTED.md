# Getting started — 내 프로젝트에 TIA 적용하기

목표: **코드 변경 → 영향받는 테스트만 선별**(실행 시간↓) + **인터랙티브 리포트**. 5분 길잡이.

> 빠르게 동작부터 보고 싶으면 → **[빠른 시작(fixture 1줄 E2E)](README.md#빠른-시작-1줄-e2e)** 또는
> 실제 블랙박스 스위트 예제 **[petclinic-demo](petclinic-demo/README.md)**. 아래는 *당신의 JVM
> 프로젝트*에 적용하는 일반 절차다.

## 0. 설치 (택1)

| 방식 | 명령 / 좌표 |
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

- **in-process** (단위·통합 테스트가 코드와 같은 JVM): **teamscale-jacoco-agent**(`mode=TESTWISE`)를
  테스트 JVM에 붙이고 `tia-junit-extension`(`@ExtendWith(io.tia.junit.TeamscaleTestwiseExtension)`)이
  테스트마다 start/end 신호 → `out` 디렉터리 → teamscale `convert`로 `testwise.json`.
  워크된 예: [`scripts/run-poc.sh`](scripts/run-poc.sh). 플러그인은 `attachTeamscaleAgent(...)` 제공.
- **out-of-process** (HTTP 블랙박스, SUT 별도 프로세스): **parallel-per-test-coverage** 에이전트를
  SUT에 붙이고 요청 `test.id` baggage로 per-test `.exec` 수집 → **`tia convert`**:
  ```bash
  "$CLI" convert --exec-dir <execDir> --classes build/classes/java/main --out testwise.json
  ```
  워크된 예: [petclinic-demo](petclinic-demo/README.md).

> 이미 `testwise.json`(또는 다른 도구의 동등 산출물)이 있으면 1단계는 건너뛴다 — 형식은
> [petclinic-demo/README §testwise.json 형식](petclinic-demo/README.md#testwisejson-형식).

## 2. 인덱싱 (베이스라인 스냅샷)

```bash
"$CLI" index --report testwise.json --repo my-service --commit "$(git rev-parse HEAD)" --db tia.db
```

## 3. 변경 영향 테스트 선별

```bash
# 워킹트리/브랜치 변경을 인덱싱 커밋과 교차 (two-dot git diff)
"$CLI" impact --db tia.db --commit <baseline-sha>
# 또는 미리 만든 diff 파일로:
"$CLI" impact --db tia.db --commit <baseline-sha> --diff-file change.diff
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

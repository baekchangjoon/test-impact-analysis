---
name: tia
description: Test-Impact-Analysis. Given a code diff, select the tests impacted by the change from an existing TIA index, and build the interactive coverage/impact HTML report. Use when asked "which tests should I run for this change/PR?", to pick the minimal impacted test set for a diff, or to generate the TIA report. Works in any skills-compatible agent (Claude, Kiro, Antigravity, Gemini CLI, …).
---

# TIA — Test-Impact-Analysis skill

This skill is a thin wrapper over the `tia` CLI. It answers, for a given code change,
**which tests are impacted** (so you run only those), and renders the **interactive HTML
report**. All logic lives in the CLI; this skill just resolves and invokes it.

## Scope (read first)

This skill **queries an existing TIA index** — it does not collect coverage. You need a
TIA database (`tia.db`) or a `testwise.json` already produced by the build/CI pipeline
(`tia convert` + `tia index`). Collecting per-test coverage (running the SUT under the
coverage agent) is the build/plugin/CI's job, not this skill's.

> **수집 모델 선택**: 인덱스를 만들 때 in/out-of-process는 "프로세스"가 아니라 **스레드 토폴로지**로
> 고른다 — 프로덕션 코드가 테스트 스레드에서 돌면 in-process, 워커 스레드(`@SpringBootTest(RANDOM_PORT)`
> +RestAssured/TestRestTemplate, WebSocket, `@Async`)면 out-of-process baggage. 잘못 고르면 커버리지가
> 침묵 손실되고 `tia convert`가 막는다. 결정 트리: GETTING-STARTED.md §1.

**No index yet?** Building the first `tia.db` is the real onboarding step. Don't report an
empty result as success — point the user to the collection runbook instead:
- **Gradle/JVM project:** [GETTING-STARTED §1 (collect → `testwise.json`)](../../GETTING-STARTED.md#1-per-test-커버리지-수집--testwisejson),
  then §2 (`tia index`). For an existing repo with many test classes, see
  [§1.1 (global extension registration, no per-class edits)](../../GETTING-STARTED.md#11-기존-레포에-적용할-때-in-process).
- **Build-native:** the [Gradle plugin guide](../../tia-gradle-plugin/README.md) (`tiaIndex` + agent attach helpers).

## Prerequisites

- **JDK 17+** on `PATH`.
- The **`tia` CLI**, resolved in this order by `scripts/run-tia.sh`:
  1. `tia` on `PATH`, else
  2. `$TIA_JAR` → `java -jar $TIA_JAR`, else
  3. a local build at `tia-cli/build/libs/tia.jar` (`./gradlew :tia-cli:shadowJar`).

  If none is found the script exits non-zero with an actionable message — **surface that
  to the user; do not report an empty result as success.**

## How to use

Always invoke the CLI through the wrapper so resolution/prechecks apply. The examples use
a path relative to the skill folder and the local-build fallback resolves from the repo
root — to stay CWD-independent, either put `tia` on `PATH`, set `$TIA_JAR`, or call the
wrapper by its absolute path:

```bash
# Which tests does this diff impact? (needs an indexed tia.db for the baseline commit)
bash scripts/run-tia.sh impact --db tia.db --commit <baseline-sha> --diff-file change.diff
# (omit --diff-file to diff the working tree vs --commit; see `… impact --help`)

# Build the interactive HTML report from a testwise.json
bash scripts/run-tia.sh report --testwise testwise.json --commit <sha> --out report.html \
  --sut-name <name> [--scenarios scenarios.json|-] [--flaky flaky.json|-] [--prod-files prod.txt|-]

# (less common, from this skill) convert per-test .exec → testwise.json
bash scripts/run-tia.sh convert --exec-dir <dir> --classes <classesDir> --out testwise.json
```

Pass `-` for optional `report` inputs you don't have; those tabs degrade gracefully.

## Interpreting `impact` output

Each selected test is printed with a confidence tag:
- `DETERMINISTIC` — the changed line is in that test's recorded coverage → run it.
- `CONSERVATIVE` — the change couldn't be mapped (new file / config) → included to be safe.

Run the selected tests; if selection is empty for an edited production file, that file is a
coverage blind spot (no test exercises it).

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
- **Not sure what's wrong?** Run `tia doctor` — it diagnoses JDK/git/`tia.yml`/index-DB/baseline
  state (PASS/WARN/FAIL/SKIP + a one-line fix for each) instead of guessing.

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
wrapper by its absolute path.

**Agents: prefer `--format json`** for `impact` (and `flaky`) — it's the versioned,
machine-readable contract (`schemaVersion`, `tests[].id/confidence/reason`, `warnings`, …)
meant for this kind of consumption; parse that instead of the default text output:

```bash
# Which tests does this diff impact? (needs an indexed tia.db for the baseline commit)
bash scripts/run-tia.sh impact --db tia.db --commit <baseline-sha> --diff-file change.diff --format json
# → {"schemaVersion":1,"tests":[{"id":"...","confidence":"DETERMINISTIC","reason":"..."}],"warnings":[]}
# (omit --diff-file to diff the working tree vs --commit; see `… impact --help`)

# Build the interactive HTML report from a testwise.json
bash scripts/run-tia.sh report --testwise testwise.json --commit <sha> --out report.html \
  --sut-name <name> [--scenarios scenarios.json|-] [--flaky flaky.json|-] [--prod-files prod.txt|-]

# (less common, from this skill) convert per-test .exec → testwise.json
bash scripts/run-tia.sh convert --exec-dir <dir> --classes <classesDir> --out testwise.json
```

Pass `-` for optional `report` inputs you don't have; those tabs degrade gracefully.

If the repo has a `tia.yml` (found by searching upward from the current directory), `impact`/
`flaky`/`report`/`index` apply its filters and `db`/`sut-name` defaults automatically — no
extra flags needed.

**MCP clients without skill support:** register `tia mcp` as a stdio MCP server —
`claude mcp add tia -- <tia 경로> mcp` — exposing `tia_impact`/`tia_doctor` as tools (the
`tia doctor` diagnostics above apply the same whether invoked via CLI or MCP).

## Interpreting `impact` output

`impact` and `flaky` also support `--format summary|markdown` for human-facing output
(default `text`, byte-frozen for existing scripts) — see [above](#how-to-use) for the
agent-preferred `json` format.

Each selected test is printed with a confidence tag:
- `DETERMINISTIC` — the changed line is in that test's recorded coverage → run it.
- `CONSERVATIVE` — the change couldn't be mapped (new file / config) → included to be safe.

Run the selected tests; if selection is empty for an edited production file, that file is a
coverage blind spot (no test exercises it).

# petclinic-demo — TIA applied to a real black-box suite

A worked, **end-to-end** example of Test-Impact-Analysis run against the out-of-process
REST Assured black-box suite of [`baekchangjoon/spring-petclinic`][petclinic] (24 test cases,
5 `…BlackBoxIT` classes). Everything here is real measurement — no mock data.

## What it shows

| View | Artifact |
|---|---|
| Interactive report (5 tabs) | [`report.html`](report.html) — open in any browser. Every column sorts asc/desc; per-test files deep-link into the JaCoCo HTML line, reverse-index tests open the local test source, the `tia impact` legend explains each term, and flaky ratios show percent + fraction. **Walkthrough: [`REPORT-GUIDE.md`](REPORT-GUIDE.md).** |
| Executive summary + caveats | [`00-SUMMARY.md`](00-SUMMARY.md) |
| Per-test impact range, reverse index, hotspots, blind spots | [`01-coverage-report.md`](01-coverage-report.md) |
| `tia impact` on 7 real diffs | [`02-impact-scenarios.txt`](02-impact-scenarios.txt) / `scenarios.json` |

Headline results: a one-line REST-controller change selects **1–3 / 24** tests (87–96 %
reduction); the suite is flaky-free across 5 runs; and only **15 / 40** production files are
touched by the black-box suite (the MVC/Thymeleaf layer and security infra are blind spots).

## Pipeline

```
petclinic bootJar (JDK21) ─► running app ◄─ -javaagent: jacocoagent-parallel.jar (port 6310)
        ▲                                       │ 1 vanilla-JaCoCo .exec per test
   ./gradlew blackboxTest (REST Assured + baggage: test.id) ┘
        │   /tmp/petclinic-coverage/<Class#method>.exec ×24
        ▼   tia convert ─► testwise.json
   tia index → tia.db   |   tia impact → selection   |   tia flaky → ratio
        │   jacococli merge+report → jacoco/ (HTML, per-test-file deep-link target)
        ▼   tia report → report.html
```

## Reproduce

```bash
bash petclinic-demo/run-petclinic-tia.sh   # build → run → collect → index → impact → flaky → HTML
open  petclinic-demo/report.html
```

Prereqs: `spring-petclinic` and [`parallel-per-test-coverage`][agent] cloned locally, JDK 21 + 17,
and `org.jacoco.cli:*:nodeps` in `~/.m2`. Paths are overridable via `PETC` / `AGENT_REPO` env vars.
The script perturbs the petclinic tree only transiently (perturb-and-revert); nothing survives.

## 이미 커버리지가 있을 때 — 리포트만 (다시) 생성

`run-petclinic-tia.sh`는 빌드부터 전부 다시 돈다. **per-test 커버리지를 이미 확보했다면**(아래
[testwise.json 형식](#testwisejson-형식)) 그 단계를 건너뛰고 리포트만 만들 수 있다. D0부터
변환·리포트는 모두 `tia` CLI 서브커맨드다(`convert`/`report`) — Python 글루(`exec_to_testwise.py`·
`make_html.py`)는 같은 동작의 deprecated 참조본이다.

```bash
CLI=tia-cli/build/install/tia/bin/tia   # 없으면: ./gradlew :tia-cli:installDist
# 또는 단일 실행 fat-jar: ./gradlew :tia-cli:shadowJar → java -jar tia-cli/build/libs/tia.jar …

# per-test .exec 가 있다면: 먼저 testwise.json 으로 변환 (jacoco core, subprocess 없음)
"$CLI" convert --exec-dir /path/to/exec --classes build/classes/java/main --out testwise.json

# (선택) tia index → SQLite 스냅샷. tia impact/flaky 를 쓸 때만 필요.
"$CLI" index --report testwise.json --repo my-service --commit "$COMMIT" --db tia.db

# 최소 리포트 — testwise.json 만으로 탭 1·2·5 생성. 옵셔널 입력은 "-" 로 생략(graceful).
"$CLI" report --testwise testwise.json --scenarios - --flaky - --prod-files - \
  --commit "$COMMIT" --out report.html --sut-name my-service
open report.html
```

탭을 더 채우려면 해당 입력을 만들어 옵션으로 넘긴다:

- **탭 3 (tia impact)** — `"$CLI" impact --db tia.db --commit "$COMMIT" --diff-file change.diff`
  로 선별을 만든 뒤 `scenarios.json`(아래 형식)으로 조립. 데모에서는 `run_scenarios.py`가 여러
  시나리오를 한 번에 만든다.
- **탭 4 (flaky)** — `"$CLI" flaky --runs run1.json,run2.json,…`(≥2회 실행 결과)를 돌려
  `flaky.json`(`{"real":{…},"synthetic":{…}}`, `synthetic`은 선택)으로 조립, `--flaky` 로 전달.
- **탭 5 (blind spots)** — 프로덕션 `.java` 목록(패키지-상대 경로, 한 줄에 하나)을 `--prod-files`
  로: `find src/main/java -name '*.java' | sed 's#src/main/java/##' > prod-files.txt`.
- **탭 1 파일 딥링크** — `--jacoco-dir` 의 JaCoCo HTML 리포트가 `report.html` 옆에 있어야 동작.
  **탭 2 테스트 열기 링크** — `--test-src-root` 에 테스트 소스 루트를 주면 `file://` 링크 생성.
  경로 축약은 `--prefix-strip <패키지접두>`.

### testwise.json 형식

`tia convert` 가 산출하고 `tia index --report`/`tia report` 가 받는 입력 계약. 다른 커버리지
도구로 뽑았어도 이 형태로만 맞추면 된다(파서: `tia-core/.../parse/TestwiseReportParser.java`).

```jsonc
{
  "tests": [
    {
      "uniformPath": "OwnerApiBlackBoxIT#getOwnerById",   // 테스트 식별자 (클래스#메서드)
      "result": "PASSED",                                  // 생략 시 UNKNOWN
      "paths": [
        {
          "path": "org/springframework/samples/petclinic/owner",  // 패키지 디렉터리(슬래시), 비어도 됨
          "files": [
            { "fileName": "OwnerRestController.java",
              "coveredLines": "52-53,60" }                 // 커버 라인 범위, "a-b,c" 형식
          ]
        }
      ]
    }
  ]
}
```

## Files

- `run-petclinic-tia.sh` — one-command orchestrator
- `exec_to_testwise.py` — **deprecated** (D0): `tia convert` 가 대체 (jacoco core, subprocess 없음)
- `run_scenarios.py` — `tia impact` scenario runner → `scenarios.json`
- `report.py` — per-test markdown report; `make_html.py` — **deprecated** (D0): `tia report` 가 대체.
  단, `make_html.py` 의 HTML 템플릿이 `tia report` 가 임베드하는 원본(tia-core `report-template.html`)
- `REPORT-GUIDE.md` — tab-by-tab walkthrough of `report.html`
- `test_make_html.py` — Python 생성기 회귀 테스트(참조용; CLI 회귀는 tia-cli/tia-core 테스트)
- `jacoco/` — JaCoCo HTML coverage report; deep-link target for the per-test tab
- `testwise.json` · `scenarios.json` · `flaky.json` · `flaky-runs/` · `prod-files.txt` — sample data
- `tia.db` (gitignored) — regenerated by the script

[petclinic]: https://github.com/baekchangjoon/spring-petclinic
[agent]: https://github.com/baekchangjoon/parallel-per-test-coverage

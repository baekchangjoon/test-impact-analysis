# Test-Impact-Analysis applied to `baekchangjoon/spring-petclinic`

**Target:** the `@Tag("blackbox")` out-of-process REST Assured API suite — 24 test cases
across 5 classes (`Auth/Owner/Pet/Vet/Concurrency …BlackBoxIT`).
**Indexed commit:** `44a63ab` (petclinic HEAD).
**Date:** 2026-06-13.

## How the data was produced (real, end-to-end — no mock data)

```
petclinic bootJar  ──(JDK21)──►  running server  ◄── -javaagent: jacocoagent-parallel.jar
                                                       (parallel-per-test-coverage, port 6310)
        ▲                                                     │ emits 1 vanilla-JaCoCo .exec / test
        │ HTTP (REST Assured + baggage: test.id=<Class#method>)│ attributed even under parallelism=8
   ./gradlew blackboxTest  ──────────────────────────────────┘
        │                         /tmp/petclinic-coverage/<Class#method>.exec   ×24
        ▼
   exec_to_testwise.py  ──(jacococli XML per exec → covered lines)──►  testwise.json
        ▼
   tia index   → tia.db (SQLite: per-test → file → covered-line bitmap)
   tia impact  → diff ∩ coverage  → selected tests + confidence
   tia flaky   → N run-results    → flaky ratio
```

All commands and intermediate artifacts are in this directory; the pipeline is reproducible
via `exec_to_testwise.py`, `report.py`, and `run-impact-scenarios.sh`.

---

## RESULT 1 — Per-test impact range  (`01-coverage-report.md §1`)

Each of the 24 tests mapped to the exact production files + lines it exercises. Examples:

| Test | Prod files | Lines | Headline reach |
|---|--:|--:|---|
| `OwnerApiBlackBoxIT#getOwnerById` | 7 | 22 | `OwnerRestController:52-53`, Owner/Pet/PetType + model entities |
| `VetApiBlackBoxIT#filterVetsBySpecialty` | 6 | 23 | `VetRestController`, `Vet`, `Specialty` |
| `AuthApiBlackBoxIT#loginWithValidCredentialsReturnsToken` | 3 | 30 | `AuthController`, `JwtUtil`, `CustomUserDetailsService` |
| `ConcurrencyBlackBoxIT#concurrentPetCreatesForSameOwnerAreAllPersisted` | 9 | 44 | widest reach — full create-pet path |

## RESULT 2 — Reverse selection index  (`01-coverage-report.md §2`)

"Touch file X → run these tests." Fan-in identifies change-risk hotspots:

| Production file | # guarding tests |
|---|--:|
| `model/BaseEntity.java` | 16 |
| `model/Person.java` | 15 |
| `model/NamedEntity.java` | 12 |
| `owner/Owner.java` | 11 |
| `owner/OwnerRestController.java` | 9 |

## RESULT 3 — `tia impact` test selection on real diffs  (`02-impact-scenarios.txt`)

Diffs were generated against the indexed HEAD (old-side aligns with the coverage line space).

| # | Change | Selected | Confidence | Reduction |
|---|---|--:|---|--:|
| A | `OwnerRestController:53` (1 line) | 3 / 24 | DETERMINISTIC | **87.5 %** |
| B | `VetRestController:40` | 1 / 24 | DETERMINISTIC | **95.8 %** |
| C | `JwtUtil:56` | 2 / 24 | DETERMINISTIC | **91.7 %** |
| D | `Owner.java:94` (shared model) | 10 / 24 | DETERMINISTIC | 58.3 % |
| E | `SecurityConfig:50` (blind spot) | **0 / 24** | — | ⚠ see limitation |
| F | `application.properties` (non-code) | 21 (all indexed) | CONSERVATIVE | safe fallback |
| G | new `.java` file | 21 (all indexed) | CONSERVATIVE | safe fallback |

DETERMINISTIC = changed line is in a test's covered-line bitmap (bit-AND hit).
CONSERVATIVE = change can't be mapped to coverage (non-code / brand-new file) → select all (safe by design; precise static fallback is a later enhancement).

## RESULT 4 — Flaky detection  (`tia flaky`)

- **Real petclinic, 5 consecutive runs (parallelism = 8):** `flaky ratio 0.000 (0/24)` — the
  black-box suite is **deterministic**, including the two concurrency tests. (`flaky-runs/run-*.json`)
- **Mechanism check (synthetic, labeled):** a test forced to flip P/F/P → `flaky ratio 0.250`,
  `FLAKY  ConcurrencyBlackBoxIT#concurrentPetCreatesForSameOwnerAreAllPersisted`. (`flaky-runs/synthetic/`)

## RESULT 5 — Coverage blind spots  (`01-coverage-report.md §4`)

**Only 15 of 40 production files are touched by any black-box test.** The suite guards the
`/api/**` REST layer; it does **not** exercise:
- the server-rendered MVC/Thymeleaf controllers — `OwnerController`, `PetController`,
  `VisitController`, `WelcomeController`, `CrashController`;
- the repositories, `PetValidator`, `PetTypeFormatter`;
- **security-critical infrastructure** — `SecurityConfig`, `JwtAuthenticationFilter`.

---

## ⚠ Two TIA limitations this run surfaced (honest caveats)

1. **Zero-coverage tests vanish from the index.** The three `…RequiresAuthentication`
   negative tests attribute *no* server-side coverage — their 401 is produced by the security
   filter chain before any included class runs. They therefore have no coverage rows, are
   **absent from `tia.db` (21 stored, not 24)**, and can be selected by **no** change — not
   even the conservative fallback. For security-negative tests that is a real safety gap.

2. **Blind-spot changes select nothing (Scenario E).** Because `SecurityConfig` /
   `JwtAuthenticationFilter` have no attributed coverage, a change to them is a `.java`
   *modification* (not an addition), so the analyzer finds zero bit-AND hits and selects
   **0 tests** — silently. The conservative fallback only triggers for *non-code* or *new*
   files, not for modifications to code that merely happens to be uncovered. Mitigation:
   treat "modified file with no coverage anywhere in the index" as a conservative trigger too.

3. **Cross-signal test identity mismatch.** The coverage agent keys tests by *method name*
   (`Class#concurrentOwnerCreates…`), but the JUnit XML used for flaky aggregation keys the
   `@DisplayName`'d concurrency tests by their *display name* (`Class#Concurrent POST /api/owners…`).
   Joining the impact view with the flaky view on test id therefore silently misses those tests.
   Mitigation: emit a stable canonical test id (e.g. `Class#method`) on every signal.

These are properties of *black-box* coverage attribution + the current analyzer, not defects in
the petclinic suite.

---

## Reproduce

```bash
bash petclinic-demo/run-petclinic-tia.sh      # build → run → collect → index → impact → flaky → HTML
open  petclinic-demo/report.html              # interactive report
```

**Deliverables in `petclinic-demo/`:**
`run-petclinic-tia.sh` (one-command orchestrator) · `report.html` (interactive) ·
`00-SUMMARY.md` · `01-coverage-report.md` · `scenarios.json` · `flaky.json` · `testwise.json` ·
`tia.db` · helpers `exec_to_testwise.py` · `run_scenarios.py` · `report.py` · `make_html.py`.

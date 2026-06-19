# 멀티 build 병합(P1-3) + `--db` 기본값(P3-4) 요구사항명세
> 출처(design spec): docs/superpowers/specs/2026-06-20-index-merge-db-default-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 요구사항 목록

### REQ-001 — `load`가 commit의 모든 build를 병합한다 (멀티모듈 union)
- 유형: Functional
- 우선순위: Must
- 설명: 같은 `commit_sha`에 build가 여러 개면(모듈별 인덱싱) `load(commit)`이 모든 build의
  테스트를 포함해야 한다. 기존 `LIMIT 1`은 마지막 build만 봐서 다른 모듈 테스트를 누락한다.
- 수용기준:
  - Given 같은 commit·같은 db에 disjoint test 집합({testA}, {testB})으로 `index`를 두 번 수행,
  - When 그 commit으로 `impact`를 실행하면,
  - Then 선별 결과에 testA·testB가 **둘 다** 나타난다.
- 검증 레벨: E2E black-box (CLI)

### REQ-002 — 재인덱싱 시 같은 `test_id`는 최신 build가 옛 것을 대체한다
- 유형: Functional
- 우선순위: Must
- 설명: 같은 commit에서 같은 `test_id`가 여러 build에 있으면, 병합 결과는 가장 높은 `build_id`의
  커버리지만 남겨야 한다(stale 라인 잔존 금지). build 내 여러 file 행은 누적된다.
- 수용기준:
  - Given 같은 `test_id`를 라인 {1,2}로 save 후 같은 commit에 라인 {2,3}으로 다시 save,
  - When `load(commit)` 결과의 그 `test_id` 커버리지를 보면,
  - Then 라인이 **{2,3}만**이고 {1}은 없다.
- 검증 레벨: integration (tia-core `CoverageStoreTest`)

### REQ-003 — 병합된 build 수를 조회할 수 있다 (`distinctBuildCount`)
- 유형: Functional
- 우선순위: Must
- 설명: `CoverageStore`는 그 commit의 distinct build 수를 상태 없는 쿼리로 노출한다
  (`int distinctBuildCount(String commit)`). coverage가 0행인 build도 정확히 카운트한다.
- 수용기준:
  - Given disjoint 2 build를 같은 commit에 save,
  - When `distinctBuildCount(commit)`을 호출하면,
  - Then 2를 반환한다. (단일 build면 1, 없으면 0.)
- 검증 레벨: integration (tia-core `CoverageStoreTest`)

### REQ-004 — 멀티 build 병합 시 `impact`가 INFO로 알린다
- 유형: Functional
- 우선순위: Should
- 설명: distinct build가 2개 이상이면 `impact` 커맨드가 stderr에 병합 INFO 한 줄을 출력한다
  (멀티모듈은 정상 동작이므로 WARN이 아닌 INFO). build 1개면 출력하지 않는다.
- 수용기준:
  - Given 같은 commit에 build 2개가 인덱싱된 db,
  - When `impact`를 실행하면,
  - Then stderr에 "build N개 … 병합" 취지의 INFO 라인이 존재한다. (build 1개면 그 라인이 없다.)
- 검증 레벨: E2E black-box (CLI)

### REQ-005 — 단일 build 동작은 회귀 없이 보존된다
- 유형: Non-functional
- 우선순위: Must
- 설명: build가 1개뿐인 기존 시나리오의 `load` 결과·`impact` 선별·`distinctBuildCount`(=1)는
  변경 전과 동일해야 한다(병합 로직 도입에 의한 회귀 금지).
- 수용기준:
  - Given 단일 build로 save된 commit,
  - When `load`/`impact`/`distinctBuildCount`를 수행하면,
  - Then 결과가 기존과 동일하고 병합 INFO는 출력되지 않는다.
- 검증 레벨: integration (tia-core) + E2E black-box

### REQ-006 — `--db` 미지정 시 git-common-dir 기본 경로로 해석한다
- 유형: Functional
- 우선순위: Must
- 설명: git 레포 안에서 `--db`를 생략하면 `index`/`impact`가 `<git-common-dir>/tia/tia.db`를
  사용한다(절대경로 정규화). 같은 cwd의 두 커맨드는 같은 DB로 수렴한다.
- 수용기준:
  - Given git 레포 임시 디렉터리, `--db` 미지정,
  - When `index` 후 `impact`를 실행하면,
  - Then `<git-common-dir>/tia/tia.db`가 생성되고 라운드트립(인덱싱→선별)이 성공한다.
- 검증 레벨: E2E black-box (CLI)

### REQ-007 — 비 git 환경에서는 XDG 폴백 경로로 해석한다
- 유형: Functional
- 우선순위: Must
- 설명: git 레포가 아니거나 git이 없으면 `${XDG_CACHE_HOME:-$HOME/.cache}/tia/tia.db`로 폴백한다.
  env 조회는 테스트 주입 가능한 seam(`resolveDefault(Function<String,String> env)`)으로 제공한다.
- 수용기준:
  - Given 비 git 디렉터리에서 `resolveDefault(env)`에 `XDG_CACHE_HOME=<tmp>` 주입,
  - When 기본 경로를 해석하면,
  - Then `<tmp>/tia/tia.db`를 반환한다. (`XDG_CACHE_HOME` 미설정이면 `~/.cache/tia/tia.db`.)
- 검증 레벨: integration (tia-cli `DbPaths` 단위)

### REQ-008 — 기본값 사용 시에만 선택 경로를 INFO로 안내한다
- 유형: Functional
- 우선순위: Should
- 설명: `--db` 미지정(기본값 사용)일 때 `index`·`impact` 각각 stderr에 "기본 인덱스 DB: <path>"
  INFO 한 줄을 출력한다. `--db` 명시 시에는 출력하지 않는다(노이즈 방지).
- 수용기준:
  - Given `--db` 미지정,
  - When `index`/`impact`를 실행하면,
  - Then 각 커맨드 stderr에 기본 경로 INFO 라인이 존재한다. (`--db` 명시 시 그 라인이 없다.)
- 검증 레벨: E2E black-box (CLI)

### REQ-009 — `--db` 명시 동작과 부모 디렉터리 생성을 보존·보강한다
- 유형: Functional
- 우선순위: Must
- 설명: `--db <path>` 명시 시 그 경로를 그대로 사용하고 INFO를 내지 않는다(기존 동작·테스트 보존).
  부모 디렉터리가 없으면 `CoverageStore` 생성자가 생성하므로 기본값·명시 경로 양쪽 모두 안전하다.
- 수용기준:
  - Given 부모 디렉터리가 없는 `--db <tmp>/sub/tia.db` 명시,
  - When `index`를 실행하면,
  - Then 그 경로에 DB가 생성되고(부모 자동 생성) INFO는 출력되지 않는다.
- 검증 레벨: E2E black-box (CLI)

## 추적 매트릭스
| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | 멀티모듈 union 병합 | `ImpactCommandTest` AT-P1-3-멀티모듈 | E2E | 🔴 planned |
| REQ-002 | 재인덱싱 최신대체 | `CoverageStoreTest` AT-P1-3-재인덱싱 | integration | 🔴 planned |
| REQ-003 | `distinctBuildCount` | `CoverageStoreTest` AT-P1-3-병합-단위 | integration | 🔴 planned |
| REQ-004 | 병합 INFO(impact) | `ImpactCommandTest` AT-P1-3-INFO | E2E | 🔴 planned |
| REQ-005 | 단일 build 회귀 보존 | `CoverageStoreTest`/`ImpactCommandTest` AT-P1-3-단일-회귀 | integration+E2E | 🔴 planned |
| REQ-006 | git-common-dir 기본값 | `IndexCommandTest`/`ImpactCommandTest` AT-P3-4-git | E2E | 🔴 planned |
| REQ-007 | XDG 폴백 | `DbPathsTest` AT-P3-4-비git | integration | 🔴 planned |
| REQ-008 | 기본값 경로 INFO | `IndexCommandTest`/`ImpactCommandTest` AT-P3-4-git | E2E | 🔴 planned |
| REQ-009 | 명시 `--db` 보존+부모 생성 | `IndexCommandTest` AT-P3-4-명시-회귀 | E2E | 🔴 planned |

Coverage: 0/9 green (0%) — target 100% (대상: Must + 미연기 Should). Could/Won't 없음, 연기·폐기 없음.

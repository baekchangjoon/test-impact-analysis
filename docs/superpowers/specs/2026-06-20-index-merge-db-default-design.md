# 멀티 build 병합(P1-3) + `--db` 기본값 자동화(P3-4) 설계

- 상태: 승인 대기(브레인스토밍 산출)
- 브랜치: `feat/index-merge-db-default`
- 출처 피드백: graph-rag (`TIA-FEEDBACK-graph-rag.pdf`) — P1-3, P3-4 잔여 코드 부분
- 선행: in-process pjacoco phase에서 P3-4 **문서**(REQ-011: `tia.db` 저장 위치 가이드)는 반영 완료.
  본 spec은 그 **코드** 잔여(`--db` 기본값 자동화)와 P1-3(멀티 build 병합)을 한 phase로 묶는다.

## 1. 배경 / 문제

### 1.1 P1-3 — `load()`가 마지막 build만 본다
`CoverageStore.load(commit)`은
`SELECT build_id, repo FROM builds WHERE commit_sha=? ORDER BY build_id DESC LIMIT 1`
로 **commit당 build 하나**만 고른 뒤 그 build의 coverage만 읽는다
(`tia-core/src/main/java/io/tia/core/store/CoverageStore.java:76-106`).

같은 `commit_sha`에 `builds` 행이 여러 개 생기는 경로:
1. **멀티모듈** — 모듈별로 `tia index`를 따로 호출하면 같은 commit에 모듈 수만큼 build가 쌓인다.
   각 build의 `test_id` 집합은 서로 **disjoint**(모듈이 다르므로).
2. **재인덱싱/재실행** — 같은 모듈을 다시 인덱싱하면 같은 `test_id`가 새 build로 또 들어온다.

현재 `LIMIT 1`은 (1)에서 **마지막 모듈을 제외한 모든 모듈의 테스트를 조용히 누락**시킨다 →
`impact` 선별이 그 테스트들을 빠뜨림(거짓 음성, 회귀 누락 위험).

근거: `CoverageStore.java:80`의 `LIMIT 1` 쿼리, 그리고 in-process pjacoco 설계의 P1-3 보류 항목
(`docs/superpowers/specs/2026-06-19-in-process-pjacoco-design.md:142`). (※ README 멀티모듈 caveat는
**package-relative 파일 키 충돌**을 다루는 별개 문제이며 본 build-누락과는 무관 — 혼동 주의.)

### 1.2 P3-4 — `--db`가 필수라 매번 경로를 넘겨야 한다
`IndexCommand`·`ImpactCommand` 모두 `@Option(names = "--db", required = true)`
(`tia-cli/.../IndexCommand.java`, `ImpactCommand.java`). REQ-011에서 "git에 커밋 금지 + 공유
비추적 위치(git common dir / `$XDG_CACHE_HOME`)" 가이드는 **문서로** 반영했고, 같은 절에
"`--db` 기본값 자동화는 향후 CLI 개선 예정"이라 명시해 두었다(`GETTING-STARTED.md:107`).
본 spec이 그 자동화를 구현한다.

## 2. 목표 / 비목표

**목표**
- (P1-3) `load(commit)`이 그 commit의 **모든 build를 `test_id` 단위 최신-build-wins로 병합**해
  멀티모듈 인덱스를 전부 보이게 하고, 재인덱싱 시 최신이 옛 것을 대체하게 한다.
- (P3-4) `--db`를 선택 옵션으로 바꾸고, 미지정 시 **git-common-dir 우선 / XDG 폴백** 기본 경로로
  자동 해석한다. `--db` 명시 시 동작·기존 테스트는 그대로 보존한다.

**비목표**
- 스키마 변경(컬럼 추가/마이그레이션) 없음 — 기존 `builds`/`coverage` 테이블 그대로 사용.
  (`builds(commit_sha)` 인덱스는 build 수가 미미해 추가하지 않는다 — 필요 시 후속 최적화.)
- 병합 정책의 사용자 설정화(merge 끄기 옵션 등) — YAGNI, 단일 정책.
- cross-service/멀티프로세스 수집(P3-3, cross-service OTel) — 별도 phase.
- **`action.yml`·Gradle plugin(`TiaArgs`/`TiaPlugin`)의 optional-db 전파 — 본 phase 밖.** 이들은
  현재 `--db`를 **항상 명시 전달**(`TiaArgs.java:15,19`)하고 action 입력 `db`는 `required: true`
  (`action.yml:6`)다. CLI `--db`를 optional로 바꾸는 것은 **하위호환**이라 이 경로들은 깨지지 않는다
  (계속 명시 전달). 기본값 자동화를 이들로 확장하는 것은 후속(§4 경계 참조).

## 한계 / 전제 (병합 정책)
- **stale-test 잔존**: `test_id`별 최신-build-wins는 같은 commit을 재인덱싱할 때 "삭제/리네임된
  테스트"를 제거하지 못한다 — 새 build에 그 `test_id`가 없으면 옛 build의 커버리지가 override되지
  않고 남는다. 인덱스는 `commit_sha` 키이므로 보통 새 커밋이 fresh build를 만들어 자연 해소되나,
  **같은 커밋 재인덱싱**에선 한계로 남는다(필요 시 해당 db 삭제 후 clean 재인덱싱).
- **cross-module `test_id` 충돌 전제**: 멀티모듈에서 모듈 간 `test_id`(uniformPath)가 **disjoint**
  여야 한다. 충돌하면 최신-build-wins가 한쪽을 조용히 폐기한다. 권장: 모듈별 고유 패키지/테스트
  네이밍(README package-collision caveat와 동일 취지).

## 3. 설계

### 3.1 P1-3 — `test_id`별 최신-build-wins 병합
`load(commit)`을 **builds-우선 2단계**로 교체한다(coverage가 0행인 build에서도 `repo`·build 수가
정확히 나오도록 — JOIN 단일 쿼리는 빈 build에서 행이 사라져 둘 다 틀어진다).

**1단계 — builds 열거.**
```sql
SELECT build_id, repo FROM builds WHERE commit_sha=? ORDER BY build_id ASC
```
- 행이 0개 → 빈 스냅샷 `new CoverageSnapshot(null, commit, List.of())` 반환(기존 no-baseline 불변).
- `build_id` 리스트(오름차순)와 `repo`(루프에서 매 행 덮어써 **마지막=최대 build_id의 repo**)를 얻는다.

**2단계 — coverage 병합.**
```sql
SELECT build_id, test_id, result, file, line_bitmap FROM coverage
WHERE build_id IN (<1단계 build_id들>) ORDER BY build_id ASC
```
- `Map<String, Long> winningBuild` 로 각 `test_id`가 채택된 build를 추적(`build_id`는 `long` —
  `CoverageStore.java`가 `getLong` 사용; **`Integer` 아님**).
- 각 행 처리:
  - `long w = winningBuild.getOrDefault(testId, -1L);` (첫 만남이면 `-1L` → 항상 reset, NPE 없음)
  - `buildId > w` 이면: 그 `test_id`의 file-map·result를 **비우고**(reset) 이 build 값으로 시작,
    `winningBuild.put(testId, buildId)`.
  - `buildId == w` 이면: 같은 build의 추가 file 행이므로 file-map에 **누적**.
- 오름차순이므로 최종적으로 각 `test_id`는 가장 높은 build의 coverage·result만 남는다.

**build 수 노출(투명성, 계층 분리).**
- `load()`는 core 계층이라 stderr를 직접 찍지 않는다. mutable 상태 대신 **별도 메서드**를 둔다:
  ```java
  int distinctBuildCount(String commit)  // SELECT COUNT(DISTINCT build_id) FROM builds WHERE commit_sha=?
  ```
  (상태 없음 → thread-safe; coverage 0행 build도 정확히 카운트.)
- `ImpactCommand`가 try-with-resources **블록 안에서** `int n = store.distinctBuildCount(commit);`로
  먼저 값을 캡처하고 `store.load(commit)` 호출 → 블록 밖에서 `n > 1`이면 stderr INFO 출력:
  `INFO: commit <sha>에 build N개 → test_id별 최신 build 병합(멀티모듈/재인덱싱).`
  (현재 `ImpactCommand.java:30`이 store 스코프를 try 블록으로 제한하므로 값 캡처가 필수.)

**경계**
- build 0개 → 빈 스냅샷(위 1단계).
- build 1개 → 병합 결과가 단일 build와 동일, `n==1`이라 INFO 없음(회귀 안전).
- 같은 build 안에서 한 test_id가 여러 file 행을 갖는 기존 구조 그대로 누적.

### 3.2 P3-4 — `--db` 기본값
- 두 커맨드의 `--db`를 `required = false`, 타입 `Path db`(null 허용)로.
- 신규 헬퍼 `tia-cli/src/main/java/io/tia/cli/DbPaths.java`. **테스트 주입 가능한 seam**을 위해
  env 조회를 파라미터로 분리한다(`System.getenv`는 JVM 런타임에 주입 불가하므로):
  ```java
  static Path resolveDefault()                              // 운영: env = System::getenv
  static Path resolveDefault(Function<String,String> env)   // 테스트: XDG_CACHE_HOME 주입
  ```
  1. `git rev-parse --git-common-dir` 실행(현재 작업 디렉터리, `ProcessBuilder`).
     **stderr는 폐기**(`redirectErrorStream` 후 미사용, 또는 `redirectError(DISCARD)`) — 비 git에서
     "fatal: not a git repository"가 콘솔을 오염시키지 않도록. exit 0 + 비어있지 않은 출력이면 그
     경로를 **절대경로로 정규화**(`toAbsolutePath().normalize()`) 후 `<common-dir>/tia/tia.db`.
  2. 실패(비0 exit / 빈 출력 / git 미설치)면 XDG 폴백(Java 구현):
     ```java
     String xdg = env.apply("XDG_CACHE_HOME");
     Path base = (xdg != null && !xdg.isBlank())
         ? Path.of(xdg)
         : Path.of(System.getProperty("user.home"), ".cache");
     return base.resolve("tia").resolve("tia.db");
     ```
  - `DbPaths`는 **디렉터리를 만들지 않는다** — 부모 디렉터리 생성은 `CoverageStore` 생성자가 담당
    (아래 §3.3), 그래야 기본값·명시 `--db` 양쪽 모두 부모 부재 시 안전하다.
- 각 커맨드 `call()` 진입부: `Path effectiveDb = (db != null) ? db : DbPaths.resolveDefault();`
  - `db == null`(기본값 사용)일 때만 stderr 한 줄: `INFO: 기본 인덱스 DB: <path>`.
  - `--db` 명시 시 출력 없음(노이즈 방지, 기존 동작 보존).
- `index`와 `impact`가 같은 cwd에서 호출되면 같은 git-common-dir → 같은 DB로 수렴(워크플로 성립).

### 3.3 계층 / 파일
- `CoverageStore.java` — `load` 2단계 병합 + `distinctBuildCount(commit)`(P1-3). **생성자에서
  `Files.createDirectories(dbFile.getParent())`**(부모 존재 시 no-op) 추가 → 기본값·명시 `--db`
  양쪽의 부모 부재를 한 곳에서 처리. 스키마·`save` 불변.
- `DbPaths.java` (신규, tia-cli) — 기본 경로 해석(P3-4). 디렉터리 생성은 하지 않음.
- `IndexCommand.java` / `ImpactCommand.java` — `--db` optional + 기본값 적용 + 기본값일 때 경로 INFO.
  - `ImpactCommand`는 추가로 병합 build 수 INFO(§3.1 "build 수 노출")를 출력.

## 4. 에러 처리 / 전제
- `git rev-parse --git-common-dir`는 `ProcessBuilder`로 실행(`ImpactCommand.runGitDiff`가 이미 git을
  shell-out 하는 패턴과 동일 — 가능하면 공용 헬퍼로). 비0 exit/빈 출력/예외 → 조용히 XDG 폴백
  (예외 전파 금지), stderr는 폐기. (기존 `runGitDiff`엔 타임아웃이 없으므로 본 헬퍼도 타임아웃은
  선택 — 패턴 일치 우선, exit/빈 출력 처리는 필수.)
- `$HOME` 미설정 같은 극단은 `System.getProperty("user.home")` 기반으로 안전 처리.
- 병합은 읽기 전용. 동시 인덱싱 중 load는 SQLite 기본 격리에 의존(기존과 동일, 변경 없음).
  `distinctBuildCount`는 상태 없는 쿼리라 `CoverageStore`의 thread-safety를 해치지 않는다.
- **CI/컨테이너 경계**: 기본값 자동 해석은 **로컬/대화형 CLI** 용이다. action.yml·Gradle plugin·
  컨테이너 E2E는 항상 `--db`를 **명시 전달**하므로, git 미설치 컨테이너(예: `temurin:17-jre`)에서
  `git rev-parse` 폴백 경로가 발동하지 않는다 → 컨테이너에서 ephemeral 경로로 새는 위험 없음.

## 5. 테스트 & 수용 기준 (E2E 먼저 red)

**E2E 레벨**: 이 프로젝트의 최고 가용 out-of-process 수준은 CLI 블랙박스(picocli end-to-end,
기존 `IndexCommandTest`/`ImpactCommandTest`와 동일 레벨 — 실제 `index`→`impact` 커맨드를 실행하고
DB 파일·stdout/stderr·종료코드를 단언). 컨테이너 E2E는 수집 파이프라인 검증용이라 본 변경(인덱스
저장/로드·CLI 경로)에는 CLI 블랙박스가 정확한 수용 레벨이다. 새 E2E를 추가한다.

**double-loop**: CLI 블랙박스가 outer(수용)이고, 병합 핵심 로직은 `tia-core`에 있으므로
`CoverageStoreTest`(inner 단위)도 함께 둔다(기존 `CoverageStoreTest`는 단일 save/load 1건뿐).

### 5.1 P1-3 수용/단위 테스트
- **AT-P1-3-멀티모듈** (CLI E2E): 같은 commit·같은 db로 `index`를 **두 번**(report A = {testA},
  report B = {testB}, disjoint) → `impact` 결과에 **testA·testB 둘 다** 선별 가능.
  - 사전(red): 현재 `LIMIT 1`에선 testB만 보임 → 실패해야 정상.
- **AT-P1-3-재인덱싱** (tia-core 단위): 같은 `test_id`를 라인 {1,2}로 save 후 {2,3}으로 다시 save →
  `load` 결과 그 test_id 라인이 **{2,3}만**(1은 stale, 없어야 함).
- **AT-P1-3-병합-단위** (tia-core 단위, `CoverageStoreTest`): disjoint 2 build save → `load`가
  두 build의 모든 test 포함; `distinctBuildCount`가 2 반환.
- **AT-P1-3-INFO** (CLI E2E): build ≥2 병합 시 **`impact` 커맨드** stderr에 병합 INFO 라인 존재;
  build 1개면 병합 INFO 없음.
- **AT-P1-3-단일-회귀** (tia-core 단위): 기존 단일 build save/load 결과·테스트 불변, `distinctBuildCount`=1.

### 5.2 P3-4 수용 테스트 (CLI E2E + DbPaths 단위)
- **AT-P3-4-git**: git repo 임시 디렉터리에서 `--db` 없이 `index`→`impact` →
  `<git-common-dir>/tia/tia.db` 생성 + 라운드트립 성공. `db==null`이므로 **`index`·`impact` 각각의
  stderr에 "기본 인덱스 DB" INFO 라인** 존재(커맨드별로 단언).
- **AT-P3-4-비git** (`DbPaths` 단위): 비 git 임시 디렉터리에서 `resolveDefault(env)`에
  `XDG_CACHE_HOME=<임시>`를 주입 → `<임시>/tia/tia.db` 반환(§3.2 seam). `XDG_CACHE_HOME` 없으면
  `~/.cache/tia/tia.db`.
- **AT-P3-4-명시-회귀**: `index --db <path>`·`impact --db <path>` **양쪽** 모두 그 경로 사용 +
  INFO 미출력(기존 동작·테스트 보존). 부모 디렉터리 부재 경로도 `CoverageStore` 생성자가 생성.

### 5.3 완료 정의
- 위 수용/단위 테스트 전부 green + 기존 `:tia-core:test`·`:tia-cli:test` 회귀 green.
- 요구사항명세(아래 §6 신규 문서) 작성 및 REQ-ID ↔ AT-* 매핑 완료 후, 추적 매트릭스 100%
  (Must + 미연기 Should).
- 영향 문서(아래) 갱신 후 PR.
- 빌드 전제: JDK 17+ (`fixture-app`의 Spring Boot 3.3.x가 요구). 본 변경 모듈(`tia-core`/`tia-cli`)
  테스트는 `JAVA_HOME`을 17+로 두고 실행.

## 6. 영향받는 문서
- `GETTING-STARTED.md` — "(`--db` 기본값 자동화는 향후 …)"(`:107`) 문구를 "`--db` 미지정 시
  git-common-dir 기본값(비 git은 XDG)" 으로 갱신. 예시에 `--db` 생략형 추가.
- `README.md` — **별 bullet로 구분**: 기존 package-collision caveat는 그대로 두고, "이제 `load`가
  commit의 **모든 build를 test_id별 최신-build-wins로 병합**(멀티모듈/재인덱싱)"을 **별도 항목**으로
  추가(둘은 다른 문제이므로 합치지 않는다).
- `docs/superpowers/requirements/2026-06-20-index-merge-db-default-requirements.md` (신규) — REQ-ID
  추적. P1-3·P3-4(코드)용 REQ. **본 문서는 plan 작성 전 `requirements-spec` 스킬로 먼저 작성**하고
  REQ-ID ↔ AT-* 매핑을 완료한다(§5.3 완료 정의의 100% 매트릭스 전제).

## 7. 다중 벤더 리뷰 반영 요약
리뷰어: Claude Sonnet(design-doc-reviewer) + Gemini 3.5 Flash(High) + Cursor(auto).

**수용(코드 정확성·실현성):**
- (Claude I1/I2) `winningBuild` 타입 `Long` + 첫 만남 `getOrDefault(-1L)` NPE 방지 → §3.1.
- (Claude I3/Cursor I2) try-with-resources 밖 `lastLoadedBuildCount()` 호출 불가 → mutable 필드 폐기,
  `distinctBuildCount(commit)` 메서드 + 블록 안 캡처 → §3.1.
- (Gemini I3) 빈-coverage build에서 JOIN이 repo/count를 누락 → builds-우선 2단계 쿼리 → §3.1.
- (Gemini I7) 인스턴스 필드 thread-safety → 상태 없는 `distinctBuildCount`로 해소 → §3.1/§4.
- (Claude I4/Cursor I4) `System.getenv` 테스트 주입 불가 → `resolveDefault(Function env)` seam +
  Java XDG 폴백 명시 → §3.2.
- (Gemini I4) 명시 `--db` 부모 부재 → `createDirectories`를 `CoverageStore` 생성자로 이동 → §3.3.
- (Gemini I6) 비 git에서 git stderr 콘솔 오염 → stderr 폐기 명시 → §3.2.
- (Cursor I3) 병합 로직 tia-core 단위 테스트 부재 → `CoverageStoreTest` 단위 추가 → §5.1.
- (Cursor I1) README caveat 오인용 → 근거를 `CoverageStore.java:80`/in-process spec으로 교정 + README
  갱신을 별 bullet로 → §1.1/§6.
- (Gemini I5/Cursor I5) stale-test 잔존·cross-module test_id 충돌 → "한계/전제" 절 신설.
- (Cursor I6) 깨진 §3.1.5 참조 → "§3.1 build 수 노출"로 교정.
- (Claude I7/Cursor I7) 커맨드별 INFO 단언 모호 → §5에서 index/impact 각각 단언으로 분리.

**부분수용(경계 명문화, 확장은 거부):**
- (Gemini I1/I2) action.yml·Gradle plugin optional-db: CLI 변경은 하위호환이라 안 깨짐 → §2 비목표 +
  §4 CI/컨테이너 경계로 명문화. 확장은 후속 phase.

**거부:**
- (Claude I6) `builds(commit_sha)` 인덱스: build 수 미미, YAGNI → §2에 선택 후속으로만 기록.
- (Cursor I8) requirements 문서 선행: 프로세스상 다음 단계가 `requirements-spec`이므로 §6에 명시로 해소.

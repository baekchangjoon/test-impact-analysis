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
`impact` 선별이 그 테스트들을 빠뜨림(거짓 음성, 회귀 누락 위험). 이는 README 멀티모듈 caveat가
지적했던 실제 손실이다.

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
- 병합 정책의 사용자 설정화(merge 끄기 옵션 등) — YAGNI, 단일 정책.
- cross-service/멀티프로세스 수집(P3-3, cross-service OTel) — 별도 phase.

## 3. 설계

### 3.1 P1-3 — `test_id`별 최신-build-wins 병합
`load(commit)`을 다음으로 교체한다.

1. 그 commit의 distinct build 수를 센다(또는 join 결과에서 도출).
2. 단일 join 쿼리로 모든 build의 coverage를 **`build_id` 오름차순**으로 읽는다:
   ```sql
   SELECT b.build_id, b.repo, c.test_id, c.result, c.file, c.line_bitmap
   FROM coverage c JOIN builds b ON c.build_id = b.build_id
   WHERE b.commit_sha = ?
   ORDER BY b.build_id ASC
   ```
3. 누적 시 **"그 `test_id`를 더 높은 `build_id`에서 처음 만나면 기존 엔트리를 리셋 후 교체"**:
   - `Map<testId, Integer> winningBuild` 로 각 test_id가 어느 build에서 채택됐는지 추적.
   - 행을 읽을 때 `build_id > winningBuild[testId]` 이면 그 test_id의 file-map·result를 **비우고**
     이 build 값으로 재시작(리셋). 같으면 같은 build 내 추가 file이므로 **누적**.
   - 오름차순이므로 최종적으로 각 `test_id`는 가장 높은 build의 coverage만 남는다.
4. `repo`는 최신 build(가장 높은 build_id) 값으로 채택.
5. **투명성**: distinct build가 2개 이상이면 stderr 한 줄(INFO — 멀티모듈은 정상 동작이므로):
   `INFO: commit <sha>에 build N개 → test_id별 최신 build 병합(멀티모듈/재인덱싱).`
   - **계층 분리**: `load()`는 core 계층이라 stderr를 직접 찍지 않는다. 대신 `CoverageStore`에
     인스턴스 필드 `private int lastLoadedBuildCount;`를 두고, `load`가 그 commit의 distinct build
     수를 계산해 이 필드에 기록한 뒤, public 게터 `int lastLoadedBuildCount()`로 노출한다.
   - `ImpactCommand`가 `load` 직후 `store.lastLoadedBuildCount() > 1`이면 위 INFO를 stderr에 출력.
     (출력 책임은 CLI 계층에 둬 core의 테스트 용이성·계층 분리를 유지.)

**경계**
- build 0개 → 빈 스냅샷(기존 no-baseline 경로 불변).
- build 1개 → 병합 로직이 단일 build와 동일 결과, build 수 1이므로 INFO 없음.
- 같은 build 안에서 한 test_id가 여러 file 행을 갖는 기존 구조 그대로 누적.

### 3.2 P3-4 — `--db` 기본값
- 두 커맨드의 `--db`를 `required = false`, 타입 `Path db`(null 허용)로.
- 신규 헬퍼 `tia-cli/src/main/java/io/tia/cli/DbPaths.java`:
  ```
  static Path resolveDefault()
  ```
  1. `git rev-parse --git-common-dir` 실행(현재 작업 디렉터리). exit 0 + 비어있지 않은 출력이면
     그 경로를 **절대경로로 정규화**한 뒤 `<common-dir>/tia/tia.db`.
  2. 실패(비 git / git 미설치)면 `${XDG_CACHE_HOME:-$HOME/.cache}/tia/tia.db`.
  - 반환 전 부모 디렉터리 `Files.createDirectories(parent)`.
- 각 커맨드 `call()` 진입부: `Path effectiveDb = (db != null) ? db : DbPaths.resolveDefault();`
  - `db == null`(기본값 사용)일 때만 stderr 한 줄: `INFO: 기본 인덱스 DB: <path>`.
  - `--db` 명시 시 출력 없음(노이즈 방지, 기존 동작 보존).
- `index`와 `impact`가 같은 cwd에서 호출되면 같은 git-common-dir → 같은 DB로 수렴(워크플로 성립).

### 3.3 계층 / 파일
- `CoverageStore.java` — `load` 병합 로직 + build 수 노출(P1-3). 스키마·`save` 불변.
- `DbPaths.java` (신규, tia-cli) — 기본 경로 해석(P3-4).
- `IndexCommand.java` / `ImpactCommand.java` — `--db` optional + 기본값 적용 + INFO 출력.
  - `ImpactCommand`는 추가로 병합 build 수 INFO(§3.1.5)를 출력.

## 4. 에러 처리 / 전제
- `git rev-parse --git-common-dir`는 `ProcessBuilder`로 실행(ImpactCommand가 이미 git을 shell-out
  하는 패턴과 동일). 타임아웃/비0 exit/빈 출력 → 조용히 XDG 폴백(예외 전파 금지).
- `$HOME` 미설정 같은 극단은 `System.getProperty("user.home")` 기반으로 안전 처리.
- 병합은 읽기 전용. 동시 인덱싱 중 load는 SQLite 기본 격리에 의존(기존과 동일, 변경 없음).

## 5. 테스트 & 수용 기준 (E2E 먼저 red)

**E2E 레벨**: 이 프로젝트의 최고 가용 out-of-process 수준은 CLI 블랙박스(picocli end-to-end,
기존 `IndexCommandTest`/`ImpactCommandTest`와 동일 레벨 — 실제 `index`→`impact` 커맨드를 실행하고
DB 파일·stdout/stderr·종료코드를 단언). 컨테이너 E2E는 수집 파이프라인 검증용이라 본 변경(인덱스
저장/로드·CLI 경로)에는 CLI 블랙박스가 정확한 수용 레벨이다. 새 E2E를 추가한다.

### 5.1 P1-3 수용 테스트
- **AT-P1-3-멀티모듈**: 같은 commit·같은 db로 `index`를 **두 번**(report A = {testA}, report B =
  {testB}, disjoint) → `impact` 결과에 **testA·testB 둘 다** 선별 가능.
  - 사전(red): 현재 `LIMIT 1`에선 testB만 보임 → 실패해야 정상.
- **AT-P1-3-재인덱싱**: 같은 `test_id`를 라인 {1,2}로 index 후 라인 {2,3}으로 다시 index →
  병합 결과 그 test_id 라인이 **{2,3}만**(1은 stale, 없어야 함).
- **AT-P1-3-INFO**: build ≥2 병합 시 stderr에 INFO 라인 존재; build 1개면 INFO 없음.
- **AT-P1-3-단일-회귀**: 기존 단일 build 시나리오 결과·테스트 불변.

### 5.2 P3-4 수용 테스트
- **AT-P3-4-git**: git repo 임시 디렉터리에서 `--db` 없이 `index`→`impact` →
  `<git-common-dir>/tia/tia.db` 생성 + 라운드트립 성공 + `db==null`일 때 stderr INFO 라인 존재.
- **AT-P3-4-비git**: 비 git 임시 디렉터리에서 `--db` 없이 → `${XDG_CACHE_HOME}/tia/tia.db`
  (테스트는 `XDG_CACHE_HOME`을 임시 경로로 주입) 사용.
- **AT-P3-4-명시-회귀**: `--db <path>` 명시 시 그 경로 사용 + INFO 미출력(기존 동작·테스트 보존).

### 5.3 완료 정의
- 위 수용 테스트 전부 green + 기존 `:tia-core:test`·`:tia-cli:test` 회귀 green.
- 요구사항명세 추적 매트릭스 100%(Must + 미연기 Should).
- 영향 문서(아래) 갱신 후 PR.

## 6. 영향받는 문서
- `GETTING-STARTED.md` — "(`--db` 기본값 자동화는 향후 …)" 문구를 "`--db` 미지정 시 git-common-dir
  기본값(비 git은 XDG)" 으로 갱신. 예시에 `--db` 생략형 추가.
- `README.md` — 멀티모듈 caveat에 "이제 `load`가 commit의 모든 build를 test_id별 최신 병합" 반영.
- `docs/superpowers/requirements/2026-06-20-index-merge-db-default-requirements.md` (신규) — REQ-ID
  추적. P1-3·P3-4(코드)용 REQ.

## 7. 다중 벤더 리뷰
본 spec은 design-doc 3-벤더 리뷰(Claude Sonnet + Gemini + Cursor) 대상. 반영 요약은 리뷰 후 §8에 기록.

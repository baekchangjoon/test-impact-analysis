# SP2 구현 계획 — 수집 필터 전파 + 플러그인 tia.yml 소비

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `io.tia` 플러그인의 tia.yml 소비(db·sut-name convention·fail-fast) + `attachCoverageAgentFromConfig` 필터 전파.

**Architecture:** 플러그인이 tia-core 로더 재사용(전이 제외로 클래스패스 위생). 변환은 순수 유틸 `GlobToClassPattern`(실매처 검증).

**참조:** spec `docs/superpowers/specs/2026-07-27-sp2-collection-filter-design.md`(**세부는 여기 따름**), 요구명세 `docs/superpowers/requirements/2026-07-27-sp2-requirements.md`(SP2-REQ-001..007; 매트릭스 직접 갱신)

## Global Constraints

- tia-core 의존은 spec §2의 exclude 3그룹 그대로 [SP2-REQ-005]
- yml convention 호출은 기존 convention(TiaPlugin.java:26~) **이후** [SP2-REQ-001]
- 변환 규칙·유니온·`*` 접미·`:` 결합은 spec §3 그대로; 단위 테스트는 실제 `org.jacoco.core`의 `WildcardMatcher`로 매칭 단언 [SP2-REQ-003]
- 기존 5-인자 attachCoverageAgent·기존 테스트 무수정 [SP2-REQ-004]
- @TempDir 트리에 `.git` 마커 필수(상향 탐색 격리). 워크트리 브랜치 `feat/sp2-collection-filter`. 커밋 `[SP2-REQ-…]` + 세션 트레일러.

## File Structure

| 파일 | 책임 |
|---|---|
| `tia-gradle-plugin/build.gradle` (수정) | tia-core(제외 지정)·org.jacoco.core(test) 의존 |
| `tia-gradle-plugin/src/main/java/io/tia/gradle/GlobToClassPattern.java` (신규) | 변환·`:` 결합 (`static String convertAll(List<String>)`, `static String convertOne(String)`) |
| `TiaPlugin.java` (수정) | apply-시점 로드·convention 주입·fail-fast·`attachCoverageAgentFromConfig`·req 메시지 |
| `TiaArgs.java` (수정) | `coverageAgentJvmArg(jar,dest,port,includes,excludes)` 오버로드 + 계약 주석 excludes 갱신 |
| `GlobToClassPatternTest.java`·`TiaPluginTest.java`(케이스 추가) | 매트릭스 수용 테스트 |
| `tia-gradle-plugin/README.md`·`GETTING-STARTED.md` (수정) | REQ-006 문서 |

---

### Task 1: GlobToClassPattern + TiaArgs 오버로드 (unit)

**REQ-IDs:** SP2-REQ-003

- [ ] 실패 테스트: `GlobToClassPatternTest` — REQ-003의 5케이스를 실매처(`new WildcardMatcher(pattern).matches(fqcn)`)로 단언 + `?` 통과 + `convertAll` 콜론 결합. red 확인.
- [ ] 구현: spec §3 규칙 그대로. `TiaArgs`에 excludes 오버로드(기존 4-인자는 위임)·계약 주석 갱신. `build.gradle`에 `testImplementation 'org.jacoco:org.jacoco.core:0.8.12'`.
- [ ] green + 기존 `TiaPluginTest` 무수정 green → Commit `feat(plugin): GlobToClassPattern — 유니온·$접미·콜론 결합, TiaArgs excludes [SP2-REQ-003]` + 매트릭스.

### Task 2: tia.yml 소비 + FromConfig (plugin)

**REQ-IDs:** SP2-REQ-001, SP2-REQ-002, SP2-REQ-004, SP2-REQ-005

- [ ] 실패 테스트: `TiaPluginTest` 케이스 추가(매트릭스 6개 — @TempDir+.git 마커+ProjectBuilder.withProjectDir; 깨진 yml apply 예외; FromConfig jvmArgs 검사). red 확인.
- [ ] 구현: `build.gradle` tia-core 의존(제외 3그룹, spec §2 블록 그대로); `TiaPlugin.apply`에 로드(try: `TiaConfigLoader.load(null, projectDir)`; `TiaConfigException` → `GradleException`) 후 convention 주입(기존 convention 라인들 **뒤에서**); `attachCoverageAgentFromConfig(Project, Test, File, File, int)` — 재로드→`GlobToClassPattern.convertAll`→`TiaArgs` 5-인자 jvmArg(비면 각 옵션 생략); `req()` 메시지에 tia.yml 안내.
- [ ] `./gradlew :tia-gradle-plugin:dependencies --configuration runtimeClasspath`로 REQ-005 확인(리포트 기록) + `./gradlew help --configuration-cache` 스모크(레포 루트 tia.yml 임시 생성 후 삭제 — 또는 @TempDir 소비 프로젝트로 확인, 방법 기록).
- [ ] green + 전체 스위트 green → Commit `feat(plugin): tia.yml 소비(db·sut-name·fail-fast) + attachCoverageAgentFromConfig [SP2-REQ-001/002/004/005]` + 매트릭스.

### Task 3: 문서·주석 동기화

**REQ-IDs:** SP2-REQ-006, SP2-REQ-007

- [ ] tia-gradle-plugin/README: yml 기본값+apply fail-fast(파급 명시)·FromConfig 사용 예·5-인자 마이그레이션 노트. GETTING-STARTED tia.yml 절에 1문단(수집 필터 리스크 + `--parallel` 금지 1줄). TiaArgs 주석은 Task 1에서 완료 확인.
- [ ] 전체 스위트 green + 매트릭스 7/7 → Commit `docs(plugin): tia.yml 소비·수집 필터 가이드 [SP2-REQ-006/007]`.

## 완료 정의

매트릭스 7/7 + 전체 스위트 green + CC 스모크 기록 + 문서 게이트.

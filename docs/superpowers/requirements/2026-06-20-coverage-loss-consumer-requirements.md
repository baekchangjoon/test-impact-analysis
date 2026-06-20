# TIA 손실 신호 소비·차단 요구사항명세 (phase 2)

> 출처(design spec): docs/superpowers/specs/2026-06-20-coverage-loss-consumer-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green).
> REQ-ID 네임스페이스: `CLC-REQ-00X`(Coverage-Loss-Consumer). 수용 테스트는 `@DisplayName("CLC-REQ-00X: …")`.
> 입력: phase 1 sidecar 계약(`<testId>.json`의 선택 필드 `incompleteAttribution`/`droppedProbes`).
> 본 phase는 **fixture sidecar**로 검증한다(pjacoco 1.1.0이 아직 해당 필드를 안 쓰므로 손수 만든 fixture `.json` 사용).
> 용어: 본 문서의 "testId" = `Testwise.Test.uniformPath`(= `<testId>.exec` 파일명 stem = JSON key `uniformPath`).
> 게이트 실패 사유 라벨: 코드/출력 일관 용어로 **`incomplete`** / **`empty`** 사용.
> malformed/누락 sidecar: 기존 `resultFor`처럼 파싱 실패는 swallow → 손실 신호 부재(= no-loss)로 취급.

## 요구사항 목록

### CLC-REQ-001 — incomplete 게이트(정밀) 기본 fail
- 유형: Functional · 우선순위: Must
- 설명: sidecar에 `incompleteAttribution==true` **또는** `droppedProbes>0`인 per-test가 1개라도 있으면
  `tia convert`가 기본 하드 실패. 실패해도 testwise.json은 작성된다(디버깅용; design 불변식).
- 수용기준:
  - Given `incompleteAttribution:true` sidecar가 섞인 exec-dir, When `tia convert`(기본), Then exit 1 +
    손실 uniformPath·사유(`incomplete`) stderr 출력.
  - Given `droppedProbes:3`만 있고 `incompleteAttribution` 키 부재인 sidecar, When `tia convert`(기본),
    Then exit 1 + 해당 uniformPath·사유(`incomplete`) 출력.
  - Given 손실 신호 전무 exec-dir, When `tia convert`, Then exit 0.
  - Given 손실 sidecar, When `tia convert`(기본, exit 1), Then `--out` testwise.json은 그래도 작성된다.
- 검증 레벨: CLI black-box (tia-cli) + tia-core unit(손실 판정 헬퍼)

### CLC-REQ-002 — `--allow-incomplete` opt-out
- 유형: Functional · 우선순위: Must
- 설명: 명시 opt-out으로 incomplete 게이트를 WARN으로 강등.
- 수용기준:
  - Given 손실 sidecar 섞인 exec-dir, When `tia convert --allow-incomplete`, Then exit 0 + WARN만 출력,
    testwise.json 정상 작성.
- 검증 레벨: CLI black-box

### CLC-REQ-003 — empty 게이트 opt-in & 게이트 독립
- 유형: Functional · 우선순위: Must
- 설명: 0커버(손실 신호 없음)는 기본 실패시키지 않는다(오탐 0). `--fail-on-empty`로만 발동하며,
  `--allow-incomplete`는 empty 게이트를 silence하지 않는다(독립).
- 수용기준:
  - Given 0커버지만 손실신호 없는 test 섞인 exec-dir, When `tia convert`(기본), Then exit 0.
  - Given 동일 입력, When `tia convert --fail-on-empty`, Then exit 1 + 사유(`empty`) 출력.
  - Given incomplete-loss test와 empty-coverage test가 함께, When `tia convert --allow-incomplete --fail-on-empty`,
    Then exit 1 + stderr에 `incomplete`·`empty` 라벨별 목록이 **각각** 출력(incomplete가 opt-out돼도 empty는
    여전히 발동 = 독립; 단일 exit 1).
- 검증 레벨: CLI black-box

### CLC-REQ-004 — testwise 전파 + NON_NULL 후방호환
- 유형: Functional · 우선순위: Must
- 설명: 손실 플래그를 testwise.json tests[]로 전파하되, 손실 없는 test엔 필드 생략.
- 수용기준:
  - Given `incompleteAttribution:true, droppedProbes:3` sidecar, When convert, Then 해당 test에
    `incompleteAttribution:true`·`droppedProbes:3` 포함.
  - Given `droppedProbes:3`만(incompleteAttribution 부재), When convert, Then 그 test에 `droppedProbes:3`만
    포함되고 `incompleteAttribution` 키는 부재.
  - Given 손실 없는 test(또는 `droppedProbes:0`), When convert, Then `incompleteAttribution`/`droppedProbes`
    키 모두 **부재**(@JsonInclude(NON_NULL); droppedProbes는 0/≤0이면 null 정규화).
- 검증 레벨: tia-core unit (TestwiseConverter)

### CLC-REQ-005 — index 파서 후방호환
- 유형: Non-functional · 우선순위: Must
- 설명: 신규 필드가 있는 testwise.json을 `TestwiseReportParser`(tia index 소비)가 깨짐 없이 처리(필드 무시).
- 수용기준:
  - Given `incompleteAttribution`/`droppedProbes` 포함 testwise.json, When `TestwiseReportParser`로 파싱,
    Then 예외 없이 기존 필드(uniformPath/result/paths) 정상 파싱. (tia index CLI 경로는 기존
    `D0AcceptanceTest` 회귀가 커버.)
- 검증 레벨: tia-core unit

### CLC-REQ-006 — 문서 결정 체크리스트(스레드 토폴로지) 자동 검증
- 유형: Non-functional · 우선순위: Must
- 설명: SKILL.md·GETTING-STARTED·README에 "스레드 토폴로지" 분류 결정 트리·게이트 플래그·③-b 레시피가 존재.
- 수용기준:
  - Given 리포 문서, When `@Test`가 repo-root 기준으로 문서를 읽음(서브모듈 CWD 문제 회피: settings.gradle을
    찾아 올라가거나 `Path.of(System.getProperty("user.dir"))`에서 repo root 해석), Then `skills/tia/SKILL.md`·
    `GETTING-STARTED.md`에 토폴로지 결정 문구(`RANDOM_PORT`, `MockMvc`, in-process/out-of-process 매핑)와
    fail-fast 레시피 프로퍼티명(`tia.inprocess.failOnWebServer`)이, `README.md`에 `--allow-incomplete`·
    `--fail-on-empty` 문서화가 포함된다.
- 검증 레벨: tia-cli unit (파일 콘텐츠 단언)

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| CLC-REQ-001 | incomplete 게이트 기본 fail | `ConvertGateTest#incompleteSidecar_failsByDefault` · `#droppedProbesOnly_failsByDefault` · `#noLoss_exit0` · `#failure_stillWritesTestwise` ; `TestwiseConverterLossTest#detectsLoss` | CLI + unit | 🔴 planned |
| CLC-REQ-002 | --allow-incomplete opt-out | `ConvertGateTest#allowIncomplete_warnsExit0` | CLI | 🔴 planned |
| CLC-REQ-003 | empty 게이트 opt-in & 독립 | `ConvertGateTest#empty_passesByDefault` · `#failOnEmpty_fails` · `#allowIncompleteWithFailOnEmpty_emptyStillFires` | CLI | 🔴 planned |
| CLC-REQ-004 | testwise 전파 + NON_NULL | `TestwiseConverterLossTest#propagatesFlags` · `#propagatesDroppedProbesOnly` · `#omitsWhenNoLoss` | unit | 🔴 planned |
| CLC-REQ-005 | index 파서 후방호환 | `TestwiseReportParserCompatTest#ignoresNewFields` | unit | 🔴 planned |
| CLC-REQ-006 | 문서 결정 체크리스트 자동검증 | `DecisionChecklistDocTest#docsHaveTopologyTreeAndFlags` | unit | 🔴 planned |

Coverage: 0/6 green (0%) — target 100% (대상: Must 6개; Should/Could/Won't 없음, 연기 없음)

## 자기검토
1. **고아 행위 없음**: spec §4(③-c-1 incomplete/empty→001/002/003, ③-c-2 전파→004, 파서→005, ③-a/③-b·
   README→006)·§6 변경표(README 포함→006) 전부 매핑. ✅
2. **원자성**: 게이트 fail(001)·opt-out(002)·empty 독립(003)·전파(004)·파서(005)·문서(006) 분리. ✅
3. **수용기준 완비**: 전 REQ Given-When-Then, 측정 가능(exit code·JSON 필드 존재/부재·파싱 무예외·문구
   포함). droppedProbes-단독·exit1-testwise작성·게이트독립·repo-root 경로해석까지 명시. ✅
4. **커버리지 규칙 명시**: 분모=Must 6, 제외 없음. ✅
5. **비목표 추적**: `tia index`가 손실 플래그를 경고/영속화하는 것은 design 비목표(기본 fail 게이트가
   손상 baseline 인덱싱을 차단) → 후속 REQ. ✅

# 설계: TIA가 in-process 손실 신호를 소비·차단 — phase 2 (test-impact-analysis)

상태: 리뷰용 초안 (3-벤더 design-doc 리뷰 반영 rev1)
작성일: 2026-06-20
브랜치: `feat-tia-loss-consumer`
대상 repo: test-impact-analysis (TIA)
선행: phase 1(pjacoco, 브랜치 `feat-coverage-loss-signals`,
`docs/superpowers/specs/2026-06-20-coverage-loss-signals-design.md`)이 **신호 계약**을 생산한다.

### phase 1 sidecar 계약 (이 phase가 소비하는 입력 스키마)
per-test `<testId>.json`(기존 `result` 등에 더해, **손실이 있을 때만** 아래 필드가 출현):
```json
{ "testId": "...", "result": "passed", "classCount": 0,
  "incompleteAttribution": true,        // bool, 손실 귀속 시에만
  "droppedProbes": 3,                    // 정수(JSON number)
  "attribution": "exact" }               // "exact" | "conservative"
```
손실이 없으면 위 3개 필드는 **부재**(phase 1이 `droppedProbes>0`일 때만 emit). `attribution`은 phase 1
sidecar에만 존재하며 본 phase의 소비 대상이 아니다(§2 비목표).

## 1. 배경 & 문제

phase 1로 손실이 **신호화**됐다(sidecar 플래그 + 에이전트 WARN/카운터). 그러나 TIA 소비 경로가
없으면 신호가 묻힌다:
- `tia convert`는 sidecar의 `result`만 읽고 손실 플래그를 무시한다(`TestwiseConverter.resultFor`).
- 손상된 per-test(워커 스레드 손실)가 그대로 testwise→index로 들어가 거짓 baseline이 된다.
- 사용자/에이전트가 in/out 수집 모델을 "프로세스" 축으로 잘못 고르도록 문서가 안내한다
  (GETTING-STARTED §1: 토폴로지 축 부재).

**정밀 신호의 힘**: phase 1이 붙으면, HTTP 블랙박스를 in-process로 오용한 모듈은 워커 스레드 드롭이
각 active test에 귀속되어 **그 테스트 전부**가 `incompleteAttribution=true`가 된다(order-service의
"커버됨" 13개까지 포함 — 시딩만 잡힌 거짓 신호도 손실로 표시됨). 즉 `incompleteAttribution`은 0커버
휴리스틱보다 **정확하고 포괄적인** 손실 신호다.

## 2. 목표 & 불변식

- **목표**: TIA가 (a) sidecar 손실 플래그를 testwise로 전파하고, (b) **손실 신호가 있으면 `tia convert`가
  기본 하드 실패**하며, (c) 사용자/에이전트가 **스레드 토폴로지** 축으로 올바른 수집 모델을 고르도록
  문서가 안내한다.
- **불변식 — 엄격(정밀) 기본값**: `incompleteAttribution==true` 또는 `droppedProbes>0`인 per-test가
  1개라도 있으면 convert가 **기본 fail**(opt-out `--allow-incomplete`). 이것이 거짓 인덱스 차단의 1차
  방어선.
- **불변식 — 오탐 0**: 0커버지만 **손실 신호가 없는** 정당한 테스트(순수 헬퍼 단위테스트 등)는 기본적으로
  실패시키지 않는다. 0커버 자체에 대한 실패는 **opt-in `--fail-on-empty`**로만 발동한다(empty 게이트와
  incomplete 게이트는 **독립**).
- **불변식 — 후방호환**: 손실 0 산출물의 testwise.json은 기존 스키마와 **byte 동일**(신규 필드는
  `@JsonInclude(NON_NULL)`로 null이면 생략). `tia index` 파서는 신규 필드를 무시해도 동작. 기존
  convert 호출 스크립트(in-repo E2E·데모)는 손실 신호가 없으므로 기본 동작이 유지된다.
- **비목표**: (a) 인덱스 DB 스키마/HTML 리포트에 손실 플래그 영속화·노출 — **후속**. 근거: 기본 fail
  게이트가 손상 baseline 생성을 애초에 차단하므로, DB에 플래그가 없어도 거짓 baseline이 인덱싱되지
  않는다. 잔여 갭은 사용자가 `--allow-incomplete`로 명시 우회 후 인덱싱한 경우뿐 → 후속 REQ로 추적.
  (b) `attribution`("exact"|"conservative")의 소비/전파 — phase 1 sidecar에만 보존, 본 phase 대상 아님.
  (c) 에이전트/수집 메커니즘 변경(phase 1 소유).

## 3. 아키텍처 — 소비 흐름

```
[phase 1 산출물]  coverage/<testId>.exec  +  <testId>.json { result, incompleteAttribution?, droppedProbes? }
        ▼  tia convert (tia-core TestwiseConverter)
  per-test 분석: 커버 라인 + sidecar 1회 읽기(result + loss DTO)
        ├─ testwise.json tests[]에 incompleteAttribution/droppedProbes 전파(@JsonInclude NON_NULL → 없으면 생략)
        ├─ incomplete 게이트(기본 ON): incompleteAttribution OR droppedProbes>0 인 test ≥1 → 목록+exit 1
        │     (opt-out --allow-incomplete → WARN만)
        └─ empty 게이트(기본 OFF): --fail-on-empty 지정 시, 0커버 test ≥1 → 목록+exit 1
        ▼
  사람/에이전트는 SKILL/GETTING-STARTED 결정 체크리스트로 수집 모델을 올바로 선택(스레드 토폴로지 축)
```

## 4. 컴포넌트

### ③-c-1 convert 손실 게이트 — `tia-cli/.../ConvertCommand` + `tia-core/.../TestwiseConverter`
- **sidecar 1회 읽기(DTO)**: 기존 `resultFor(exec)`를 `sidecarOf(exec)`로 대체 — 한 번의 Jackson Map
  파싱으로 `result`(String) + `incompleteAttribution`(Boolean) + `droppedProbes`(Long)을 담은 작은 record
  `Sidecar(String result, boolean incompleteAttribution, Long droppedProbes)`를 반환(중복 I/O 제거 —
  리뷰 Gemini I4). `incompleteAttribution`은 Sidecar에선 **primitive `boolean`**(맵에 없으면 false),
  숫자는 **`Number.longValue()`**로 읽는다(Jackson Map은 작은 정수를 `Integer`로 주므로 `(Long)` 직접
  캐스트는 ClassCastException — 리뷰 Claude I3): `Object r=m.get("droppedProbes");
  Long d = r instanceof Number ? ((Number)r).longValue() : null;`,
  `boolean ia = Boolean.TRUE.equals(m.get("incompleteAttribution"));`.
- **두 독립 게이트**:
  - **incomplete(정밀, 기본 ON)**: per-test가 `incompleteAttribution==true` 또는 `droppedProbes!=null && >0`
    이면 손실. 그런 test ≥1 → 손실 testId 목록(사유 incompleteAttribution) stderr + **exit 1** (opt-out
    `--allow-incomplete` → WARN만, exit 0).
  - **empty(휴리스틱, 기본 OFF)**: `--fail-on-empty` 지정 시에만, `paths.isEmpty()`(커버 파일 0개)인 test
    ≥1 → 목록(사유 empty) + exit 1. (0커버가 정당한 모듈을 위해 기본 OFF — 리뷰 Gemini I2.)
  - 두 플래그는 **서로 다른 게이트**를 제어하므로 동시 지정 모호성 없음(리뷰 Claude I5/Gemini I1/Cursor I7).
    두 게이트가 같은 실행에서 모두 발동하면 **각 게이트가 자기 사유 라벨(incomplete / empty)로 testId
    목록을 stderr에 따로 출력**한 뒤 **단일 exit 1**(리뷰 재검토 I3).
- 손실 판정 기준 "0커버"는 **`Test.paths().isEmpty()`**로 규범화(`analyze()`가 covered line>0 파일만 추가
  하므로 paths 비면 0커버 — 리뷰 Cursor I4).
- **exit code**: 실패는 **exit 1**(general failure). picocli는 파싱에러에 2를 쓰므로 2 회피; ImpactCommand의
  strict-no-baseline은 3 → 충돌 피해 1 사용(리뷰 Gemini I5/Cursor I6).
- testwise.json은 항상 정상 작성(게이트는 exit code; 산출물은 디버깅용 보존).

### ③-c-2 testwise 전파 — `tia-core/.../Testwise` + `TestwiseConverter` + `TestwiseReportParser`
- `Testwise.Test` record에 **선택 필드** 추가: `Boolean incompleteAttribution`, `Long droppedProbes`.
  record에 **`@JsonInclude(JsonInclude.Include.NON_NULL)`** 부여(또는 mapper 전역 NON_NULL) → null이면
  JSON에서 생략(후방호환·CLC-REQ-003 — 리뷰 3벤더 공통 I1).
- **canonical ctor 갱신(리뷰 Claude I4)**: record 컴포넌트 추가로 단일 호출부
  `TestwiseConverter.convert`의 `new Testwise.Test(testId, sidecar.result(), analyze(...))`를
  `new Testwise.Test(testId, sidecar.result(), analyze(...),
  sidecar.incompleteAttribution() ? Boolean.TRUE : null, sidecar.droppedProbes())`로 갱신
  (Sidecar의 primitive `boolean`을 Test 필드용 `Boolean`으로 — false면 null 정규화해 NON_NULL 생략과 일관).
- `TestwiseReportParser`(tia index 소비)는 신규 필드를 **무시해도** 무방 — 파싱 무손상을 테스트로 고정
  (코드 변경 최소/무).

### ③-a 결정 체크리스트(문서) — `skills/tia/SKILL.md` + `GETTING-STARTED.md`
- 분류 축을 "프로세스" → **"프로덕션 코드가 어느 스레드에서 실행되나"**로 재정의:
  - 테스트 스레드 실행(직접 호출 / MockMvc / `@WebMvcTest` / `webEnvironment=MOCK` / WebTestClient
    bindToApplicationContext) → **in-process** OK.
  - 다른 스레드 실행(`@SpringBootTest(RANDOM_PORT|DEFINED_PORT)` + RestAssured/TestRestTemplate/
    WebTestClient bindToServer, WebSocket/STOMP, @Async) → **out-of-process baggage 필수**.
- 결정 트리(짧은 표) + "in-process로 HTTP 블랙박스 수집 시 워커 스레드 커버리지가 침묵 손실되고, phase 1
  신호(WARN/카운터/`incompleteAttribution`)와 `convert`의 incomplete 게이트가 이를 잡는다" 안내.
- SKILL.md "Scope"에서 이 체크리스트로 점프하는 포인터(신선한 에이전트가 결정 시점에 봄).

### ③-b 수집 fail-fast 레시피(문서) — `GETTING-STARTED.md`
- in-process 수집 중 임베디드 웹서버가 부팅되면 토폴로지 미스매치다. 소비자 수집 하니스에 넣는
  fail-fast 레시피를 1급 문서화: 작은 `ApplicationListener<WebServerInitializedEvent>`(testRuntimeOnly)로
  부팅 감지 시 즉시 실패. opt-out 프로퍼티 **`tia.inprocess.failOnWebServer`(기본 true)** 명시 + Gradle
  의존성 스니펫(리뷰 Cursor I8).
- **위치 명확화(리뷰 Claude I9/Cursor)**: 이 가드는 **SUT가 임베디드 서버를 띄우는** 잘못된 토폴로지에서만
  의미가 있다. TIA의 `scripts/run-inprocess-e2e.sh`는 이미 올바른 토폴로지(서버 미기동, 직접 호출)라 가드
  대상이 아님을 명시 — 레시피는 소비자 repo의 SUT-부팅 구성에 둔다. TIA 라이브러리에 강제 코드는 두지
  않는다(phase 1 WARN/카운터 + ③-c incomplete 게이트가 이미 런타임/convert 레벨에서 잡으므로 보조적).

## 5. 수용/E2E 테스트 (정의된 done)

최고 feasible 레벨 = TIA CLI 블랙박스(`tia convert` 실제 실행) + tia-core 단위. **fixture sidecar**를
구성해 검증(예: `T_LOSS.exec`+`T_LOSS.json{incompleteAttribution:true,droppedProbes:3}`,
`T_OK.exec`+`T_OK.json{result:passed}`, `T_EMPTY.exec`(0커버)). 각 테스트는 `@DisplayName`에 REQ-ID.

1. **CLC-REQ-001 (incomplete 게이트 기본 fail)**: `incompleteAttribution`(또는 droppedProbes>0) sidecar가
   섞인 exec-dir로 `tia convert`(기본) → **exit 1** + 손실 testId·사유 출력. 손실 0 → exit 0.
2. **CLC-REQ-002 (--allow-incomplete opt-out)**: 동일 입력 + `--allow-incomplete` → exit 0 + WARN만.
3. **CLC-REQ-003 (empty 게이트 opt-in 독립)**: 0커버지만 손실신호 없는 sidecar → 기본 convert는 exit 0
   (오탐 0); `--fail-on-empty` 지정 시에만 exit 1. 그리고 `--allow-incomplete`가 empty 실패를 silence하지
   **않음**(독립 검증).
4. **CLC-REQ-004 (testwise 전파 + NON_NULL)**: `incompleteAttribution:true` sidecar → testwise.json 해당
   test에 `incompleteAttribution:true`(+droppedProbes); 손실 0 test엔 **필드 부재**(NON_NULL).
5. **CLC-REQ-005 (index 파서 후방호환)**: 신규 필드 포함 testwise.json을 `tia index`/`TestwiseReportParser`가
   깨짐 없이 처리(필드 무시).
6. **CLC-REQ-006 (문서 결정 체크리스트 — 자동 검증)**: `@Test`가 `skills/tia/SKILL.md`·`GETTING-STARTED.md`를
   `Files.readString`으로 읽어 토폴로지 결정 트리 존재·핵심 문구(`RANDOM_PORT`, `MockMvc`,
   in-process/out-of-process 매핑) 포함을 단언(리뷰 Claude I6/Cursor I9 — 수동 아닌 자동).

## 6. 변경 컴포넌트 요약

| seam | 변경 |
|---|---|
| `tia-core/.../convert/Testwise` | `Test` record에 `Boolean incompleteAttribution`·`Long droppedProbes` + `@JsonInclude(NON_NULL)` |
| `tia-core/.../convert/TestwiseConverter` | `resultFor`→`sidecarOf`(1회 읽기 DTO, `Number.longValue()`); Test 전파; 손실 판정 헬퍼(`paths.isEmpty()`/플래그) |
| `tia-cli/.../ConvertCommand` | `--allow-incomplete`(기본 false=게이트 ON), `--fail-on-empty`(기본 false=opt-in); 손실 목록 출력 + exit 1 |
| `tia-core/.../parse/TestwiseReportParser` | 신규 필드 무시 후방호환(테스트로 고정; 코드 변경 최소/무) |
| `skills/tia/SKILL.md` | Scope에 스레드-토폴로지 결정 체크리스트 포인터 |
| `GETTING-STARTED.md` | §1 분류 축 재정의 + 결정 트리 + ③-b fail-fast 레시피(`tia.inprocess.failOnWebServer`) |
| `README.md` | convert 예시 옆에 incomplete 게이트/`--allow-incomplete`/`--fail-on-empty` 한 줄 문서화 |
| `tia-core/src/test`, `tia-cli/src/test` | CLC-REQ-001~006 테스트 + fixture sidecar |

## 7. 리스크 & 엣지

- **0커버 정당 테스트(오탐) — 해소**: empty 게이트를 기본 OFF로 분리해, 손실신호 없는 0커버는 기본
  실패하지 않는다. incomplete 게이트(기본 ON)는 phase 1의 명시 신호에만 반응하므로 오탐이 구조적으로 없다.
- **breaking change — 해소**: `--fail-on-empty` opt-in화로 기존 in-repo convert 호출(run-inprocess-e2e.sh,
  petclinic-demo 등)은 손실 신호가 없는 한 기본 동작 유지(in-process 직접호출·out-of-process baggage 모두
  incompleteAttribution 미발생). `tia-cli` `D0AcceptanceTest`(convert→report, exit 0 단언)도 fixture에
  손실 sidecar가 없어 green 유지 — 단, 그 fixture에 손실 sidecar를 추가하면 게이트가 발동하므로 주의.
  README/데모 문서만 신규 플래그를 반영(문서 동기화).
- **wrong classesDir 전손실**: classesDir가 비거나 오설정이면 모든 test가 0커버가 되나, empty 게이트가
  기본 OFF라 기본 convert는 통과(전 test empty는 incomplete와 구분되는 신호; `--fail-on-empty` 사용 시에만
  전건 실패로 드러남). 문서에 안내.
- **DB 미영속(잔여 갭)**: 기본 fail 게이트가 손상 baseline 인덱싱을 차단하므로 DB에 플래그가 없어도
  거짓 baseline이 안 생긴다. 사용자가 `--allow-incomplete`로 명시 우회 후 인덱싱하면 impact가 손상
  baseline을 모른 채 매칭하는 갭이 남음 → **후속 REQ**(DB 스키마/impact 경고)로 추적(리뷰 Gemini I3).
- **후방호환 직렬화**: `@JsonInclude(NON_NULL)` + 선택 필드라 손실 0 산출물은 기존과 동일; 파서 무시를
  테스트로 고정.

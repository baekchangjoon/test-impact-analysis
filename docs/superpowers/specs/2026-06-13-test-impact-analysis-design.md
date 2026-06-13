# 테스트 영향 분석(TIA) 시스템 — 설계 문서

- 작성일: 2026-06-13
- 상태: 설계 확정 → 구현 계획(writing-plans) 진입 대기
- 환경: Spring Boot MSA, RestAssured 기반 out-of-process 블랙박스 테스트, GitHub Actions(셀프호스티드 러너), Kiro CLI / Claude Code
- 선행 문서: `test-impact-analysis-브레인스토밍-정리.md`, `병렬 Per-Test 커버리지 에이전트 브레인스토밍 정리.md`

이 문서는 4개 Phase 전체를 아우르는 **시스템 설계**다. 구현은 Phase별 독립 spec→plan→구현 사이클로 진행한다.

---

## 1. 목표 & 범위

### 1.1 두 가지 목적

| # | 목적 | 판정 방식 |
|---|------|-----------|
| 1 | 비즈니스 코드 변경 영향 범위(클래스/메소드/라인) → **실행할 테스트 선별** (TIA/RTS) | `테스트 커버 라인 ∩ git diff 라인 ≠ ∅` → 영향 있음 |
| 2 | per-test 커버리지로 **테스트 실패 원인 분류** (코드 변경 vs 인프라/플레이키) | `실패 테스트 커버집합 ∩ diff = ∅` → 코드 무관 → 인프라/플레이키 의심 |

두 판정 모두 **결정론적**이다. "정량은 코드로, 정성은 LLM으로" 테제에 부합하며, 목적 2는 qe-rca-action에 DETERMINISTIC 신호로 주입한다.

### 1.2 범위
- Phase 0~3 전체를 하나의 설계로 다루되, 구현은 Phase별 독립 사이클.
- **테스트 스킵**(무관 테스트 미실행)은 설계에 포함하되 **기본 OFF**(선별은 권고/정보 제공으로 시작) + 주기적 전체 실행 안전망. 신뢰 확보 후에만 실제 스킵 활성화.

---

## 2. 아키텍처 개요

```
                ┌─ 동적 (주력) ─────────────────────────────┐
   nightly job  │ CoverageCollector 인터페이스 (드롭인 seam) │
   ───────────► │  ├ teamscale/jacoco 직렬 (Phase 0 주력)     │──┐
                │  └ jacocoagent-parallel.jar (완성 시 교체)  │  │
                │ MSA 동시 dump → 테스트↔서비스별 커버집합     │  │
                └────────────────────────────────────────────┘  │
                ┌─ 정적 (보조) ─────────────────────────────┐    ├─► 매핑 스토어
                │ scip-java 호출그래프 / AST 폴백(Java 8)     │──┤   (커밋 1급 키,
                │ 커버리지 사각지대 · staleness 간극 보완      │    │    Roaring Bitmap)
                └────────────────────────────────────────────┘  │
                                                                 ▼
   조회 레이어 ── PR 코멘트 / CLI / qe-rca DETERMINISTIC 신호 / 자체 MCP 서버
```

**핵심 테제**
- 동적 데이터를 **항상 우선**, 정적은 보조. Spring 프록시/AOP/리플렉션/비동기로 인한 정적 그래프 오탐·미탐 상존.
- MSA 동시 dump가 크로스 레포 매핑을 정적 분석 없이 동적으로 획득 — 이 접근의 **숨은 최대 가치**.
- 수집은 `CoverageCollector` 인터페이스 뒤에 격리 → 직렬/병렬 구현체 무중단 교체.

---

## 3. 동적 수집 파이프라인

### 3.0 수집 레이어 인터페이스 — 드롭인 seam (핵심 설계 결정)

TIA의 모든 다운스트림(저장소·diff∩cover·PR 코멘트·MCP)은 **`CoverageCollector` 인터페이스**에만 의존한다. 직렬/병렬 구현체는 교체 가능하며, 교체 시 다운스트림은 무변경이다.

**산출물 계약 (인터페이스 경계, 병렬 에이전트 프로젝트의 D2)**
- 포맷: `.exec 호환` 또는 자체 포맷 + 변환기. **JaCoCo classId/probe 체계 유지** 필수.
- 키: `(test_id, commit_sha, service, class_fqn, method_sig, line_bitmap)`
- **정밀도: 라인 수준 필수.** 병렬 에이전트도 TIA용으로는 라인 모드 고정 요구 → 목적 2의 `라인 ∩ diff`가 정밀도 분기 없이 항상 성립.

| 구현체 | 컨텍스트 분리 방식 | 상태 |
|--------|-------------------|------|
| `teamscale/jacoco 직렬` | reset→실행→dump 직렬 사이클 | Phase 0 주력 |
| `jacocoagent-parallel.jar` | ThreadLocal 스토어 + Baggage `test.id` (in-process) | 완성 시 **무중단 드롭인** (별도 프로젝트, 독립 일정) |

TIA는 인터페이스에만 의존하고 병렬 에이전트에 직접 의존하지 않는다. TIA Phase 0~3는 직렬로 완주 가능하며, 병렬 에이전트는 준비되면 교체한다.

### 3.1 수집 메커니즘 (직렬 구현체)
- 대상 앱: `-javaagent:jacocoagent.jar=output=tcpserver,port=6300,address=*`
- 테스트 측: JUnit5 Extension에서 `@BeforeEach reset` → 테스트 실행 → `@AfterEach dump`
- 구현은 직접 소켓 대신 **jacoco-core ExecDumpClient** 활용. 단, **1순위 PoC는 teamscale-jacoco-agent**(JaCoCo 래핑, HTTP 엔드포인트로 테스트 시작/종료 신호 → testwise coverage JSON 산출, Teamscale 서버 불필요)로 reset/dump 사이클 직접 구현을 대체.
- 대안: ① JMX 인터페이스 ② jacococli dump CLI(PoC 최속) ③ 테스트 ID 전파 + 자체 계측(구축비 2~3배).

### 3.2 직렬 구현체에 격리되는 속성

아래 항목들은 **직렬 구현체의 속성**이며 TIA 본체의 속성이 아니다. 병렬 에이전트 드롭인 시 대체된다.

| 직렬 구현체 속성 | 병렬 전환 시 |
|------------------|--------------|
| 직렬 실행 강제 (병렬 시 커버리지 혼합) | 해제 — ThreadLocal 컨텍스트 분리 |
| reset→dump 경계 | Baggage `test.id` 경계로 대체 |
| 노이즈 베이스라인 차감 (§3.4) | 불필요 — 노이즈는 `test.id` 없어 자동 비귀속 |
| quiesce-then-dump (§5 Kafka) | Baggage 비동기 전파로 정밀 대체 |
| 경계 누수 watchdog dump (§3.3) | Baggage 경계로 대체 |

### 3.3 경계 누수 방어 [결정 8.4]
테스트 종료 신호 누락(실패/타임아웃) 시 커버리지 누수 방어:
- **경계 보장은 `beforeEach reset`에 위임** — afterEach dump가 실패해도 다음 테스트로 오염이 전파되지 않음.
- `afterEach`는 try/finally + 글로벌 watchdog으로 강제 dump.
- 테스트별 max-duration 초과 시 강제 dump+reset, 해당 결과는 **'오염' 플래그**로 표시.
- 결정론적이고 구현 단순. (직렬 구현체 전용.)

### 3.4 노이즈 베이스라인 차감 [결정 8.5] (직렬 전용, 2단)
백그라운드 노이즈(@Scheduled, 헬스체크 등) 제거:
1. **베이스라인 차감**: 무부하 N초 dump로 노이즈 베이스라인 생성 후 차감.
2. **트레이스 교차검증**: sessionId + 트레이스 교차검증으로 신뢰도 보정.

### 3.5 운영 모델
- 오버헤드: dump 1회 수십~수백 ms, 500테스트 기준 스위트당 +15분.
- → **나이틀리 매핑 DB 갱신, 평시 조회 전용** 구조.
- 클래스 ID 정합성: `.exec`는 바이트코드 해시 식별 → 측정/분석 시점 빌드 아티팩트 동일성 보장.

### 3.6 MSA 보너스
테스트당 전 서비스 동시 dump → "테스트 T ↔ 서비스별 커버 메소드 집합" 매핑 자동 산출. 크로스 레포 매핑의 상당 부분을 정적 분석 없이 동적으로 획득.

---

## 4. 정적 보조 경로

- **scip-java** 호출그래프: 커버리지 없는 신규 코드 + staleness 간극 보완. `static_edges` 재귀 CTE로 영향 전파.
- **Java 8 레거시**:
  - 동적(JaCoCo 0.8.x)은 Java 8 런타임 지원 → Phase 0부터 무비용 포함.
  - 정적(scip-java)은 최신 JDK 구동 필요 → 레거시 빌드 체인과 마찰. 해당 레포는 기존 7단계 AST 파이프라인(JavaParser, Java 8 문법 완전 지원)으로 대체 커버.
  - 후순위로 둘 것은 "레거시의 정적 그래프"뿐.
- **과신 금지**: 동적 데이터를 항상 우선.

---

## 5. 크로스 레포 / API 경계 / Kafka

### 5.1 Baggage `test.id` — Phase 0 필수 선결
RestAssured 클라이언트가 **모든 인입 요청에 `test.id` Baggage를 주입**한다. Phase 0부터 의무.
- 직렬 모드: dump 라벨(IAgent.setSessionId)로만 사용 → 비용 거의 없음.
- 병렬 모드: 컨텍스트 활성화 입력 와이어가 이미 연결됨 → **재작업 0**.

OTel Baggage의 역할: ① dump 라벨 신뢰성 ② Kafka/멀티홉 귀속(자동 계측이 Kafka 헤더까지 전파) ③ 트레이스-커버리지 교차 검증.

### 5.2 API 경계 매핑 [결정 8.6] — 동적 우선 + OpenAPI 보조
- **동적 우선**: MSA 동시 dump로 실제 호출된 provider 메소드를 직접 귀속.
- **OpenAPI 보조**: 커버 안 된 경계를 스펙으로 보완.
- 어노테이션(@RestController ↔ @FeignClient/RestAssured) 매칭은 fallback.
- 계약 테스트(Pact/Spring Cloud Contract): 컨슈머↔프로바이더 의존을 명시 산출물로 → 경계 매핑 부산물.

### 5.3 Kafka 메시지 귀속 (Phase 3)
- 커버리지 수집은 컨슈머 JVM 에이전트로 해결. 난점은 비동기 처리분의 테스트 귀속.
- **Quiesce-then-dump**: dump 전 컨슈머 그룹 lag=0까지 Awaitility 대기(타임아웃 예: 10s). 직렬 실행과 궁합 좋음. (병렬 시 Baggage 전파로 대체.)
- **헤더 전파(정밀)**: OTel Baggage가 Kafka 헤더 자동 전파 → 컨슈머 측 RecordInterceptor(spring-kafka)로 헤더→처리 로그 교차 검증.

---

## 6. 저장소 & 데이터 모델

### 6.1 설계 원칙
1. **커밋이 1급 시민**: 커버리지 매핑은 특정 빌드 스냅샷 → 모든 레코드 키에 commit SHA 포함.
2. **사람이 DB를 직접 보지 않는다**: 개발자/테스터 모두 자기 워크플로우 안에서 답을 받는다.

### 6.2 스키마 초안
```sql
builds(repo, commit_sha, build_id, indexed_at)
tests(test_id, test_name, test_repo)
coverage(test_id, build_id, service, class_fqn, method_sig, line_bitmap)
static_edges(build_id, caller_method, callee_method, edge_kind)  -- CALL/DI/OVERRIDE
api_edges(consumer_repo, http_method, path_pattern, provider_repo, controller_method)
```
- 라인 정보는 행 전개 대신 **Roaring Bitmap 블롭**. 테스트 500 × 메소드 2,000 최악 100만 행/빌드가 비트맵 압축 시 빌드당 수십 MB. "diff 라인 ∩ 커버 라인" 판정 = 비트 AND 1회.
- 정적 그래프 핵심 쿼리: `static_edges` 재귀 탐색(영향 전파).

### 6.3 저장 기술 — 단계적 채택
- **Phase 0~1 (시작점): 레포당 SQLite + S3.** CodeGraph `.codegraph/` 패턴 차용. 나이틀리 잡이 `.exec` 가공 → `tia-{repo}-{commit}.sqlite` S3 업로드 → Action/CLI/MCP가 다운로드 후 로컬 쿼리. 인프라 신청 0, 운영 0, 스냅샷 자체가 불변 버전.
- **Phase 3 (크로스 레포 본격화): 중앙 PostgreSQL.** api_edges 조인, "서비스 A 변경 → B의 테스트" 다중 레포 쿼리. 재귀 CTE로 그래프 탐색, 접근 제어·감사 확보.
- **중간 대안: S3 Parquet + DuckDB.** raw `.exec`는 S3 영구 불변 원본, 가공 매핑은 커밋별 파티셔닝 Parquet, DuckDB가 S3 직접 쿼리.

### 6.4 기각된 선택지
- **Neo4j 등 그래프 DB**: 레포 10개·메소드 수만 개 규모에서 재귀 CTE로 충분. 탐색 깊이 10단계+ 상시화 전까지 과잉.
- **처음부터 중앙 DB**: 통신사 환경 DB 신청·보안 검토가 구축보다 오래 걸릴 수 있음 → SQLite로 가치 증명 후 인프라 정당화.

### 6.5 신선도(staleness) 저장 설계 내장 [9.6]
- 조회 시점에 매핑 기준 커밋 ↔ 개발자 HEAD diff 계산 → 그 사이 변경된 메소드는 **"저신뢰" 플래그 + 정적 그래프 폴백**.
- 응답 형태 고정: `영향 테스트 목록 + 매핑 기준 커밋 + 신뢰도`. → 허용 staleness가 고정 정책이 아닌 **응답 메타데이터로 자연 해소**.
- 보존 정책: 최근 빌드 N개 + 주간 스냅샷으로 시작.

---

## 7. 조회 인터페이스

| 소비자 | 경로 | 형태 |
|--------|------|------|
| 개발자 | PR 생성 시 GitHub Action이 diff → 매핑 조회 | **PR 코멘트 이원화** + 로컬 CLI |
| 테스터/RCA | qe-rca-action이 실패 시 커버집합∩diff 판정 조회 | DETERMINISTIC 신호로 Jira 첨부 |
| 대화형 탐색 | 저장소 위 얇은 조회 레이어에 **자체 MCP 서버** | 도구 3종 + 필드별 provenance 태그 |

### 7.1 PR 코멘트 이원화 [결정 9.7.1]
- **고신뢰 메인 목록**: 확실한 영향 테스트.
- **`⚠️ 검토 필요` 별도 섹션**: 기준 커밋 이후 변경된 메소드로 인한 저신뢰(정적 폴백) 테스트.
- 신뢰도 점수 단일 목록보다 **섹션 분리가 개발자 행동을 더 바꾼다**는 판단.
- 로컬용 CLI `tia impact --diff HEAD~1` 병행.

### 7.2 MCP 서버 — provenance 태깅 [결정 9.7.3]
- 도구 3종: `impacted_tests(diff)`, `coverage_of(test_id)`, `triage(test_id, commit_range)`.
- MCP 도구는 **구조화 데이터만 반환**, 각 필드에 **provenance 태그**: `DETERMINISTIC`(커버 AND 결과) / `INFERRED`(정적 폴백) / `STALE`.
- LLM 추론은 그 위 대화 레이어에서만, 태그를 근거로 인용. "정량은 코드, 정성은 LLM" 테제와 일치.

---

## 8. 병렬화 전략 [결정 8.1]

JaCoCo 직렬 구현체는 컨텍스트 분리 불가(프로브가 클래스당 전역 `boolean[]`, sessionId는 dump 라벨일 뿐). 전환 경로:

- **목표 종착점**: `jacocoagent-parallel.jar` 드롭인. JaCoCo ProbeInserter 발화를 ByteBuddy Advice로 후킹 → ThreadLocal 컨텍스트별 스토어에 복제 기록(Datadog dd-trace-java 패턴, Apache 2.0 레퍼런스). in-process 분리이므로 **인스턴스 비증식, 메모리 비용 없음**.
- **interim fallback**: 인스턴스 분리 — JUnit5 병렬 워커당 Testcontainers 독립 앱+에이전트. 4워커 기준 메모리 4배 vs 수행 시간 1/4. 에이전트 미완성 시에만 사용. (절충안 "변경 서비스만 격리"는 공유 서비스 커버리지 오염으로 해당 판정 제외 필요.)
- **전환 트리거**: SLA는 PR 지연이 아니라 **직렬 스위트가 나이틀리 잡 윈도우를 초과**하는 시점. 도달 시 에이전트 준비됐으면 드롭인, 아니면 인스턴스 분리.

---

## 9. Phase 계획 & 리소스 추정

전제: 주 10시간, Claude Code(구독) 활용, 대상 5~10개 레포 MSA.

| Phase | 내용 | 기간 | 시간 |
|-------|------|------|------|
| 0 | 단일 레포 PoC: teamscale-jacoco-agent per-test 매핑 + git diff 교차 → 영향 테스트 선별. **플레이키 비율 선측정 포함.** `test.id` Baggage 주입 선결 | 2주 | 20~25h |
| 1 | 실패 분류기: 커버집합∩diff 판정 → qe-rca-action DETERMINISTIC 신호 주입 | 1~2주 | 10~15h |
| 2 | scip-java 정적 그래프 보강(커버리지 사각지대) | 2~3주 | 20~30h |
| 3 | 크로스 레포 API 경계 매핑(컨트롤러↔클라이언트↔RestAssured) + Kafka 귀속 | 3~4주 | 30~40h |
| **합계** | | **약 2.5~3개월** | **80~110h** |

**권장 진행 순서**: ① teamscale-jacoco-agent 직렬 testwise PoC(1주 내 검증) + `test.id` Baggage 주입 → ② Baggage를 Kafka 귀속·라벨 정합성용 병행 활용 → ③ 나이틀리 윈도우 초과 시 병렬 전환(에이전트 드롭인 우선, 미완성 시 인스턴스 분리).

금전 비용: OSS + 기존 인프라로 사실상 0원. Claude Max 구독 시 추가 API 비용 0 수렴, API 직결 시 월 5~15만원.

---

## 10. 리스크 및 반대 관점

- **최대 숨은 비용은 운영**: 커버리지 데이터는 머지 즉시 낡기 시작 → 나이틀리 재수집 파이프라인이 영구 유지 과제. 허용 staleness는 §6.5로 응답 메타데이터화하여 해소.
- **플레이키 테스트가 판정 신뢰도를 잠식**: "diff 무관 실패 = 인프라" 판정의 ROI는 스위트 안정성에 종속 → Phase 0에서 플레이키 비율 선측정.
- **정적 그래프 과신 금지**: Spring 프록시/리플렉션/비동기 오탐·미탐 상존 → 동적 데이터 항상 우선.
- **벤더 무료 티어 급폐지 전례**(Sourcegraph Cody Free/Pro 2025.7 종료 등) → 외부 의존 최소화 전략 타당.
- **병렬 에이전트 종속성**: TIA는 인터페이스에만 의존하므로 에이전트 일정 지연이 TIA를 막지 않음. 단 병렬 에이전트의 JaCoCo 내부 클래스(ProbeInserter) 후킹은 JaCoCo 업그레이드마다 파손 가능 → 버전 매트릭스 카나리 테스트 필요(병렬 에이전트 프로젝트 책임).

---

## 11. 확정된 설계 결정 요약

| # | 결정 | 근거 |
|---|------|------|
| D1 | 경계 누수 방어 = reset 우선 + watchdog dump | 결정론·구현 단순, 다음 테스트 오염 차단 |
| D2 | 노이즈 = 베이스라인 차감 + 트레이스 교차(2단, 직렬 전용) | 단순 1차 + 정밀 2차 |
| D3 | API 경계 = 동적 우선 + OpenAPI 보조 | 동적 우선 테제 일관 |
| D4 | 무관 테스트 스킵 = 설계 포함, 기본 OFF + 주기적 전체실행 | 신뢰 확보 후 활성화 |
| D5 | PR 코멘트 = 이원화(고신뢰 + ⚠️ 별도 섹션) | 섹션 분리가 행동을 바꿈 |
| D6 | 병렬 전환 트리거 = 나이틀리 윈도우 초과 | 평시 조회 전용 구조라 PR 지연 무관 |
| D7 | MCP = 필드별 provenance 태그(DETERMINISTIC/INFERRED/STALE) | 정량/정성 경계 명시 |
| D8 | 수집 레이어 = `CoverageCollector` 인터페이스(드롭인 seam) | 직렬/병렬 무중단 교체 |
| D9 | `test.id` Baggage = Phase 0 필수 선결 | 병렬 에이전트 입력 와이어 사전 연결 |
| D10 | 병렬 전환 종착점 = `jacocoagent-parallel.jar` 드롭인, 인스턴스 분리는 interim fallback | in-process 분리가 메모리 비용 없음 |
| D11 | 산출물 정밀도 = 라인 수준 필수(병렬 에이전트도 라인 모드 고정) | 목적 2의 라인∩diff 항상 성립 |

---

## 12. 구현 중 튜닝 항목 (설계 결정 아님)

- 인스턴스 분리 절충안의 recall 손실 정량화 [8.7] — Phase 측정.
- Roaring Bitmap 역방향 쿼리("이 라인 커버하는 테스트") 보완 인덱스 [9.7.2] — 필요 시 추가.
- 병렬 에이전트 정밀도/컨텍스트 전파/카나리 [병렬 doc Q1~Q6] — 병렬 에이전트 프로젝트 책임, TIA Phase 0 데이터로 검증.

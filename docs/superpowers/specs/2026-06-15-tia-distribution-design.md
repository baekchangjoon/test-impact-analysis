# TIA 배포(Distribution) 설계 문서

- 작성일: 2026-06-15
- 상태: 멀티모델 리뷰 반영 → **D0·D1·D1.5·D2·D3 구현 완료(머지)** → D4 조건부(미착수). **v0.1.1 릴리스(라이브)**: GitHub Packages·ghcr 이미지·GitHub Release(tia.jar·SBOM·라이선스 고지) + SBOM/취약점 게이트·E2E-2/3 발동. 수집 모델(out/in-process)은 실제 에이전트로 실증(petclinic 데모·run-poc); 플러그인-드리븐 전체 E2E-4는 후속.
- 리뷰: cross-vendor design-doc 리뷰 2라운드(전체 + D1.5 추가분) — Claude(opus/sonnet/haiku)·Gemini 3.5 Flash·GPT-5.2. 주요 지적 반영(jacoco core/report 라이브러리화, D2 impact-only, 병렬 포트 동적할당, report 동치=JSON-island, PREFIX 영향 정정 등)
- 선행 문서: [`2026-06-13-test-impact-analysis-design.md`](2026-06-13-test-impact-analysis-design.md) (시스템 설계), `petclinic-demo/README.md` (현행 end-to-end 데모)
- 범위: **이미 구현된 TIA 엔진(Phase 0 PoC)을 사용자에게 어떤 형태로 전달할 것인가** — 패키징·배포·통합. 엔진 알고리즘 자체는 선행 설계 문서를 따른다.

---

## 1. 목표 & 비목표

### 1.1 목표
- TIA를 **두 사용자층**이 "조립 없이" 도입하게 한다.
  - **QE 팀** — 테스트 선별, 테스트케이스 유효성/blind-spot 식별, 플레이키 추세 소비. 주 소비물은 **리포트**와 **선별 결과**.
  - **백엔드 개발팀** — 변경 코드에 대한 최적 테스트 선별. 주 소비 지점은 **로컬(커밋 전)**과 **PR/CI**.
- "쉽게"의 정의를 **단일 진입점 + 무설정 실행**으로 고정한다(§3.1).

### 1.2 비목표
- 엔진 알고리즘 변경(커버리지∩diff, 플레이키 비율 등)은 본 문서 범위 밖.
- 멀티테넌트 SaaS 제품화는 본 문서에서 **방향만** 다루고 설계하지 않는다(§6, Phase D4 후보).
- 커버리지 수집 에이전트(teamscale / parallel-per-test-coverage) 자체의 배포는 별도 — 본 문서는 **번들/문서화 책임**만 정의한다(§5.3).

### 1.3 성공 기준 (definition of done)
1. 신규 사용자가 **README의 한 경로**만 따라 자신의 SUT에 대해 선별 결과 + 리포트를 얻는다.
2. §7의 **E2E/수용 테스트가 전부 GREEN**.
3. 각 산출물(라이브러리·CLI·이미지·플러그인)이 **버전 핀**과 **재현 가능한 릴리스 절차**를 가진다.

---

## 2. 현행 자산과 격차

### 2.1 자산 (이미 있는 것)
- `tia-core` — 엔진(라이브러리). 커버리지 파싱·diff 교차·선별·플레이키.
- `tia-cli` — picocli `application` 플러그인 기반 CLI. 서브커맨드 `index` · `impact` · `flaky`. `installDist`로 launcher+lib 산출.
- `tia-junit-extension`, `coverage`, `fixture-app`, `e2e` — JUnit 연동·수집·픽스처·E2E.
- `petclinic-demo/` — 실제 블랙박스 스위트 end-to-end 데모 + 인터랙티브 HTML 리포트 생성기.
- `docker-compose.e2e.yml`, `scripts/` — 컨테이너 E2E 자산.

### 2.2 격차 (배포를 막는 것) — **선행 해결 대상**
1. **워크플로 3분할.** 사용 흐름이 ① JVM CLI(`tia-cli`) + ② **Python 글루**(`exec_to_testwise.py`·`make_html.py`·`run_scenarios.py`) + ③ **외부 커버리지 에이전트**로 흩어져 있다. 어떤 패키지든 이 셋을 묶지 않으면 "단일 진입점" 약속이 깨진다.
2. **CLI가 fat-jar가 아님.** `installDist`는 launcher 스크립트 + lib 디렉터리 분산 → 단일 파일 배포 불가.
3. **퍼블리시 경로 부재.** `tia-core`/`tia-cli`가 아티팩트 저장소(Maven Central/GitHub Packages)에 게시되지 않음.
4. **버전·SUT 비종속성 미검증.** 리포트 생성기는 최근에야 SUT 이름·옵셔널 입력을 지원. 또한 `make_html.py`에 `PREFIX="org/springframework/samples/petclinic/"`가 하드코딩돼 `short()`로 경로 축약에 쓰인다. 다른 SUT에서 **기능이 파손되지는 않으나**(경로가 축약 없이 전체로 표시될 뿐), **표시 품질 저하 + 골든 비교 안정성**에 영향 → 표시용 `--prefix-strip`/LCP 자동 감지로 일반화(§4 D0).

> **결론:** 패키징 형태 선택보다 **격차 #1(글루 통합)·#2(fat-jar)가 모든 형태의 선행 조건**이다. Phase D0가 이를 처리한다(§4). 격차 #3(게시 경로)은 D1에서 발동하지만, 네임스페이스/저장소 예약(O1)은 **D0와 병행해 미리** 확보해 D1 정체를 막는다.

---

## 3. 핵심 결정

### 3.1 "쉽게 = 단일 진입점 + 무설정"
TIA는 **빌드 타임 · 레포 로컬** 결정 엔진이다(소스·커버리지가 로컬, 선별은 빌드 시점). 따라서 1차 전달 형태는 **로컬/CI에서 도는 실행물**이어야 하며, 원격 서비스는 부차적이다.

### 3.2 단일 형태가 아니라 **계층형 배포**
하나로 모든 사용자를 만족시킬 수 없으므로 **엔진 1개를 여러 표면으로 감싼다**:

```
                  tia-core (library, 엔진)
                        │
          tia CLI (fat-jar; Python 글루 흡수: convert/report 서브커맨드)
        ╱        │         │            │            ╲
 Docker 이미지  Action  Gradle 플러그인  Agent Skill   (선택) MCP 서버
 (무설정 CI)  (CI 통합) (빌드 네이티브)  (SKILL.md,    (구조적 tool-call)
                                       cross-tool 표준)
```

모든 상위 표면은 **동일한 CLI/코어**를 호출한다(중복 로직 금지).

### 3.3 사용자층 ↔ 형태 매핑

| 사용자 | 주 사용 지점 | 1차 형태 | 2차 |
|---|---|---|---|
| 백엔드 개발자 | 로컬 커밋 전 / PR | **Gradle 플러그인** (`./gradlew tiaImpact`) | fat-jar CLI |
| 백엔드 팀 CI | PR 게이트 | **GitHub Action** | Docker 이미지 |
| 개발자/QE (에이전트·IDE) | Claude·Kiro·Antigravity 등에서 자연어 질의 | **Agent Skill (SKILL.md)** | (선택) MCP |
| QE | 리포트·추세·blind-spot | **HTML 리포트**(CLI `report`) + CI 아티팩트 | (Phase D4) 대시보드 |
| 프레임워크/도구 제작 | 임베딩 | **`tia-core` 라이브러리** | — |

> 표는 각 층의 **최고 가치 형태**이고, 실제 이용 가능 시점은 Phase에 따른다: 로컬 CLI는 D0(또는 D1 fat-jar)부터, GitHub Action은 D2부터, Gradle 플러그인은 D3부터. 롤아웃 커뮤니케이션은 이 타이밍을 반영한다.

### 3.4 옵션별 판정 (요청된 4형태 + 추가)

| 형태 | 판정 | 근거 |
|---|---|---|
| 라이브러리(`tia-core`) | **채택(토대)** | 이미 라이브러리. 게시하면 플러그인·타도구의 엔진. 단독 end-user 도구는 아님 |
| Fat-jar CLI | **채택(1차 베이스)** | `shadowJar`로 단일 실행 jar. polyglot·CI 스크립트·상위 표면의 백엔드 |
| Docker 이미지 | **채택(보조)** | JDK17 + fat-jar CLI를 한 이미지에(impact-only, §D2). 무설정 CI/데모 |
| 웹 서비스 | **보류(조건부)** | 선별은 빌드 타임·로컬이라 SaaS는 마찰만 큼. **단** 다중 레포·팀의 선별/플레이키 이력 집계 대시보드라면 Phase D4 제품 후보 |
| (+) Gradle/Maven 플러그인 | **채택(도입 1순위)** | TIA는 빌드 타임 결정 → JVM 팀 도입 마찰 최소. 요청 목록 외지만 최고 레버리지 |
| (+) GitHub Action | **채택(CI 1순위)** | `uses: org/tia-action@v1` 한 줄로 PR 통합 |
| (+) **Agent Skill (`SKILL.md`)** | **채택(에이전트 표준)** | Anthropic이 오픈 표준으로 공개한 포맷. Claude·Kiro·Antigravity·Gemini CLI·Cursor·Copilot·Codex 등 40+ 도구가 동일 폴더를 소비 → "한 번 작성, 모든 skills-호환 에이전트 재사용". scripts가 D0 CLI 호출(§D1.5) |

---

## 4. 단계별 롤아웃

각 Phase는 독립 릴리스 가능하며 이전 Phase에만 의존한다.

### Phase D0 — 통합 선행작업 (격차 #1·#2 해소) · **필수**
- **네이밍 정리:** picocli **command 이름은 이미 `tia`**(현행 `TiaCommand`)다. 바뀌는 것은 **실행 launcher 바이너리(`tia-cli`→`tia`)와 fat-jar 파일명(`tia.jar`)**뿐이다. Maven 좌표·Gradle 모듈명은 유지. 전체 네이밍 매트릭스는 §5.1.
- Python 글루를 JVM CLI 서브커맨드로 흡수:
  - `tia convert --exec-dir <dir> --classes <dir> [--sources <dir>] --out <testwise.json>` ← `exec_to_testwise.py` (per-test .exec → testwise.json). **변환은 CLI 의존이 아니라 라이브러리 의존**으로 한다: `org.jacoco:org.jacoco.core`(`.exec` 로딩·`Analyzer`로 클래스파일 분석→라인 커버리지)와 필요 시 `org.jacoco:org.jacoco.report`를 in-process로 사용 — `org.jacoco.cli`(메인 엔트리 실행물, 안정적 라이브러리 API 아님)나 외부 `~/.m2` jar 탐색·subprocess는 제거. 라이선스 영향은 §5.4.
  - `tia report --testwise <json> --scenarios <json|-> --flaky <json|-> --prod-files <txt|-> --commit <sha> --out <html> [--sut-name <s>] [--jacoco-dir <d>] [--test-src-root <p>]` ← `make_html.py` (옵셔널 입력·SUT명·딥링크 인자 그대로 보존). **경로 축약 `PREFIX`는 하드코딩 대신 `--prefix-strip` 인자 또는 커버 경로의 최장공통접두(LCP) 자동 감지로 일반화.**
- **단일 실행 jar:** D0는 **`shadowJar`(fat-jar)를 채택**한다 — E2E-1의 `java -jar tia.jar …`가 이를 전제. 네이티브(`jpackage`/GraalVM)는 기동시간 요구가 실증될 때 별도 Phase(O2)에서 추가.
- **호환:** 기존 `index`/`impact`/`flaky` 인터페이스·플래그 불변. Python 스크립트는 데모 보존용으로 남기되 "deprecated, CLI로 대체" 명시.
- **수용:** §7 E2E-1.
- **규모:** L (Python→Java 포트 2건 + JaCoCo core/report 라이브러리화 + shadowJar).

### Phase D1 — 라이브러리 + CLI 게시 · **1차 배포**
- 선행: 네임스페이스/저장소(O1) 확정 — D0와 병행 예약.
- `tia-core`·`tia-cli`를 GitHub Packages(우선) 또는 Maven Central에 게시. SemVer 도입. (네이밍: **실행 launcher/fat-jar는 `tia`/`tia.jar`**로 개명하되 **Maven 좌표 artifactId는 `tia-cli` 유지** — 좌표 변경은 호환성 비용이라 별도 결정. E2E-2의 `io.tia:tia-cli`와 일관.)
- **버전 단일 소스:** 현재 `build.gradle`(`version='0.1.0'`)과 `TiaCommand.java`(`version="tia 0.1.0"`)가 이중 선언이다. 빌드 시 생성 리소스(`version.properties`) 또는 picocli `versionProvider`로 일원화해 `tia --version`이 릴리스 버전과 항상 일치하게 한다.
- 릴리스 워크플로(CI 태그 → 빌드 → 게시 → fat-jar를 GitHub Release 자산으로 첨부).
- README에 "설치: jar 다운로드 또는 의존성 좌표" 섹션.
- **수용:** §7 E2E-2.
- **규모:** M.

### Phase D1.5 — 표준 Agent Skill 배포 (SKILL.md) · **에이전트 표면**
대화형 에이전트(Claude·Kiro·Antigravity 등)에서 "이 변경에 영향받는 테스트?"를 자연어로 묻고 선별/리포트를 받게 한다. 개발자의 로컬·커밋 전 사용과 QE 탐색에 직접 대응.

- **표준 근거:** Agent Skills(`SKILL.md`)는 Anthropic이 만들어 **오픈 표준**으로 공개했고, Claude/Claude Code·**Kiro**(`~/.kiro/skills/`)·**Google Antigravity**·Gemini CLI·Cursor·GitHub Copilot·Codex 등 **40+ 도구가 동일 포맷**을 소비한다(progressive disclosure: name/description → SKILL.md → 번들 scripts). 출처/스펙: `agentskills.io`(spec + Client Showcase) — 구현 시 버전 스냅샷을 핀(O5).
- **"호환"의 정의:** 도구 간 보장되는 공통분모는 **SKILL.md 파싱·활성화**다. **scripts 셸 실행 허용 여부는 도구·권한 모델에 종속**하므로, 설치 경로와 함께 도구별로 "검증됨 vs 가정"을 구분해 명시(R8/O5). → 한 폴더를 한 번 만들면 skills-호환 에이전트 전반에서 재사용.
- **산출물:** `tia` 스킬 폴더 = `SKILL.md`(name/description + "변경 영향 테스트 선별·리포트" 워크플로 지침) + `scripts/`(D0 fat-jar CLI 호출). **로직은 CLI에 있고 스킬은 얇은 절차 래퍼**(중복 금지, §3.2 원칙).
- **스킬의 책임 경계(전제):** 스킬은 **이미 만들어진 TIA 인덱스(DB)에 대한 질의** 표면이다 — 주로 `impact`(선별)와 `report`(리포트). 커버리지 수집→`convert`→`index`(에이전트·SUT 실행이 필요한 무거운 단계, §5.3/D3.1)는 **스킬의 책임이 아니라** CLI/플러그인/CI의 일이다. (DB가 있으면 스킬이 `index`까지 호출할 수 있으나 수집 자체는 범위 밖.)
- **인덱스 DB 취득:** 로컬 질의에는 대상 커밋의 DB가 있어야 한다. 출처는 (a) 로컬 `tia index` 산출물, 또는 (b) CI가 만든 DB를 아티팩트/원격 저장소에서 받아오기. 커밋 기준 자동 취득(예: `tia pull --repo <n> --commit <sha>`)은 향후 과제(O7) — 그전에는 DB 경로를 인자/환경변수로 지정.
- **CLI 취득:** D1 이전(D0만) — scripts는 `tia`(PATH) 또는 `TIA_JAR` 환경변수가 가리키는 로컬 빌드 fat-jar를 사용. D1 이후(권장) — 고정 버전 Release 자산을 다운로드·SHA-256 핀·캐시.
- **사전 점검/실패 동작:** scripts는 호출 전 **JDK(17+)·CLI(jar) 가용성을 점검**하고, 미충족이면 설치 안내가 담긴 메시지를 출력하고 **non-zero로 종료**한다(빈 선별 반환 금지 — 에이전트가 실패를 인지하도록).
- **배포:** 스킬 폴더를 레포/릴리스 자산으로 공개. 각 도구는 자기 관례 위치에 같은 폴더를 설치(Claude `.claude/skills/`, Kiro `~/.kiro/skills/`, Antigravity·Gemini CLI 등 — 정확한 경로는 구현 시 각 도구 문서로 확인, O5).
- **의존:** D0(CLI — 스킬 scripts가 호출) 필수, D1(버전 핀 다운로드) 권장. **D2/D3와 병렬** 가능(축이 다름 — 에이전트 표면 vs CI/빌드).
- **MCP는 본 Phase 범위 밖:** 구조적 tool-call 통합 수요가 실증되면 별도로 착수(O6). D1.5의 산출물·수용·규모는 SKILL.md 경로만 포함한다.
- **수용 기준(스펙 준수):** (a) 참조 스펙 URL/버전 핀(agentskills.io specification), (b) `SKILL.md` 최소 필수 필드(`name`/`description`)만 사용해 호환성 폭 최대화, (c) scripts 실행 권한·실패 규약(JDK/CLI 미충족 시 non-zero + actionable 메시지) 표준화, (d) 도구별 설치 경로를 **"검증됨"(실제 설치·동작 확인) vs "가정"(미검증)**으로 구분 표기. (§7 E2E-1.5와 함께)
- **규모:** S~M (스킬 폴더 + scripts는 얇음; 도구별 설치 검증이 비용).

### Phase D2 — Docker 이미지 + GitHub Action · **CI 도입**
- **D2 범위 = impact-only(확정):** 이미지/Action은 **이미 만들어진 DB + diff를 소비해 선별만** 한다 → **JDK 17만** 필요(§5.2 충돌 없음). 무거운 `convert`/`index`(SUT 실행·classfile 접근·SUT JDK 필요)는 D2 범위 밖(빌드/플러그인/사전 CI 단계의 일). 이로써 "무설정" 목표가 호스트 빌드 의존으로 약화되지 않는다.
- 이미지: `ghcr.io/<org>/tia:<ver>` — JDK17 + fat-jar CLI. **실행 계약:** 엔트리포인트는 `/usr/local/bin/tia` 래퍼가 `java -jar /opt/tia/tia.jar` 호출(= `tia` 명령과 fat-jar 양립).
- GitHub Action(composite 또는 Docker; O3): 입력 `db`/`commit`/`diff`/`base-ref`.
- **Action 데이터 플로우(고정):** baseline DB 취득(아티팩트/캐시/Release — 대상 커밋 기준) → diff 생성(`base-ref...HEAD`) → `tia impact` → PR 코멘트/체크. **DB 미존재·commit 불일치·staleness 시 동작은 명시적 규칙으로**: 기본은 **CONSERVATIVE 전체 선택**(누락 위험 0, 안전망)이며 `--strict`에서만 실패. 이 규칙을 수용 기준에 포함.
- **수용:** §7 E2E-3.
- **규모:** M.

### Phase D3 — Gradle 플러그인 (이후 Maven) · **도입률 극대화**
- `io.tia` Gradle 플러그인. 태스크: `tiaIndex`·`tiaImpact`·`tiaReport`. 커버리지 수집(에이전트) 연결 + diff 산출 + 선별을 빌드 그래프에 통합.
- **D3.1 에이전트 와이어링(최대 리스크):** 플러그인은 (a) 커버리지 에이전트 jar를 전용 Gradle configuration으로 해소(자동 다운로드는 §5.3 정책), (b) `Test` 태스크 JVM에 실제 에이전트 계약대로 `-javaagent:<jar>=destfile=<dir>,port=<ctrl>,includes=…`를 주입하고 per-test 드라이버를 `-Dpjacoco.control-url`로 연결, (c) per-test `<testId>.exec`를 `destfile` 디렉터리에서 수확 → `tia convert`. **control 포트는 고정**(ephemeral 아님; `io.pjacoco.agent.AgentOptions` 확인)이라 측정 JVM은 단일이어야 함 → 플러그인이 `maxParallelForks = 1`로 고정(고정 포트 충돌 방지). teamscale/parallel 차이는 §5.3의 어댑터 경계로 흡수한다.
- **D3.2 diff 출처:** `tiaImpact`는 기본적으로 **two-dot** `git diff <base-ref>`(base-ref = 인덱싱 커밋; old-side가 인덱싱 베이스라인 라인공간과 정렬 — 선행 설계 §6.2)에서 diff를 얻는다. ※ three-dot(`base...HEAD`)은 merge-base 기준이라 베이스라인 라인공간과 어긋날 수 있어 채택하지 않음(D2/CLI `ImpactCommand`도 two-dot). CI용으로 `--diff-file`(precomputed diff) 오버라이드를 허용한다(`tia.diffFile`).
- 설정: 베이스라인 ref, 스킵 ON/OFF(기본 OFF — 선행 설계의 안전망 정책 계승), 리포트 출력 경로.
- **수용:** §7 E2E-4.
- **규모:** L (에이전트 와이어링이 핵심 난이도).

### Phase D4 — (조건부) 집계 대시보드 / 웹 서비스
- 트리거 조건(측정 가능): D0~D3 출시·활성 사용 후 **2개 이상 팀**이 교차 레포 이력/추세 질의를 명시 요청(이슈/디스커션 투표 또는 명시 RFP)할 때. 착수 전 게이트 리뷰로 별도 제품 설계로 분기.
- 형태 후보: 선별/플레이키/blind-spot 결과(JSON)를 수집·시각화하는 read-only 대시보드. 엔진은 여전히 CI에서 로컬 실행, 서비스는 **결과만** 받는다(소스/커버리지 원본 비전송).
- 본 문서에서는 **착수하지 않음** — 별도 제품 설계로 분리.

---

## 5. 산출물 사양

### 5.1 버전·호환 & 네이밍 매트릭스
- 전 산출물 **SemVer**. `tia-core`의 testwise.json/DB 스키마는 호환성 계약(파서: `TestwiseReportParser`). 스키마 변경 시 major.
- CLI 플래그는 추가-호환(기존 플래그 제거는 major).

| 표면 | 이름 |
|---|---|
| picocli command 이름 | `tia` (현행 유지) |
| installDist 바이너리 | `tia` (현행 `tia-cli`에서 변경) |
| 배포 fat-jar 파일명 | `tia.jar` |
| Gradle 모듈명 | `tia-cli` (유지) |
| Maven 좌표 | `io.tia:tia-cli` (유지) |
| Docker 이미지 | `ghcr.io/<org>/tia:<ver>` |
| Agent Skill 폴더 | `tia` |

- **호환 유지 범위:** 서브커맨드·플래그·출력 포맷은 불변. 변경은 launcher 바이너리/파일명에 한정.

### 5.2 JDK 매트릭스
TIA 단계는 **두 개의 JDK 컨텍스트**를 가진다(데모 `run-petclinic-tia.sh`가 그렇다).

| 단계 | JDK | 근거 |
|---|---|---|
| 엔진·CLI(`tia-core`/`index`/`impact`/`flaky`/`report`) | 17 (현행) | 순수 JVM 로직 |
| `tia convert`(JaCoCo core/report 분석) · SUT 실행 · 에이전트 | **SUT/에이전트와 호환되는 JDK(데모는 21)** | JaCoCo·에이전트가 SUT 바이트코드를 분석하므로 SUT classfile/JDK에 맞춰야 함 |

- `tia convert`의 실제 제약은 두 가지로 나뉜다: (1) **분석 대상 classfile major 버전 지원** = JaCoCo/ASM 버전에 종속(레포는 jacoco `0.8.12` 고정 → 지원 classfile 상한이 결정됨), (2) **SUT 실행 JDK**(SUT를 띄워 커버리지를 수집할 때). D0는 둘 중 무엇을 고정/가변으로 둘지 결정한다(일반적으로 JaCoCo 버전 핀 = classfile 상한, SUT JDK는 사용자 환경 가변).
- Docker 이미지는 SUT 실행 컨텍스트를 만족하는 JDK를 싣고, 플러그인은 사용자 빌드 toolchain으로 분리한다.

### 5.3 커버리지 에이전트 책임 경계 — **두 가지 수집 흐름**
TIA는 에이전트를 **포함하지 않는다**. 지원 에이전트는 산출물 형태가 달라 파이프라인이 분기한다:

| 에이전트 | 산출물 | `tia convert` |
|---|---|---|
| teamscale-jacoco-agent (Phase 0 주력) | per-test **testwise JSON 직접** | **불필요** → 바로 `tia index` |
| parallel-per-test-coverage | per-test **`.exec`** | **필요** (.exec → testwise) |

- `CoverageCollector`는 **선행 설계상의 개념적 seam**이다(Phase 0에 실제 인터페이스로 도입된 상태는 아님; 도입은 D3 후보). D0~D2 범위에서 실재하는 추상화 경계는 **testwise.json 계약**과 **CLI 서브커맨드 경계**다 — 두 에이전트 흐름의 차이는 그 경계(아래 표)에서 흡수된다. D0~D1 베이스라인은 teamscale, parallel은 준비되는 대로 D2+에서 드롭인(O4).
- 번들/다운로드 정책(이분화): **기본 = 번들하지 않음** + 플러그인/Action이 전용 configuration으로 GitHub Releases/Maven Central에서 **SHA-256 핀 검증** 후 다운로드. **데모/샌드박스 전용 태그에서만** 에이전트 jar를 `/opt/tia/agent.jar`로 동봉. 트레이드오프(업데이트 cadence·보안 스캔 범위·이미지 크기)를 함께 기록. 버전 핀 문서화.

### 5.4 라이선스·공급망
- TIA 자체는 **MIT** 유지. 번들 서드파티: Apache-2.0(Jackson·picocli·RoaringBitmap·SQLite JDBC)·BSD-3-Clause(ASM)·**EPL-2.0(JaCoCo core/report)**. 모두 MIT 양립, EPL은 파일 단위 약copyleft(비전염). 혼합 라이선스 명시 + EPL 소스 안내는 `THIRD-PARTY-NOTICES.md`.
- **산출물별 컴플라이언스(구현 완료):**
  | 산출물 | 포함물 | 상태 |
  |---|---|---|
  | fat-jar(`tia.jar`) | `META-INF/THIRD-PARTY-NOTICES.md` + `META-INF/licenses/{Apache-2.0,BSD-3-Clause-ASM,EPL-2.0}.txt` | ✅ shadowJar 동봉 |
  | GitHub Release 자산 | `THIRD-PARTY-NOTICES.md` + `tia-licenses.zip`(전문) | ✅ release.yml |
  | Docker 이미지 | `/licenses`(LICENSE+NOTICE+전문) + `image.licenses` 라벨 | ✅ Dockerfile |
  | Maven 아티팩트 | POM(좌표·MIT) | ☑ 기본 |
- **SBOM·취약점 스캔(구현 완료):** CycloneDX Gradle 플러그인 → `./gradlew cyclonedxBom`(build/reports/bom.json). CI `security` 잡 + release 게이트가 **Trivy로 SBOM 스캔(HIGH/CRITICAL 시 실패→릴리스 차단, ignore-unfixed)**. SBOM은 릴리스 자산으로 첨부.

---

## 6. 웹 서비스에 대한 입장 (명시적 보류 근거)
- TIA의 1차 가치(선별)는 **빌드 타임·레포 로컬**이라 서비스화 시 (1) 소스/커버리지 전송의 보안·규정 부담, (2) 빌드와의 동기화 상태(staleness) 관리, (3) 인증/멀티테넌시 비용이 든다.
- 반면 **QE의 이력·추세** 요구는 서비스에 적합하다. 따라서 "선별 = 로컬, 집계 = (선택적) 서비스"로 **분리**하고, 서비스는 Phase D4 조건부로 미룬다.

---

## 7. E2E / 수용 테스트 사양

> 본 문서의 정의상 "E2E"는 **실행 가능한 최고 수준의 out-of-process 검증**(실제 산출물을 빌드/실행해 사용자 경험을 재현)이다. 각 Phase의 done은 해당 E2E GREEN을 포함한다.

| ID | Phase | 시나리오 | 통과 기준 |
|---|---|---|---|
| **E2E-1** | D0 | 단일 fat-jar로 per-test .exec → testwise → 리포트: `java -jar tia.jar convert …` → `… report …` | (a) **convert 동치:** 고정 픽스처(`.exec` + classes 스냅샷)로 생성한 testwise.json이 골든 파일과 **구조적 JSON 동등**(순서·타임스탬프 무시). (b) **report 동치:** 비교 단위를 HTML DOM이 아니라 **리포트에 임베드된 JSON 모델(`__DATA__` 아일랜드)의 canonical JSON 비교**로 고정한다(HTML은 공백·속성순서·정렬에 민감 → 불안정). **정규화 규칙(규범):** 머신 절대경로·타임스탬프·커밋 해시 길이·머신별 디렉터리 제거/치환; 리스트(테스트·파일·시나리오) 정렬 키 고정; 필수 계약 필드만 비교(비필수 무시); 불일치 시 최소 failing fragment 출력. 픽스처·골든·테스트는 `e2e`(또는 `tia-cli` 통합테스트)에 신규 작성. ※기존 `test_make_html.py` 11종은 **Python**을 검증하므로 그대로 두되, JVM 포트 검증은 위 신규 동치 테스트가 담당. |
| **E2E-1.5** | D1.5 | 동일 스킬 폴더를 **둘 이상의 skills-호환 에이전트**(최소 Claude Code + 1종: Kiro 또는 Gemini CLI)에 설치 → 고정 diff(E2E-1 픽스처 재사용)로 "이 diff에 영향받는 테스트?" 질의 | **골든 대비:** 양쪽 모두 scripts가 `tia impact`를 호출해 **사전 정의된 골든 선별 목록과 일치**(서로 같기만 한 게 아니라 ground truth와 일치). **자동화:** scripts를 직접 호출(에이전트 UI 우회)해 동일 입력→동일 출력을 CI로 검증; 에이전트 UI의 스킬 활성화는 첫 릴리스 수동 검증 후 자동화 추적(O5). JDK/CLI 미충족 시 actionable 에러 + non-zero(빈 결과 아님). |
| **E2E-2** | D1 | 깨끗한 환경에서 게시 좌표로 의존성 해소 + `tia --version` | 빈 `~/.m2`·`~/.gradle` 컨테이너에서 `io.tia:tia-cli:<ver>`(확정된 O1 저장소) 해소·실행 성공, GitHub Release fat-jar 다운로드 후 `java -jar tia.jar --version`이 릴리스 버전과 일치. (O1 선결.) |
| **E2E-3** | D2 | `docker run ghcr.io/<org>/tia impact …` + 전용 샌드박스 레포에 Action 적용 | (a) 무설정 컨테이너에서 선별 출력. (b) 전용 테스트 레포(예: `<org>/tia-action-sandbox`)의 고정 샘플 PR에 Action이 선별 결과를 체크/코멘트로 게시. PR 코멘트 자동검증이 초기 비현실적이면 **첫 릴리스 수동 검증 후 자동화는 D3+로 연기**(O3에 기록). |
| **E2E-4** | D3 | 샘플 Gradle 프로젝트(픽스처)에 플러그인 적용 → `./gradlew tiaImpact` | 에이전트 와이어링(D3.1)으로 per-test 커버리지 수집 → 변경 diff에 대해 선별 테스트 목록 출력, `tiaReport`가 report.html 생성, 스킵 OFF 기본 확인. |
| **E2E-R** | 전 Phase | 회귀: petclinic-demo end-to-end가 새 CLI 경로로도 동작 | `run-petclinic-tia.sh`(CLI 흡수판) GREEN, **리포트 7대 계약 유지**(REPORT-GUIDE §재현/회귀): ①SUT 타이틀 ②전 컬럼 정렬 ③JaCoCo 딥링크 ④테스트 소스 링크(+graceful) ⑤impact 용어/베이스라인 ⑥flaky 퍼센트+분수 ⑦`</script>` 인젝션 방어. |

- **E2E-R 이식성:** 현재 `run-petclinic-tia.sh`는 `PETC`/`AGENT_REPO`에 개발자 머신 절대경로를 하드코딩해 CI·신규 사용자가 그대로는 못 돌린다. E2E-R을 hermetic하게 만들려면 (a) SUT·에이전트를 **고정 가능한 아티팩트/서브모듈/컨테이너 이미지**로 제공(`docker-compose.e2e.yml` 활용), (b) 경로를 문서화된 기본값의 env로 매개변수화. 신규 사용자 경로(§1.3.1)는 사전 클론된 형제 레포를 요구해선 안 된다.
- 각 E2E는 **바깥 루프**로 먼저 작성(실패 상태에서 시작), 내부는 단위 TDD로 구동한다.
- CI: 기존 `report-generator` 잡을 확장해 D0 convert/report 동치 테스트를 포함. D2~D3는 전용 통합 잡 추가.

---

## 8. 리스크 & 오픈 이슈

| # | 리스크 | 완화 |
|---|---|---|
| R1 | 글루 흡수 시 Python↔Java 동작 불일치(리포트 미세 차이) | E2E-1을 **구조 동치**(머신 절대경로·타임스탬프 정규화 후)로 고정. 바이트 동일은 리포트의 머신 경로 때문에 비목표 |
| R2 | 커버리지 에이전트 다양성(teamscale vs parallel) | `CoverageCollector` 어댑터 경계(§5.3) + 두 수집 흐름 분기 명시 + 둘 다 E2E 커버 |
| R3 | 게시 권한/네임스페이스(`io.tia` 좌표·`ghcr` org) | D0와 병행 예약, D1 착수 전 확정 (O1) |
| R4 | JDK 컨텍스트 충돌(엔진 17 vs SUT/수집 21) | §5.2 JDK 매트릭스로 분리, 플러그인 toolchain, 이미지 자체 JDK |
| R5 | 스킵 활성화 신뢰(오선별로 테스트 누락) | 선행 설계의 "기본 OFF + 주기적 전체 실행" 계승. 활성화는 단계적: D0~D1 숨김 OFF → 실측 플레이키/오선별 데이터 수집 → 임계 충족 시 "권고" 노출 → 팀 피드백 후에만 기본 ON 검토 |
| R6 | E2E-R 비이식성(하드코딩 절대경로) | SUT/에이전트를 컨테이너/아티팩트로 hermetic화(§7 E2E-R 노트) |
| R7 | JaCoCo core/report 번들로 fat-jar 비대·라이선스 혼합 | EPL-2.0 NOTICE 동봉(§5.4), 크기 영향 측정 |
| R8 | Agent Skill 표준 버전 변경 → 폴더 구조/frontmatter 비호환 | SKILL.md는 최소 필수 필드만 사용, 도구별 설치 검증 CI(O5), major 변경 시 버전 핀 스킬 폴더 유지 |

**오픈 이슈**
- O1: 배포 네임스페이스/저장소(GitHub Packages vs Maven Central, ghcr org) 확정. **D0 병행 예약.**
- O2: fat-jar(`shadowJar`, D0 채택) vs 네이티브(`jpackage`/GraalVM) — 네이티브는 기동시간 요구 시 별도 Phase.
- O3: Action을 composite vs Docker 기반으로 할지(기동 속도 vs 환경 일관성); PR-코멘트 자동검증 자동화 시점.
- O4: parallel-per-test-coverage 에이전트 준비/호환 검증 시점(테이블 D2 드롭인 전제).
- O5: Agent Skill 스펙 버전(폴더 구조·frontmatter)·도구별 설치 경로(Claude/Kiro/Antigravity/Gemini CLI 등)·권한 모델(스킬 scripts의 로컬 셸 실행 허용)·에이전트 UI 활성화 자동검증은 구현 시점 현행 spec(agentskills.io)·각 도구 문서로 확인.
- O6: (선택) MCP 서버 구현 여부/Phase 귀속 — 구조적 tool-call 통합 수요가 실증될 때 결정. D1.5 범위 밖.
- O7: 인덱스 DB 자동 취득(`tia pull` 류 — 커밋 기준 원격 DB 동기화) 설계 — 로컬/에이전트 오프라인 질의 편의.

---

## 9. 권장 순서 요약

| Phase | 내용 | 규모 |
|---|---|---|
| D0 | 글루 통합(convert/report) + shadowJar + JaCoCo core/report 라이브러리화 | **L** |
| D1 | 라이브러리·CLI 게시 + 버전 일원화 | M |
| D1.5 | 표준 Agent Skill(SKILL.md) — 기존 인덱스 질의 표면 (MCP는 O6 별도) | S~M |
| D2 | Docker 이미지 + GitHub Action | M |
| D3 | Gradle 플러그인(에이전트 와이어링) | **L** |
| D4 | (조건부) 집계 대시보드 | — (분리) |

**D0 → D1 → D1.5 → D2 → D3 → D4** 순(D1.5·D2·D3는 축이 달라 병렬 가능). 가장 빠른 사용자 가치는 **D0+D1**(단일 CLI 배포), 에이전트 도입의 표준 표면은 **D1.5**(한 스킬 폴더로 Claude·Kiro·Antigravity 등 cross-tool), 빌드/CI 도입 결정타는 **D2~D3**다. 웹 서비스는 QE 이력 집계 수요(§D4 트리거)가 실증될 때만.

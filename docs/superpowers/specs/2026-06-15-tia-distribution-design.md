# TIA 배포(Distribution) 설계 문서

- 작성일: 2026-06-15
- 상태: 설계 초안 → 3-모델 리뷰 → 확정 → Phase별 구현
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
4. **버전·SUT 비종속성 미검증.** 리포트 생성기는 최근에야 SUT 이름·옵셔널 입력을 지원. 또한 `make_html.py`에 `PREFIX="org/springframework/samples/petclinic/"`가 하드코딩돼 있고 이 값은 단순 표시가 아니라 **모델 키(파일·blind·역인덱스 경로)에 구조적으로** 쓰인다(`short()`). → 다른 SUT에서는 경로 축약이 깨지므로 일반화가 필요(§4 D0).

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
        ╱           │            ╲
 Docker 이미지   GitHub Action   Gradle/Maven 플러그인
 (무설정 CI)     (CI 한 줄 통합)   (빌드 네이티브 통합)
```

모든 상위 표면은 **동일한 CLI/코어**를 호출한다(중복 로직 금지).

### 3.3 사용자층 ↔ 형태 매핑

| 사용자 | 주 사용 지점 | 1차 형태 | 2차 |
|---|---|---|---|
| 백엔드 개발자 | 로컬 커밋 전 / PR | **Gradle 플러그인** (`./gradlew tiaImpact`) | fat-jar CLI |
| 백엔드 팀 CI | PR 게이트 | **GitHub Action** | Docker 이미지 |
| QE | 리포트·추세·blind-spot | **HTML 리포트**(CLI `report`) + CI 아티팩트 | (Phase D4) 대시보드 |
| 프레임워크/도구 제작 | 임베딩 | **`tia-core` 라이브러리** | — |

> 표는 각 층의 **최고 가치 형태**이고, 실제 이용 가능 시점은 Phase에 따른다: 로컬 CLI는 D0(또는 D1 fat-jar)부터, GitHub Action은 D2부터, Gradle 플러그인은 D3부터. 롤아웃 커뮤니케이션은 이 타이밍을 반영한다.

### 3.4 옵션별 판정 (요청된 4형태 + 추가)

| 형태 | 판정 | 근거 |
|---|---|---|
| 라이브러리(`tia-core`) | **채택(토대)** | 이미 라이브러리. 게시하면 플러그인·타도구의 엔진. 단독 end-user 도구는 아님 |
| Fat-jar CLI | **채택(1차 베이스)** | `shadowJar`로 단일 실행 jar. polyglot·CI 스크립트·상위 표면의 백엔드 |
| Docker 이미지 | **채택(보조)** | JDK+CLI+`jacococli`(+에이전트/Python 제거 후엔 불필요)를 한 이미지에. 무설정 CI/데모 |
| 웹 서비스 | **보류(조건부)** | 선별은 빌드 타임·로컬이라 SaaS는 마찰만 큼. **단** 다중 레포·팀의 선별/플레이키 이력 집계 대시보드라면 Phase D4 제품 후보 |
| (+) Gradle/Maven 플러그인 | **채택(도입 1순위)** | TIA는 빌드 타임 결정 → JVM 팀 도입 마찰 최소. 요청 목록 외지만 최고 레버리지 |
| (+) GitHub Action | **채택(CI 1순위)** | `uses: org/tia-action@v1` 한 줄로 PR 통합 |

---

## 4. 단계별 롤아웃

각 Phase는 독립 릴리스 가능하며 이전 Phase에만 의존한다.

### Phase D0 — 통합 선행작업 (격차 #1·#2 해소) · **필수**
- **CLI 개명:** 현행 산출물은 `tia-cli` installDist launcher이고, 본 문서의 목표 엔트리포인트/아티팩트는 `tia` / `tia.jar`다(application name·아티팩트명 변경). §2.1(현행)과 §3.2·§7(목표)의 이름 차이는 이 의도적 개명 때문이며 모순이 아니다.
- Python 글루를 JVM CLI 서브커맨드로 흡수:
  - `tia convert --exec-dir <dir> --classes <dir> --out <testwise.json>` ← `exec_to_testwise.py` (per-test .exec → testwise.json). **`jacococli` 내부화 = `org.jacoco:org.jacoco.cli` 의존성 추가 + in-process API 호출**(외부 `~/.m2` 탐색 제거). 라이선스 영향은 §5.4.
  - `tia report --testwise <json> --scenarios <json|-> --flaky <json|-> --prod-files <txt|-> --commit <sha> --out <html> [--sut-name <s>] [--jacoco-dir <d>] [--test-src-root <p>]` ← `make_html.py` (옵셔널 입력·SUT명·딥링크 인자 그대로 보존). **경로 축약 `PREFIX`는 하드코딩 대신 `--prefix-strip` 인자 또는 커버 경로의 최장공통접두(LCP) 자동 감지로 일반화.**
- **단일 실행 jar:** D0는 **`shadowJar`(fat-jar)를 채택**한다 — E2E-1의 `java -jar tia.jar …`가 이를 전제. 네이티브(`jpackage`/GraalVM)는 기동시간 요구가 실증될 때 별도 Phase(O2)에서 추가.
- **호환:** 기존 `index`/`impact`/`flaky` 인터페이스·플래그 불변. Python 스크립트는 데모 보존용으로 남기되 "deprecated, CLI로 대체" 명시.
- **수용:** §7 E2E-1.
- **규모:** L (Python→Java 포트 2건 + jacoco.cli 내부화 + shadowJar).

### Phase D1 — 라이브러리 + CLI 게시 · **1차 배포**
- 선행: 네임스페이스/저장소(O1) 확정 — D0와 병행 예약.
- `tia-core`·`tia-cli`를 GitHub Packages(우선) 또는 Maven Central에 게시. SemVer 도입.
- **버전 단일 소스:** 현재 `build.gradle`(`version='0.1.0'`)과 `TiaCommand.java`(`version="tia 0.1.0"`)가 이중 선언이다. 빌드 시 생성 리소스(`version.properties`) 또는 picocli `versionProvider`로 일원화해 `tia --version`이 릴리스 버전과 항상 일치하게 한다.
- 릴리스 워크플로(CI 태그 → 빌드 → 게시 → fat-jar를 GitHub Release 자산으로 첨부).
- README에 "설치: jar 다운로드 또는 의존성 좌표" 섹션.
- **수용:** §7 E2E-2.
- **규모:** M.

### Phase D2 — Docker 이미지 + GitHub Action · **CI 도입**
- 이미지: `ghcr.io/<org>/tia:<ver>` — JDK + fat-jar CLI. (jacococli는 D0에서 의존성으로 흡수되므로 별도 번들 불필요 — §5.4.) 엔트리포인트 = `tia`.
- GitHub Action(composite 또는 Docker; O3): 입력 `db`/`commit`/`diff`/`base-ref` → `tia impact` 실행, PR 코멘트/체크로 선별 결과 출력.
- **수용:** §7 E2E-3.
- **규모:** M.

### Phase D3 — Gradle 플러그인 (이후 Maven) · **도입률 극대화**
- `io.tia` Gradle 플러그인. 태스크: `tiaIndex`·`tiaImpact`·`tiaReport`. 커버리지 수집(에이전트) 연결 + diff 산출 + 선별을 빌드 그래프에 통합.
- **D3.1 에이전트 와이어링(최대 리스크):** 플러그인은 (a) 커버리지 에이전트 jar를 전용 Gradle configuration으로 해소(자동 다운로드는 §5.3 정책), (b) `Test` 태스크의 JVM fork에 `-javaagent:<agentJar>=…`(parallel은 control-port, 데모 기준 6310)를 주입, (c) per-test dump 산출물(`.exec` 또는 testwise JSON)을 수확. teamscale/parallel 차이는 §5.3의 어댑터 경계로 흡수한다.
- **D3.2 diff 출처:** `tiaImpact`는 기본적으로 `git diff <base-ref>...HEAD`(base-ref = 인덱싱 커밋, 선행 설계 §6.2 라인공간 정렬)에서 diff를 얻고, CI용으로 `--diff-file` 프로퍼티 오버라이드를 허용한다.
- 설정: 베이스라인 ref, 스킵 ON/OFF(기본 OFF — 선행 설계의 안전망 정책 계승), 리포트 출력 경로.
- **수용:** §7 E2E-4.
- **규모:** L (에이전트 와이어링이 핵심 난이도).

### Phase D4 — (조건부) 집계 대시보드 / 웹 서비스
- 트리거 조건(측정 가능): D0~D3 출시·활성 사용 후 **2개 이상 팀**이 교차 레포 이력/추세 질의를 명시 요청(이슈/디스커션 투표 또는 명시 RFP)할 때. 착수 전 게이트 리뷰로 별도 제품 설계로 분기.
- 형태 후보: 선별/플레이키/blind-spot 결과(JSON)를 수집·시각화하는 read-only 대시보드. 엔진은 여전히 CI에서 로컬 실행, 서비스는 **결과만** 받는다(소스/커버리지 원본 비전송).
- 본 문서에서는 **착수하지 않음** — 별도 제품 설계로 분리.

---

## 5. 산출물 사양

### 5.1 버전·호환
- 전 산출물 **SemVer**. `tia-core`의 testwise.json/DB 스키마는 호환성 계약(파서: `TestwiseReportParser`). 스키마 변경 시 major.
- CLI 플래그는 추가-호환(기존 플래그 제거는 major).

### 5.2 JDK 매트릭스
TIA 단계는 **두 개의 JDK 컨텍스트**를 가진다(데모 `run-petclinic-tia.sh`가 그렇다).

| 단계 | JDK | 근거 |
|---|---|---|
| 엔진·CLI(`tia-core`/`index`/`impact`/`flaky`/`report`) | 17 (현행) | 순수 JVM 로직 |
| `tia convert`(커버리지 수집/jacococli) · SUT 실행 · 에이전트 | **SUT/에이전트와 호환되는 JDK(데모는 21)** | jacococli·에이전트가 SUT 바이트코드를 분석하므로 SUT JDK에 맞춰야 함 |

- `tia convert`의 jacoco 분석은 **SUT를 빌드한 JDK**를 따른다(데모 21). Docker 이미지는 이 컨텍스트를 만족하는 JDK를 싣는다. 플러그인은 사용자 빌드의 toolchain을 사용해 분리한다.

### 5.3 커버리지 에이전트 책임 경계 — **두 가지 수집 흐름**
TIA는 에이전트를 **포함하지 않는다**. 지원 에이전트는 산출물 형태가 달라 파이프라인이 분기한다:

| 에이전트 | 산출물 | `tia convert` |
|---|---|---|
| teamscale-jacoco-agent (Phase 0 주력) | per-test **testwise JSON 직접** | **불필요** → 바로 `tia index` |
| parallel-per-test-coverage | per-test **`.exec`** | **필요** (.exec → testwise) |

- 둘 다 `CoverageCollector` 인터페이스(선행 설계 §3.0 D8) 뒤에 있어 다운스트림은 무변경. D0~D1 베이스라인은 teamscale, parallel은 준비되는 대로 D2+에서 드롭인(준비 시점은 O4).
- 번들/다운로드 의미: Docker 이미지는 에이전트 jar를 `/opt/tia/agent.jar`로 동봉(또는 미동봉), 플러그인은 전용 configuration으로 GitHub Releases/Maven Central에서 **SHA-256 검증** 후 받는다. 버전 핀 문서화.

### 5.4 라이선스·공급망
- TIA 자체는 **MIT** 유지. 단 D0에서 흡수하는 **`org.jacoco.cli`는 EPL-2.0**이며 fat-jar/이미지에 번들된다 → 배포물에 혼합 라이선스(MIT + EPL-2.0)를 명시하고 NOTICE 동봉. (D2 이미지의 별도 jacococli 번들은 이로써 불필요.)
- 릴리스에 SBOM 첨부, 의존성 취약점 스캔을 릴리스 게이트에 포함.

---

## 6. 웹 서비스에 대한 입장 (명시적 보류 근거)
- TIA의 1차 가치(선별)는 **빌드 타임·레포 로컬**이라 서비스화 시 (1) 소스/커버리지 전송의 보안·규정 부담, (2) 빌드와의 동기화 상태(staleness) 관리, (3) 인증/멀티테넌시 비용이 든다.
- 반면 **QE의 이력·추세** 요구는 서비스에 적합하다. 따라서 "선별 = 로컬, 집계 = (선택적) 서비스"로 **분리**하고, 서비스는 Phase D4 조건부로 미룬다.

---

## 7. E2E / 수용 테스트 사양

> 본 문서의 정의상 "E2E"는 **실행 가능한 최고 수준의 out-of-process 검증**(실제 산출물을 빌드/실행해 사용자 경험을 재현)이다. 각 Phase의 done은 해당 E2E GREEN을 포함한다.

| ID | Phase | 시나리오 | 통과 기준 |
|---|---|---|---|
| **E2E-1** | D0 | 단일 fat-jar로 per-test .exec → testwise → 리포트: `java -jar tia.jar convert …` → `… report …` | (a) **convert 동치:** 고정 픽스처(`.exec` + classes 스냅샷)로 생성한 testwise.json이 골든 파일과 **구조적 JSON 동등**(순서·타임스탬프 무시). (b) **report 동치:** 같은 입력으로 생성한 `report.html`이 Python 산출물과 **DOM/모델 구조 동등**(머신 절대경로·타임스탬프 정규화 후) — 바이트 동일이 아님(리포트는 머신 절대경로를 담음, REPORT-GUIDE §로컬링크). 픽스처·골든·테스트는 `e2e`(또는 `tia-cli` 통합테스트)에 신규 작성. ※기존 `test_make_html.py` 11종은 **Python**을 검증하므로 그대로 두되, JVM 포트 검증은 위 신규 동치 테스트가 담당. |
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
| R7 | jacoco.cli 내부화로 fat-jar 비대·라이선스 혼합 | EPL-2.0 NOTICE 동봉(§5.4), 크기 영향 측정 |

**오픈 이슈**
- O1: 배포 네임스페이스/저장소(GitHub Packages vs Maven Central, ghcr org) 확정. **D0 병행 예약.**
- O2: fat-jar(`shadowJar`, D0 채택) vs 네이티브(`jpackage`/GraalVM) — 네이티브는 기동시간 요구 시 별도 Phase.
- O3: Action을 composite vs Docker 기반으로 할지(기동 속도 vs 환경 일관성); PR-코멘트 자동검증 자동화 시점.
- O4: parallel-per-test-coverage 에이전트 준비/호환 검증 시점(테이블 D2 드롭인 전제).

---

## 9. 권장 순서 요약

| Phase | 내용 | 규모 |
|---|---|---|
| D0 | 글루 통합(convert/report) + shadowJar + jacoco.cli 내부화 | **L** |
| D1 | 라이브러리·CLI 게시 + 버전 일원화 | M |
| D2 | Docker 이미지 + GitHub Action | M |
| D3 | Gradle 플러그인(에이전트 와이어링) | **L** |
| D4 | (조건부) 집계 대시보드 | — (분리) |

**D0 → D1 → D2 → D3 → D4** 순. 가장 빠른 사용자 가치는 **D0+D1**(단일 CLI 배포)이고, 도입률을 끌어올리는 결정타는 **D2~D3**다. 웹 서비스는 QE 이력 집계 수요(§D4 트리거)가 실증될 때만.

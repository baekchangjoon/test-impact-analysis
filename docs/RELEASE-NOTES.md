# TIA 릴리스 노트

이 문서는 사용자 대면 변경(behavior 변경, CLI/API 표면, 설정 스키마)을 요약합니다. 구현 세부는
각 커밋 메시지와 `docs/superpowers/specs/`의 설계 문서를 참조하세요.

## Unreleased (0.2.0 이후 main)

버전은 아직 `0.2.0`(변경 없음)이며, 아래는 0.2.0 릴리스 이후 `main`에 누적된 변경입니다.

### 사용성 개선 (SP1~SP5)

- **SP1 — `tia.yml` 설정 + 소비 필터 + 출력 포맷 4종.** 레포 루트 `tia.yml`(또는 `--config`)로
  include/exclude 필터·`db`·`sut-name` 기본값을 선언할 수 있고, `impact`/`flaky`는
  `--format text|summary|json|markdown`을 지원합니다(기본 `text`는 기존 출력과 동일).
- **SP2 — Gradle 플러그인의 `tia.yml` 소비.** `io.tia` 플러그인이 `tia.yml`의 필터를 읽어 수집
  범위에 반영하고, configuration cache(CC)와 함께 동작합니다.
- **SP3 — 온보딩 명령 3종.** `tia init`(`tia.yml` 생성 마법사) / `tia doctor`(환경·설정·인덱스
  6항목 진단, `--format json` 지원) / `tia demo`(레포 안에서 실수집→인덱싱→impact→리포트 전 과정
  체험)가 추가되고 GETTING-STARTED가 이 흐름 중심으로 재구성되었습니다.
- **SP4 — `tia mcp`.** 최소 stdio MCP 서버(도구 `tia_impact`/`tia_doctor`)와, CLI가 저장소 루트
  밖 working directory에서도 동작하도록 하는 시임이 추가되었습니다.
- **SP5 — Action PR 코멘트 + 리포트 가이드 내장.** GitHub Action에 opt-in PR 코멘트 게시 스텝이
  추가되고, HTML 리포트의 각 탭에 "이 탭 읽는 법" 해설이 내장되었습니다.

### 부수 개선

- `scripts/setup-pjacoco.sh`가 pjacoco 소스 빌드 대신 릴리스 에셋 다운로드를 우선 사용(sha256
  검증 포함)하도록 전환되어 CI 실행 시간이 줄었습니다.
- 의존성 `jackson` 2.17.2 → 2.18.8 (Trivy HIGH 취약점 해소, GHSA-r7wm-3cxj-wff9).
- 같은 commit에 여러 모듈을 각각 인덱싱해도 `impact`가 모든 build를 test_id별 최신-build-wins로
  병합해 선별하도록 개선(과거엔 마지막 build만 반영되어 다른 모듈 테스트가 누락될 수 있었음).

### 이번 하드닝 배치 (FU-REQ-001..008)

- **FU-REQ-001.** JDK 22+에서 `System.console() != null`이 리다이렉트 시에도 non-null이 되는
  문제에 대비해 `Tty.interactive()` 헬퍼를 신설하고 `impact`/`flaky`/`init`의 TTY 판별을 여기로
  교체(파이프에 ANSI가 새지 않도록).
- **FU-REQ-002.** `tia.yml` 상향 탐색이 `.git` 루트가 없는 경우 파일시스템 루트까지 올라가던 것을
  `user.home` 도달 시(홈 포함, 홈 상위 배제) 중단하도록 경계를 둠.
- **FU-REQ-003.** `CoverageStore`의 SQLite 커넥션을 읽기/쓰기 오픈으로 분리 — `busy_timeout`은
  모든 오픈에, `journal_mode=WAL`은 쓰기 오픈에만 적용(읽기 전용 도구가 DB 파일을 변형하지 않는
  불변식 보존)하고, `save()`의 builds/coverage INSERT를 단일 트랜잭션으로 묶어 동시 접근 시
  `SQLITE_BUSY`와 반쪽짜리 커밋을 줄임.
- **FU-REQ-004.** git worktree에서 실행할 때 `DbPaths`가 공용 git 디렉터리를 기준으로 DB 경로를
  해석하도록 정정.
- **FU-REQ-005/006.** `doctor`/`impact summary` E2E를 더 허메틱하게 만들고 단언을 강화.
- **FU-REQ-007/008.** CI에 `action-pr-comment-smoke` 잡과 `demo-weekly` 잡을 추가(전자는 실 PR에서
  라벨 게이트로, 후자는 매주 회귀 확인).

### 호환성 (Breaking / 호환성 주의)

**`tia-core`의 `ReportBuilder.Inputs` 레코드 시그니처가 SP1에서 변경되었습니다.**

```java
// 변경 전 (0.2.0 및 그 이전)
public record Inputs(Path testwise, Path scenarios, Path flaky, Path prodFiles,
                     String commit, String sut, String jacoco, Path testSrcRoot,
                     String prefixStrip) {}

// 변경 후 (현재 main) — 마지막에 FilterSet 파라미터 추가
public record Inputs(Path testwise, Path scenarios, Path flaky, Path prodFiles,
                     String commit, String sut, String jacoco, Path testSrcRoot,
                     String prefixStrip, FilterSet filters) {}
```

- `filters`는 `null`이면 `FilterSet.none()`(필터 없음)으로 취급됩니다.
- **영향 범위: `io.tia:tia-core`를 라이브러리로 직접 의존해 `ReportBuilder.Inputs`를 생성하는
  외부 코드만 해당합니다.** 이 레코드를 생성하는 위치는 저장소 안에 `tia-cli`의
  `ReportCommand`뿐이며 이번 변경과 함께 갱신되었으므로, **`tia` CLI(fat-jar/installDist)만
  사용하는 사용자는 영향이 없습니다.**
- 마이그레이션: 기존 9-인자 호출부에 `FilterSet` 인자(필터가 없으면 `null` 또는
  `FilterSet.none()`)를 추가하세요.

## 0.2.0 (2026-06-20)

`tia convert`에 in-process 손실 신호(sidecar `incompleteAttribution`/`droppedProbes`) 감지 시
기본 `exit 1`로 막는 게이트가 추가된 것을 기점으로 0.1.1 → 0.2.0으로 버전을 올렸습니다
(`--allow-incomplete`로 경고만 남기고 통과 가능). 함께 pjacoco v1.2.0(손실 신호 제공)을
소비하도록 CI/e2e 의존성을 갱신했습니다.

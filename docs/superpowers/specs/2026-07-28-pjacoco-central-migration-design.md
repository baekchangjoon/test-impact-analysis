# pjacoco Maven Central 이전 반영 — 설계 + 최소 요구명세 (통합)

> pjacoco가 `io.github.beltian.pjacoco:*` **2.0.0**으로 Maven Central에 게시되었다(2026-07-28 확인:
> agent·testkit·testkit-junit4/5·testkit-restassured·maven-plugin 6종, repo1 존재·`.sha256` 제공,
> testkit POM에 실의존 선언). 저장소도 `beltian/parallel-per-test-coverage`로 이관. v2.0.0은 v1.4.1과
> 기능 동일(좌표 이전 + Central 배포 배선 — 패키지명·에이전트 옵션 불변, 에이전트 jar는
> `pjacoco-agent-<ver>.jar`로 v1.x `jacocoagent-parallel-*.jar`에서 개명, shadowJar 산출물명도
> `pjacoco-agent.jar`). **Gradle 플러그인은 Central/Portal 미게시**(로컬 게시 경로 유지). TIA의 소비
> 지점을 Central 기준으로 이전하되, **오프라인 폴백 능력은 보존**한다(mavenLocal 경로 유지 — 아래).
> 완료 정의(DoD): 매트릭스 전부 green — 수용 레벨은 **PR CI의 실 E2E 잡 3종 + 전체 스위트**.

## 0. 소비 지점 전수 (조사 결과, 3-슬롯 리뷰 반영)

| 지점 | 현재 | 이전 후 |
|---|---|---|
| `e2e/build.gradle` 의존 | `io.pjacoco:pjacoco-testkit-{junit5,restassured}:1.4.0` | `io.github.beltian.pjacoco:*:2.0.0` |
| `e2e/build.gradle` repositories | `mavenLocal(); mavenCentral()` | **`mavenCentral(); mavenLocal()`** — Central 우선, mavenLocal은 오프라인 폴백용으로 **유지**(제거 시 소스 빌드 폴백 경로가 죽음) |
| `e2e/build.gradle:52` | inProcessTesterTest 기본 agentJar `tools/pjacoco/jacocoagent-parallel.jar` | `tools/pjacoco/pjacoco-agent.jar` |
| `scripts/setup-pjacoco.sh` | GitHub 릴리스 에셋 다운로드(agent+testkit 3종)+합성 POM m2 설치+클론 폴백 | 에이전트 jar를 **repo1에서** 다운로드(`.sha256` 검증). testkit 합성-POM 설치 삭제(Central 해소). 오프라인 폴백=클론(`beltian/…`, v2.0.0)+`-PreleaseVersion=2.0.0` 빌드+**publishToMavenLocal 유지**(오프라인 testkit 해소의 유일 경로 — agent-only 축소 소견은 이 근거로 기각) |
| 에이전트 파일명 | `tools/pjacoco/jacocoagent-parallel.jar` | `tools/pjacoco/pjacoco-agent.jar` |
| 파일명 참조(코드·스크립트) | DoctorCommand:146(안내 문구 포함), docker-compose.e2e.yml:14, demo-collect.sh:44, e2e/build.gradle:52, petclinic-demo/run-petclinic-tia.sh:18(외부 클론 build/libs 산출물명) | 전부 `pjacoco-agent.jar`. ※ DoctorCommandE2ETest는 파일명 참조 없음(체크6 항상 SKIP — 픽스처 수정 대상 아님, 초안 오류 정정) |
| docker-compose `~/.m2` 마운트 | testkit mavenLocal 해소용 | **유지**(오프라인 폴백 시 tester의 유일한 해소 경로). 인라인 코멘트만 "Central 우선, mavenLocal 폴백"으로 갱신. 결정 변수는 docker-e2e-tester.sh가 아니라 e2e/build.gradle의 repositories다 |
| CI `test` 잡의 setup 스텝 | testkit m2 설치 부수효과 목적(에이전트 jar는 그 잡에서 미사용 — 태그 제외로 실행 안 됨) | **스텝 제거**(testkit은 컴파일 시 Central 자동 해소; 죽은 다운로드 제거). E2E 3잡·demo-weekly에는 유지 |
| CI env | `PJACOCO_REF: v1.4.0` ×4 + "미게시" 코멘트 | env 제거(스크립트 기본 2.0.0), 코멘트 갱신 |
| 문서·안내 문구 | README:102(소스 빌드 문구)·:308(`jacocoagent-parallel.jar` 드롭인), GETTING-STARTED:140(doctor 출력 예)·:184("공개 배포 후"), tia-gradle-plugin/README:66·72(플러그인 id/버전)·79-80(구 GAV 예시)·85(캐비앗)·100·112(`libs/jacocoagent-parallel.jar` 예시)·135(구 GAV), petclinic-demo/README:23·00-SUMMARY:11(다이어그램 jar명), InitCommand:172-173(maven-plugin "미배포 시" 헤지), THIRD-PARTY-NOTICES:33(출처), RELEASE-NOTES | 실좌표(`io.github.beltian.pjacoco:*:2.0.0`)·새 jar명·Central 안내로 갱신. GETTING-STARTED:239는 **클래스명이라 불변**(초안 인용 오류 정정). SKILL.md는 pjacoco 언급 없음 — 범위 제외 |
| `TiaPlugin`/`TiaArgs`/`TestwiseConverter` | 패키지 `io.pjacoco.*` javadoc·에이전트는 caller-provided File | **불변**(패키지명 유지) |

## 1. 요구사항

### MIG-REQ-001 — e2e testkit을 Central 좌표로 (Must)
- `e2e/build.gradle`: `io.github.beltian.pjacoco:pjacoco-testkit-junit5:2.0.0`·`pjacoco-testkit-restassured:2.0.0`.
  repositories는 `mavenCentral(); mavenLocal()` **순서로 유지**(mavenLocal은 오프라인 폴백 해소용 — 코멘트로
  사유 명시). 테스트 import(`io.pjacoco.testkit.*`)는 패키지 불변으로 무수정.
- 수용기준: Given 깨끗한 로컬 m2(**`io/pjacoco`·`io/github/beltian` 둘 다** 임시 대피), When `./gradlew
  :e2e:test`, Then Central 해소로 green(대피분 복원). CI 전체 잡 green.
- 참고: 스크립트 기본 버전과 build.gradle 핀의 이중 관리는 기존과 동일하게 코멘트 동기화로 유지 — 빌드
  타임 가드는 **명시적 범위 외**(최소 마이그레이션).

### MIG-REQ-002 — setup-pjacoco.sh Central 전환 (Must)
- 에이전트 jar를 `https://repo1.maven.org/maven2/io/github/beltian/pjacoco/pjacoco-agent/<V>/pjacoco-agent-<V>.jar`
  에서 다운로드하고 `.sha256` 검증(기존 fetch_verified 관용). 대상: `tools/pjacoco/pjacoco-agent.jar`.
  `PJACOCO_VERSION` 기본 2.0.0. testkit 합성-POM m2 설치 로직 삭제. 오프라인 폴백은 클론
  `beltian/parallel-per-test-coverage`@`v2.0.0` + `-PreleaseVersion=2.0.0` 빌드 + `publishToMavenLocal`
  **유지**(오프라인 시 e2e testkit 해소의 유일 경로) + shadowJar 산출물(`pjacoco-agent.jar`) 복사.
  출력 계약 `PJACOCO_AGENT_JAR=<path>` 불변.
- 수용기준: ① Given 에이전트 부재, When 실행, Then repo1 다운로드+sha256 검증 후 새 경로 존재·출력 계약
  유지(로컬 실증). ② **폴백 경로 1회 실증**: `RELEASE_BASE`를 무효 URL로 강제한 1회 실행에서 클론 빌드가
  새 jar명으로 성공(Task 1 중 수동 확인·리포트 기록). ③ CI(In-process/Parallel E2E) green.

### MIG-REQ-003 — 에이전트 파일명 전파 (Must)
- §0 "파일명 참조" 행의 5개 지점(DoctorCommand 경로·안내 문구, compose -javaagent, demo-collect.sh,
  e2e/build.gradle:52 기본값, petclinic-demo/run-petclinic-tia.sh) 전부 `pjacoco-agent.jar`로. `~/.m2`
  마운트는 유지(§0 결정).
- 수용기준: 전체 스위트 green + Container E2E·In-process E2E CI 잡 green(파일명 체인이 실제로 동작하는
  유일한 검증 지점) + DemoCommandE2ETest green.

### MIG-REQ-004 — CI·문서 정리 (Must)
- ci.yml: `test` 잡의 "pjacoco 해소" 스텝 **제거**(사유: §0 — testkit은 Central 자동 해소, 에이전트는 그
  잡에서 미사용), E2E 3잡·demo-weekly는 스텝 유지 + `PJACOCO_REF` env 제거 + "미게시" 코멘트 갱신.
- 문서: §0 "문서·안내 문구" 행의 전 지점 갱신 — tia-gradle-plugin/README는 예시 GAV·플러그인 id를 v2
  기준으로(**플러그인 id는 업스트림 v2 소스에서 실확인** — Central/Portal 미게시이므로 "로컬 게시 필요"
  캐비앗은 플러그인에 한정해 유지), InitCommand Maven 힌트에서 "미배포 시" 헤지 제거(maven-plugin 실좌표
  안내), RELEASE-NOTES 미릴리스 절에 항목 추가.
- 수용기준(기계적): `git grep -nE "io\.pjacoco:|id ['\"]io\.pjacoco|jacocoagent-parallel" -- ':!docs/superpowers'`
  잔존 0(좌표형·jar명만 — 패키지형 `io.pjacoco.`는 불변이라 패턴에서 제외됨) + "미게시(준비 중)"류 캐비앗
  잔존 0(대상 파일 수동 확인) + docs 게이트.

## 2. 추적 매트릭스

| REQ-ID | 요구사항 | 수용 검증 | Level | Status |
|--------|----------|-----------|-------|--------|
| MIG-REQ-001 | testkit Central 소비 | 깨끗한 m2(양 그룹 대피)에서 :e2e:test green + CI 전체 | suite+CI | 🔴 planned |
| MIG-REQ-002 | setup 스크립트 Central 전환 | 로컬 실증(정상+폴백 강제 각 1회) + CI E2E green | script+CI | 🔴 planned |
| MIG-REQ-003 | 파일명 전파(5지점) | 전체 스위트 + Container/In-process E2E CI green | E2E+CI | 🔴 planned |
| MIG-REQ-004 | CI·문서 정리 | 기계적 git grep 잔존 0 + docs 게이트 | docs | 🔴 planned |

Coverage: 0/4 green — target 100% (대상: Must 4)

## 3. 태스크 플랜

- **Task 1 — 빌드·스크립트·코드** [MIG-REQ-001..003]: e2e 좌표·repositories 순서, setup-pjacoco.sh 재작성,
  파일명 5지점 전파, 깨끗한 m2 검증(양 그룹 대피→복원), 폴백 강제 1회 실증, 로컬 스위트 green. 커밋 1개.
- **Task 2 — CI·문서** [MIG-REQ-004]: test 잡 스텝 제거·env 정리·코멘트, 문서 전 지점 갱신(플러그인 id
  업스트림 실확인 포함), RELEASE-NOTES, 기계적 grep 0 확인. 커밋 1개.
- 이후: whole-branch 리뷰 → PR → CI green(실 E2E 3종 = 수용 게이트) → 리베이스 머지 → demo-weekly
  dispatch 1회(새 경로 자연 검증).

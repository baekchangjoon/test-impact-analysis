# Task 1 — 빌드·스크립트·코드 [MIG-REQ-001..003] 완료 보고

Plan: `docs/superpowers/specs/2026-07-28-pjacoco-central-migration-design.md` §3 Task 1
Worktree: `/Users/changjoonbaek/github_test-impact-analysis/test-impact-analysis/.claude/worktrees/pjacoco-central`
Branch: `chore/pjacoco-central-2.0.0`

## 변경 파일

- `e2e/build.gradle` — testkit 좌표 `io.github.beltian.pjacoco:pjacoco-testkit-{junit5,restassured}:2.0.0`,
  repositories `mavenCentral(); mavenLocal()` 순서 + 사유 코멘트, `inProcessTesterTest` 기본
  agentJar `tools/pjacoco/pjacoco-agent.jar`.
- `scripts/setup-pjacoco.sh` — 전면 재작성(v4 전략). 에이전트 jar만 repo1에서 다운로드+sha256 검증,
  testkit 합성-POM m2 설치 로직 삭제(Central 자동 해소로 불필요), 오프라인 폴백은 `beltian/parallel-per-test-coverage`
  클론(`v2.0.0`)+`-PreleaseVersion=2.0.0` 빌드+`publishToMavenLocal`(유지) + shadowJar 산출물 복사.
  `PJACOCO_VERSION` 기본 2.0.0. `PJACOCO_RELEASE_BASE` 환경변수 신설(정상 경로 미사용, 폴백 강제
  검증용). 출력 계약 `PJACOCO_AGENT_JAR=<path>` 불변.
- `tia-cli/src/main/java/io/tia/cli/DoctorCommand.java:146` — 체크 경로 `tools/pjacoco/pjacoco-agent.jar`.
- `docker-compose.e2e.yml:14` — `-javaagent` 경로 `pjacoco-agent.jar`. `~/.m2` 마운트(29행) 유지, 인라인
  코멘트를 "Central 우선, mavenLocal 폴백"으로 갱신.
- `scripts/demo-collect.sh:44` — `AGENT_JAR` 파일명 `pjacoco-agent.jar`.
- `petclinic-demo/run-petclinic-tia.sh:18` — `AGENT_JAR` 파일명 `pjacoco-agent.jar`(외부 클론 산출물명).
- `TiaPlugin`/`TiaArgs`/`TestwiseConverter`: 무수정(패키지 `io.pjacoco.*` 그대로 — 스펙 §0 결정).

`git grep -nE "io\.pjacoco:|jacocoagent-parallel" -- ':!docs' ':!*.md'` — 잔존 0건 확인(문서·Task 2 대상 제외).

## 검증 증거

### a) 정상 경로(로컬 실증)
```
$ rm -rf tools/pjacoco && bash scripts/setup-pjacoco.sh
에이전트 jar 다운로드: https://repo1.maven.org/maven2/io/github/beltian/pjacoco/pjacoco-agent/2.0.0/pjacoco-agent-2.0.0.jar
에이전트 jar 다운로드·검증 완료 (v2.0.0)
PJACOCO_AGENT_JAR=.../tools/pjacoco/pjacoco-agent.jar
```
다운로드+sha256 검증+`PJACOCO_AGENT_JAR=` 출력 계약 확인. `run-inprocess-e2e.sh`/`run-parallel-e2e.sh`가
`sed -n 's/^PJACOCO_AGENT_JAR=//p'`로 stdout을 파싱하는 소비 지점도 무수정 확인(grep으로 확인, 계약 불변).

### b) 폴백 경로(1회 실증, "진짜" 클론까지 확인)
사전에 `$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage`가 이전 세션에서
`main`(v1.4.1+4커밋, v2.0.0 태그 아님)으로 이미 존재해 클론 스킵 분기가 재사용되는 것을 발견 →
그 디렉터리를 삭제하고 재실행해 실제 clone 코드 경로를 검증:
```
$ rm -rf "$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage"
$ rm -rf tools/pjacoco
$ PJACOCO_RELEASE_BASE="https://.../pjacoco-agent/NONEXISTENT-VERSION" bash scripts/setup-pjacoco.sh
다운로드 실패 → 소스 빌드로 폴백
pjacoco 소스 없음 → clone: https://github.com/beltian/parallel-per-test-coverage.git@v2.0.0
Cloning into '.../parallel-per-test-coverage'...
Note: switching to '0e968b64d8664a4c7ba8d5aee1cc21d4cadc94ae'.   # = tag v2.0.0 (확인됨)
...
BUILD SUCCESSFUL in 11s
57 actionable tasks: 57 executed
PJACOCO_AGENT_JAR=.../tools/pjacoco/pjacoco-agent.jar
```
- 클론 커밋이 `git ls-remote --tags origin`으로 확인한 `v2.0.0` 태그 커밋(`0e968b6...`)과 일치.
- `tools/pjacoco/pjacoco-agent.jar` 생성 확인(4,871,202 bytes).
- `~/.m2/repository/io/github/beltian/pjacoco/pjacoco-testkit-{junit5,restassured}/2.0.0/*.jar` 신규
  타임스탬프(실행 시각)로 갱신 확인 — `publishToMavenLocal`이 실제 io/github/beltian 그룹으로 게시함.
  (~/.m2 위생: 실제 `~/.m2`에 게시됐으나 Central과 동일 좌표·버전이라 허용 범위 — 기록만 함.)

### c) 클린 m2 증명
```
$ mv ~/.m2/repository/io/pjacoco <scratch>/pjacoco
$ mv ~/.m2/repository/io/github/beltian <scratch>/beltian
$ rm -rf tools/pjacoco && bash scripts/setup-pjacoco.sh   # 에이전트만 재확보(정상 경로)
$ ./gradlew --no-daemon :e2e:test
BUILD SUCCESSFUL in 19s
12 actionable tasks: 12 executed
$ mv <scratch>/pjacoco ~/.m2/repository/io/pjacoco
$ mv <scratch>/beltian ~/.m2/repository/io/github/beltian
# 복원 확인: ls 양쪽 모두 OK
$ ./gradlew --no-daemon build
BUILD SUCCESSFUL in 30s
37 actionable tasks: 25 executed, 12 up-to-date
```
`io/pjacoco`·`io/github/beltian` 둘 다 대피한 상태에서 `:e2e:test` green → Central 해소 확인. 복원 후
전체 `build` green. 이 검증은 프로세스/컨테이너를 띄우지 않으므로 teardown/누수 게이트 해당 없음
(로컬 `~/.m2` 이동만 수행, 대피분은 즉시 원위치로 복원 완료).

## 상태

- MIG-REQ-001: 🟡 로컬 실증 완료·CI 대기 (§2 매트릭스 업데이트는 이 커밋에 포함하지 않음 — Task 2 범위
  아니라면 문서 갱신은 Task 2에서. 여기서는 코드/스크립트만 변경했으므로 매트릭스 상태 문구는 지시대로
  보고에만 기록)
- MIG-REQ-002: 🟡 로컬 실증 완료(정상+폴백 각 1회)·CI 대기
- MIG-REQ-003: 🟡 (CI의 Container/In-process E2E가 최종 게이트)

## 우려사항

- 폴백 드릴에서 `~/.m2/repository/io/github/beltian/pjacoco`에 실제 `publishToMavenLocal`이 이뤄짐
  (스펙이 명시적으로 허용한 부작용 — "Central과 동일 좌표/버전이라 허용"). 별도 격리된 m2가 아니라
  사용자의 실 `~/.m2`이므로, 반복 실행 시 로컬 캐시에 동일 좌표가 계속 재게시되는 점은 인지 필요(문제는
  아님).
- `$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage`(PJACOCO_SRC 기본 경로)는 스크립트
  외부의 캐시 디렉터리라 존재 시 재검증 없이 재사용된다(기존 스크립트와 동일한 사전 동작 — 이번
  마이그레이션이 새로 만든 리스크 아님). 이번 검증에서는 잘못된 브랜치(main, v2.0.0 아님)로 남아있던
  걸 발견해 삭제 후 재클론으로 확인했다.
- CI(E2E 3잡·demo-weekly)의 실 그린은 Task 2(CI/문서 정리) 이후 PR 단계에서 최종 확인 필요 — 이 태스크
  범위는 로컬 실증까지.

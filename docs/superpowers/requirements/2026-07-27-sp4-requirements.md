# SP4 요구사항명세 — `tia mcp` + 스킬 강화
> 출처(design spec): docs/superpowers/specs/2026-07-27-sp4-agent-surface-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 요구사항 목록

### SP4-REQ-001 — initialize 협상·serverInfo
- 유형: Functional / 우선순위: Must
- 설명: initialize는 클라이언트 protocolVersion이 지원 목록{2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05}에 있으면 에코, 아니면 2025-06-18로 응답. capabilities.tools 존재. serverInfo.version은 raw 버전(“tia ” 접두 금지).
- 수용기준: Given 2025-11-25 요청, Then 에코+tools+접두 없는 version / Given 미지원 버전, Then 2025-06-18 응답.
- 검증 레벨: E2E stdio 블랙박스

### SP4-REQ-002 — tools/list 계약
- 유형: Functional / 우선순위: Must
- 설명: 2개 도구(tia_impact·tia_doctor)와 inputSchema(impact의 required=commit), **비어있지 않은 description**(spec §2 확정 문구 — 자연어 발견성의 핵심) 반환.
- 수용기준: Given tools/list, Then 도구 2개 + impact required에 "commit" + 두 도구 description 비어있지 않음.
- 검증 레벨: E2E

### SP4-REQ-003 — tia_impact 호출(JSON 계약·git_ref 매핑)
- 유형: Functional / 우선순위: Must
- 설명: 절대경로 db+diff_file 지정 호출이 SP1 impact JSON(schemaVersion=1, tests[])을 content[0].text로 반환, isError=false. `git_ref` 인자는 `--git-ref`로 정확히 전달된다.
- 수용기준:
  - Given db+diff_file(절대), When 호출, Then SP1 JSON 파싱 + isError=false.
  - Given git_ref 지정(인덱싱 커밋과 다른 ref), When 호출, Then 결과가 ref에 따라 달라짐(매핑 실증).
- 검증 레벨: E2E

### SP4-REQ-004 — working_dir 실효(diff·기본 DB·상대경로 db/diff)
- 유형: Functional / 우선순위: Must
- 설명: working_dir는 tia.yml 탐색(--search-root)+암시적 git diff(runGitDiff workingDir)+기본 DB 해석(DbPaths workingDir 오버로드)에 실제 반영된다(ImpactCommand·DoctorCommand 히든 `--working-dir` 시임 — hidden 단언 포함; 미지정 시 기존 동작). 상대 db·diff_file은 어댑터가 working_dir 기준 절대화.
- 수용기준:
  - Given db·diff_file 생략 + working_dir=프로세스 cwd와 다른 @TempDir git 레포, When tia_impact, Then 결과가 그 레포 기준. **픽스처: 인덱스는 `DbPaths.resolveDefault(workingDir)` 계산 경로(`<repo>/.git/tia/tia.db`)에 직접 인덱싱하고, 비-0 선별을 단언**(no-baseline 0건 성공으로 새는 공허 테스트 금지).
  - Given 상대 diff_file + working_dir, When 호출, Then working_dir 기준 해석 성공.
  - Given **상대 db** + working_dir, When 호출, Then working_dir 기준 경로의 인덱스가 사용됨(비-0 선별 단언 — 오경로 빈 DB 침묵 성공 차단).
- 검증 레벨: E2E

### SP4-REQ-005 — tia_doctor 호출(FAIL≠isError 실증)
- 유형: Functional / 우선순위: Must
- 설명: doctor JSON 반환; `DoctorCommand.call()`이 정상 반환한 exit 1(FAIL 존재)도 isError=false(SP3 §3 불변식 — 어댑터/파싱 수준 실패는 면제 제외).
- 수용기준: Given **깨진 tia.yml이 있는 working_dir(FAIL·exit 1 확정 픽스처 — 빈 디렉터리는 WARN만 나와 FAIL 경로 미검증)**, When tia_doctor, Then doctor JSON(체크3 FAIL 포함) + isError=false.
- 검증 레벨: E2E

### SP4-REQ-006 — 오류·수명 계약
- 유형: Functional / 우선순위: Must
- 설명: -32601(미지원 메서드)/-32700(파싱 불능, id=null, 서버 생존)/-32602(**도구 미존재·required 부재 두 가지만** — 수제 사전 검사, 타입/값 문제는 isError로 수렴)/isError(실행 실패); `ping`→`{}`; notification 무응답; EOF exit 0. 응답은 부트스트랩 시 저장한 실제 stdout 참조로만 기록.
- 수용기준: 매트릭스 테스트 그대로(ping·required 부재·깨진 라인 후 생존 포함).
- 검증 레벨: E2E

### SP4-REQ-007 — CLI 배선
- 유형: Functional / 우선순위: Must
- 설명: TiaCommand subcommands·usage에 mcp 등록. mcp는 CLI 옵션이 없다(모든 파라미터는 stdin JSON-RPC).
- 수용기준: CliWiringTest#subcommandsIncludeMcp — mcp 서브커맨드 존재 단언(옵션 단언 없음 — 명명 관례 혼동 방지).
- 검증 레벨: CLI acceptance

### SP4-REQ-008 — 문서(스킬·README) 갱신
- 유형: Non-functional(문서) / 우선순위: Must
- 설명·수용기준(체크리스트): ① SKILL.md `--format json` 우선 예시 ② SKILL.md tia.yml 자동 적용 안내 1줄 ③ SKILL.md MCP 설정 1줄(기존 doctor 문구와 중복 없이 통합) ④ README "사용 형태" 표에 MCP 행 ⑤ README "현재 범위 & 한계"의 해당 불릿에서 **"자체 MCP 서버" 부분만 제거**하고 "PR 코멘트 이원화"는 유지(SP5는 단일 코멘트만 구현 — 이원화는 여전히 후속).
- 검증 레벨: build/docs 게이트

### SP4-REQ-009 — 하위호환 + 실 클라이언트 스모크
- 유형: Non-functional / 우선순위: Must
- 설명: 기존 스위트 무변경 green(히든 시임 미지정 시 동작 불변). 실 클라이언트 수동 스모크 1회: `./gradlew :tia-cli:installDist` 후 `claude mcp add tia-local -- $PWD/tia-cli/build/install/tia/bin/tia mcp` 등록 → tools/list·tia_doctor 1회 → 협상 protocolVersion 기록 → **모든 종료 경로에서 `claude mcp remove tia-local` 정리(trap)**. `claude` CLI 부재/등록 실패 시: 사유를 리포트에 명시하고 본 REQ를 미충족(🔴/🟡)으로 표기(침묵 스킵 금지).
- 수용기준: 전체 스위트 green + 스모크 기록(또는 실패 사유 명기).
- 검증 레벨: 전체 스위트 + 수동 스모크(기록)

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| SP4-REQ-001 | initialize 협상 | McpCommandE2ETest#initializeEchoesSupportedVersion / #unsupportedVersionFallsBack | E2E | 🔴 planned |
| SP4-REQ-002 | tools/list(+description) | McpCommandE2ETest#toolsListSchema | E2E | 🔴 planned |
| SP4-REQ-003 | tia_impact JSON·git_ref | McpCommandE2ETest#impactReturnsSp1Json / #gitRefMappedToCliOption | E2E | 🔴 planned |
| SP4-REQ-004 | working_dir 실효(+상대 db) | McpCommandE2ETest#workingDirGovernsDiffAndDb / #relativeDiffFileResolvedAgainstWorkingDir / #relativeDbResolvedAgainstWorkingDir + CliWiringTest(--working-dir hidden 단언) | E2E+CLI | 🟡 partial(Task 1: DbPaths.resolveDefault(workingDir)+gitCommonDir(workingDir) · ImpactCommand/DoctorCommand 히든 `--working-dir`(diff·기본 DB 해석 배선) 구현·green — DbPathsTest#workingDirGitRepoUsesItsCommonDir/#workingDirNonGitFallsBackToCacheHome, ImpactCommandTest#workingDirGovernsDiffAndDefaultDb, CliWiringTest#workingDirHiddenSeamOnImpactAndDoctor. McpCommandE2ETest(MCP 어댑터 경유·상대경로 db/diff_file 절대화)는 Task 2에서 완료) |
| SP4-REQ-005 | tia_doctor FAIL≠isError | McpCommandE2ETest#doctorFailIsNotError(깨진 tia.yml 픽스처) | E2E | 🔴 planned |
| SP4-REQ-006 | 오류·수명(+ping) | McpCommandE2ETest#unknownMethod32601 / #parseError32700ThenAlive / #unknownTool32602 / #missingRequired32602 / #execFailureIsError / #notificationNoResponse / #eofExitsZero / #pingReturnsEmptyObject | E2E | 🔴 planned |
| SP4-REQ-007 | CLI 배선 | CliWiringTest#subcommandsIncludeMcp | CLI | 🔴 planned |
| SP4-REQ-008 | 문서 갱신(5항목 체크리스트) | PR 전 docs 게이트 점검 | build | 🔴 planned |
| SP4-REQ-009 | 하위호환·스모크 | 전체 스위트 + 수동 스모크 기록(trap 정리) | suite | 🔴 planned |

Coverage: 0/9 green (0%) — target 100% (대상: Must 9 = 9; McpCommandE2ETest 총 17 메서드)

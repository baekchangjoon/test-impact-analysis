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
- 설명: 2개 도구(tia_impact·tia_doctor)와 inputSchema(impact의 required=commit) 반환.
- 수용기준: Given tools/list, Then 도구 2개 + impact required에 "commit".
- 검증 레벨: E2E

### SP4-REQ-003 — tia_impact 호출(JSON 계약 반환)
- 유형: Functional / 우선순위: Must
- 설명: 절대경로 db+diff_file 지정 호출이 SP1 impact JSON(schemaVersion=1, tests[])을 content[0].text로 반환, isError=false.
- 수용기준: 매트릭스 테스트 그대로.
- 검증 레벨: E2E

### SP4-REQ-004 — working_dir 실효(diff·기본 DB·상대경로)
- 유형: Functional / 우선순위: Must
- 설명: working_dir는 tia.yml 탐색(--search-root)+암시적 git diff(runGitDiff workingDir)+기본 DB 해석(DbPaths workingDir 오버로드)에 실제 반영된다(ImpactCommand·DoctorCommand 히든 `--working-dir` 시임; 미지정 시 기존 동작). 상대 db/diff_file은 어댑터가 working_dir 기준 절대화.
- 수용기준:
  - Given db·diff_file 생략 + working_dir=프로세스 cwd와 다른 @TempDir git 레포(커밋·인덱스 존재), When tia_impact, Then 결과가 그 레포 기준(diff·DB 모두).
  - Given 상대 diff_file + working_dir, When 호출, Then working_dir 기준으로 해석되어 성공.
- 검증 레벨: E2E

### SP4-REQ-005 — tia_doctor 호출(FAIL≠isError)
- 유형: Functional / 우선순위: Must
- 설명: doctor JSON 반환; exit 1(FAIL 존재)도 isError=false(SP3 §3 불변식 근거 — 실행 자체 실패만 isError).
- 수용기준: Given 빈 디렉터리 working_dir, Then doctor JSON + isError=false.
- 검증 레벨: E2E

### SP4-REQ-006 — 오류·수명 계약
- 유형: Functional / 우선순위: Must
- 설명: -32601(미지원 메서드)/-32700(파싱 불능, id=null, 서버 생존)/-32602(도구 미존재·required 부재 — 수제 사전 검사만)/isError(실행 실패); notification 무응답; EOF exit 0. 응답은 부트스트랩 시 저장한 실제 stdout 참조로만 기록.
- 수용기준: 매트릭스 테스트 6~7 그대로(-32602 required 부재 케이스 포함, 깨진 라인 후 후속 요청 정상).
- 검증 레벨: E2E

### SP4-REQ-007 — CLI 배선
- 유형: Functional / 우선순위: Must
- 설명: TiaCommand subcommands·usage에 mcp 등록.
- 수용기준: CliWiringTest에 mcp 존재 단언.
- 검증 레벨: CLI acceptance

### SP4-REQ-008 — 문서(스킬·README) 갱신
- 유형: Non-functional(문서) / 우선순위: Must
- 설명: SKILL.md json-우선·tia.yml 안내·MCP 설정 1줄(기존 doctor 문구와 중복 없이 통합); README 사용 형태 표 MCP 행 + "이후 확장"의 MCP 항목 갱신.
- 수용기준: 갱신 문서 검토 시 전 항목 기재.
- 검증 레벨: build/docs 게이트

### SP4-REQ-009 — 하위호환 + 실 클라이언트 스모크
- 유형: Non-functional / 우선순위: Must
- 설명: 기존 스위트 무변경 green(히든 시임 미지정 시 동작 불변). 실 클라이언트(`claude mcp add`) 수동 스모크 1회 — tools/list·호출 1회·협상된 protocolVersion 기록(자동화 불가 한계 명시).
- 수용기준: 전체 스위트 green + 스모크 기록.
- 검증 레벨: 전체 스위트 + 수동 스모크(기록)

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| SP4-REQ-001 | initialize 협상 | McpCommandE2ETest#initializeEchoesSupportedVersion / #unsupportedVersionFallsBack | E2E | 🔴 planned |
| SP4-REQ-002 | tools/list | McpCommandE2ETest#toolsListSchema | E2E | 🔴 planned |
| SP4-REQ-003 | tia_impact JSON | McpCommandE2ETest#impactReturnsSp1Json | E2E | 🔴 planned |
| SP4-REQ-004 | working_dir 실효 | McpCommandE2ETest#workingDirGovernsDiffAndDb / #relativeDiffFileResolvedAgainstWorkingDir | E2E | 🔴 planned |
| SP4-REQ-005 | tia_doctor | McpCommandE2ETest#doctorFailIsNotError | E2E | 🔴 planned |
| SP4-REQ-006 | 오류·수명 | McpCommandE2ETest#unknownMethod32601 / #parseError32700ThenAlive / #unknownTool32602 / #missingRequired32602 / #execFailureIsError / #notificationNoResponse / #eofExitsZero | E2E | 🔴 planned |
| SP4-REQ-007 | CLI 배선 | CliWiringTest#optionsForMcp | CLI | 🔴 planned |
| SP4-REQ-008 | 문서 갱신 | PR 전 docs 게이트 점검 | build | 🔴 planned |
| SP4-REQ-009 | 하위호환·스모크 | 전체 스위트 + 수동 스모크 기록 | suite | 🔴 planned |

Coverage: 0/9 green (0%) — target 100% (대상: Must 9 = 9)

# SP4 설계 — 에이전트 표면: `tia mcp` 서브커맨드 + 스킬 강화

- 날짜: 2026-07-27
- 상위 맥락: 사용성 개선 5-SP 분해(SP1 spec §0)의 마지막. 사용자 확정: MCP는 **`tia mcp` 서브커맨드**(배포물 하나, `claude mcp add` 한 줄 설정 — 별도 Node 패키지 없음).

## 1. 목표·비범위

**목표**

1. `tia mcp` — **stdio MCP 서버** 서브커맨드: 스킬 미지원 에이전트 클라이언트에서도 자연어로 impact/doctor를 쓸 수 있게 한다. 도구는 SP1의 JSON 계약(schemaVersion=1)을 그대로 반환하는 얇은 어댑터.
2. `skills/tia/SKILL.md` 강화: tia.yml 인지, `--format json` 우선 사용, `tia doctor` 안내(SP3에서 일부 반영됨 — 정리), MCP 설정법 1줄.

**비범위**

- MCP resources/prompts/sampling — tools만.
- HTTP/SSE 전송 — stdio만.
- flaky/report/init 도구화 — 1차는 `tia_impact`·`tia_doctor` 2종(에이전트 핵심 질의). 필요 실증 시 후속.
- 외부 MCP SDK 의존 — 필요한 표면(initialize/tools/list/tools/call)이 작아 **jackson 기반 최소 구현**(신규 무거운 의존 회피; 프로토콜 표면을 §3에 고정).

## 2. 도구 정의

| 도구 | 입력 스키마(JSON Schema) | 동작 |
|---|---|---|
| `tia_impact` | `commit`(required string), `db`(string, 선택 — 미지정 시 tia.yml/기본값 해석), `diff_file`(string 선택), `git_ref`(string 선택), `working_dir`(string 선택 — 탐색·git 기준, 기본 서버 프로세스 cwd) | 인프로세스로 `impact --format json` 실행(해당 옵션 매핑) → stdout JSON을 text 콘텐츠로 반환. exit≠0이면 `isError: true` + stderr 요약 |
| `tia_doctor` | `working_dir`(string 선택) | 인프로세스 `doctor --format json` → JSON 반환. doctor의 exit 1(FAIL 존재)도 **정상 도구 결과**(JSON에 상태가 있음 — isError 아님; 실행 자체 실패만 isError) |

- **인프로세스 실행**: DemoCommand와 동일 패턴 — `new CommandLine(new TiaCommand()).execute(...)` + System.out/err 스왑 캡처(+finally 복원). 서브프로세스 없음(단일 JVM).
- `working_dir`는 각 커맨드의 기존 히든 `--search-root` 시임으로 전달(cwd 전역 상태 불변).

## 3. MCP 프로토콜 표면 (최소 구현 계약)

- 전송: stdio, **개행 구분 JSON-RPC 2.0**(메시지당 한 줄). stdout에는 JSON-RPC 응답만(로그는 stderr).
- 지원 메서드:
  - `initialize` → `{protocolVersion: "2024-11-05", capabilities: {tools: {}}, serverInfo: {name: "tia", version: <CLI 버전>}}` (클라이언트가 다른 protocolVersion을 보내도 서버는 위 버전으로 응답 — 협상 최소화)
  - `notifications/initialized` → 무시(응답 없음)
  - `tools/list` → §2의 2개 도구(JSON Schema 포함)
  - `tools/call` → 실행 결과 `{content: [{type: "text", text: <JSON 문자열>}], isError: <bool>}`
  - `ping` → `{}` / 알 수 없는 메서드 → JSON-RPC 오류 `-32601`
- 종료: stdin EOF에서 정상 종료(exit 0). 파싱 불능 라인은 JSON-RPC `-32700` 오류 응답 후 계속(서버 생존).
- id 없는 요청(notification)은 응답을 쓰지 않는다.

## 4. 스킬 강화 (skills/tia/SKILL.md)

- "How to use"에 **`--format json` 우선**(에이전트는 json을 파싱하고 사람 보고 시 요약) 명시 — 기존 예시를 json 사용으로 갱신.
- tia.yml 존재 시 필터·기본값이 자동 적용됨을 1줄 안내(레포 루트 확인 팁).
- MCP 설정 1줄: `claude mcp add tia -- <tia 경로> mcp` (스킬 미지원 클라이언트용 대안임을 명시).
- SP3의 doctor 안내와 문구 정리(중복 제거).

## 5. 에러 처리

- 도구 실행 중 커맨드 exit≠0(doctor 제외) → `isError: true` + 캡처된 stderr 마지막 10줄.
- JSON-RPC 레벨: 파싱 오류 `-32700`, 미지원 메서드 `-32601`, 도구 미존재/입력 스키마 위반 `-32602`.
- 서버는 개별 요청 실패로 죽지 않는다(EOF만이 정상 종료 경로).

## 6. 테스트 전략과 E2E/수용 명세

**E2E(인프로세스 stdio 블랙박스)**: `McpCommandE2ETest` — System.in을 준비된 JSON-RPC 라인 스트림으로, System.out을 캡처 버퍼로 스왑(@Execution(SAME_THREAD))해 `tia mcp`를 실행하고 응답 라인을 파싱 단언:

1. `initialize` → protocolVersion·capabilities.tools·serverInfo 존재.
2. `tools/list` → 2개 도구 + inputSchema에 required `commit`(impact).
3. `tools/call tia_impact`(사전 인덱싱된 @TempDir db + diff_file) → content[0].text가 SP1 impact JSON(schemaVersion=1, tests[])로 파싱됨, isError=false.
4. `tools/call tia_doctor`(빈 디렉터리 working_dir) → doctor JSON 파싱 + isError=false(WARN이어도).
5. 오류 경로: 알 수 없는 메서드 → -32601; 깨진 JSON 라인 → -32700 후 후속 요청 정상 처리(서버 생존); 미존재 도구 → -32602; impact 실행 실패(존재하지 않는 diff_file) → isError=true.
6. notification(id 없음) → 응답 없음; EOF → exit 0.

**CLI 배선**: CliWiringTest에 mcp 등록 단언. **스킬 문서**: docs 게이트(json 우선·MCP 설정·tia.yml 안내 존재). **하위호환**: 기존 스위트 무변경 green.

**실 클라이언트 스모크(수동 1회, 한계 명시)**: `claude mcp add`로 실제 등록해 tools/list·1회 호출 확인 — 자동화 불가(외부 클라이언트), 리포트에 기록.

## 7. 리스크와 반론

- **수제 프로토콜 구현의 드리프트**: MCP 스펙 진화 시 어긋날 수 있다 — 표면을 §3 계약으로 고정하고 E2E가 그 계약을 잠근다. 반론(SDK 채택)은 의존 무게·셰이딩 이슈로 기각(표면이 4메서드 뿐).
- **stdout 오염**: 도구가 실행하는 커맨드의 stdout을 스왑 캡처하므로 JSON-RPC 채널과 분리됨 — 스왑 복원 실수는 E2E 5(서버 생존)로 검출.
- **동시성**: stdio 단일 클라이언트·순차 처리(멀티스레드 없음) — 명시.

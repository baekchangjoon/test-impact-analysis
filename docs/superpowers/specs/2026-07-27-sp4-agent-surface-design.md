# SP4 설계 — 에이전트 표면: `tia mcp` 서브커맨드 + 스킬 강화

- 날짜: 2026-07-27
- 상태: 3-벤더 리뷰(Sonnet ×3 대체 슬롯) 소견 반영 완료
- 상위 맥락: 사용성 개선 5-SP 분해(SP1 spec §0)의 마지막. 사용자 확정: MCP는 **`tia mcp` 서브커맨드**(배포물 하나, `claude mcp add` 한 줄 설정 — 별도 Node 패키지 없음).
- **SP1 계약 갱신(명시적 대체)**: SP1 §2는 "MCP는 CLI를 서브프로세스로 실행해 JSON 소비(비-JVM 구현 가능성)"라 했으나, 사용자가 `tia mcp` 서브커맨드(단일 JVM 내장)를 확정하면서 비-JVM 근거가 소멸 — **본 설계의 인프로세스 실행이 SP1 §2의 해당 문구를 대체한다**(SP1 spec에도 전방 참조 주석을 추가한다). JSON 계약(schemaVersion=1) 소비 자체는 동일.

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
| `tia_impact` | `commit`(required string), `db`(string, 선택 — 미지정 시 tia.yml/기본값 해석), `diff_file`(string 선택), `git_ref`(string 선택), `working_dir`(string 선택 — 탐색·git·기본 DB 기준, 기본 서버 프로세스 cwd) | 인프로세스로 `impact --format json` 실행(해당 옵션 매핑) → stdout JSON을 text 콘텐츠로 반환. exit≠0이면 `isError: true` + stderr 요약 |
| `tia_doctor` | `working_dir`(string 선택) | 인프로세스 `doctor --format json` → JSON 반환. doctor의 exit 1(FAIL 존재)도 **정상 도구 결과**(isError 아님; 실행 자체 실패만 isError). 근거: SP3 §3 불변식 "doctor는 예외로 죽지 않는다 — FAIL ≥1 → exit 1"이라 exit 1은 진단 내용이지 실행 실패가 아니다 |

- **인프로세스 실행**: DemoCommand의 캡처+finally-복원 관용구를 따르되 **System.out도 함께 스왑**한다(DemoCommand는 err만 스왑 — 도구 결과 JSON은 stdout 캡처가 본질). 서브프로세스 없음(단일 JVM).
- **working_dir의 실효 범위(기존 시임만으로는 불충분 — 리뷰 확인 사실)**: `--search-root`는 tia.yml 탐색에만 쓰이고, ①`DbPaths.resolveDefault()`의 `git rev-parse --git-common-dir`는 `.directory()` 미지정(프로세스 cwd 고정) ②`ImpactCommand`의 암시적 diff는 `runGitDiff(base, null)`(cwd 고정)이다. 따라서 SP4는 다음 시임을 함께 뚫는다:
  - `DbPaths`: workingDir 인자 오버로드(`gitCommonDir(Path)`에 `.directory(workingDir)` — 기존 무인자 경로는 위임 유지).
  - `ImpactCommand`·`DoctorCommand`: 히든 `--working-dir <dir>`(테스트/MCP 시임) — impact는 `runGitDiff(base, workingDir)`와 기본 DB 해석에, doctor는 기본 DB 해석에 사용(doctor의 체크 2·5·6은 기존 search-root 경로 유지). 미지정 시 기존 동작(완전 하위호환).
  - MCP 어댑터는 `working_dir`를 각 커맨드의 `--search-root`와 `--working-dir` **둘 다**에 전달한다.
- **상대 경로 계약**: `db`·`diff_file`은 상대 경로면 **MCP 어댑터가 working_dir 기준으로 절대화**한 뒤 커맨드에 전달한다(단일 JVM엔 호출별 cwd가 없으므로 — 계약 명시).

## 3. MCP 프로토콜 표면 (최소 구현 계약)

- 전송: stdio, **개행 구분 JSON-RPC 2.0**(메시지당 한 줄). stdout에는 JSON-RPC 응답만(로그는 stderr).
- **stdout 안전 불변식**: 서버는 부트스트랩 시 **실제 stdout의 `PrintStream` 참조를 저장**하고, 모든 JSON-RPC 응답을 그 저장된 참조로 쓴다(도구 실행의 System.out 스왑과 무관하게 — 복원 실수가 응답 채널을 오염시킬 수 없는 구조).
- 지원 메서드:
  - `initialize` → `{protocolVersion: <협상>, capabilities: {tools: {}}, serverInfo: {name: "tia", version: <버전>}}`.
    **버전 협상**: 클라이언트가 요청한 protocolVersion이 지원 목록 `{2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05}`에 있으면 **그대로 에코**(tools-only 표면은 이 개정판들에서 동일), 아니면 `2025-06-18`로 응답(클라이언트가 판단·연결 종료 가능 — MCP 협상 규약). 근거: 최고(最古) 버전 고정은 신형 클라이언트(Claude Desktop 등)에서 실증된 거부 사례가 있음.
    **serverInfo.version**: `/tia-version.properties`의 raw `version` 프로퍼티를 직접 읽는다(`VersionProvider`의 CLI 배너 문자열 `"tia X.Y.Z"` 재사용 금지 — 접두어 오염).
  - `notifications/initialized` → 무시(응답 없음)
  - `tools/list` → §2의 2개 도구(JSON Schema 포함)
  - `tools/call` → 실행 결과 `{content: [{type: "text", text: <JSON 문자열>}], isError: <bool>}`
  - `ping` → `{}` / 알 수 없는 메서드 → JSON-RPC 오류 `-32601`
- **입력 검증(-32602의 범위)**: JSON Schema 완전 검증기 없음(수제 최소 구현) — 서버는 **도구 미존재·required 필드 부재·명백한 타입 불일치만 사전 검사**해 `-32602`를 반환하고, 그 외 값-수준 문제는 커맨드 실행 실패(isError: true)로 수렴한다(명시).
- 종료: stdin EOF에서 정상 종료(exit 0). 파싱 불능 라인은 JSON-RPC `-32700` 오류 응답(**id는 null** — 요청 id 파싱 불가) 후 계속(서버 생존).
- id 없는 요청(notification)은 응답을 쓰지 않는다.
- **CLI 배선**: `TiaCommand` subcommands 배열·usage 문자열에 `mcp` 추가(기존 관례; CliWiringTest 등록 단언 포함).

## 4. 스킬 강화 (skills/tia/SKILL.md)

- "How to use"에 **`--format json` 우선**(에이전트는 json을 파싱하고 사람 보고 시 요약) 명시 — 기존 예시를 json 사용으로 갱신.
- tia.yml 존재 시 필터·기본값이 자동 적용됨을 1줄 안내(레포 루트 확인 팁).
- MCP 설정 1줄: `claude mcp add tia -- <tia 경로> mcp` (스킬 미지원 클라이언트용 대안임을 명시).
- doctor 안내는 SP3에서 이미 1곳 존재 — 새 문구가 그와 **중복되지 않게 통합**한다("중복 제거"가 아니라 중복 생성 방지).
- **README 동기화(문서 게이트 범위)**: "사용 형태(배포 표면)" 표에 MCP 행 추가 + "현재 범위 & 한계"의 "자체 MCP 서버 — 이후 확장" 항목 제거/갱신.

## 5. 에러 처리

- 도구 실행 중 커맨드 exit≠0(doctor 제외) → `isError: true` + 캡처된 stderr 마지막 10줄.
- JSON-RPC 레벨: 파싱 오류 `-32700`, 미지원 메서드 `-32601`, 도구 미존재/입력 스키마 위반 `-32602`.
- 서버는 개별 요청 실패로 죽지 않는다(EOF만이 정상 종료 경로).

## 6. 테스트 전략과 E2E/수용 명세

**E2E(인프로세스 stdio 블랙박스)**: `McpCommandE2ETest` — System.in을 준비된 JSON-RPC 라인 스트림으로, System.out을 캡처 버퍼로 스왑해 `tia mcp`를 실행하고 응답 라인을 파싱 단언. @Execution(SAME_THREAD) + **System.in/out을 finally/@AfterEach에서 반드시 복원**(System.setIn은 이 코드베이스 최초 패턴 — 복원 규율 명시):

1. `initialize`(신형 protocolVersion 2025-11-25 요청) → **에코 응답** + capabilities.tools·serverInfo(version에 "tia " 접두 없음) 존재; 미지원 버전 요청 → 2025-06-18 응답.
2. `tools/list` → 2개 도구 + inputSchema에 required `commit`(impact).
3. `tools/call tia_impact`(사전 인덱싱된 @TempDir db + diff_file, 절대 경로) → content[0].text가 SP1 impact JSON(schemaVersion=1, tests[])로 파싱됨, isError=false.
4. **working_dir 실효 E2E**: `db`·`diff_file` 모두 생략 + `working_dir`=프로세스 cwd와 **다른** @TempDir git 레포(커밋·인덱스 존재, git_ref 기본) → 결과가 working_dir 레포 기준으로 계산됨(diff·기본 DB 해석 모두 — I1/I2 시임 고정).
5. `tools/call tia_doctor`(빈 디렉터리 working_dir) → doctor JSON 파싱 + isError=false(WARN이어도).
6. 오류 경로: 알 수 없는 메서드 → -32601; 깨진 JSON 라인 → -32700(id=null) 후 후속 요청 정상 처리(서버 생존); 미존재 도구 → -32602; **required 필드(commit) 부재 → -32602**(사전 검사); impact 실행 실패(존재하지 않는 diff_file) → isError=true.
7. notification(id 없음) → 응답 없음; EOF → exit 0.
8. 상대 `diff_file` + working_dir → working_dir 기준 절대화되어 성공.

**CLI 배선**: CliWiringTest에 mcp 등록 단언. **스킬 문서**: docs 게이트(json 우선·MCP 설정·tia.yml 안내 존재). **하위호환**: 기존 스위트 무변경 green.

**실 클라이언트 스모크(수동 1회, 한계 명시)**: `claude mcp add`로 실제 등록해 tools/list·1회 호출 확인 — 자동화 불가(외부 클라이언트), 리포트에 기록.

## 7. 리스크와 반론

- **수제 프로토콜 구현의 드리프트**: MCP 스펙 진화 시 어긋날 수 있다 — 표면을 §3 계약(버전 협상 목록 포함)으로 고정하고 E2E가 잠근다. 실 클라이언트 스모크가 협상 결과(실제 protocolVersion)를 기록한다. 반론(SDK 채택)은 의존 무게·셰이딩 이슈로 기각(표면이 5메서드 뿐).
- **stdout 오염**: 응답 채널은 부트스트랩 시 저장한 실제 stdout 참조로만 쓴다(§3 불변식) — 스왑 복원 실수가 구조적으로 응답을 오염시킬 수 없고, E2E 6(서버 생존)이 추가 방어선.
- **동시성**: stdio 단일 클라이언트·순차 처리(멀티스레드 없음) — 명시.
- **working-dir 시임의 침습**: ImpactCommand·DbPaths에 히든 시임이 추가되지만 미지정 시 기존 동작과 동일(완전 하위호환) — 기존 스위트 무변경 green이 증거.

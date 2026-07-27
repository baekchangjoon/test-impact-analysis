# SP4 구현 계획 — `tia mcp` + 스킬 강화

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 최소 stdio MCP 서버(`tia mcp`, 도구 2종) + 스킬/README 갱신.

**Architecture:** McpCommand(수제 JSON-RPC 루프, jackson) + 기존 커맨드 인프로세스 실행. working-dir 시임을 ImpactCommand/DoctorCommand/DbPaths에 추가.

**참조:** spec `docs/superpowers/specs/2026-07-27-sp4-agent-surface-design.md`(**세부는 여기 따름 — §2 시임 표·§3 프로토콜 계약 그대로**), 요구명세 `docs/superpowers/requirements/2026-07-27-sp4-requirements.md`(SP4-REQ-001..009; 매트릭스 직접 갱신)

## Global Constraints

- 응답은 부트스트랩 시 저장한 실제 stdout `PrintStream` 참조로만 기록 [SP4-REQ-006]
- 버전 협상 목록·serverInfo raw 버전·-32700 id=null·-32602 범위(수제 사전 검사만)는 spec §3 그대로 [SP4-REQ-001/006]
- working-dir 시임: DbPaths workingDir 오버로드 + ImpactCommand/DoctorCommand 히든 `--working-dir`(미지정 시 기존 동작 — 기존 스위트 무변경 green) [SP4-REQ-004/009]
- E2E는 System.in/out 스왑 + **finally/@AfterEach 복원**(System.setIn 최초 도입), @Execution(SAME_THREAD)
- 워크트리 브랜치 `feat/sp4-agent-surface`. 커밋 `[SP4-REQ-…]` + 세션 트레일러.

## File Structure

| 파일 | 책임 |
|---|---|
| `tia-cli/src/main/java/io/tia/cli/McpCommand.java` (신규) | JSON-RPC 루프·협상·도구 디스패치·인프로세스 실행(out/err 스왑 캡처) |
| `tia-cli/src/main/java/io/tia/cli/DbPaths.java` (수정) | `resolveDefault(Path workingDir)` 계열 오버로드(`gitCommonDir(Path)` `.directory()`) |
| `ImpactCommand.java`·`DoctorCommand.java` (수정) | 히든 `--working-dir`(diff·기본 DB 해석 배선) |
| `TiaCommand.java` (수정) | mcp 등록·usage |
| e2e `io/tia/e2e/mcp/McpCommandE2ETest.java` (신규) + `CliWiringTest`(수정) | 매트릭스 수용 테스트 |
| `skills/tia/SKILL.md`·`README.md` (수정) | REQ-008 |

---

### Task 1: working-dir 시임 (DbPaths·Impact·Doctor)

**REQ-IDs:** SP4-REQ-004(시임 부분), SP4-REQ-009(하위호환)

- [ ] 실패 테스트: `DbPathsTest`에 workingDir 오버로드 케이스(@TempDir git 레포 → 그 레포의 common-dir 반환; 비-git → 기존 폴백) + `ImpactCommandTest`에 `--working-dir`로 다른 레포의 diff·기본 DB를 쓰는 케이스(기존 테스트 픽스처 패턴 재사용) + `CliWiringTest`에 impact/doctor `--working-dir` **hidden 단언**(기존 --repo-root 관례). red.
- [ ] 구현: `DbPaths.gitCommonDir(Path workingDir)`(`.directory(workingDir.toFile())`; 기존 무인자는 위임) + `resolveDefault(Path workingDir)`; ImpactCommand 히든 `--working-dir` → `runGitDiff(base, workingDirFile)`·`DbPaths.resolveDefault(workingDir)`에 배선(미지정 null → 기존 경로); DoctorCommand 동일(기본 DB 해석만).
- [ ] green + 전체 스위트 무변경 green → Commit `feat(cli): working-dir 시임 — DbPaths·impact·doctor [SP4-REQ-004]` + 매트릭스(부분 기록).

### Task 2: McpCommand + E2E

**REQ-IDs:** SP4-REQ-001..007

- [ ] 실패 E2E 작성: `McpCommandE2ETest` — **요구명세 추적 매트릭스의 테스트명 전부(총 17 메서드 — 매트릭스가 유일한 소스오브트루스)**, @DisplayName SP4-REQ 태그, spec §6 시나리오·픽스처 레시피 그대로(특히 workingDir 케이스는 `DbPaths.resolveDefault(workingDir)` 경로에 직접 인덱싱+비-0 선별 단언; doctor는 깨진 tia.yml FAIL 픽스처) + `CliWiringTest#subcommandsIncludeMcp`(존재만 — mcp는 CLI 옵션 없음). 헬퍼: 요청 라인들을 `\n` 연결한 ByteArrayInputStream → System.setIn, 응답 stdout 캡처 후 라인별 jackson 파싱, finally 복원. 메시지 형태는 spec §3 리터럴 예시 기준. red.
- [ ] 구현: `McpCommand`(§3 계약·리터럴 예시 그대로 — 저장된 stdout 참조·개행 JSON-RPC 루프·협상 목록·2도구 스키마/description 상수·수제 required 검사(-32602는 도구 미존재·required 부재만)·ping·인프로세스 실행 out/err 스왑·상대 db/diff_file working_dir 절대화·EOF exit 0). TiaCommand 등록·usage.
- [ ] green + 전체 스위트 green → Commit `feat(cli): tia mcp — 최소 stdio MCP 서버(도구 2종) [SP4-REQ-001..007]` + 매트릭스.

### Task 3: 문서 + 실 클라이언트 스모크

**REQ-IDs:** SP4-REQ-008, SP4-REQ-009

- [ ] SKILL.md(json-우선 예시·tia.yml 안내·MCP 1줄 — 기존 doctor 문구와 통합)·README(사용 형태 표 MCP 행; "현재 범위 & 한계" 불릿은 **"자체 MCP 서버"만 제거하고 "PR 코멘트 이원화"는 유지**). REQ-008 체크리스트 5항목 전부. 전체 스위트 green.
- [ ] 실 클라이언트 스모크(모든 종료 경로 정리): `./gradlew :tia-cli:installDist` 후
  `trap 'claude mcp remove tia-local 2>/dev/null' EXIT; claude mcp add tia-local -- "$PWD/tia-cli/build/install/tia/bin/tia" mcp` → tools/list·tia_doctor 1회 → 협상 protocolVersion 기록. **`claude` CLI 부재/등록 실패 시**: 사유를 리포트에 기록하고 REQ-009를 미충족으로 표기(침묵 스킵 금지).
- [ ] 매트릭스 9/9 → Commit `docs(skill,readme): MCP 표면·json 우선 안내 [SP4-REQ-008/009]`.

## 완료 정의

매트릭스 9/9 + 전체 스위트 green + 스모크 기록 + 문서 게이트.

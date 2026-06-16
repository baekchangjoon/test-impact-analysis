# 외부 레포 스모크 런북

전제: 대상은 단일 Spring Boot 레포, RestAssured(또는 임의 JUnit5) 스위트 보유. 에이전트 API는 `agent-api.md` 참조.

> **로컬/CI 컨테이너 검증 (권장, 검증됨)**: `docker compose -f docker-compose.e2e.yml up --abort-on-container-exit --exit-code-from tester`
> — SUT(앱+에이전트) 컨테이너와 tester(RestAssured+확장) 컨테이너를 분리해 **실제 out-of-process 토폴로지**로 돌린다.
> 컨테이너는 자체 네트워크 네임스페이스라, 호스트에서 gradle 테스트 워커 아웃바운드를 막는 샌드박스도 우회한다.
> 본 레포에서 testGreeting·testPrice 본문값 검증 + 실제 수집→impact(testPrice DETERMINISTIC)까지 통과 확인.

## 절차
0. **직렬 실행 보장(설계 §3.3)**: 대상 스위트의 JUnit 병렬 실행을 끈다 — `junit.jupiter.execution.parallel.enabled=false`(기본값 유지). 병렬이면 testwise 커버리지가 섞여 매핑이 오염된다.
1. 대상 테스트 모듈 의존성에 `io.tia:tia-junit-extension:0.1.0` 추가.
2. 테스트 클래스(또는 전역)에 `@ExtendWith(io.tia.junit.TeamscaleTestwiseExtension.class)` 부착.
   (전역 적용: `src/test/resources/META-INF/services/...` + `junit.jupiter.extensions.autodetection.enabled=true`)
3. 대상 앱을 에이전트와 함께 기동(agent-api.md 옵션):
   `-javaagent:<jar>=mode=TESTWISE,includes=*<대상패키지>.*,http-server-port=8123,class-dir=<classes>,out=<dir>`
4. 스위트 실행: `-Dfixture.baseUrl=<앱주소> -Dtia.agent.url=http://localhost:8123` (대상의 baseURI 주입 방식에 맞게)
5. 앱 종료 후 convert로 testwise JSON 생성:
   `bin/convert --class-dir <classes> --in <out> --testwise-coverage -o report.json` → `report-1.json`
6. 인덱싱: `tia index --report report-1.json --repo <레포> --commit $(git rev-parse HEAD) --db tia-<레포>.db`
7. 영향 분석: `tia impact --db tia-<레포>.db --commit <인덱싱커밋>` (--git-ref 미지정 시 베이스=인덱싱커밋 → 라인 공간 정렬 [§6.2 4-B]. **diff 베이스가 인덱싱 커밋과 달라지면 비트 AND 무효** — 현재 구현은 라인 재조정 미구현)

## 체크리스트 (성공 기준)
- [ ] convert 리포트에 대상 패키지 클래스가 covered로 등장 (includes 필터 정확)
- [ ] 코드 한 줄(커버된 라인) 수정 → 그 줄 커버 테스트가 DETERMINISTIC으로 선별됨
- [ ] 커버 안 된 라인 수정 → 미선별 (라인 정밀도 D11 — 파일 수준이면 선별됐을 것)
- [ ] application.yml/SQL 변경 → "보수적 전체 선택" + 1-A 주의 메시지 (목적1 안전망)
- [ ] 신규 .java 추가 → "보수적 전체 선택" (영향 불명, 정적 폴백 이후 확장)
- [ ] 직렬 수행 오버헤드(스위트 +시간) 기록 — 병렬화 트리거(설계 §8) 판단 근거
- [ ] @Scheduled 등 백그라운드가 있는 레포에서 공유 유틸 과다 커버 관찰 (설계 1-C — 본 픽스처에선 NoiseScheduler→TextUtil로 재현됨)

## 알려진 한계 (설계 문서 연결)
- 비코드 변경은 매핑 불가 → 보수적 폴백으로만 방어 (§1.3)
- 커버리지는 인덱싱 커밋 기준 → staleness는 응답의 "매핑 기준 커밋"으로 노출 (§6.5), 라인 재조정은 이후 확장 ([4-B])
- 노이즈 베이스라인 차감(§3.4)은 현재 미구현 — 관찰만

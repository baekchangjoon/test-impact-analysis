# teamscale-jacoco-agent testwise API — 실측 검증 (2026-06-13, v36.5.2)

> Task 2 산출물. 계획의 가정값 대비 **레포 이전 + convert 단계**가 실제와 다름을 확인하고 고정.

## 다운로드
- 레포 이전: `cqse/teamscale-jacoco-agent` → **`cqse/teamscale-java-profiler`**
- 자산: `teamscale-jacoco-agent.zip` (버전 없는 이름)
- URL: `https://github.com/cqse/teamscale-java-profiler/releases/download/v36.5.2/teamscale-jacoco-agent.zip`
- jar: `teamscale/teamscale-jacoco-agent/lib/teamscale-jacoco-agent.jar`
- convert CLI: `teamscale/teamscale-jacoco-agent/bin/convert`
- VERSION.txt: `36.5.2-jacoco-0.8.14`

## 에이전트 옵션 (standalone testwise — Teamscale 서버 불필요)
```
-javaagent:<jar>=mode=TESTWISE,includes=*io.tia.fixture.*,http-server-port=8123,class-dir=<compiled classes>,out=<dir>
```
- `teamscale-server-url` 없이 기동됨(검증).
- `out=` 산출물: `<out>/<timestamp>/jacoco-*.exec` + `test-execution-*.json` — **testwise JSON이 아님!** (convert 필요)

## REST 컨트롤 (http-server-port)
- `POST /test/start/{testId}` → 204 (테스트 시작, 직전까지 reset)
- `POST /test/end/{testId}` → 204 (body: TestExecution `{"uniformPath","durationMillis","result"}`)
- `POST /testrun/start`, `/testrun/end` (TIA 선택용 — 단순 수집엔 불필요)
- `POST /dump`, `/partition`, `/revision`
- **verb = POST** → HttpAgentClient의 POST 가정 정확.
- **testId(uniformPath)는 `%2F` 인코딩 필수** (단일 path 세그먼트). 실측: 원시 슬래시 `/test/start/io/tia/...` = **HTTP 500**, `%2F` 인코딩 `/test/start/io%2Ftia%2F...` = **204**. → HttpAgentClient는 `URLEncoder`로 인코딩(앞선 "원시 슬래시" 가정은 실측으로 정정됨).

## testwise JSON 생성 (convert)
```
bin/convert --class-dir <classes> --in <out-dir> --testwise-coverage -o <report>.json
```
- 출력은 `--split-after`(기본 5000) 때문에 **`<report>-1.json`** 처럼 `-N` 접미사가 붙는다 → `find -name 'report*.json'`로 잡을 것.
- 산출 형식(= `TestwiseReportParser` 기대와 **정확히 일치**, 검증됨):
  `{"tests":[{"uniformPath","result","paths":[{"path","files":[{"fileName","coveredLines":"8-9"}]}]}]}`
- `coveredLines`는 범위 문자열(`"8-9"`) → `LineRangeParser`가 처리.
- "No test details found" 경고는 양성(details=TIA 메타데이터, 커버리지 산출엔 무관).

## 검증된 전체 흐름 (scripts/run-poc.sh)
fixture-app + 에이전트(TESTWISE) → `.exec`+`test-execution.json` → `convert -t` → testwise JSON
→ `tia index` → `tia impact`. 실제 수집 기반으로 목적1(커버라인 변경→testPrice)·D11(미커버 라인→0개) 동작 확인.

# 리포트 리디자인 요구사항명세 — `tia report` 4페이지 + 실제 diff 연결
> 출처: docs/report-redesign/README.md (시안과 확정된 결정)
> 완료 정의(DoD): 아래 매트릭스의 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐

## 요구사항 목록

### RPT-REQ-001 — `tia impact --format json`에 변경 파일별 결과 추가
- 유형: Functional · 우선순위: Must
- 설명: JSON 출력에 `totalTests`, `conservative`, `changedFiles[]`(`file`, `kind`=`modified|new|unmappable`,
  `modified`에 한해 `tests[]`)를 추가한다. 기존 필드와 `schemaVersion`(1)은 바꾸지 않는다(추가만).
- 수용기준:
  - Given 수정 파일 2개(하나는 커버 테스트 없음)·신규 1개·매핑 불가 1개, When json 렌더, Then `changedFiles`가
    4개이고 종류·테스트 목록이 일치하며 `schemaVersion`은 1이다.
- 검증 레벨: 단위

### RPT-REQ-002 — `tia report --impact`
- 유형: Functional · 우선순위: Must
- 설명: `--impact <path>`로 RPT-REQ-001의 JSON을 받아 모델의 `impact`로 싣는다. 미지정·`-`·없는 경로는 `null`.
- 수용기준:
  - Given impact JSON, When `tia report --impact`, Then 데이터 아일랜드의 `impact`가 입력과 같다.
  - Given `--impact -` 또는 미지정, Then `impact`는 `null`이고 exit 0.
- 검증 레벨: 단위 + CLI

### RPT-REQ-003 — 4개 페이지, 변경 영향 페이지는 변경 데이터가 있을 때만
- 유형: Functional · 우선순위: Must
- 설명: 리포트는 변경 영향 · 연결 구조 · 커버리지 지도 · 안정성 페이지로 구성하고 페이지마다 "이 탭 읽는 법"을
  하나씩 둔다. 변경 영향 페이지는 `impact`(실제 diff) 또는 `scenarios`(데모)가 있을 때만 존재한다.
  변경 항목은 보호 없음 → 전체 실행 유발 → 정밀 선별(많은 순)으로 정렬한다.
- 수용기준:
  - Given 렌더된 HTML, Then `tab-guide` 블록이 4개다.
  - Given 템플릿, Then 변경 영향 페이지 정의가 변경 데이터 유무에 묶여 있다(구조 검사).
- 검증 레벨: 단위(구조). 브라우저에서의 실제 분기 실행은 JVM 테스트 범위 밖이며 수동 확인으로 보완한다.

### RPT-REQ-004 — 자기완결형
- 유형: Non-functional · 우선순위: Must
- 설명: 리포트는 HTML 한 장이며 외부 URL·외부 스크립트·외부 스타일·웹폰트를 참조하지 않는다.
- 수용기준: Given 렌더된 HTML, Then `http://`·`https://`·`<link`·`<script src`·`@import`가 없다.
- 검증 레벨: 단위

### RPT-REQ-005 — 규모 대응(한 화면 길이 고정)
- 유형: Non-functional · 우선순위: Should
- 설명: 한 화면에 한쪽 최대 26개 항목. 넘으면 묶음으로 보여주고 드릴다운한다. 목록은 12개까지 보여주고
  전체 목록 페이지(50개씩, 이름으로 거르기)로 연결한다.
- 검증 레벨: 수동(시안의 합성 1,000/10,000건 데이터로 확인). 자동화된 수용 테스트 없음.

## 추적 매트릭스

| REQ | 수용 테스트 | 레벨 | 상태 |
|---|---|---|---|
| RPT-REQ-001 | ImpactFormatsTest#jsonCarriesTotalsAndPerFileImpact | 단위 | 본문 "검증 현황" 참조 |
| RPT-REQ-002 | ReportBuilderTest#impactJsonIsEmbeddedOrNull / D0AcceptanceTest#reportEmbedsImpactJsonFromCli / CliWiringTest | 단위·CLI | 〃 |
| RPT-REQ-003 | ReportBuilderTest#tabGuidesRenderedOncePerPage / #impactPageIsGatedOnChangeData | 단위(구조) | 〃 |
| RPT-REQ-004 | ReportBuilderTest#reportIsSelfContained | 단위 | 〃 |
| RPT-REQ-005 | (수동) | 수동 | 자동 테스트 없음 |

## 범위 밖 (후속)

- `petclinic-demo/make_html.py`(deprecated)는 구 디자인 그대로 둔다. 폐기 여부는 미결.
- 모델의 `perTest`/`reverse` 중복 제거(대형 SUT 리포트 용량).
- "그 외 N개" 묶음 안으로 들어가는 탐색, 전체 목록의 정렬.
- run 누적 추세 화면.
- 커버리지 0인 테스트가 인덱스에서 빠지는 본체 누락(`CoverageStore.save`).

#!/usr/bin/env python3
"""집계 JaCoCo CSV에서 커버리지 %를 Markdown 표로 출력 (CI GITHUB_STEP_SUMMARY용).

XML 대신 CSV를 파싱한다 — 신뢰된 자체 생성 파일이지만 XXE/billion-laughs 표면을 아예 두지 않기 위함.
"""
import csv
import glob
import sys

paths = glob.glob("coverage/build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.csv")
if not paths:
    print("⚠️ 커버리지 CSV를 찾을 수 없음 (testCodeCoverageReport 실행 여부 확인)")
    sys.exit(0)

# JaCoCo CSV의 카운터 컬럼(..._MISSED / ..._COVERED, 클래스별 행) → 합산. (CLASS는 카운터가 아니라 클래스명 컬럼)
metrics = ["INSTRUCTION", "BRANCH", "LINE", "METHOD"]
total = {m: [0, 0] for m in metrics}  # [missed, covered]
with open(paths[0], newline="", encoding="utf-8") as fh:
    for row in csv.DictReader(fh):
        for m in metrics:
            total[m][0] += int(row[f"{m}_MISSED"])
            total[m][1] += int(row[f"{m}_COVERED"])

print("## 📊 테스트 커버리지 (집계: tia-core / tia-cli / tia-junit-extension)\n")
print("| 지표 | 커버 | 전체 | % |")
print("|------|------|------|----|")
for m in metrics:
    missed, covered = total[m]
    tot = missed + covered
    if tot:
        print(f"| {m} | {covered} | {tot} | {100 * covered / tot:.1f}% |")

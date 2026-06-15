#!/usr/bin/env python3
"""Bridge: parallel-per-test-coverage per-test `.exec` -> TIA testwise JSON.

DEPRECATED (D0): superseded by `tia convert` (org.jacoco.core in-process; no jacococli
subprocess). Kept for reference/demo. `run-petclinic-tia.sh` now uses `tia convert`.

For each `<Class#method>.exec` emitted by the agent, render a vanilla-JaCoCo XML
report against the app's compiled classes, extract per-source-file covered lines
(instruction coverage ci>0), and emit one testwise `tests[]` entry. The combined
document is what `tia index` consumes.
"""
import glob
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

COV_DIR = sys.argv[1] if len(sys.argv) > 1 else "/tmp/petclinic-coverage"
CLASSES = sys.argv[2]
JACOCO_CLI = sys.argv[3]
JAVA = sys.argv[4]
OUT = sys.argv[5]


def compress(nums):
    """[1,2,3,5] -> '1-3,5' (teamscale coveredLines range syntax)."""
    nums = sorted(set(nums))
    out, i = [], 0
    while i < len(nums):
        j = i
        while j + 1 < len(nums) and nums[j + 1] == nums[j] + 1:
            j += 1
        out.append(str(nums[i]) if i == j else f"{nums[i]}-{nums[j]}")
        i = j + 1
    return ",".join(out)


tests = []
for exec_path in sorted(glob.glob(os.path.join(COV_DIR, "*.exec"))):
    test_id = os.path.basename(exec_path)[:-len(".exec")]
    xml_path = exec_path + ".report.xml"
    subprocess.run(
        [JAVA, "-jar", JACOCO_CLI, "report", exec_path,
         "--classfiles", CLASSES, "--xml", xml_path],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    root = ET.parse(xml_path).getroot()
    paths = []
    for pkg in root.findall("package"):
        files = []
        for sf in pkg.findall("sourcefile"):
            lines = [int(l.get("nr")) for l in sf.findall("line")
                     if int(l.get("ci", "0")) > 0]
            if lines:
                files.append({"fileName": sf.get("name"),
                              "coveredLines": compress(lines)})
        if files:
            paths.append({"path": pkg.get("name"), "files": files})

    result = "UNKNOWN"
    companion = exec_path[:-len(".exec")] + ".json"
    if os.path.exists(companion):
        with open(companion) as fh:
            result = json.load(fh).get("result", "UNKNOWN").upper()

    tests.append({"uniformPath": test_id, "result": result, "paths": paths})

with open(OUT, "w") as fh:
    json.dump({"tests": tests}, fh, indent=2)

total_files = sum(len(p["files"]) for t in tests for p in t["paths"])
print(f"wrote {OUT}: {len(tests)} tests, {total_files} (test,file) coverage rows")

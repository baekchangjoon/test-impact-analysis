#!/usr/bin/env python3
"""Run the `tia impact` scenarios and emit both human text and scenarios.json.

For each scenario we perturb the petclinic working tree, capture
`git diff --unified=0` (old-side aligns with the indexed HEAD line space), run
`tia impact`, parse the selection, then revert. No working-tree change survives.

Usage: run_scenarios.py <petclinic_dir> <tia_cli> <db> <commit> <out_json>
"""
import json
import os
import re
import subprocess
import sys

PETC, CLI, DB, COMMIT, OUT = sys.argv[1:6]
TOTAL = 24
SEL_RE = re.compile(r"^(DETERMINISTIC|CONSERVATIVE|LOW_CONFIDENCE)\t(.+)$")


def git(*args):
    return subprocess.run(["git", "-C", PETC, *args], capture_output=True, text=True).stdout


def impact(diff_text):
    with open("/tmp/tia-scenario.diff", "w") as fh:
        fh.write(diff_text)
    out = subprocess.run(
        [CLI, "impact", "--db", DB, "--commit", COMMIT, "--diff-file", "/tmp/tia-scenario.diff"],
        capture_output=True, text=True, env={**os.environ}).stdout
    selected, reasons = [], []
    for line in out.splitlines():
        m = SEL_RE.match(line)
        if m:
            selected.append({"confidence": m.group(1), "testId": m.group(2)})
        elif line.startswith("# 주의:"):
            reasons.append(line[len("# 주의:"):].strip())
    return selected, reasons, out


def modify_line(relpath, line):
    path = os.path.join(PETC, relpath)
    with open(path) as fh:
        lines = fh.readlines()
    backup = "".join(lines)
    lines[line - 1] = lines[line - 1].rstrip("\n") + " // tia-probe\n"
    with open(path, "w") as fh:
        fh.write("".join(lines))
    diff = git("diff", "--unified=0", "--", relpath)
    with open(path, "w") as fh:
        fh.write(backup)
    return diff


def append_lines(relpath):
    path = os.path.join(PETC, relpath)
    with open(path) as fh:
        backup = fh.read()
    with open(path, "a") as fh:
        fh.write("\n# tia-probe\n")
    diff = git("diff", "--unified=0", "--", relpath)
    with open(path, "w") as fh:
        fh.write(backup)
    return diff


def new_file(relpath):
    path = os.path.join(PETC, relpath)
    with open(path, "w") as fh:
        fh.write("package org.springframework.samples.petclinic.owner;\n"
                 "public class NewFeature { public int answer() { return 42; } }\n")
    git("add", "-N", relpath)
    diff = git("diff", "--unified=0", "--", relpath)
    git("rm", "-f", "--cached", relpath)
    os.remove(path)
    return diff


SCENARIOS = [
    ("A", "precise — change OwnerRestController:53 (a covered line)",
     "modify", "src/main/java/org/springframework/samples/petclinic/owner/OwnerRestController.java", 53),
    ("B", "module-scoped — change VetRestController:40",
     "modify", "src/main/java/org/springframework/samples/petclinic/vet/VetRestController.java", 40),
    ("C", "security — change JwtUtil:56",
     "modify", "src/main/java/org/springframework/samples/petclinic/security/JwtUtil.java", 56),
    ("D", "shared model fan-out — change Owner.java:94",
     "modify", "src/main/java/org/springframework/samples/petclinic/owner/Owner.java", 94),
    ("E", "BLIND SPOT — change SecurityConfig:50 (no black-box test covers it)",
     "modify", "src/main/java/org/springframework/samples/petclinic/security/SecurityConfig.java", 50),
    ("F", "non-code change (application.properties) → conservative select-all",
     "noncode", "src/main/resources/application.properties", None),
    ("G", "new .java file → conservative select-all (no prior coverage)",
     "newfile", "src/main/java/org/springframework/samples/petclinic/owner/NewFeature.java", None),
]

results = []
for sid, title, kind, relpath, line in SCENARIOS:
    if kind == "modify":
        diff = modify_line(relpath, line)
    elif kind == "noncode":
        diff = append_lines(relpath)
    else:
        diff = new_file(relpath)
    selected, reasons, raw = impact(diff)
    diff_excerpt = "\n".join(
        l for l in diff.splitlines()
        if l.startswith("@@") or (l[:1] in "+-" and not l.startswith(("+++", "---"))))[:600]
    n = len(selected)
    conservative = any("보수적" in r or "conservative" in r.lower() for r in reasons)
    results.append({
        "id": sid, "title": title, "kind": kind, "target": relpath, "line": line,
        "diff": diff_excerpt, "selected": selected, "reasons": reasons,
        "count": n, "total": TOTAL, "conservative": conservative,
    })
    print(f"\n=== SCENARIO {sid} — {title} ===")
    print(diff_excerpt[:300])
    print(f"→ selected {n}{' (CONSERVATIVE all-indexed)' if conservative else ''}")
    for s in selected:
        print(f"   {s['confidence']}\t{s['testId']}")
    for r in reasons:
        print(f"   # {r}")

with open(OUT, "w") as fh:
    json.dump(results, fh, indent=2, ensure_ascii=False)
print(f"\nwrote {OUT}: {len(results)} scenarios")

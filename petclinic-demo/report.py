#!/usr/bin/env python3
"""Render the full set of TIA views from the indexed testwise coverage.

Produces, to stdout (Markdown):
  1. Per-test impact range   (each test -> production files/lines it exercises)
  2. Reverse selection index  (each production file -> guarding tests)
  3. Coverage hotspots / reach stats
  4. Black-box blind spots    (production files no black-box test touches)
"""
import json
import sys

TESTWISE = sys.argv[1]
PROD_FILES = sys.argv[2]  # newline list of package-relative prod .java paths

with open(TESTWISE) as fh:
    tests = json.load(fh)["tests"]


def lines_of(covered):
    n = 0
    for part in covered.split(","):
        if not part:
            continue
        if "-" in part:
            lo, hi = part.split("-")
            n += int(hi) - int(lo) + 1
        else:
            n += 1
    return n


# Flatten: test -> {file: lineCount}
per_test = {}
for t in tests:
    fm = {}
    for p in t["paths"]:
        for f in p["files"]:
            full = f"{p['path']}/{f['fileName']}"
            fm[full] = lines_of(f["coveredLines"])
    per_test[t["uniformPath"]] = {"result": t["result"], "files": fm}

# Reverse: file -> set(tests)
rev = {}
for tid, d in per_test.items():
    for f in d["files"]:
        rev.setdefault(f, set()).add(tid)

print("## 1. Per-test impact range — what each black-box test actually exercises\n")
print("| Test case | Result | Prod files | Covered lines | Top files (lines) |")
print("|---|---|--:|--:|---|")
for tid in sorted(per_test):
    d = per_test[tid]
    files = d["files"]
    total = sum(files.values())
    top = sorted(files.items(), key=lambda kv: -kv[1])[:3]
    top_s = ", ".join(f"`{f.split('/')[-1]}`({n})" for f, n in top) or "—"
    print(f"| `{tid}` | {d['result']} | {len(files)} | {total} | {top_s} |")

print("\n## 2. Reverse selection index — touch a file, these tests must run\n")
print("| Production file | # guarding tests | Tests |")
print("|---|--:|---|")
for f in sorted(rev, key=lambda k: (-len(rev[k]), k)):
    short = f.replace("org/springframework/samples/petclinic/", "…/")
    tids = ", ".join(f"`{t.split('#')[0][:-len('BlackBoxIT')] or t}#{t.split('#')[1]}`"
                     if "#" in t else t for t in sorted(rev[f]))
    print(f"| `{short}` | {len(rev[f])} | {tids} |")

print("\n## 3. Coverage hotspots & reach\n")
reach = sorted(per_test.items(), key=lambda kv: -len(kv[1]["files"]))
print("**Widest-reach tests** (most production files touched):")
for tid, d in reach[:5]:
    print(f"- `{tid}` → {len(d['files'])} files, {sum(d['files'].values())} lines")
print("\n**Narrowest-reach tests:**")
for tid, d in reach[-5:]:
    print(f"- `{tid}` → {len(d['files'])} files, {sum(d['files'].values())} lines")
print("\n**Most-shared production files** (highest fan-in = riskiest to change):")
for f in sorted(rev, key=lambda k: -len(rev[k]))[:8]:
    short = f.replace("org/springframework/samples/petclinic/", "…/")
    print(f"- `{short}` ← guarded by {len(rev[f])} tests")

with open(PROD_FILES) as fh:
    prod = [l.strip() for l in fh if l.strip().endswith(".java")]
covered_files = set(rev)
blind = [p for p in prod if p not in covered_files]
print(f"\n## 4. Black-box blind spots — {len(blind)}/{len(prod)} production files "
      f"untouched by any black-box test\n")
for p in sorted(blind):
    short = p.replace("org/springframework/samples/petclinic/", "…/")
    print(f"- `{short}`")

print(f"\n---\n**Totals:** {len(per_test)} tests · "
      f"{len(covered_files)} production files covered · "
      f"{sum(sum(d['files'].values()) for d in per_test.values())} (test·line) coverage points")

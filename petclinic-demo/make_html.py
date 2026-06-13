#!/usr/bin/env python3
"""Build a self-contained interactive HTML TIA report.

Inputs (all already produced by the pipeline):
  testwise.json   — per-test coverage (views 1,2,3,5)
  scenarios.json  — tia impact scenarios (view 4)
  flaky.json      — {"real": {...}, "synthetic": {...}}
  prod_files.txt  — package-relative production .java paths (blind-spot denominator)

Usage: make_html.py <testwise.json> <scenarios.json> <flaky.json> <prod_files.txt> <commit> <out.html>
"""
import json
import sys

TESTWISE, SCEN, FLAKY, PROD, COMMIT, OUT = sys.argv[1:7]
PREFIX = "org/springframework/samples/petclinic/"


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


tests = json.load(open(TESTWISE))["tests"]
scenarios = json.load(open(SCEN))
flaky = json.load(open(FLAKY))
prod = [l.strip() for l in open(PROD) if l.strip().endswith(".java")]

per_test, rev = {}, {}
for t in tests:
    fm = {}
    for p in t["paths"]:
        for f in p["files"]:
            full = f"{p['path']}/{f['fileName']}"
            fm[full] = lines_of(f["coveredLines"])
            rev.setdefault(full, []).append(t["uniformPath"])
    per_test[t["uniformPath"]] = {"result": t["result"], "files": fm,
                                  "total": sum(fm.values()), "nfiles": len(fm)}

covered_files = set(rev)
blind = sorted(p for p in prod if p not in covered_files)
short = lambda f: f.replace(PREFIX, "…/")

model = {
    "commit": COMMIT,
    "perTest": [{"id": tid, "result": d["result"], "nfiles": d["nfiles"],
                 "lines": d["total"],
                 "files": [{"f": short(f), "n": n} for f, n in
                           sorted(d["files"].items(), key=lambda kv: -kv[1])]}
                for tid, d in sorted(per_test.items())],
    "reverse": [{"file": short(f), "n": len(ts), "tests": sorted(ts)}
                for f, ts in sorted(rev.items(), key=lambda kv: (-len(kv[1]), kv[0]))],
    "scenarios": scenarios,
    "flaky": flaky,
    "blind": [short(p) for p in blind],
    "nProd": len(prod), "nCovered": len(covered_files),
    "nTests": len(per_test),
    "totalPoints": sum(d["total"] for d in per_test.values()),
}

HTML = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TIA report · spring-petclinic blackbox</title>
<style>
:root{--bg:#0d1117;--panel:#161b22;--line:#30363d;--fg:#e6edf3;--mut:#8b949e;
--acc:#58a6ff;--ok:#3fb950;--warn:#d29922;--bad:#f85149;--det:#388bfd;--con:#d29922}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);
font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
header{padding:24px 28px;border-bottom:1px solid var(--line);background:var(--panel)}
h1{margin:0 0 4px;font-size:20px}.sub{color:var(--mut);font-size:13px}
.kpis{display:flex;gap:14px;flex-wrap:wrap;margin-top:16px}
.kpi{background:var(--bg);border:1px solid var(--line);border-radius:10px;padding:12px 16px;min-width:120px}
.kpi b{display:block;font-size:24px}.kpi span{color:var(--mut);font-size:12px}
nav{display:flex;gap:4px;padding:0 20px;background:var(--panel);border-bottom:1px solid var(--line);flex-wrap:wrap}
nav button{background:none;border:0;color:var(--mut);padding:12px 16px;cursor:pointer;font-size:14px;border-bottom:2px solid transparent}
nav button.on{color:var(--fg);border-bottom-color:var(--acc)}
main{padding:24px 28px;max-width:1180px}section{display:none}section.on{display:block}
h2{font-size:16px;margin:0 0 6px}.hint{color:var(--mut);margin:0 0 16px;font-size:13px}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{text-align:left;padding:8px 10px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--mut);font-weight:600;cursor:pointer;user-select:none;position:sticky;top:0;background:var(--panel)}
tr:hover td{background:#1c2230}.num{text-align:right;font-variant-numeric:tabular-nums}
code,.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
.tag{display:inline-block;padding:1px 7px;border-radius:20px;font-size:11px;font-weight:600}
.t-det{background:rgba(56,139,253,.15);color:var(--det)}.t-con{background:rgba(210,153,34,.15);color:var(--con)}
.t-pass{background:rgba(63,185,80,.13);color:var(--ok)}.bar{height:6px;background:var(--bg);border-radius:3px;overflow:hidden;min-width:60px}
.bar i{display:block;height:100%;background:var(--acc)}
.chips{display:flex;flex-wrap:wrap;gap:4px}.chip{background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:2px 7px;font-size:11px}
input.search{width:100%;max-width:340px;padding:8px 10px;background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:8px;margin-bottom:14px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px 18px;margin-bottom:16px}
.card.warn{border-color:var(--warn)}.card.bad{border-color:var(--bad)}
.scen-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.big{font-size:22px;font-weight:700}.redu{color:var(--ok);font-weight:700}
pre{background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:10px;overflow:auto;font-size:12px;margin:10px 0}
.add{color:var(--ok)}.del{color:var(--bad)}.gutter{color:var(--mut)}
.note{background:rgba(210,153,34,.08);border-left:3px solid var(--warn);padding:10px 14px;border-radius:0 8px 8px 0;margin:8px 0}
.flex{display:flex;gap:18px;flex-wrap:wrap}.grow{flex:1;min-width:280px}
.blindgrid{columns:2;column-gap:24px}.blindgrid div{break-inside:avoid;padding:3px 0}
.foot{color:var(--mut);font-size:12px;padding:20px 28px;border-top:1px solid var(--line)}
</style></head><body>
<header>
<h1>Test-Impact-Analysis · spring-petclinic black-box suite</h1>
<div class="sub">indexed commit <code id="commit"></code> · out-of-process REST Assured + parallel-per-test-coverage agent · all data is real end-to-end measurement</div>
<div class="kpis" id="kpis"></div>
</header>
<nav id="nav"></nav>
<main>
<section id="s-pertest"><h2>1 · Per-test impact range</h2>
<p class="hint">각 블랙박스 테스트가 실제로 실행한 프로덕션 파일/라인. 행 클릭 시 파일 목록 펼침. 헤더 클릭 정렬.</p>
<input class="search" id="ptq" placeholder="filter tests…"><div id="pttbl"></div></section>
<section id="s-reverse"><h2>2 · Reverse selection index</h2>
<p class="hint">"이 파일을 고치면 이 테스트들을 돌려라." fan-in이 높을수록 변경 위험 큰 핫스팟.</p>
<input class="search" id="rvq" placeholder="filter files…"><div id="rvtbl"></div></section>
<section id="s-impact"><h2>3 · tia impact — 실제 diff 기반 선별</h2>
<p class="hint">인덱싱 HEAD에 대한 diff → 커버리지와 교차. DETERMINISTIC=정밀 히트, CONSERVATIVE=매핑 불가 시 안전 전체선택.</p>
<div id="scen"></div></section>
<section id="s-flaky"><h2>4 · Flaky detection</h2><div id="flaky"></div></section>
<section id="s-blind"><h2>5 · Coverage blind spots</h2>
<p class="hint" id="blindhint"></p><div class="blindgrid" id="blindlist"></div>
<div class="note"><b>왜 중요한가:</b> 블랙박스 스위트는 <code>/api/**</code> REST 계층을 지키지만, 여기 나열된 파일들은 어떤 테스트도 건드리지 않는다. 이 파일들에 대한 <em>수정</em>은 DETERMINISTIC 선별에서 0개를 반환한다(시나리오 E 참조).</div>
</section>
</main>
<div class="foot">Generated by <code>make_html.py</code> from <code>testwise.json · scenarios.json · flaky.json</code>. Reproduce with <code>run-petclinic-tia.sh</code>.</div>
<script>
const D = __DATA__;
document.getElementById('commit').textContent = D.commit.slice(0,12);
const pct = n => Math.round((1 - n/D.nTests)*100);
document.getElementById('kpis').innerHTML = [
 ['black-box tests', D.nTests],['prod files covered', D.nCovered],
 ['prod files blind', D.blind.length],['(test·line) points', D.totalPoints]
].map(([l,v])=>`<div class="kpi"><b>${v}</b><span>${l}</span></div>`).join('');

const TABS=[['s-pertest','1 · Per-test range'],['s-reverse','2 · Reverse index'],
['s-impact','3 · tia impact'],['s-flaky','4 · Flaky'],['s-blind','5 · Blind spots']];
const nav=document.getElementById('nav');
TABS.forEach(([id,l],i)=>{const b=document.createElement('button');b.textContent=l;b.onclick=()=>{
 document.querySelectorAll('nav button').forEach(x=>x.classList.remove('on'));
 document.querySelectorAll('section').forEach(x=>x.classList.remove('on'));
 b.classList.add('on');document.getElementById(id).classList.add('on');};
 if(i===0)b.classList.add('on');nav.appendChild(b);});
document.getElementById('s-pertest').classList.add('on');

// ---- 1. per-test ----
function ptRows(q){q=(q||'').toLowerCase();
 return D.perTest.filter(t=>t.id.toLowerCase().includes(q)).map(t=>{
  const max=Math.max(...D.perTest.map(x=>x.lines),1);
  const files=t.files.map(f=>`<span class="chip">${f.f.split('/').pop()} <b>${f.n}</b></span>`).join('');
  return `<tr class="exp"><td><code>${t.id}</code></td>
   <td><span class="tag t-pass">${t.result}</span></td>
   <td class="num">${t.nfiles}</td>
   <td class="num">${t.lines}</td>
   <td><div class="bar"><i style="width:${100*t.lines/max}%"></i></div></td></tr>
   <tr class="det" style="display:none"><td colspan="5"><div class="chips">${files||'<span class=gutter>no attributed coverage</span>'}</div></td></tr>`;
 }).join('')}
function drawPt(q){document.getElementById('pttbl').innerHTML=
 `<table><thead><tr><th data-k="id">Test</th><th>Result</th><th class="num" data-k="nfiles">Files</th><th class="num" data-k="lines">Lines</th><th>reach</th></tr></thead><tbody>${ptRows(q)}</tbody></table>`;
 document.querySelectorAll('#pttbl tr.exp').forEach(r=>r.onclick=()=>{const d=r.nextElementSibling;d.style.display=d.style.display==='none'?'':'none';});
 document.querySelectorAll('#pttbl th[data-k]').forEach(th=>th.onclick=()=>{const k=th.dataset.k;
  D.perTest.sort((a,b)=>typeof a[k]==='string'?a[k].localeCompare(b[k]):b[k]-a[k]);drawPt(document.getElementById('ptq').value);});}
document.getElementById('ptq').oninput=e=>drawPt(e.target.value);drawPt('');

// ---- 2. reverse ----
function drawRv(q){q=(q||'').toLowerCase();
 const rows=D.reverse.filter(r=>r.file.toLowerCase().includes(q)).map(r=>{
  const max=Math.max(...D.reverse.map(x=>x.n),1);
  const chips=r.tests.map(t=>`<span class="chip">${t.replace('BlackBoxIT','')}</span>`).join('');
  return `<tr class="exp"><td><code>${r.file}</code></td><td class="num">${r.n}</td>
   <td><div class="bar"><i style="width:${100*r.n/max}%"></i></div></td></tr>
   <tr class="det" style="display:none"><td colspan="3"><div class="chips">${chips}</div></td></tr>`;}).join('');
 document.getElementById('rvtbl').innerHTML=`<table><thead><tr><th>Production file</th><th class="num">#tests</th><th>fan-in</th></tr></thead><tbody>${rows}</tbody></table>`;
 document.querySelectorAll('#rvtbl tr.exp').forEach(r=>r.onclick=()=>{const d=r.nextElementSibling;d.style.display=d.style.display==='none'?'':'none';});}
document.getElementById('rvq').oninput=e=>drawRv(e.target.value);drawRv('');

// ---- 3. scenarios ----
function fmtDiff(s){return s.split('\\n').map(l=>{
 if(l.startsWith('@@'))return `<span class="gutter">${esc(l)}</span>`;
 if(l.startsWith('+'))return `<span class="add">${esc(l)}</span>`;
 if(l.startsWith('-'))return `<span class="del">${esc(l)}</span>`;return esc(l);}).join('\\n');}
function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
document.getElementById('scen').innerHTML=D.scenarios.map(s=>{
 const blind=s.id==='E', cons=s.conservative;
 const cls=blind?'card bad':(cons?'card warn':'card');
 const sel=s.selected.map(x=>`<tr><td><span class="tag ${x.confidence==='DETERMINISTIC'?'t-det':'t-con'}">${x.confidence}</span></td><td><code>${x.testId}</code></td></tr>`).join('')
   ||'<tr><td colspan=2 class="gutter">— 선별된 테스트 없음 —</td></tr>';
 const redu = cons? '<span class="redu">safe fallback</span>'
   : (blind? '<span class="del">0 selected ⚠ blind spot</span>'
   : `<span class="redu">${pct(s.count)}% reduction</span> · ${s.count}/${s.total} selected`);
 const reasons=s.reasons.map(r=>`<div class="note">${esc(r)}</div>`).join('');
 return `<div class="${cls}"><div class="scen-head"><span class="big">${s.id}</span>
   <span>${esc(s.title)}</span></div>
   <div style="margin:8px 0">${redu}</div>
   <pre>${fmtDiff(s.diff)}</pre>
   <table><tbody>${sel}</tbody></table>${reasons}</div>`;}).join('');

// ---- 4. flaky ----
const fr=D.flaky.real, fs=D.flaky.synthetic;
document.getElementById('flaky').innerHTML=`
 <div class="flex">
  <div class="card grow"><h2 style="margin-top:0">Real · ${fr.runs} consecutive runs (parallelism 8)</h2>
   <p class="hint">실측. 동시성 테스트 포함.</p>
   <div class="big" style="color:var(--ok)">flaky ratio ${fr.ratio.toFixed(3)}</div>
   <div class="hint">${fr.flaky.length}/${fr.total} flaky → 스위트는 결정론적(deterministic)</div></div>
  <div class="card grow warn"><h2 style="margin-top:0">Mechanism check · synthetic (labeled)</h2>
   <p class="hint">한 테스트를 P/F/P로 강제 → 탐지 동작 검증.</p>
   <div class="big" style="color:var(--warn)">flaky ratio ${fs.ratio.toFixed(3)}</div>
   <div class="chips" style="margin-top:8px">${fs.flaky.map(t=>`<span class="chip" style="border-color:var(--warn)">FLAKY · ${t}</span>`).join('')}</div></div>
 </div>`;

// ---- 5. blind ----
document.getElementById('blindhint').textContent=`프로덕션 ${D.nProd}개 중 ${D.blind.length}개 파일이 어떤 블랙박스 테스트에도 닿지 않음 (${D.nCovered}개만 커버).`;
document.getElementById('blindlist').innerHTML=D.blind.map(f=>`<div><code>${f}</code></div>`).join('');
</script></body></html>"""

open(OUT, "w").write(HTML.replace("__DATA__", json.dumps(model, ensure_ascii=False)))
print(f"wrote {OUT}  ({len(model['perTest'])} tests, {len(model['scenarios'])} scenarios, "
      f"{len(model['blind'])} blind spots)")

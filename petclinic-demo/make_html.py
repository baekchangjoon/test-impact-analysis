#!/usr/bin/env python3
"""Build a self-contained interactive HTML TIA report.

DEPRECATED (D0): superseded by `tia report` (Java port; same template + identical
__DATA__ model — verified canonically equal). Kept for reference/demo. The HTML template
here is the source of truth that `tia report` embeds verbatim (tia-core resource
report-template.html). `run-petclinic-tia.sh` now uses `tia report`.

Inputs:
  testwise.json   — per-test coverage (tabs 1,2,5). REQUIRED.
  scenarios.json  — tia impact scenarios (tab 3). Optional — pass "-" to omit.
  flaky.json      — {"real": {...}, "synthetic": {...}} (tab 4). Optional — pass "-".
  prod_files.txt  — package-relative production .java paths (tab 5 denominator). Optional — "-".

Optional inputs degrade gracefully: an omitted ("-") or missing scenarios/flaky/prod file
renders a "데이터 없음" placeholder for that tab instead of failing. So testwise.json alone
("bring your own coverage") still yields tabs 1·2·5. See petclinic-demo/README.md.

Usage:
  make_html.py <testwise.json> <scenarios.json|-> <flaky.json|-> <prod_files.txt|-> \\
               <commit> <out.html> [sut_name] [jacoco_rel_dir] [test_src_root]

Optional trailing args (enable cross-links):
  sut_name       — name of the System Under Test, used in the title/header
                   (default: "spring-petclinic")
  jacoco_rel_dir — path (relative to out.html, or absolute) of the JaCoCo HTML
                   report root. Per-test file chips link into it. (default: "jacoco")
  test_src_root  — root of the SUT test sources. Test ids are linked to the local
                   .java file (file:// link) so the test can be opened. (default: none)
"""
import json
import os
import sys

args = sys.argv[1:]
TESTWISE, SCEN, FLAKY, PROD, COMMIT, OUT = args[:6]
SUT = args[6] if len(args) > 6 and args[6] else "spring-petclinic"
JACOCO = args[7] if len(args) > 7 and args[7] else "jacoco"
TEST_SRC = args[8] if len(args) > 8 else ""
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


def first_line(covered):
    for part in covered.split(","):
        if part:
            return int(part.split("-")[0])
    return 1


def optional(path):
    """A positional input that may be absent: "-" (or "") or a missing file → None.

    A non-"-" path that doesn't exist is almost certainly a typo, so warn (but still
    degrade gracefully) rather than silently rendering an empty tab."""
    if not path or path == "-":
        return None
    if not os.path.exists(path):
        print(f"warning: input {path!r} not found — that tab will be empty", file=sys.stderr)
        return None
    return path


with open(TESTWISE) as fh:
    tests = json.load(fh)["tests"]
# scenarios.json (tab 3) and flaky.json (tab 4) are optional: pass "-" to omit them
# and the report renders a "데이터 없음" placeholder for that tab instead of crashing.
# This is the "bring your own coverage" path — testwise.json alone yields tabs 1·2·5.
scenarios = json.load(open(p)) if (p := optional(SCEN)) else []
flaky = json.load(open(p)) if (p := optional(FLAKY)) else None
prod = ([l.strip() for l in open(p) if l.strip().endswith(".java")]
        if (p := optional(PROD)) else [])

# map: simple test class name -> absolute .java path (for file:// open-local links)
test_src = {}
if TEST_SRC and os.path.isdir(TEST_SRC):
    for root, _, fnames in os.walk(TEST_SRC):
        for fn in fnames:
            if fn.endswith(".java"):
                test_src.setdefault(fn[:-5], os.path.abspath(os.path.join(root, fn)))

per_test, rev = {}, {}
for t in tests:
    files = []
    for p in t["paths"]:
        pkg = p["path"]                      # slash form: org/.../owner
        for f in p["files"]:
            fname = f["fileName"]
            full = f"{pkg}/{fname}"
            n = lines_of(f["coveredLines"])
            files.append({"full": full, "pkg": pkg.replace("/", "."),
                          "file": fname, "n": n, "line": first_line(f["coveredLines"])})
            rev.setdefault(full, []).append(t["uniformPath"])
    files.sort(key=lambda x: -x["n"])
    per_test[t["uniformPath"]] = {"result": t["result"], "files": files,
                                  "total": sum(x["n"] for x in files), "nfiles": len(files)}

covered_files = set(rev)
blind = sorted(p for p in prod if p not in covered_files)
short = lambda f: f.replace(PREFIX, "…/")


def rev_entry(full, ts):
    pkg, _, fname = full.rpartition("/")
    return {"file": short(full), "pkg": pkg.replace("/", "."), "fname": fname,
            "n": len(ts), "tests": sorted(ts)}


model = {
    "commit": COMMIT,
    "sut": SUT,
    "jacoco": JACOCO,
    "testSrc": test_src,
    "perTest": [{"id": tid, "result": d["result"], "nfiles": d["nfiles"],
                 "lines": d["total"],
                 "files": [{"f": short(x["full"]), "file": x["file"], "pkg": x["pkg"],
                            "n": x["n"], "line": x["line"]} for x in d["files"]]}
                for tid, d in sorted(per_test.items())],
    "reverse": [rev_entry(f, ts)
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
<title>TIA report · __SUT__ blackbox</title>
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
th{color:var(--mut);font-weight:600;cursor:pointer;user-select:none;position:sticky;top:0;background:var(--panel);white-space:nowrap}
th:hover{color:var(--fg)}th .arr{color:var(--acc);font-size:11px}
tr:hover td{background:#1c2230}.num{text-align:right;font-variant-numeric:tabular-nums}
code,.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
.tag{display:inline-block;padding:1px 7px;border-radius:20px;font-size:11px;font-weight:600}
.t-det{background:rgba(56,139,253,.15);color:var(--det)}.t-con{background:rgba(210,153,34,.15);color:var(--con)}
.t-pass{background:rgba(63,185,80,.13);color:var(--ok)}.bar{height:6px;background:var(--bg);border-radius:3px;overflow:hidden;min-width:60px}
.bar i{display:block;height:100%;background:var(--acc)}
.chips{display:flex;flex-wrap:wrap;gap:4px}.chip{background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:2px 7px;font-size:11px;color:var(--fg);text-decoration:none;display:inline-block}
a.chip{cursor:pointer}a.chip:hover{border-color:var(--acc);color:var(--acc)}a.chip b{color:inherit}
input.search{width:100%;max-width:340px;padding:8px 10px;background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:8px;margin-bottom:14px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px 18px;margin-bottom:16px}
.card.warn{border-color:var(--warn)}.card.bad{border-color:var(--bad)}
.legend{background:var(--bg);border:1px solid var(--line);border-radius:12px;padding:14px 18px;margin-bottom:18px}
.legend h3{margin:0 0 10px;font-size:13px;color:var(--mut);text-transform:uppercase;letter-spacing:.04em}
.legend dl{margin:0;display:grid;grid-template-columns:max-content 1fr;gap:8px 14px;align-items:baseline}
.legend dt{margin:0;white-space:nowrap}.legend dd{margin:0;color:var(--mut);font-size:13px}
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
<h1>Test-Impact-Analysis · <span id="sut"></span> black-box suite</h1>
<div class="sub">indexed commit <code id="commit"></code> · out-of-process REST Assured + parallel-per-test-coverage agent · all data is real end-to-end measurement</div>
<div class="kpis" id="kpis"></div>
</header>
<nav id="nav"></nav>
<main>
<section id="s-pertest"><h2>1 · Per-test impact range</h2>
<p class="hint">각 블랙박스 테스트가 실제로 실행한 프로덕션 파일/라인. 행 클릭 시 파일 목록 펼침 — 파일명을 클릭하면 JaCoCo 커버리지 HTML의 해당 라인으로 이동. 헤더 클릭으로 오름차순/내림차순 정렬.</p>
<input class="search" id="ptq" placeholder="filter tests…"><div id="pttbl"></div></section>
<section id="s-reverse"><h2>2 · Reverse selection index</h2>
<p class="hint">"이 파일을 고치면 이 테스트들을 돌려라." fan-in이 높을수록 변경 위험 큰 핫스팟. 행 클릭 시 테스트 목록 펼침 — 테스트명을 클릭하면 로컬 테스트 소스 파일을 연다. 헤더 클릭으로 오름차순/내림차순 정렬.</p>
<input class="search" id="rvq" placeholder="filter files…"><div id="rvtbl"></div></section>
<section id="s-impact"><h2>3 · tia impact — diff 기반 테스트 선별</h2>
<p class="hint"><b>베이스라인 커밋</b>(<code id="impact-base"></code> — <code>tia index</code>로 per-test 커버리지를 DB에 저장한 시점)에 <b>적용하려는 변경(diff)</b>을 비교하고, 그 변경 라인을 커버하는 테스트만 골라낸다.</p>
<div class="note">📌 <b>무엇과 무엇을 비교하나?</b> <code>origin HEAD ↔ local HEAD</code> 같은 고정 비교가 <b>아니다</b>. 베이스라인은 위의 인덱싱된 커밋이고, 비교 대상은 "지금 평가하려는 diff" — 커밋 전 워킹트리, 브랜치 vs 베이스라인, PR diff 등 무엇이든 된다. <b>이 데모에서는</b> 시나리오마다 대상 파일/라인에 <code>// tia-probe</code> 한 줄을 넣은 <b>합성 diff</b>를 베이스라인과 비교했다(perturb-and-revert — 워킹트리 변경은 즉시 복원). <b>선별 규칙:</b> diff의 변경 라인 ∩ 각 테스트의 커버 라인 ≠ ∅ → 🎯 정밀 선별.</div>
<div class="legend"><h3>용어 설명</h3><dl>
<dt><span class="tag t-det">🎯 정밀 선별</span></dt><dd><b>DETERMINISTIC</b> — 변경된 코드 라인이 이 테스트의 커버리지 기록에 <b>정확히 포함</b>되어 있음. 즉 이 변경이 직접 실행하는 테스트.</dd>
<dt><span class="tag t-con">🛡 보수적 선별</span></dt><dd><b>CONSERVATIVE</b> — 변경을 특정 커버리지에 <b>매핑할 수 없어</b>(새 파일·설정 변경 등) 누락을 막기 위해 <b>안전하게 후보로 포함</b>. fallback.</dd>
<dt><span class="redu">▼ NN% 절감</span></dt><dd>전체 테스트 중 <b>실행을 건너뛸 수 있는 비율</b>. 높을수록 TIA 이득이 큼.</dd>
<dt><span class="redu">🛡 safe fallback</span></dt><dd>보수적 선별로 전체를 포함한 경우 — 절감은 없지만 누락 위험 0.</dd>
<dt><span class="del">⚠ 0개 선별</span></dt><dd>어떤 테스트도 닿지 않는 파일을 변경 → 선별 0개. <b>커버리지 사각지대</b>(탭 5 참조).</dd>
</dl></div>
<div id="scen"></div></section>
<section id="s-flaky"><h2>4 · Flaky detection</h2><div id="flaky"></div></section>
<section id="s-blind"><h2>5 · Coverage blind spots</h2>
<p class="hint" id="blindhint"></p><div class="blindgrid" id="blindlist"></div>
<div class="note"><b>왜 중요한가:</b> 블랙박스 스위트는 <code>/api/**</code> REST 계층을 지키지만, 여기 나열된 파일들은 어떤 테스트도 건드리지 않는다. 이 파일들에 대한 <em>수정</em>은 정밀 선별(DETERMINISTIC)에서 0개를 반환한다(시나리오 E 참조).</div>
</section>
</main>
<div class="foot">Generated by <code>make_html.py</code> from <code>testwise.json · scenarios.json · flaky.json</code>. Reproduce with <code>run-petclinic-tia.sh</code>.</div>
<script>
const D = __DATA__;
document.getElementById('sut').textContent = D.sut;
document.title = `TIA report · ${D.sut} blackbox`;
document.getElementById('commit').textContent = D.commit.slice(0,12);
document.getElementById('impact-base').textContent = D.commit.slice(0,12);
const pct = n => Math.round((1 - n/D.nTests)*100);
const esc = s => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
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

// ---- cross-link helpers ----
const jacocoHref = f => `${D.jacoco}/${f.pkg}/${f.file}.html#L${f.line}`;
const testSrcHref = id => {const cls=id.split('#')[0];const p=D.testSrc[cls];return p?('file://'+p):null;};

// ---- generic sortable header (asc/desc toggle on every column) ----
function headHtml(cols, sort){
 // arrow on the clicked column only (sort.i); "reach"/"fan-in" share a key with
 // Lines/#tests so keying off sort.k alone would mark two columns at once.
 return '<tr>'+cols.map((c,i)=>{
  const arr = sort.i===i ? `<span class="arr">${sort.dir>0?'▲':'▼'}</span>` : '';
  return `<th class="${c.num?'num':''}" data-k="${c.k}" data-i="${i}">${c.label} ${arr}</th>`;
 }).join('')+'</tr>';
}
function applySort(arr, sort){
 const k=sort.k, dir=sort.dir;
 arr.sort((a,b)=>{const av=a[k],bv=b[k];
  if(typeof av==='string'||typeof bv==='string') return dir*String(av).localeCompare(String(bv));
  return dir*(av-bv);});
}
function wireSort(sel, cols, sort, data, redraw){
 document.querySelectorAll(sel+' th[data-k]').forEach(th=>th.onclick=()=>{
  const k=th.dataset.k, i=+th.dataset.i;
  if(sort.i===i) sort.dir*=-1;                                   // same column → flip direction
  else {sort.k=k; sort.i=i; sort.dir = (typeof data[0][k]==='string')?1:-1;} // new column → sensible default
  applySort(data, sort); redraw();
 });
}

// ---- 1. per-test ----
const PT_COLS=[{k:'id',label:'Test'},{k:'result',label:'Result'},
 {k:'nfiles',label:'Files',num:1},{k:'lines',label:'Lines',num:1},{k:'lines',label:'reach'}];
const ptSort={k:'id',i:0,dir:1};
function ptRows(q){q=(q||'').toLowerCase();
 const max=Math.max(...D.perTest.map(x=>x.lines),1);
 return D.perTest.filter(t=>t.id.toLowerCase().includes(q)).map(t=>{
  const files=t.files.map(f=>`<a class="chip" href="${esc(jacocoHref(f))}" target="_blank" title="JaCoCo 커버리지: ${esc(f.file)}:${f.line}"><span class="mono">${esc(f.file)}</span> <b>${f.n}</b></a>`).join('');
  return `<tr class="exp"><td><code>${esc(t.id)}</code></td>
   <td><span class="tag t-pass">${esc(t.result)}</span></td>
   <td class="num">${t.nfiles}</td>
   <td class="num">${t.lines}</td>
   <td><div class="bar"><i style="width:${100*t.lines/max}%"></i></div></td></tr>
   <tr class="det" style="display:none"><td colspan="5"><div class="chips">${files||'<span class=gutter>no attributed coverage</span>'}</div></td></tr>`;
 }).join('')}
function drawPt(){
 document.getElementById('pttbl').innerHTML=
 `<table><thead>${headHtml(PT_COLS,ptSort)}</thead><tbody>${ptRows(document.getElementById('ptq').value)}</tbody></table>`;
 document.querySelectorAll('#pttbl tr.exp').forEach(r=>r.onclick=e=>{
  if(e.target.closest('a'))return; const d=r.nextElementSibling;d.style.display=d.style.display==='none'?'':'none';});
 wireSort('#pttbl', PT_COLS, ptSort, D.perTest, drawPt);}
document.getElementById('ptq').oninput=drawPt;drawPt();

// ---- 2. reverse ----
const RV_COLS=[{k:'file',label:'Production file'},{k:'n',label:'#tests',num:1},{k:'n',label:'fan-in'}];
const rvSort={k:'n',i:1,dir:-1};
function rvRows(q){q=(q||'').toLowerCase();
 const max=Math.max(...D.reverse.map(x=>x.n),1);
 return D.reverse.filter(r=>r.file.toLowerCase().includes(q)).map(r=>{
  const chips=r.tests.map(t=>{const href=testSrcHref(t);const label=esc(t.replace('BlackBoxIT',''));
   return href?`<a class="chip" href="${esc(href)}" title="로컬 테스트 소스 열기: ${esc(t)}">${label}</a>`:`<span class="chip">${label}</span>`;}).join('');
  return `<tr class="exp"><td><code>${esc(r.file)}</code></td><td class="num">${r.n}</td>
   <td><div class="bar"><i style="width:${100*r.n/max}%"></i></div></td></tr>
   <tr class="det" style="display:none"><td colspan="3"><div class="chips">${chips}</div></td></tr>`;}).join('');}
function drawRv(){
 document.getElementById('rvtbl').innerHTML=`<table><thead>${headHtml(RV_COLS,rvSort)}</thead><tbody>${rvRows(document.getElementById('rvq').value)}</tbody></table>`;
 document.querySelectorAll('#rvtbl tr.exp').forEach(r=>r.onclick=e=>{
  if(e.target.closest('a'))return; const d=r.nextElementSibling;d.style.display=d.style.display==='none'?'':'none';});
 wireSort('#rvtbl', RV_COLS, rvSort, D.reverse, drawRv);}
document.getElementById('rvq').oninput=drawRv;drawRv();

// ---- 3. scenarios ----
const CONF={DETERMINISTIC:{cls:'t-det',label:'🎯 정밀 선별',title:'DETERMINISTIC: 변경 라인이 이 테스트의 커버리지에 정확히 매핑됨'},
            CONSERVATIVE:{cls:'t-con',label:'🛡 보수적 선별',title:'CONSERVATIVE: 매핑 불가 → 안전하게 후보 포함'}};
function fmtDiff(s){return s.split('\\n').map(l=>{
 if(l.startsWith('@@'))return `<span class="gutter">${esc(l)}</span>`;
 if(l.startsWith('+'))return `<span class="add">${esc(l)}</span>`;
 if(l.startsWith('-'))return `<span class="del">${esc(l)}</span>`;return esc(l);}).join('\\n');}
document.getElementById('scen').innerHTML=D.scenarios.length? D.scenarios.map(s=>{
 const blind=s.id==='E', cons=s.conservative;
 const cls=blind?'card bad':(cons?'card warn':'card');
 const sel=s.selected.map(x=>{const c=CONF[x.confidence]||{cls:'t-con',label:x.confidence,title:x.confidence};
   return `<tr><td><span class="tag ${c.cls}" title="${esc(c.title)}">${c.label}</span></td><td><code>${esc(x.testId)}</code></td></tr>`;}).join('')
   ||'<tr><td colspan=2 class="gutter">— 선별된 테스트 없음 —</td></tr>';
 const redu = cons? `<span class="redu">🛡 safe fallback</span> — 전체 ${s.total}개를 보수적으로 포함 (절감 없음, 누락 위험 0)`
   : (blind? '<span class="del">⚠ 0개 선별 — 커버리지 사각지대 (탭 5 참조)</span>'
   : `<span class="redu">▼ ${pct(s.count)}% 절감</span> · 전체 ${s.total}개 중 ${s.count}개만 실행`);
 const reasons=s.reasons.map(r=>`<div class="note">${esc(r)}</div>`).join('');
 return `<div class="${cls}"><div class="scen-head"><span class="big">${esc(s.id)}</span>
   <span>${esc(s.title)}</span></div>
   <div style="margin:8px 0">${redu}</div>
   <pre>${fmtDiff(s.diff)}</pre>
   <table><tbody>${sel}</tbody></table>${reasons}</div>`;}).join('')
 : '<div class="note">시나리오 데이터가 없다. <code>tia impact</code>로 diff 기반 선별을 만들어 <code>scenarios.json</code>으로 넘기면 이 탭이 채워진다(미지정 시 인자에 <code>-</code> 전달).</div>';

// ---- 4. flaky ----
const ratioTxt = o => `${(o.ratio*100).toFixed(1)}% (${o.flaky.length}/${o.total})`;
const flakyChip = (t,color) => {const href=testSrcHref(t);const label='FLAKY · '+esc(t);
  return href?`<a class="chip" href="${esc(href)}" style="border-color:${color}" title="로컬 테스트 소스 열기: ${esc(t)}">${label}</a>`
             :`<span class="chip" style="border-color:${color}">${label}</span>`;};
const realCard = fr => `
  <div class="card grow"><h2 style="margin-top:0">① 실측 (Real) · ${fr.runs}회 연속 실행</h2>
   <p class="hint">동일한 스위트를 <b>${fr.runs}번</b> 실제로 돌려, 매번 결과(Pass/Fail)가 바뀌는 테스트가 있는지 측정.</p>
   <div class="big" style="color:var(--ok)">flaky ratio ${ratioTxt(fr)}</div>
   <div class="hint">${fr.flaky.length}/${fr.total}개가 flaky${fr.flaky.length?'':' → 모두 같은 결과 → 스위트는 <b>결정론적(deterministic)</b>'}.</div>
   <div class="chips" style="margin-top:8px">${fr.flaky.map(t=>flakyChip(t,'var(--ok)')).join('')}</div></div>`;
const synthCard = fs => `
  <div class="card grow warn"><h2 style="margin-top:0">② 검출기 검증 (synthetic · 양성 대조군)</h2>
   <p class="hint">실제 실행이 <b>아니다</b>. 한 테스트의 결과를 <b>일부러 Pass→Fail→Pass로 뒤집은</b> 가짜 실행으로 검출기를 시험한다. 검출기가 이를 flaky로 <b>집어내면 정상</b>.</p>
   <div class="big" style="color:var(--warn)">flaky ratio ${ratioTxt(fs)}</div>
   <div class="hint">${fs.flaky.length}/${fs.total}개 검출 = 조작한 테스트를 정확히 잡아냄. → 왼쪽의 0%가 "검출기 고장으로 0"이 아니라 <b>"진짜 안정적이라 0"</b>임을 보증한다.</div>
   <div class="chips" style="margin-top:8px">${fs.flaky.map(t=>flakyChip(t,'var(--warn)')).join('')}</div></div>`;
if(!D.flaky || !D.flaky.real){
 document.getElementById('flaky').innerHTML='<div class="note">flaky 데이터가 없다. <code>tia flaky --runs run1.json,run2.json,…</code>(≥2회 실행) 결과를 <code>flaky.json</code>으로 넘기면 이 탭이 채워진다(미지정 시 인자에 <code>-</code> 전달).</div>';
}else{
 const intro=D.flaky.synthetic?'<p class="hint">두 카드는 <b>서로 다른 것</b>을 측정한다 — 값이 같을 필요가 없다. 왼쪽은 실제 스위트가 흔들리는지(<b>실측</b>), 오른쪽은 그 <b>flaky 검출기 자체가 제대로 동작하는지</b>(<b>양성 대조군</b>).</p>':'';
 document.getElementById('flaky').innerHTML=`${intro}<div class="flex">${realCard(D.flaky.real)}${D.flaky.synthetic?synthCard(D.flaky.synthetic):''}</div>`;
}

// ---- 5. blind ----
document.getElementById('blindhint').textContent=`프로덕션 ${D.nProd}개 중 ${D.blind.length}개 파일이 어떤 블랙박스 테스트에도 닿지 않음 (${D.nCovered}개만 커버).`;
document.getElementById('blindlist').innerHTML=D.blind.map(f=>`<div><code>${esc(f)}</code></div>`).join('');
</script></body></html>"""

# Inject the model as a JS literal. Escape "</" so a value containing "</script>"
# (or any "</…") cannot close the inline <script> early — a JSON-island XSS/breakout.
# "<\/" is a valid JS string escape and round-trips through JSON.parse unchanged.
data_js = json.dumps(model, ensure_ascii=False).replace("</", "<\\/")
sut_title = SUT.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
out_html = HTML.replace("__SUT__", sut_title).replace("__DATA__", data_js)
open(OUT, "w").write(out_html)
print(f"wrote {OUT}  ({len(model['perTest'])} tests, {len(model['scenarios'])} scenarios, "
      f"{len(model['blind'])} blind spots)")

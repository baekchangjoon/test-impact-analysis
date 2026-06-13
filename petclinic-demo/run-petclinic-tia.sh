#!/usr/bin/env bash
# One-command end-to-end TIA over the spring-petclinic black-box suite.
#
#   build agent+app  →  start app with coverage agent  →  run blackbox suite (per-test .exec)
#   →  convert to testwise JSON  →  tia index  →  tia impact scenarios  →  tia flaky (5 runs)
#   →  self-contained interactive HTML report.
#
# Assumes spring-petclinic and parallel-per-test-coverage are cloned as siblings of (or at the
# paths below relative to) this repo. Override paths with env vars PETC / AGENT_REPO if needed.
# Everything is reproducible; no working-tree change to petclinic survives (perturb-and-revert).
set -euo pipefail

# ---- paths -----------------------------------------------------------------
TIA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO="$TIA/petclinic-demo"
PETC="${PETC:-/Users/changjoonbaek/github_spring-petclinic/spring-petclinic}"
AGENT_REPO="${AGENT_REPO:-/Users/changjoonbaek/github_spring-petclinic/parallel-per-test-coverage}"
AGENT_JAR="$AGENT_REPO/build/libs/jacocoagent-parallel.jar"
APP_JAR="$PETC/build/libs/spring-petclinic-4.0.0-SNAPSHOT.jar"
CLASSES="$PETC/build/classes/java/main"
CLI="$TIA/tia-cli/build/install/tia-cli/bin/tia-cli"
COV=/tmp/petclinic-coverage
PORT=8080 CTRL=6310

JDK21="$(/usr/libexec/java_home -v 21)"
JDK17="$(/usr/libexec/java_home -v 17)"
JACOCO_CLI="$(find "$HOME/.m2" -name 'org.jacoco.cli-*-nodeps.jar' -print -quit 2>/dev/null)"
[ -n "$JACOCO_CLI" ] || { echo "❌ jacococli (org.jacoco.cli:*:nodeps) not found in ~/.m2"; exit 1; }

say(){ printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }

# ---- 0. build artifacts if missing ----------------------------------------
say "0/7  build artifacts (agent · app · tia-cli) if missing"
[ -f "$AGENT_JAR" ] || ( cd "$AGENT_REPO" && JAVA_HOME="$JDK21" ./gradlew shadowJar )
[ -f "$APP_JAR" ]   || ( cd "$PETC" && JAVA_HOME="$JDK21" ./gradlew bootJar )
[ -d "$CLASSES" ]   || ( cd "$PETC" && JAVA_HOME="$JDK21" ./gradlew classes )
[ -x "$CLI" ]       || ( cd "$TIA"  && JAVA_HOME="$JDK17" ./gradlew :tia-cli:installDist )
COMMIT="$(cd "$PETC" && git rev-parse HEAD)"
echo "petclinic HEAD = $COMMIT"

# ---- 1. start app with coverage agent -------------------------------------
say "1/7  start petclinic with parallel-per-test-coverage agent (port $CTRL)"
STALE="$(lsof -ti tcp:$PORT 2>/dev/null || true)"; [ -n "$STALE" ] && { echo "freeing stale :$PORT (pid $STALE)"; kill -9 $STALE 2>/dev/null || true; sleep 2; }
rm -rf "$COV"; mkdir -p "$COV" "$DEMO"
"$JDK21/bin/java" \
  -javaagent:"$AGENT_JAR=destfile=$COV,port=$CTRL,includes=org.springframework.samples.petclinic.*" \
  -jar "$APP_JAR" --server.port=$PORT > "$DEMO/server.log" 2>&1 &
APP_PID=$!
cleanup(){ kill "$APP_PID" 2>/dev/null || true; }
trap cleanup EXIT
for i in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$PORT/api/auth/login" \
      -H 'Content-Type: application/json' -d '{"username":"admin","password":"password"}')" = "200" ] \
    && { echo "app up after ${i}s"; break; }
  [ "$i" = 90 ] && { echo "❌ app did not come up"; tail -20 "$DEMO/server.log"; exit 1; }
  sleep 1
done

# ---- 2. run black-box suite with per-test coverage routing ----------------
say "2/7  run black-box suite (per-test .exec via baggage test.id)"
( cd "$PETC" && JAVA_HOME="$JDK21" ./gradlew blackboxTest --rerun --console=plain \
    -Dpetclinic.base-url="http://localhost:$PORT" \
    -Dpjacoco.control-url="http://127.0.0.1:$CTRL" ) > "$DEMO/blackbox.log" 2>&1
echo "per-test .exec files: $(ls -1 "$COV"/*.exec | wc -l | tr -d ' ')"

# ---- 3. convert .exec -> testwise JSON ------------------------------------
say "3/7  convert per-test .exec → testwise JSON"
python3 "$DEMO/exec_to_testwise.py" "$COV" "$CLASSES" "$JACOCO_CLI" "$JDK21/bin/java" "$DEMO/testwise.json"

# ---- 4. index --------------------------------------------------------------
say "4/7  tia index → SQLite snapshot"
rm -f "$DEMO/tia.db"
JAVA_HOME="$JDK17" "$CLI" index --report "$DEMO/testwise.json" \
  --repo spring-petclinic --commit "$COMMIT" --db "$DEMO/tia.db"

# ---- 5. impact scenarios + markdown report --------------------------------
say "5/7  tia impact scenarios + per-test markdown report"
( cd "$PETC" && find src/main/java -name '*.java' | sed 's#src/main/java/##' | sort ) > "$DEMO/prod-files.txt"
JAVA_HOME="$JDK17" python3 "$DEMO/run_scenarios.py" "$PETC" "$CLI" "$DEMO/tia.db" "$COMMIT" "$DEMO/scenarios.json"
python3 "$DEMO/report.py" "$DEMO/testwise.json" "$DEMO/prod-files.txt" > "$DEMO/01-coverage-report.md"

# ---- 6. flaky: 5 real runs + synthetic mechanism check --------------------
say "6/7  tia flaky — 5 real runs + synthetic detection check"
RUNS="$DEMO/flaky-runs"; rm -rf "$RUNS"; mkdir -p "$RUNS/synthetic"
for run in 1 2 3 4 5; do
  ( cd "$PETC" && JAVA_HOME="$JDK21" ./gradlew blackboxTest --rerun --console=plain \
      -Dpetclinic.base-url="http://localhost:$PORT" >/dev/null 2>&1 )
  python3 - "$PETC" "$RUNS/run-$run.json" <<'PY'
import glob,sys,json,xml.etree.ElementTree as ET
petc,out=sys.argv[1],sys.argv[2];res={}
for x in glob.glob(petc+"/build/test-results/blackboxTest/*.xml"):
    r=ET.parse(x).getroot();cls=r.get("name").split(".")[-1]
    for tc in r.findall("testcase"):
        res[f"{cls}#{tc.get('name')}"]=tc.find("failure") is None and tc.find("error") is None
json.dump({"results":res},open(out,"w"))
PY
done
REAL_RATIO_JSON=$(JAVA_HOME="$JDK17" "$CLI" flaky --runs "$(ls "$RUNS"/run-*.json | paste -sd, -)")
echo "$REAL_RATIO_JSON"
# synthetic: clone run-1 but flip one test P/F/P across 3 runs
python3 - "$RUNS" <<'PY'
import json,sys,os
runs=sys.argv[1];base=json.load(open(runs+"/run-1.json"))["results"]
# Flip an existing key so totals stay consistent. NOTE: JUnit XML uses @DisplayName for the
# concurrency tests, while the coverage agent keys by method name — a real cross-signal id
# mismatch. We pick a concurrency display-name key that actually exists in the run data.
flip=next((k for k in base if "oncurren" in k.lower()), next(iter(base)))
for i,v in enumerate([True,False,True],1):
    r=dict(base);r[flip]=v;json.dump({"results":r},open(f"{runs}/synthetic/run-{i}.json","w"))
PY
SYN=$(JAVA_HOME="$JDK17" "$CLI" flaky --runs "$RUNS/synthetic/run-1.json,$RUNS/synthetic/run-2.json,$RUNS/synthetic/run-3.json")
echo "synthetic: $SYN"
# assemble flaky.json from the two CLI outputs
python3 - "$REAL_RATIO_JSON" "$SYN" "$DEMO/flaky.json" <<'PY'
import sys,re,json
def parse(s):
    m=re.search(r"ratio: ([\d.]+) \((\d+)/(\d+)\)",s)
    flaky=re.findall(r"FLAKY\t(.+)",s)
    return {"ratio":float(m.group(1)),"flaky":flaky,"total":int(m.group(3))}
real=parse(sys.argv[1]);real["runs"]=5
syn=parse(sys.argv[2])
json.dump({"real":real,"synthetic":syn},open(sys.argv[3],"w"))
PY

# ---- 7. HTML report --------------------------------------------------------
say "7/7  build interactive HTML report"
python3 "$DEMO/make_html.py" "$DEMO/testwise.json" "$DEMO/scenarios.json" "$DEMO/flaky.json" \
  "$DEMO/prod-files.txt" "$COMMIT" "$DEMO/report.html"

echo; echo "✅ done. open: $DEMO/report.html"

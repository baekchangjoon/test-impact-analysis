#!/usr/bin/env python3
"""Regression / acceptance tests for make_html.py (the TIA HTML report generator).

This is the highest-feasible test level for a self-contained HTML artifact: we run
make_html.py against fixture JSON and assert the generated report honours each
user-facing contract. The interactive JS behaviour (sort toggling, row expansion)
is exercised manually in a browser; here we assert the wiring and data island that
back those behaviours are present and well-formed.

Run:  python3 -m unittest discover -s petclinic-demo -p 'test_*.py'
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
MAKE_HTML = os.path.join(HERE, "make_html.py")

TESTWISE = {"tests": [
    {"uniformPath": "OwnerApiBlackBoxIT#getOwnerById", "result": "PASSED",
     "paths": [{"path": "org/springframework/samples/petclinic/owner",
                "files": [{"fileName": "OwnerRestController.java", "coveredLines": "52-53,60"}]}]},
    {"uniformPath": "AuthApiBlackBoxIT#login", "result": "PASSED",
     "paths": [{"path": "org/springframework/samples/petclinic/security",
                "files": [{"fileName": "AuthController.java", "coveredLines": "46-47"}]}]},
    # a hostile id that would break out of the <script> island if injected raw
    {"uniformPath": "EvilIT#x</script><b>boom", "result": "PASSED", "paths": []},
]}
SCENARIOS = [{"id": "A", "title": "precise change", "kind": "modify", "target": "x", "line": 53,
              "diff": "@@ -53 +53 @@\n-a\n+a // p",
              "selected": [{"confidence": "DETERMINISTIC", "testId": "OwnerApiBlackBoxIT#getOwnerById"}],
              "reasons": [], "count": 1, "total": 3, "conservative": False}]
FLAKY = {"real": {"ratio": 0.0, "flaky": [], "total": 3, "runs": 5},
         "synthetic": {"ratio": 0.5, "flaky": ["AuthApiBlackBoxIT#login"], "total": 3}}
PROD = ["org/springframework/samples/petclinic/owner/OwnerRestController.java",
        "org/springframework/samples/petclinic/security/AuthController.java",
        "org/springframework/samples/petclinic/system/CrashController.java"]  # blind spot
COMMIT = "fe8128079e12abcdef0123456789abcdef012345"
SUT = "acme-widgets"


def generate(tmp, sut=SUT, jacoco="jacoco", with_test_src=True):
    """Run make_html.py with fixtures; return (html_text, data_model_dict)."""
    p = lambda n: os.path.join(tmp, n)
    for name, obj in (("testwise.json", TESTWISE), ("scenarios.json", SCENARIOS), ("flaky.json", FLAKY)):
        with open(p(name), "w") as fh:
            json.dump(obj, fh)
    with open(p("prod.txt"), "w") as fh:
        fh.write("\n".join(PROD) + "\n")
    test_src = ""
    if with_test_src:
        test_src = p("testsrc")
        api = os.path.join(test_src, "org/springframework/samples/petclinic/api")
        os.makedirs(api)
        for cls in ("OwnerApiBlackBoxIT", "AuthApiBlackBoxIT"):
            with open(os.path.join(api, cls + ".java"), "w") as fh:
                fh.write("class " + cls + " {}\n")
    args = [sys.executable, MAKE_HTML, p("testwise.json"), p("scenarios.json"),
            p("flaky.json"), p("prod.txt"), COMMIT, p("out.html"), sut, jacoco, test_src]
    subprocess.run(args, check=True, capture_output=True, text=True)
    with open(p("out.html")) as fh:
        html = fh.read()
    data = None
    for line in html.splitlines():
        if line.startswith("const D = "):
            data = json.loads(line[len("const D = "):].rstrip(";"))
            break
    assert data is not None, "could not locate the `const D = {...}` data island"
    return html, data


class MakeHtmlReport(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.html, cls.data = generate(cls._tmp.name)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    # 1 — title uses the SUT name, not a hardcoded project
    def test_title_uses_sut_name(self):
        m = re.search(r"<title>(.*?)</title>", self.html)
        assert m is not None, "report has no <title>"
        title = m.group(1)
        self.assertIn(SUT, title)
        self.assertNotIn("spring-petclinic", title)
        self.assertEqual(self.data["sut"], SUT)
        self.assertIn('id="sut"', self.html)  # H1 fills SUT dynamically

    # 2 — every column is sortable (asc/desc toggle), incl. the bar columns
    def test_all_columns_sortable(self):
        for fn in ("function headHtml", "function applySort", "function wireSort"):
            self.assertIn(fn, self.html)
        self.assertIn("sort.dir*=-1", self.html)        # same column → flip direction
        self.assertIn('data-i="${i}"', self.html)       # arrow keyed to clicked column
        self.assertIn("label:'reach'", self.html)       # per-test bar column sortable
        self.assertIn("label:'fan-in'", self.html)      # reverse bar column sortable

    # 3 — per-test files deep-link into the JaCoCo HTML at the covered line
    def test_jacoco_deeplink(self):
        self.assertIn("jacocoHref", self.html)
        self.assertIn(".html#L", self.html)
        owner = next(t for t in self.data["perTest"] if t["id"].startswith("OwnerApi"))
        f = owner["files"][0]
        self.assertEqual(f["pkg"], "org.springframework.samples.petclinic.owner")
        self.assertEqual(f["file"], "OwnerRestController.java")
        self.assertEqual(f["line"], 52)  # first covered line of "52-53,60"

    # 4 — reverse-index tests link to the local test source file
    def test_reverse_test_source_links(self):
        self.assertIn("testSrcHref", self.html)
        self.assertIn("'file://'", self.html)
        self.assertIn("OwnerApiBlackBoxIT", self.data["testSrc"])
        self.assertTrue(self.data["testSrc"]["OwnerApiBlackBoxIT"].endswith("OwnerApiBlackBoxIT.java"))

    def test_reverse_links_degrade_without_test_src(self):
        with tempfile.TemporaryDirectory() as t:
            _, data = generate(t, with_test_src=False)
            self.assertEqual(data["testSrc"], {})  # no crash, just no links

    # 5 — tia impact terms are explained in plain language
    def test_impact_legend_explains_terms(self):
        for term in ("정밀 선별", "보수적 선별", "DETERMINISTIC", "CONSERVATIVE"):
            self.assertIn(term, self.html)
        # comparison baseline is spelled out and not framed as origin↔local
        self.assertIn("베이스라인", self.html)
        self.assertIn('id="impact-base"', self.html)

    # 6 — flaky ratio shown as both percent and fraction; names linked
    def test_flaky_ratio_percent_and_fraction(self):
        self.assertIn("ratioTxt", self.html)
        self.assertIn("o.ratio*100", self.html)
        self.assertIn("(${o.flaky.length}/${o.total})", self.html)
        self.assertIn("flakyChip", self.html)

    # security — a value containing "</script>" must not break out of the island
    def test_script_island_is_escaped(self):
        self.assertEqual(self.html.count("</script>"), 1)  # only the real closing tag
        self.assertIn("<\\/script>", self.html)            # hostile id neutralised
        # the data still round-trips: the evil id survives as data
        ids = [t["id"] for t in self.data["perTest"]]
        self.assertIn("EvilIT#x</script><b>boom", ids)

    # model integrity — blind spots, counts
    def test_model_counts(self):
        self.assertEqual(self.data["nTests"], 3)
        self.assertEqual(self.data["nProd"], 3)
        self.assertIn("…/system/CrashController.java", self.data["blind"])  # uncovered → blind


if __name__ == "__main__":
    unittest.main(verbosity=2)

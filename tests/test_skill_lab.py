"""Skill lab: Install anyway overrules only the scanner, never the house rules or the safety read."""
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402


def blocked_run(rid: str, last: str, **extra) -> dict:
    return {"id": rid, "request": "install hallmark", "rounds": 4, "status": "blocked", "name": "hallmark",
            "source": "https://github.com/nutlope/hallmark", "scores": [], "created": "2026-10-29T00:00:00+00:00",
            "messages": [{"at": "", "who": "you", "text": "install hallmark"}, {"at": "", "who": "lab", "text": last}], **extra}


class SkillLabTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def setUp(self):
        lab = self.h.tmp / "data" / "skill-lab-test"
        lab.mkdir(parents=True, exist_ok=True)
        runs = lab / "runs.json"
        runs.write_text(json.dumps([
            blocked_run("aaaaaaa1", "**Blocked by the scanner** (risk 100/100, CRITICAL). Not installed."),
            blocked_run("aaaaaaa2", "**Blocked by the safety read.** Not installed:\n- asks for keys"),
        ]), encoding="utf-8")
        self.started = mock.patch.object(server, "skill_lab_run")  # the real run clones from GitHub
        for p in (mock.patch.object(server, "SKILL_LAB_DIR", lab), mock.patch.object(server, "SKILL_LAB_RUNS", runs), self.started):
            p.start()
            self.addCleanup(p.stop)
        self.runs = runs

    def run_of(self, rid: str) -> dict:
        return next(r for r in json.loads(self.runs.read_text(encoding="utf-8")) if r["id"] == rid)

    def test_scanner_block_can_be_overruled(self):
        status, _ = self.h.api("POST", "/api/skill-lab/aaaaaaa1/install-anyway")
        self.assertEqual(status, 200)
        run = self.run_of("aaaaaaa1")
        self.assertEqual((run["status"], run["override"], run["messages"][-1]["text"]), ("running", True, "Install anyway"))

    def test_safety_read_block_stays(self):
        status, _ = self.h.api("POST", "/api/skill-lab/aaaaaaa2/install-anyway")
        self.assertEqual(status, 400)
        self.assertEqual(self.run_of("aaaaaaa2")["status"], "blocked")

    def test_bad_or_unknown_id(self):
        self.assertIn(self.h.api("POST", "/api/skill-lab/zz%2E%2Ex/install-anyway")[0], (400, 404))
        self.assertEqual(self.h.api("POST", "/api/skill-lab/bbbbbbb9/install-anyway")[0], 404)


if __name__ == "__main__":
    unittest.main()

"""TKT-0019: a Claude error, its session-limit notice or an off-topic reply is never a completed heartbeat check."""
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402
from scheduler.scheduler import get_history  # noqa: E402

HEALTHY = """Jhon, heartbeat is **healthy**. All 6 checks pass.

| Check | Result | Limit | Status |
|---|---|---|---|
| Agents: claude, opencode, agy | all found | exist | pass |
| Hermes gateway (port 8642) | ok, v0.22.5 | answers | pass |
| Disk C: | 80.1% | under 90% | pass |
| Disk D: | 70.2% | under 90% | pass |
| Memory | 60.0% | under 80% | pass |
| Audit log, last 200 lines | 0 | under 5 | pass |
"""
# the 2 Nov 00:00 format: one row per CLI and one Disk row carrying both drives
DEGRADED = """Jhon, heartbeat is **degraded**. 1 of 6 checks fails: disk C:.

| Check | Result | Detail |
|---|---|---|
| claude | pass | found |
| opencode | pass | found (npm shim) |
| agy | pass | found |
| hermes gateway | pass | `/health` ok, v0.22.5 |
| Disk | **fail** | C: 90.3% used (24 GB free). D: 88.5% passes (79 GB free) |
| Memory | pass | 70.6% used |
| Audit errors | pass | 0 error/fail actions in the last 200 lines |
"""
JSON_REPORT = "```json\n" + json.dumps({"status": "degraded", "checks": {
    "cli": {"status": "pass"}, "hermes_gateway": {"status": "pass"}, "disk": {"C:": "fail", "D:": "pass"},
    "memory": {"status": "pass"}, "audit_errors": {"status": "pass", "count": 0}}}) + "\n```"
# the 1 Nov 17:01 format: checks at the top level, hermes inside "agents", drives without a colon
JSON_TOP = """Jhon, heartbeat is **degraded**.

```json
{
  "agents":   {"claude": "pass", "opencode": "pass", "agy": "pass", "hermes_gateway_8642": "pass (200)"},
  "disk":     {"C": "FAIL 90.27%", "D": "88.18% (ok)"},
  "memory":   {"used": "71.0%", "status": "pass"},
  "audit_log": {"real_errors_last_200": 0, "status": "pass"}
}
```
"""
# the 1 Nov 10:00 format: a plain fenced block
FENCED = """Jhon, heartbeat is **degraded**.

```
Agents  claude   PASS   claude.exe found
        opencode PASS   found
        agy      PASS   agy.exe found
        hermes   PASS   gateway /health = ok (v0.22.5)
Disk    C:       FAIL   90% used
        D:       PASS   87% used
Memory           PASS   74% used
Audit            PASS   3 real error lines in the last 200
```
"""
# the 2 Nov 06:01 format: a list of named checks, drives as disk_c_under_90 (was misread as "no result for C:/D:")
JSON_LIST = "```json\n" + json.dumps({"run": "heartbeat", "verdict": "degraded", "checks": [
    {"check": "agents_present", "pass": True}, {"check": "hermes_gateway", "pass": True},
    {"check": "disk_c_under_90", "pass": False}, {"check": "disk_d_under_90", "pass": False},
    {"check": "memory_under_80", "pass": False}, {"check": "audit_recent_errors", "pass": True}]}) + "\n```"
# the 3 Nov 15:01 format: the audit row named "Recent errors"
RECENT_ERRORS = HEALTHY.replace("| Audit log, last 200 lines | 0 | under 5 | pass |", "| Recent errors (limit 5) | 7 | under 5 | fail |")
LIMIT = "You've hit your session limit · resets 6pm (America/Los_Angeles)"
OFF_TOPIC = ("Jhon, that last call was a mistake. There was no code review, so there was nothing to report. "
             "The heartbeat result above stands.")


def claude_json(result, is_error=False):
    return json.dumps({"type": "result", "is_error": is_error, "result": result, "usage": {}, "total_cost_usd": 0})


class HeartbeatResultTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def run_heartbeat(self, cli_reply):
        """One scheduled heartbeat on a mocked Claude CLI -> (API result, job_done event, job_run and skill_run audits)."""
        # guard open: these tests are about judging Claude's reply (TKT-0018 covers the guard in test_core)
        with mock.patch.object(server, "run_cli", return_value=cli_reply), mock.patch.dict(server._resting, clear=True), \
                mock.patch.object(server, "claude_guard", return_value=None):
            api = self.h.api("POST", "/api/skills/heartbeat/run", {"agent": "claude"})[1]
            server.run_scheduled({"id": "hb-test", "name": "Heartbeat Test", "kind": "skill", "skill": "heartbeat", "agent": "claude"}, "cron")
        done = [e for e in get_history(500) if e.get("job_id") == "hb-test"][-1]
        audit = [json.loads(l) for l in (server.BASE_DIR / "audit" / "audit.log").read_text().splitlines()]
        job_run = [e for e in audit if e.get("action") == "job_run" and e.get("job") == "Heartbeat Test"][-1]
        skill_run = [e for e in audit if e.get("action") == "skill_run" and e.get("skill") == "heartbeat"][-1]
        return api, done, job_run, skill_run

    def assert_not_completed(self, cli_reply, want):
        api, done, job_run, skill_run = self.run_heartbeat(cli_reply)
        for where, status in (("api", api["status"]), ("history", done["status"]), ("job_run", job_run["status"]),
                              ("skill_run", skill_run["status"])):
            self.assertEqual(status, want, where)
        self.assertTrue(api["reason"] and done["reason"] and job_run["reason"])
        last = (server.BASE_DIR / "skills" / "heartbeat" / "learnings.md").read_text(encoding="utf-8").rstrip().splitlines()[-1]
        self.assertIn(want.upper(), last)

    def test_session_limit_notice_fails(self):
        self.assert_not_completed((0, claude_json(LIMIT), ""), "failed")  # the 1 Nov 00:01 run said is_error false

    def test_is_error_with_exit_zero_fails(self):
        self.assert_not_completed((0, claude_json("API Error: overloaded", is_error=True), ""), "failed")

    def test_empty_and_malformed_replies_fail(self):
        self.assert_not_completed((0, claude_json(""), ""), "failed")
        self.assert_not_completed((1, "not json", "boom"), "failed")

    def test_off_topic_reply_is_unverified(self):
        self.assert_not_completed((0, claude_json(OFF_TOPIC), ""), "unverified")

    def test_complete_reports_pass_healthy_or_degraded(self):
        for report in (HEALTHY, DEGRADED, JSON_REPORT, JSON_TOP, FENCED, JSON_LIST, RECENT_ERRORS):
            api, done, job_run, skill_run = self.run_heartbeat((0, claude_json(report), ""))
            self.assertEqual([api["status"], done["status"], job_run["status"], skill_run["status"]], ["completed"] * 4, report[:40])
            self.assertEqual(api["output"], report.strip())

    def test_missing_or_duplicate_check_is_unverified(self):
        missing = HEALTHY.replace("| Memory | 60.0% | under 80% | pass |\n", "")
        self.assertIn("memory", server.heartbeat_gap(missing))
        dup = HEALTHY + "| Disk C: | 91% | under 90% | fail |\n"
        self.assertIn("twice", server.heartbeat_gap(dup))
        self.assert_not_completed((0, claude_json(missing), ""), "unverified")

    def test_other_skills_are_not_held_to_the_heartbeat_checks(self):
        d = server.BASE_DIR / "skills" / "plain-test"
        d.mkdir(parents=True, exist_ok=True)
        (d / "SKILL.md").write_text("do it")
        with mock.patch.object(server, "execute_agent", return_value="done, no table"):
            self.assertEqual(server.run_skill("plain-test")["status"], "completed")


if __name__ == "__main__":
    unittest.main()

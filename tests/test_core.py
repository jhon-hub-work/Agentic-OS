"""Core endpoint smoke tests for Agentic OS (v4.0).

Run with: python3 tests/run_all.py  (or: python3 tests/test_core.py)
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402


class CoreEndpointTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def test_status_healthy(self):
        status, data = self.h.api("GET", "/api/status")
        self.assertEqual(status, 200)
        self.assertEqual(data.get("status"), "healthy")
        self.assertIn("agents", data)

    def test_all_agents_present(self):
        _, data = self.h.api("GET", "/api/status")
        names = {a.get("name") for a in data.get("agents", [])}
        self.assertEqual(names, {"claude", "opencode", "hermes", "agy"})

    def test_skills_list(self):
        status, data = self.h.api("GET", "/api/skills")
        self.assertEqual(status, 200)
        self.assertIsInstance(data, list)

    def test_brain_files(self):
        status, data = self.h.api("GET", "/api/brain")
        self.assertEqual(status, 200)
        self.assertIsInstance(data, dict)

    def test_scheduler_jobs(self):
        status, data = self.h.api("GET", "/api/scheduler/jobs")
        self.assertEqual(status, 200)
        self.assertIsInstance(data, list)

    def test_audit_log(self):
        status, data = self.h.api("GET", "/api/audit")
        self.assertEqual(status, 200)

    def test_cost_analytics(self):
        status, data = self.h.api("GET", "/api/cost")
        self.assertEqual(status, 200)

    def test_plugins(self):
        status, data = self.h.api("GET", "/api/plugins")
        self.assertEqual(status, 200)

    def test_prompts(self):
        status, data = self.h.api("GET", "/api/prompts")
        self.assertEqual(status, 200)

    def test_settings_masked(self):
        status, data = self.h.api("GET", "/api/settings")
        self.assertEqual(status, 200)
        text = str(data)
        self.assertNotIn("ghp_", text, "API token must be masked")

    def test_standards(self):
        status, data = self.h.api("GET", "/api/standards")
        self.assertEqual(status, 200)

    def test_kanban_board(self):
        status, data = self.h.api("GET", "/api/kanban/board")
        self.assertEqual(status, 200)

    def test_goals(self):
        status, data = self.h.api("GET", "/api/goals")
        self.assertEqual(status, 200)

    def test_journal_entries(self):
        status, data = self.h.api("GET", "/api/journal/entries")
        self.assertEqual(status, 200)

    def test_agent_health(self):
        status, data = self.h.api("GET", "/api/agents/health")
        self.assertEqual(status, 200)

    def test_skill_analytics(self):
        status, data = self.h.api("GET", "/api/analytics/skills")
        self.assertEqual(status, 200)

    def test_sessions_list(self):
        status, data = self.h.api("GET", "/api/sessions/list")
        self.assertEqual(status, 200)

    def test_backups_list(self):
        status, data = self.h.api("GET", "/api/backups")
        self.assertEqual(status, 200)

    def test_security_headers_present(self):
        import urllib.request

        req = urllib.request.Request(self.h.base_url + "/api/status")
        with urllib.request.urlopen(req, timeout=10) as resp:
            headers = dict(resp.headers)
        self.assertIn("x-content-type-options", headers)
        self.assertEqual(headers["x-content-type-options"], "nosniff")
        self.assertIn("x-frame-options", headers)
        self.assertIn("strict-transport-security", headers)

    def test_cors_restricted_to_localhost(self):
        import urllib.request

        req = urllib.request.Request(self.h.base_url + "/api/status")
        req.add_header("Origin", "http://evil.example.com")
        with urllib.request.urlopen(req, timeout=10) as resp:
            allow = resp.headers.get("access-control-allow-origin")
        self.assertIsNone(allow, "Non-localhost origin must not be allowed")

    def test_dashboard_served(self):
        status, _ = self.h.request("GET", "/")
        self.assertEqual(status, 200)

    def test_manifest_served(self):
        status, _ = self.h.request("GET", "/manifest.json")
        self.assertEqual(status, 200)


class NeedsOwnerTests(unittest.TestCase):
    def test_owner_only_asks(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
        from jobs.pipeline import needs_owner
        self.assertEqual(needs_owner({"jd": "Edit our reels in Premiere. Send CV and portfolio.", "draft": "Hi"}), "")
        self.assertTrue(needs_owner({"jd": "Please attach a voice recording introducing yourself."}))
        self.assertTrue(needs_owner({"jd": "Send a short Loom video about you."}))
        self.assertTrue(needs_owner({"jd": "Shortlisted people do a paid trial task."}))
        self.assertTrue(needs_owner({"jd": "ok", "draft": "My rate is [ADD: rate]"}))


class ClaudeGuardTests(unittest.TestCase):
    """Background jobs run free unless Claude is picked for them, and then only under the usage limits."""
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()  # moves server paths (settings.json) into a temp dir
        import server
        cls.s = server
        cls.real_usage = server.claude_usage

    @classmethod
    def tearDownClass(cls):
        cls.s.claude_usage = cls.real_usage
        cls.h.stop()

    def use(self, usage, background=None):
        import json
        self.s.claude_usage = lambda: usage
        f = self.s.BASE_DIR / "data" / "settings.json"
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps({"background": background or {}}))

    def test_guard(self):
        s = self.s
        self.use({"five_hour": 10, "seven_day": 20})
        self.assertEqual(s.bg_agent("verdict"), s.BACKGROUND_DEFAULT)       # free by default
        self.assertEqual(s.bg_agent("verdict", "opencode"), "opencode")      # a non-Claude choice stands
        self.use({"five_hour": 10, "seven_day": 20}, {"verdict": "claude"})
        self.assertEqual(s.bg_agent("verdict"), "claude")                    # picked, and under the limits
        self.use({"five_hour": 60, "seven_day": 20}, {"verdict": "claude"})
        self.assertEqual(s.bg_agent("verdict"), s.BACKGROUND_DEFAULT)       # 5-hour window over 50%
        self.use(None, {"verdict": "claude"})
        self.assertEqual(s.bg_agent("verdict"), s.BACKGROUND_DEFAULT)       # unknown usage counts as no
        self.use({"five_hour": 10, "seven_day": 90})
        with self.assertRaises(RuntimeError):                                # his CV never goes to a free model
            s.job_agent("claude", "draft this")

    def test_free_limit_rotation(self):
        import time
        from unittest import mock
        s = self.s
        self.use({"five_hour": 10, "seven_day": 20})
        s._resting.clear()
        calls = []
        def fake(agent, message, model="", effort=""):
            calls.append(agent)
            return {"opencode": "opencode failed: 429 rate limit, retry in 2h", "hermes": "hermes says hi"}[agent]
        with mock.patch.object(s, "_execute_agent", side_effect=fake):
            self.assertEqual(s.execute_agent("opencode", "x"), "hermes says hi")   # the next free agent took it
            self.assertAlmostEqual(s._resting["opencode"], time.time() + 7200, delta=60)
            self.assertEqual(s.execute_agent("opencode", "y"), "hermes says hi")   # resting: not even tried
            self.assertEqual(calls, ["opencode", "hermes", "hermes"])
        with mock.patch.object(s, "_execute_agent", return_value="hermes failed: HTTP 429 quota"),              mock.patch.dict(s.BACKGROUND_PICKS, {"fallback": "hermes"}):  # only free agents in this test
            self.assertTrue(s.execute_agent("hermes", "z").startswith(s.ALL_LIMITED))  # all out: the caller waits
        s._resting.clear()

    def test_background_team_and_auto_apply_guarded(self):
        from unittest import mock
        s = self.s
        self.use({"five_hour": 70, "seven_day": 20})  # over the 5-hour limit
        ran = []
        with mock.patch.object(s, "execute_agent", side_effect=lambda a, p, *x, **k: ran.append(a) or "done"), \
             mock.patch.object(s, "save_thread"):
            s.run_team({"id": "t1", "start_agent": "claude", "message": "x", "steps": [], "background": True}, 1)
            s.run_team({"id": "t2", "start_agent": "claude", "message": "x", "steps": [], "background": False}, 1)
        self.assertEqual(ran, [s.BACKGROUND_DEFAULT, "claude"])  # scheduled: free; started by Jhon: Claude
        board = {"jobs": [{"id": "g1", "status": "drafted", "url": "https://boards.greenhouse.io/x/jobs/1"},
                          {"id": "o1", "status": "drafted", "url": "https://www.onlinejobs.ph/jobseekers/job/1"}]}
        with mock.patch.object(s.pipeline, "load", return_value=board), \
             mock.patch.object(s.pipeline, "needs_owner", return_value=""), \
             mock.patch.object(s.pipeline, "decide") as decide, \
             mock.patch.object(s, "apply_approved", return_value="applied"):
            s.auto_apply_clean()
        self.assertEqual([c.args[0] for c in decide.call_args_list], ["o1"])  # the Claude-filled form waits

    def test_smart_falls_back_to_codex_then_claude(self):
        from unittest import mock
        s = self.s
        self.use({"five_hour": 10, "seven_day": 20})
        s.AGENTS.append("codex") if "codex" not in s.AGENTS else None
        answers = {"hermes": s.ALL_LIMITED + " until 17:00.", "codex": "Codex failed: quota", "claude": "hi from claude"}
        with mock.patch.object(s, "execute_agent", side_effect=lambda a, p, *x, **k: answers[a]):
            r = s.chat(s.ChatRequest(agent="smart", message="how does smart work"))["response"]
        self.assertEqual((r["by"], r["content"]), ("claude", "hi from claude"))  # Codex failed too, Claude under the limit
        self.use({"five_hour": 90, "seven_day": 20})
        with mock.patch.object(s, "execute_agent", side_effect=lambda a, p, *x, **k: answers[a]):
            r = s.chat(s.ChatRequest(agent="smart", message="how does smart work"))["response"]
        self.assertTrue(r["content"].startswith(s.ALL_LIMITED))  # over the limit: Claude is never the backup

    def test_smart_pick(self):
        s = self.s
        self.use({"five_hour": 10, "seven_day": 20})
        self.assertEqual(s.smart_pick("why does server.py throw a KeyError?")[0], "opencode")
        self.assertEqual(s.smart_pick("latest news on the RTX 6090 price")[0], "hermes")
        self.assertEqual(s.smart_pick("help me decide between two job offers")[0], "claude")
        self.assertEqual(s.smart_pick("hi, how are you?")[0], "hermes")
        self.use({"five_hour": 70, "seven_day": 20})
        self.assertEqual(s.smart_pick("help me decide between two job offers")[0], "opencode")  # over the limit: no Claude


if __name__ == "__main__":
    unittest.main(verbosity=2)

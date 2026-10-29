"""Approvals queue: one list from every source, and each decision goes through the source's own code path."""
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402
from jobs import people, pipeline  # noqa: E402

LIVE_ID = "a" * 32


def _write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


class ApprovalsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def setUp(self):
        t = self.h.tmp
        self.lei_board = t / "data" / "jobs" / "lei.json"
        self.live = mock.MagicMock(return_value={"approvals": [
            {"id": LIVE_ID, "tool": "send_email", "args": {"to": "x@y.z"}, "reason": "you asked", "created": 1.0e9, "expires_at": 1.0e9 + 300}]})
        self.apply = mock.MagicMock()
        self.tick = mock.MagicMock()
        self.lab_run = mock.MagicMock()
        self.patches = [
            mock.patch.object(server, "jharvis_ui", self.live),        # never the real Jharvis on 3901
            mock.patch.object(server, "apply_approved", self.apply),   # never a real apply in Brave
            mock.patch.object(server, "ticket_tick", self.tick),       # never a real Claude fix
            mock.patch.object(server, "skill_lab_run", self.lab_run),  # never a real GitHub clone
            mock.patch.object(people, "PEOPLE", {"lei": {"name": "Lei Ann"}}),
            mock.patch.object(people, "board", lambda pid: self.lei_board),
        ]
        for p in self.patches:
            p.start()
        job = {"company": "Acme", "url": "https://example.com/j", "found": "2026-10-29T01:00:00+00:00", "draft": "Hi"}
        _write(pipeline.BOARD, {"runs": [], "jobs": [{**job, "id": "a000000001", "title": "Editor", "status": "drafted"},
                                                     {**job, "id": "a000000002", "title": "Queued", "status": "queued"}]})
        _write(self.lei_board, {"runs": [], "jobs": [{**job, "id": "b000000001", "title": "Bookkeeper", "status": "drafted"}]})
        _write(server.TICKETS_FILE, [
            {"id": "TKT-0001", "title": "Fix it", "status": "awaiting_approval", "plan": "Do X", "assignee": "Claude",
             "created": "2026-10-28T00:00:00+00:00", "updated": "2026-10-28T01:00:00+00:00", "log": []},
            {"id": "TKT-0002", "title": "Not yet", "status": "planning", "plan": "", "created": "2026-10-28T00:00:00+00:00", "log": []}])
        _write(server.JHARVIS_APPROVALS, [
            {"id": "j1", "tool": "post_slack", "args": {}, "status": "pending", "asked": "2026-10-29T02:00:00+00:00"},
            {"id": "j2", "tool": "old", "args": {}, "status": "approved", "asked": "2026-10-27T02:00:00+00:00"}])
        msg = lambda text: [{"at": "", "who": "lab", "text": text}]  # noqa: E731
        _write(server.SKILL_LAB_RUNS, [
            {"id": "aaaaaaa1", "name": "hallmark", "status": "blocked", "blocked_by": "scanner", "created": "2026-10-29T03:00:00+00:00",
             "messages": msg("**Blocked by the scanner**"), "scores": [], "rounds": 4, "request": "x", "source": ""},
            {"id": "aaaaaaa2", "name": "other", "status": "blocked", "blocked_by": "house", "created": "2026-10-29T03:00:00+00:00",
             "messages": msg("**Blocked: house rules**"), "scores": [], "rounds": 4, "request": "x", "source": ""}])

    def tearDown(self):
        for p in self.patches:
            p.stop()

    def ids(self):
        status, data = self.h.api("GET", "/api/approvals")
        self.assertEqual(status, 200)
        self.assertEqual(data["count"], len(data["approvals"]))
        return {i["id"] for i in data["approvals"]}

    def decide(self, aid, decision, note=""):
        return self.h.api("POST", f"/api/approvals/{aid}", {"decision": decision, "note": note})

    def test_gathers_every_pending_source_and_nothing_else(self):
        self.assertEqual(self.ids(), {"ticket:TKT-0001", "job::a000000001", "job:lei:b000000001", "jharvis:j1",
                                      f"jharvis-live:{LIVE_ID}", "skill:aaaaaaa1"})

    def test_jharvis_off_still_lists_the_rest(self):
        self.live.side_effect = ConnectionRefusedError()
        self.assertNotIn(f"jharvis-live:{LIVE_ID}", self.ids())
        self.assertIn("ticket:TKT-0001", self.ids())

    def test_ticket_approve_and_deny_use_ticket_paths(self):
        status, t = self.decide("ticket:TKT-0001", "approve")
        self.assertEqual((status, t["status"]), (200, "approved"))
        self.tick.assert_called()  # approve_ticket starts the fix
        self.assertEqual(self.decide("ticket:TKT-0001", "approve")[0], 409)  # no longer waiting
        server._update_ticket("TKT-0001", lambda x: x.update(status="awaiting_approval"))
        status, t = self.decide("ticket:TKT-0001", "deny", "wrong folder")
        self.assertEqual((status, t["status"]), (200, "new"))
        self.assertIn("wrong folder", t["log"][-1]["text"])

    def test_job_approve_starts_apply_and_deny_skips(self):
        status, j = self.decide("job::a000000001", "approve")
        self.assertEqual((status, j["status"]), (200, "approved"))
        self.apply.assert_called_once_with("a000000001", "")
        status, j = self.decide("job:lei:b000000001", "deny")
        self.assertEqual((status, j["status"]), (200, "skipped"))
        self.assertEqual(self.decide("job:nobody:b000000001", "deny")[0], 404)

    def test_jharvis_dashboard_and_live(self):
        status, a = self.decide("jharvis:j1", "approve")
        self.assertEqual((status, a["status"]), (200, "approved"))
        self.live.reset_mock(return_value=True)
        self.live.return_value = {"ok": True}
        self.assertEqual(self.decide(f"jharvis-live:{LIVE_ID}", "deny")[0], 200)
        self.live.assert_called_once_with("POST", f"/api/approvals/{LIVE_ID}", {"approved": False})
        self.assertEqual(self.decide("jharvis-live:state", "approve")[0], 400)

    def test_skill_approve_installs_anyway_and_deny_dismisses(self):
        self.assertEqual(self.decide("skill:aaaaaaa1", "approve")[0], 200)
        self.lab_run.assert_called_once()
        server._lab_update("aaaaaaa1", lambda r: r.update(status="blocked"))
        self.assertEqual(self.decide("skill:aaaaaaa1", "deny")[0], 200)
        self.assertNotIn("skill:aaaaaaa1", self.ids())
        self.assertEqual(self.decide("skill:aaaaaaa2", "approve")[0], 400)  # only scanner blocks can be overruled

    def test_bad_input(self):
        self.assertEqual(self.decide("ticket:TKT-0001", "maybe")[0], 400)
        self.assertEqual(self.decide("nope:1", "approve")[0], 404)


if __name__ == "__main__":
    unittest.main()

"""Approvals queue: one list from every source, and each decision goes through the source's own code path."""
import json
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402
import tidy  # noqa: E402
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
            {"id": "j1", "tool": "post_slack", "args": {}, "status": "pending", "asked": datetime.now(timezone.utc).isoformat()},
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

    def test_every_card_has_plain_words(self):
        _, data = self.h.api("GET", "/api/approvals")
        for i in data["approvals"]:
            self.assertTrue(all(i.get(k) for k in ("what", "why", "if_no")), i["id"])
        j1 = next(i for i in data["approvals"] if i["id"] == "jharvis:j1")
        self.assertNotIn("post_slack", j1["what"])  # no tool name up front
        live = next(i for i in data["approvals"] if i["id"] == f"jharvis-live:{LIVE_ID}")
        self.assertEqual(live["why"], "He needs it to carry on with what you asked.")  # Jharvis's raw reason waits in the fold
        self.assertIn("you asked", live["detail"])
        for i in data["approvals"]:
            if i["kind"] == "jharvis":
                self.assertEqual(i["title"], i["what"])  # Waiting on you shows the plain words, never "run <tool>"

    def test_ask_without_plain_words_gets_a_fallback(self):
        status, a = self.h.api("POST", "/api/jharvis/approvals", {"tool": "run_shell", "args": {"command": "del C:\\x.txt"}})
        self.assertEqual(status, 200)
        self.assertEqual(a["what"], "Jharvis wants to: Make a change on your PC.")
        self.assertNotIn("del ", a["what"] + a["why"] + a["if_no"])
        self.assertEqual(a["details"], "del C:\\x.txt")  # the raw command waits in the fold
        _, a = self.h.api("POST", "/api/jharvis/approvals", {"tool": "made_up_tool", "args": {"x": 1}})
        self.assertEqual(a["what"], "Jharvis wants to: Do one step of what you asked.")  # no tool name, even unknown
        self.assertIn('"x": 1', a["details"])
        for tool in ("os_job_decision", "set_job_floor", "github_merge_pr", "github_some_new_thing", "post_slack", "bill_mark_paid"):
            what = self.h.api("POST", "/api/jharvis/approvals", {"tool": tool, "args": {}})[1]["what"]
            self.assertNotEqual(what, a["what"], tool)
            self.assertNotIn("_", what, tool)

    def test_details_always_carry_the_real_command(self):
        cmd = {"command": "del C:\\x.txt"}
        _, a = self.h.api("POST", "/api/jharvis/approvals", {"tool": "run_shell", "args": cmd, "what": "Tidy a file.", "details": "cleanup step"})
        self.assertIn("cleanup step", a["details"])
        self.assertIn(cmd["command"], a["details"])  # the model's details can't hide what really runs
        _, a = self.h.api("POST", "/api/jharvis/approvals", {"tool": "run_shell", "args": cmd, "details": f"Runs {cmd['command']}"})
        self.assertEqual(a["details"], f"Runs {cmd['command']}")  # already there: not added twice
        _, a = self.h.api("POST", "/api/jharvis/approvals", {"tool": "run_shell", "args": cmd, "details": "x" * 4500})
        self.assertIn(cmd["command"], a["details"])  # long model details can't push the real command past the cut

    def test_stale_jharvis_card_counts_as_expired(self):
        old = (datetime.now(timezone.utc) - timedelta(minutes=7)).isoformat()  # Jharvis died, or its expire call failed
        _write(server.JHARVIS_APPROVALS, [{"id": "j3", "tool": "run_shell", "args": {}, "status": "pending", "asked": old}])
        self.assertEqual(self.h.api("GET", "/api/jharvis/approvals")[1]["approvals"], [])
        self.assertNotIn("jharvis:j3", self.ids())
        self.assertEqual(self.h.api("POST", "/api/jharvis/approvals/j3", {"approve": True})[0], 409)

    def test_tidy_cards_are_plain(self):
        card = {"id": "work-claude-halfway", "path": "D:\\Work\\Claude\\halfway", "rel": "Work/Claude/halfway", "kind": "half",
                "why": "Has a HANDOFF/PLAN but nobody touched it for 30 days.", "pick": "Start a debate on how to finish it",
                "bytes": 2e9, "files": 3, "newest": "2026-10-01T00:00:00+00:00", "status": "pending", "only": []}
        drl = {**card, "id": "drl", "kind": "drl", "why": "Looks like Dr L work outside her folders.", "pick": "Move it into D:\\Dr L\\_sorted"}
        self.addCleanup(tidy.STATE.unlink, missing_ok=True)  # the other tests expect no tidy card
        for c in (card, drl):
            _write(tidy.STATE, {"cards": [c], "last_scan": "2026-10-30T00:00:00+00:00"})
            item = next(i for i in self.h.api("GET", "/api/approvals")[1]["approvals"] if i["kind"] == "tidy")
            up_front = " ".join(item[k] for k in ("title", "what", "why", "if_no"))
            for tech in ("\\", "/", "HANDOFF", "PLAN"):
                self.assertNotIn(tech, up_front, c["kind"])
            self.assertIn(c["path"], item["detail"])  # the real place stays in the fold

    def test_ticket_priority_in_plain_words(self):
        server._update_ticket("TKT-0001", lambda x: x.update(due="2099-01-01T00:00:00+00:00"))
        server._update_ticket("TKT-0002", lambda x: x.update(due="2000-01-01T00:00:00+00:00", severity="P2"))
        late = server.ticket_needs()[-1]
        self.assertEqual(late["detail"], "Not yet (high priority)")

    def test_set_a_persons_pay_floor(self):
        status, p = self.h.api("PUT", "/api/jobs/people/lei/floor", {"usd_per_hour": 7.5})
        self.assertEqual((status, p), (200, {"id": "lei", "name": "Lei Ann", "min_hourly_usd": 7.5}))
        self.assertEqual(people.PEOPLE["lei"]["min_hourly_usd"], 7.5)  # the next run uses it without a restart
        saved = json.loads((self.h.tmp / "config" / "owner.json").read_text(encoding="utf-8"))
        self.assertEqual(saved["people"]["lei"]["min_hourly_usd"], 7.5)
        self.assertEqual(self.h.api("PUT", "/api/jobs/people/nobody/floor", {"usd_per_hour": 7})[0], 404)
        for bad in (0, 201, "seven"):
            self.assertIn(self.h.api("PUT", "/api/jobs/people/lei/floor", {"usd_per_hour": bad})[0], (400, 422), bad)

    def test_plain_words_pass_through(self):
        plain = {"what": "Change Lei Ann's lowest rate to $5 an hour.", "why": "You asked me to.",
                 "if_no": "Her rate stays at $6.", "details": "set floor lei 5"}
        _, a = self.h.api("POST", "/api/jharvis/approvals", {"tool": "run_shell", "args": {"command": "x"}, **plain})
        _, data = self.h.api("GET", "/api/jharvis/approvals")  # what the dashboard's home page and the phone read
        got = next(x for x in data["approvals"] if x["id"] == a["id"])
        self.assertEqual({k: got[k] for k in plain}, {**plain, "details": "x\n\n" + plain["details"]})  # the real command leads the fold
        old = next(x for x in data["approvals"] if x["id"] == "j1")  # stored before the plain fields existed
        self.assertTrue(old["what"] and old["if_no"])
        item = next(i for i in self.h.api("GET", "/api/approvals")[1]["approvals"] if i["id"] == f"jharvis:{a['id']}")
        self.assertEqual((item["what"], item["detail"]), (plain["what"], got["details"]))
        self.live.return_value = {"approvals": [{"id": LIVE_ID, "tool": "send_email", "args": {}, "created": 1.0e9,
                                                 "expires_at": 1.0e9 + 300, **plain}]}
        live = next(i for i in self.h.api("GET", "/api/approvals")[1]["approvals"] if i["id"] == f"jharvis-live:{LIVE_ID}")
        self.assertEqual((live["what"], live["detail"]), (plain["what"], plain["details"]))

    def test_panel_keeps_plain_words(self):
        self.h.api("POST", "/api/jharvis/panels", {"id": "bill", "title": "Meralco", "lines": ["P 2,310"], "what": "Check this bill.", "why": ""})
        p = next(x for x in self.h.api("GET", "/api/jharvis/panels")[1]["panels"] if x["id"] == "bill")
        self.assertEqual(p["what"], "Check this bill.")
        self.assertNotIn("why", p)  # empty fields aren't stored
        self.h.api("DELETE", "/api/jharvis/panels/bill")

    def test_waiting_on_you_names_tickets_not_ids(self):
        server._update_ticket("TKT-0001", lambda x: x.update(due="2099-01-01T00:00:00+00:00"))
        server._update_ticket("TKT-0002", lambda x: x.update(due="2000-01-01T00:00:00+00:00", severity="high"))
        waiting, late = server.ticket_needs()
        self.assertEqual((waiting["detail"], waiting["details"]), ("Fix it", "TKT-0001"))
        self.assertEqual((late["detail"], late["details"]), ("Not yet (high priority)", "TKT-0002"))

    def test_jharvis_follows_only_his_own_tickets(self):
        status, t = self.h.api("POST", "/api/tickets", {"title": "Mine", "severity": "P4", "project": str(self.h.tmp),
                                                        "by": "jharvis"})
        self.assertEqual((status, t["by"]), (200, "jharvis"))
        status, data = self.h.api("GET", "/api/tickets?by=jharvis")
        self.assertEqual([x["id"] for x in data["tickets"]], [t["id"]])
        self.assertEqual(self.h.api("GET", "/api/tickets?by=someone")[1]["tickets"], [])

    def test_a_ticket_jharvis_is_asking_about_is_listed_once(self):
        _write(server.JHARVIS_APPROVALS, [{"id": "j3", "tool": "os_ticket_plan", "args": {"ticket": "TKT-0001"},
                                           "status": "pending", "asked": datetime.now(timezone.utc).isoformat()}])
        self.assertEqual(self.ids() & {"ticket:TKT-0001", "jharvis:j3"}, {"jharvis:j3"})

    def test_bad_input(self):
        self.assertEqual(self.decide("ticket:TKT-0001", "maybe")[0], 400)
        self.assertEqual(self.decide("nope:1", "approve")[0], 404)


if __name__ == "__main__":
    unittest.main()

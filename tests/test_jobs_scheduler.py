"""Job pipeline + scheduler unit tests: the jobs/ selftests, the seen.json logic and the scheduler reload.

No server, no network, no real data/: every path is patched to a temp dir and the agents are fakes.
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import jobs.apply_runner as apply_runner  # noqa: E402
import scheduler.scheduler as sched  # noqa: E402
from jobs import lanes, pay, people, pipeline, sources  # noqa: E402


class SelftestTests(unittest.TestCase):
    def test_jobs_selftests(self):
        for mod in (lanes, pay, pipeline, people, sources, apply_runner):
            with self.subTest(mod=mod.__name__):
                mod._selftest()


class SeenTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.seen_file = self.tmp / "seen.json"
        self.board_file = self.tmp / "board.json"

    def _run(self, offers, replies):
        calls = iter(replies)
        run_agent = lambda agent, prompt: next(calls) if agent == "opencode" else "A fine draft. " * 10
        with mock.patch.multiple(pipeline, BOARD=self.board_file, SEEN=self.seen_file, MAX_SCORE=4, BATCH=2,
                                 CV=self.tmp / "no-cv.md", ANSWERS=self.tmp / "no-answers.md",
                                 hoot_offers=lambda log=print: offers, indeed_alert_offers=lambda seen, log=print: [],
                                 pay_ok=lambda o: True), \
                mock.patch.object(pipeline.sources, "fetch_all", lambda log=print: []), \
                mock.patch.object(pipeline.lanes, "gate", lambda o, jd: "nope" if o["title"] == "Reject" else ""), \
                mock.patch.object(pipeline.lanes, "bar", lambda j: 3):
            return pipeline.run(run_agent, log=lambda m: None)

    def test_only_answered_offers_are_seen(self):
        self.seen_file.write_text(json.dumps(["oldid00001"]))  # the old list format
        offers = [{"url": f"https://x/{n}", "company": f"Co{n}", "title": "Video Editor", "jd": ""} for n in range(6)]
        offers.append({"url": "https://x/rej", "company": "CoR", "title": "Reject", "jd": ""})
        good = json.dumps([{"i": 0, "score": 4}, {"i": 1, "score": 4}])
        stats = self._run(offers, [f"here: {good} (scores are [1-5])", "sorry, can't [1-5]"])
        seen = json.loads(self.seen_file.read_text())
        ids = [pipeline.job_id(o["url"]) for o in offers]
        self.assertEqual(stats["scored"], 2)
        self.assertTrue(any("batch 2 got no scores" in e for e in stats["errors"]))
        self.assertEqual(seen[ids[0]], "keep")             # on the board: never ages out
        self.assertEqual(seen[ids[1]], "keep")
        self.assertIn(ids[6], seen)                        # rejected by the gate
        self.assertIn("oldid00001", seen)                  # old list entries survive the format change
        for i in ids[2:6]:                                 # failed batch + past MAX_SCORE: back next run
            self.assertNotIn(i, seen)

    def test_save_seen_keeps_board_jobs_and_drops_old(self):
        seen = {"stale": "2000-01-01T00:00:00+00:00", "recent": pipeline.now(), "kept": "keep"}
        pipeline.save_seen(self.seen_file, seen, [{"id": "skipped1", "status": "skipped"}])
        self.assertEqual(json.loads(self.seen_file.read_text()),
                         {"recent": seen["recent"], "kept": "keep", "skipped1": "keep"})


class SchedulerReloadTests(unittest.TestCase):
    def test_bad_files_do_not_drop_good_schedules(self):
        from apscheduler.schedulers.background import BackgroundScheduler
        tmp = Path(tempfile.mkdtemp())
        (tmp / "a-good.json").write_text(json.dumps({"id": "good", "name": "good", "cron": "0 * * * *"}))
        (tmp / "b-broken.json").write_text("{not json")
        (tmp / "c-badcron.json").write_text(json.dumps({"id": "bad", "name": "bad", "cron": "not a cron"}))
        s = sched.CronScheduler()
        s._scheduler = BackgroundScheduler()  # never started: jobs stay pending, nothing fires
        s._scheduler.add_job(print, "interval", hours=1, id="removed", name="removed")
        with mock.patch.object(sched, "JOBS_DIR", tmp):
            s._reload_jobs()
        self.assertEqual([j.id for j in s._scheduler.get_jobs()], ["good"])


class DailySearchWaitTests(unittest.TestCase):
    """TKT-0022: the scheduled daily search waits for a running invite check instead of skipping and saying 'completed'."""
    DAILY = {"id": "daily-test", "name": "Daily Test", "kind": "jobs"}

    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, str(ROOT / "tests"))
        from harness import Harness  # paths only, no server start: nothing writes to real data/
        cls.h = Harness()
        import server
        cls.s = server

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def setUp(self):
        import threading
        self.events, self.active, self.max_active = [], 0, 0
        self.count_lock = threading.Lock()
        self.release = {True: threading.Event(), False: threading.Event()}  # keyed by invites_only
        self.started = {True: threading.Event(), False: threading.Event()}
        self.fail = False

    def fake_run(self, run_agent, log=print, after=None, invites_only=False):
        with self.count_lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        self.events.append(("start", "invites" if invites_only else "daily"))
        self.started[invites_only].set()
        self.release[invites_only].wait(5)
        with self.count_lock:
            self.active -= 1
        self.events.append(("end", "invites" if invites_only else "daily"))
        if self.fail and not invites_only:
            raise RuntimeError("search broke")
        return {"drafted": 0, "auto_sent": 0, "needs_you": 0}

    def patched(self):
        from contextlib import ExitStack
        stack = ExitStack()
        stack.enter_context(mock.patch.object(pipeline, "run", self.fake_run))
        stack.enter_context(mock.patch.object(sched, "emit_event", lambda e: self.events.append(("done", e["status"]))))
        stack.enter_context(mock.patch.object(self.s, "append_audit", lambda e: None))
        stack.enter_context(mock.patch.object(self.s, "run_followups", lambda: 0))
        stack.enter_context(mock.patch.dict(people.PEOPLE, clear=True))
        return stack

    def thread(self, target, *args):
        import threading
        t = threading.Thread(target=target, args=args, daemon=True)
        t.start()
        return t

    def test_daily_waits_for_invite_check(self):
        with self.patched():
            invites = self.thread(self.s.run_upwork_invites)
            self.assertTrue(self.started[True].wait(5))
            daily = self.thread(self.s.run_scheduled, self.DAILY, "cron")
            daily.join(0.5)
            self.assertTrue(daily.is_alive())                     # waiting, not skipped
            self.assertEqual(self.events, [("start", "invites")])  # no search and no completion yet
            self.release[False].set()
            self.release[True].set()
            invites.join(5)
            daily.join(5)
        self.assertEqual(self.events, [("start", "invites"), ("end", "invites"),
                                       ("start", "daily"), ("end", "daily"), ("done", "completed")])
        self.assertEqual(self.max_active, 1)
        self.assertFalse(self.s._jobs_lock.locked())

    def test_invite_check_during_daily_search(self):
        with self.patched():
            daily = self.thread(self.s.run_scheduled, self.DAILY, "cron")
            self.assertTrue(self.started[False].wait(5))
            self.assertEqual(self.s.run_upwork_invites(), {"skipped": "a job run is already going"})
            self.release[False].set()
            daily.join(5)
        self.assertEqual(self.events, [("start", "daily"), ("end", "daily"), ("done", "completed")])
        self.assertEqual(self.max_active, 1)
        self.assertFalse(self.s._jobs_lock.locked())

    def test_ordinary_call_still_skips_when_busy(self):
        self.s._jobs_lock.acquire()
        try:
            self.assertEqual(self.s.run_job_pipeline(), {"skipped": "a job run is already going"})
        finally:
            self.s._jobs_lock.release()

    def test_search_error_records_failure_and_frees_lock(self):
        self.fail = True
        self.release[False].set()
        with self.patched():
            self.s.run_scheduled(self.DAILY, "cron")
        self.assertEqual(self.events, [("start", "daily"), ("end", "daily"), ("done", "failed")])
        self.assertFalse(self.s._jobs_lock.locked())


if __name__ == "__main__":
    unittest.main()

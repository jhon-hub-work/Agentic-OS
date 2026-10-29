"""Watchdog decisions: act only after a run of failed checks, then back off; never touch the PC in focus mode."""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import watchdog  # noqa: E402

MIN = 60


def run(states_up, need=3, grace=300):
    """Feed one check a minute; return the verdicts."""
    s = watchdog.new_state()
    return [watchdog.decide(s, up, i * MIN, need, grace) for i, up in enumerate(states_up)]


class DecideTests(unittest.TestCase):
    def test_blip_is_ignored(self):
        self.assertEqual(run([True, False, False, True, False, True]), ["up", "down", "down", "up", "down", "up"])

    def test_restarts_after_three_down(self):
        self.assertEqual(run([False] * 3), ["down", "down", "restart"])

    def test_grace_then_doubling_backoff(self):
        v = run([False] * 40)  # 40 min down, grace 5 min
        restarts = [i for i, x in enumerate(v) if x == "restart"]
        self.assertEqual(restarts, [2, 7, 17, 37])  # waits 5, 10, 20 min between tries

    def test_backoff_capped_at_an_hour(self):
        s = watchdog.new_state()
        t = [i * MIN for i in range(600) if watchdog.decide(s, False, i * MIN, 3, 300) == "restart"]
        self.assertLessEqual(max(b - a for a, b in zip(t, t[1:])), watchdog.MAX_WAIT)

    def test_back_after_outage_and_flapping_keeps_backoff(self):
        s = watchdog.new_state()
        for i in range(3):
            watchdog.decide(s, False, i * MIN, 3, 300)
        self.assertEqual(s["tries"], 1)
        self.assertEqual(watchdog.decide(s, True, 4 * MIN, 3, 300), "back")
        self.assertEqual(s["tries"], 1)  # up for a minute is not forgiven yet
        watchdog.decide(s, True, 4 * MIN + watchdog.STABLE, 3, 300)
        self.assertEqual(s["tries"], 0)  # stable for 30 min: forgiven

    def test_voice_waits_out_model_loading(self):
        need, grace = watchdog.RULES["voice"]
        self.assertNotIn("restart", run([False] * (need - 1), need, grace))


class CheckTests(unittest.TestCase):
    """check() with the probes and restarts swapped for fakes: nothing real is started or killed."""

    def setUp(self):
        self.started = []
        self.states = {n: watchdog.new_state() for n in watchdog.SERVICES}

    def services(self, os_up, hermes_up, voice_up):
        return {"os": (lambda: os_up, lambda: self.started.append("os")),
                "hermes": (lambda: hermes_up, lambda: self.started.append("hermes")),
                "voice": (lambda: voice_up, lambda: self.started.append("voice"))}

    def checks(self, n, services, running=False, dry=False):
        with mock.patch.object(watchdog, "SERVICES", services), mock.patch.object(watchdog, "running", lambda p: running):
            for _ in range(n):
                watchdog.check(self.states, dry, verbose=False)

    def test_os_not_started_twice_while_its_process_runs(self):
        self.checks(5, self.services(False, True, True), running=True)
        self.assertEqual(self.started, [])

    def test_os_started_when_no_process(self):
        self.checks(3, self.services(False, True, True), running=False)
        self.assertEqual(self.started, ["os"])

    def test_hermes_left_to_os_server_while_os_down(self):
        self.checks(5, self.services(False, False, True), running=True)
        self.assertNotIn("hermes", self.started)

    def test_hermes_restarted_when_os_up(self):
        self.checks(3, self.services(True, False, True))
        self.assertEqual(self.started, ["hermes"])

    def test_voice_closed_app_left_alone(self):
        self.checks(12, self.services(True, True, False), running=False)
        self.assertEqual(self.started, [])

    def test_dry_run_starts_nothing(self):
        self.checks(12, self.services(False, False, False), running=True, dry=True)
        self.assertEqual(self.started, [])


class FocusTests(unittest.TestCase):
    def test_focus_flag_pauses_everything(self):
        with mock.patch.object(watchdog, "FOCUS_FLAG", mock.Mock(exists=lambda: True)), \
             mock.patch.object(watchdog, "check") as check, mock.patch.object(watchdog, "setup_log"), \
             mock.patch.object(sys, "argv", ["watchdog.py", "--once", "--dry-run"]):
            watchdog.main()
        check.assert_not_called()


if __name__ == "__main__":
    unittest.main()

"""Jhon, 3 Nov: "Always allow" on Jharvis's card, and the phone unlock a parked card waits for."""
import json
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402


class AlwaysAllowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def setUp(self):
        server.JHARVIS_APPROVALS.parent.mkdir(parents=True, exist_ok=True)
        server.JHARVIS_APPROVALS.write_text("[]", encoding="utf-8")
        self.audit = mock.MagicMock()
        p = mock.patch.object(server, "append_audit", self.audit)
        p.start()
        self.addCleanup(p.stop)

    def ask(self, **kw):
        return self.h.api("POST", "/api/jharvis/approvals", {"tool": "run_shell", "args": {"command": "npm run build"}, **kw})[1]

    def test_always_counts_only_on_a_grantable_card(self):
        a = self.ask(grantable=True)
        self.assertTrue(self.h.api("GET", "/api/jharvis/approvals")[1]["approvals"][0]["grantable"])
        self.assertTrue(self.h.api("POST", f"/api/jharvis/approvals/{a['id']}", {"approve": True, "always": True})[1]["always"])
        b = self.ask()  # Jharvis's floor said no: no button, and a hand-made "always" is ignored
        self.assertFalse(b["grantable"])
        self.assertFalse(self.h.api("POST", f"/api/jharvis/approvals/{b['id']}", {"approve": True, "always": True})[1]["always"])
        c = self.ask(grantable=True)  # a deny is never an always
        self.assertFalse(self.h.api("POST", f"/api/jharvis/approvals/{c['id']}", {"approve": False, "always": True})[1]["always"])
        d = self.ask(grantable=True)  # an old page that sends only approve
        self.assertFalse(self.h.api("POST", f"/api/jharvis/approvals/{d['id']}", {"approve": True})[1]["always"])

    def test_the_audit_names_the_tool_never_the_command(self):
        a = self.ask(grantable=True, args={"command": "python D:\\Dr L\\clinic.py"})
        self.h.api("POST", f"/api/jharvis/approvals/{a['id']}", {"approve": True, "always": True})
        entry = self.audit.call_args[0][0]
        self.assertEqual(entry["msg_preview"], "approved (always): run_shell")
        self.assertNotIn("clinic", json.dumps(entry))


class UnlockPingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def setUp(self):
        d = self.h.tmp / "data" / "jharvis"
        d.mkdir(parents=True, exist_ok=True)
        for name, f in (("QUIET_FILE", "quiet.json"), ("PHONE_OUTBOX", "outbox.json")):
            (d / f).unlink(missing_ok=True)
            p = mock.patch.object(server, name, d / f)
            p.start()
            self.addCleanup(p.stop)

    def test_a_parked_card_asks_the_phone_for_its_next_unlock(self):
        state = self.h.api("POST", "/api/jharvis/quiet/want-unlock")[1]
        self.assertEqual((state["summary"], state["want_unlock"], state["held"]), ("waiting_unlock", True, 0))
        state = self.h.api("POST", "/api/jharvis/quiet/unlocked")[1]
        self.assertEqual(state["summary"], "none")  # nothing held: no summary is due
        self.assertNotIn("want_unlock", state)
        self.assertLess(abs(datetime.fromisoformat(state["unlocked"]).timestamp() - datetime.now(timezone.utc).timestamp()), 60)

    def test_quiet_on_hides_the_ask_and_a_real_summary_still_comes_due(self):
        self.h.api("POST", "/api/jharvis/quiet/want-unlock")
        self.assertEqual(self.h.api("PUT", "/api/jharvis/quiet", {"on": True, "by": "jharvis"})[1]["summary"], "none")
        self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "chat", "text": "Bee: hi"})
        self.assertEqual(self.h.api("PUT", "/api/jharvis/quiet", {"on": False, "by": "jharvis"})[1]["summary"], "waiting_unlock")
        self.assertEqual(self.h.api("POST", "/api/jharvis/quiet/unlocked")[1]["summary"], "due")


if __name__ == "__main__":
    unittest.main()

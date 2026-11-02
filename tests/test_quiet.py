"""Quiet mode (2 Nov): Jhon's sleep / Do Not Disturb. While on, everything that would ping or speak to him is held,
and Jharvis sums it up at his first unlock after it ends."""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402


class QuietTests(unittest.TestCase):
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

    def quiet(self, on, by="jharvis"):
        status, state = self.h.api("PUT", "/api/jharvis/quiet", {"on": on, "by": by})
        self.assertEqual(status, 200)
        return state

    def outbox(self):
        return self.h.api("GET", "/api/phone/outbox")[1]["items"]

    def test_starts_off(self):
        self.assertEqual(self.h.api("GET", "/api/jharvis/quiet")[1],
                         {"on": False, "since": None, "by": None, "held": 0, "summary": "none"})
        self.assertEqual(self.h.api("PUT", "/api/jharvis/quiet", {"on": True, "by": "someone"})[0], 400)

    def test_on_holds_say_and_notify_but_not_replies(self):
        state = self.quiet(True, by="mode")
        self.assertTrue(state["on"])
        self.assertEqual((state["by"], state["summary"]), ("mode", "none"))
        self.assertTrue(state["since"])
        self.assertEqual([(i["kind"], i["on"]) for i in self.outbox()], [("dnd", True)])  # the phone mirrors it
        status, item = self.h.api("POST", "/api/phone/outbox", {"kind": "say", "text": "Your render finished"})
        self.assertEqual((status, item["result"], item["kind"]), (200, "held", "say"))
        self.h.api("POST", "/api/phone/outbox", {"kind": "notify", "text": "Meralco bill due Friday"})
        reply = self.h.api("POST", "/api/phone/outbox", {"kind": "reply", "text": "on my way", "conv": "x|Bee"})[1]
        self.assertEqual([i["kind"] for i in self.outbox()], ["dnd", "reply"])  # say/notify never reached the phone
        self.assertIsNone(reply["result"])
        held = self.h.api("GET", "/api/jharvis/quiet/held")[1]["items"]
        self.assertEqual([(i["kind"], i["text"]) for i in held],
                         [("say", "Your render finished"), ("notify", "Meralco bill due Friday")])
        self.assertLess(held[0]["n"], held[1]["n"])
        self.assertTrue(held[0]["at"])
        self.assertEqual(self.h.api("GET", "/api/jharvis/quiet")[1]["held"], 2)

    def test_same_state_is_a_no_op(self):
        first = self.quiet(True)
        again = self.quiet(True, by="mode")
        self.assertEqual((again["since"], again["by"]), (first["since"], "jharvis"))
        self.assertEqual(len(self.outbox()), 1)
        self.quiet(False)
        self.quiet(False)
        self.assertEqual([b["on"] for b in server._jload(server.PHONE_OUTBOX, [])], [True, False])
        self.assertEqual([i["on"] for i in self.outbox()], [False])  # the off supersedes the unrun on

    def test_phone_changes_are_not_echoed_back(self):
        self.quiet(True, by="phone")
        self.quiet(False, by="phone")
        self.assertEqual(self.outbox(), [])

    def test_summary_waits_for_unlock_then_held_cleared(self):
        self.quiet(True, by="phone")
        self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "chat", "text": "Bee on Messenger: you up?"})
        self.h.api("POST", "/api/phone/outbox", {"kind": "say", "text": "Job search found 3 roles"})
        state = self.quiet(False, by="phone")
        self.assertEqual((state["on"], state["summary"], state["held"]), (False, "waiting_unlock", 2))
        self.assertEqual(self.h.api("POST", "/api/jharvis/quiet/unlocked")[1]["summary"], "due")
        self.assertEqual(self.h.api("POST", "/api/jharvis/quiet/unlocked")[1]["summary"], "due")  # stays due
        top = max(i["n"] for i in self.h.api("GET", "/api/jharvis/quiet/held")[1]["items"])
        self.assertEqual(self.h.api("DELETE", f"/api/jharvis/quiet/held?upto={top}")[0], 200)
        state = self.h.api("GET", "/api/jharvis/quiet")[1]
        self.assertEqual((state["held"], state["summary"]), (0, "none"))
        self.assertEqual(self.h.api("POST", "/api/phone/outbox", {"kind": "say", "text": "hi"})[1]["result"], None)

    def test_off_with_nothing_held_needs_no_summary(self):
        self.quiet(True)
        self.assertEqual(self.quiet(False)["summary"], "none")
        self.assertEqual(self.h.api("POST", "/api/jharvis/quiet/unlocked")[1]["summary"], "none")

    def test_back_on_before_unlock_keeps_held_for_next_summary(self):
        self.quiet(True)
        self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "jharvis", "text": "Backup done"})
        self.quiet(False)
        state = self.quiet(True)
        self.assertEqual((state["summary"], state["held"]), ("none", 1))
        self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "declined", "text": "Didn't send the invoice email"})
        self.assertEqual(self.quiet(False)["held"], 2)

    def test_delete_keeps_later_items(self):
        self.quiet(True)
        a = self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "chat", "text": "one"})[1]
        self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "chat", "text": "two"})
        self.quiet(False)
        self.h.api("POST", "/api/jharvis/quiet/unlocked")
        self.h.api("DELETE", f"/api/jharvis/quiet/held?upto={a['n']}")
        state = self.h.api("GET", "/api/jharvis/quiet")[1]
        # stays "due" on purpose: Jharvis sends leftovers found within 2 min of a summary as one plain line
        self.assertEqual((state["held"], state["summary"]), (1, "due"))

    def test_held_cap_and_checks(self):
        self.assertEqual(self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "dance", "text": "x"})[0], 400)
        self.assertEqual(self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "chat", "text": "  "})[0], 400)
        item = self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "chat", "text": "x" * 5000})[1]
        self.assertEqual(len(item["text"]), 2000)
        for i in range(305):
            server.quiet_hold("chat", f"m{i}", only_if_on=False)
        items = self.h.api("GET", "/api/jharvis/quiet/held")[1]["items"]
        self.assertEqual((len(items), items[-1]["text"]), (300, "m304"))

    def test_dnd_outbox_item_goes_through(self):
        self.quiet(True)
        status, item = self.h.api("POST", "/api/phone/outbox", {"kind": "dnd", "on": False, "text": ""})
        self.assertEqual((status, item["kind"], item["on"], item["result"]), (200, "dnd", False, None))

    def test_dnd_outbox_item_needs_on(self):
        self.assertEqual(self.h.api("POST", "/api/phone/outbox", {"kind": "dnd", "text": ""})[0], 400)
        self.assertEqual(self.outbox(), [])

    def test_held_while_off_waits_for_next_unlock(self):
        # Jharvis's quiet flag can lag up to 5 s, so an item filed just after quiet ended still gets summarised
        self.quiet(True)
        self.quiet(False)
        self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "jharvis", "text": "Draft ready"})
        state = self.h.api("GET", "/api/jharvis/quiet")[1]
        self.assertEqual((state["held"], state["summary"]), (1, "waiting_unlock"))
        self.assertEqual(self.h.api("POST", "/api/jharvis/quiet/unlocked")[1]["summary"], "due")
        self.h.api("POST", "/api/jharvis/quiet/held", {"kind": "chat", "text": "Bee: you up?"})
        self.assertEqual(self.h.api("GET", "/api/jharvis/quiet")[1]["summary"], "due")  # a due summary stays due

    def test_new_quiet_change_drops_older_dnd_items(self):
        self.quiet(True, by="mode")
        self.quiet(False, by="phone")  # Jhon turned DND off on the phone before it ran the "on"
        self.assertEqual(self.outbox(), [])
        self.quiet(True, by="mode")
        self.quiet(False, by="jharvis")
        self.assertEqual([(i["kind"], i["on"]) for i in self.outbox()], [("dnd", False)])
        old = [b for b in server._jload(server.PHONE_OUTBOX, []) if b["kind"] == "dnd" and b["result"]]
        self.assertEqual({b["result"] for b in old}, {"superseded"})


if __name__ == "__main__":
    unittest.main()

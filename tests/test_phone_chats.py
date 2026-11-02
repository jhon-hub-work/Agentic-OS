"""Chat alerts (1 Nov): phone chat notifications in, mutes, and the reply outbox the phone empties."""
import sys
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402


class PhoneChatTests(unittest.TestCase):
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
        for name, f in (("PHONE_CHATS", "chats.json"), ("PHONE_MUTES", "mutes.json"), ("PHONE_OUTBOX", "outbox.json")):
            (d / f).unlink(missing_ok=True)
            p = mock.patch.object(server, name, d / f)
            p.start()
            self.addCleanup(p.stop)

    def test_chat_in_then_muted(self):
        bee = {"conv": "com.facebook.orca|Bee", "app": "Messenger", "title": "Bee", "sender": "Bee", "text": "hi", "can_reply": True}
        self.assertEqual(self.h.api("POST", "/api/phone/chat", bee)[1]["muted"], False)
        chats = self.h.api("GET", "/api/phone/chats?after=0")[1]["chats"]
        self.assertEqual([c["text"] for c in chats], ["hi"])
        self.assertEqual(self.h.api("GET", f"/api/phone/chats?after={chats[0]['seq']}")[1]["chats"], [])
        self.h.api("POST", "/api/phone/mutes", {"conv": bee["conv"]})
        self.assertTrue(self.h.api("POST", "/api/phone/chat", bee)[1]["muted"])
        self.assertEqual(len(self.h.api("GET", "/api/phone/chats?after=0")[1]["chats"]), 1)  # muted one not stored
        self.h.api("POST", "/api/phone/mutes", {"conv": bee["conv"], "muted": False})
        self.assertEqual(self.h.api("GET", "/api/phone/mutes")[1]["mutes"], [])

    def test_outbox_reply_done_once_and_stale_dropped(self):
        status, _ = self.h.api("POST", "/api/phone/outbox", {"kind": "reply", "text": "on my way"})
        self.assertEqual(status, 400)  # a reply needs its chat
        item = self.h.api("POST", "/api/phone/outbox", {"kind": "reply", "text": "on my way", "conv": "x|Bee"})[1]
        self.assertEqual([i["id"] for i in self.h.api("GET", "/api/phone/outbox")[1]["items"]], [item["id"]])
        self.assertEqual(self.h.api("POST", f"/api/phone/outbox/{item['id']}", {"result": "sent"})[0], 200)
        self.assertEqual(self.h.api("POST", f"/api/phone/outbox/{item['id']}", {"result": "sent"})[0], 404)  # never twice
        self.assertEqual(self.h.api("GET", f"/api/phone/outbox/{item['id']}")[1]["result"], "sent")
        self.assertEqual(self.h.api("GET", "/api/phone/outbox")[1]["items"], [])
        old = self.h.api("POST", "/api/phone/outbox", {"kind": "say", "text": "hello"})[1]
        with mock.patch.object(server.time, "time", return_value=time.time() + 700):
            self.assertNotIn(old["id"], [i["id"] for i in server.phone_outbox()["items"]])

    def test_outbox_notify_is_a_notification_kind(self):
        status, item = self.h.api("POST", "/api/phone/outbox", {"kind": "notify", "text": "Meralco bill due Friday"})
        self.assertEqual((status, item["kind"]), (200, "notify"))
        self.assertEqual(self.h.api("POST", "/api/phone/outbox", {"kind": "dance", "text": "x"})[0], 400)


if __name__ == "__main__":
    unittest.main()

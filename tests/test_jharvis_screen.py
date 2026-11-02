"""Jharvis's home screen: the shapes his dots form, closing the Waiting on you card, bill cards and photos."""
import re
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402

PNG, JPG = bytes([0x89]) + b"PNG" + bytes([13, 10, 26, 10]), bytes([0xFF, 0xD8, 0xFF, 0xE0])  # the first bytes of a real PNG / JPEG


class JharvisScreenTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def setUp(self):
        d = self.h.tmp / "data" / "jharvis"
        patches = [mock.patch.object(server, "JHARVIS_SHAPE", d / "shape.json"),
                   mock.patch.object(server, "NEEDS_CLOSED", d / "needs-closed.json"),
                   mock.patch.object(server, "JHARVIS_PANELS", d / "panels.json")]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)

    def test_shape_by_name_or_drawing_then_cleared(self):
        status, _ = self.h.api("POST", "/api/jharvis/shape", {"name": "heart", "seconds": 5})
        self.assertEqual(status, 200)
        self.assertEqual(self.h.api("GET", "/api/jharvis/panels")[1]["shape"]["name"], "heart")
        status, _ = self.h.api("POST", "/api/jharvis/shape", {"paths": ["M10 10 L90 90 Z"], "fill": True})
        self.assertEqual(status, 200)
        self.h.api("DELETE", "/api/jharvis/shape")
        self.assertIsNone(self.h.api("GET", "/api/jharvis/panels")[1]["shape"])

    def test_shape_rejects_anything_but_path_data(self):
        for body in ({"paths": ['M0 0"/><script>alert(1)</script>']}, {"name": "../x"}, {}, {"name": "orb", "seconds": 999}):
            self.assertEqual(self.h.api("POST", "/api/jharvis/shape", body)[0], 400, body)

    def test_close_all_also_closes_waiting_on_you_until_it_changes(self):
        self.assertFalse(self.h.api("GET", "/api/jharvis/home")[1]["closed"])
        self.h.api("DELETE", "/api/jharvis/panels/all")
        self.assertTrue(self.h.api("GET", "/api/jharvis/home")[1]["closed"])
        server.NEEDS_CLOSED.write_text('{"sig": "older items"}')  # what needs him changed since: the card is back
        self.assertFalse(self.h.api("GET", "/api/jharvis/home")[1]["closed"])

    def test_closed_waiting_on_you_comes_back_when_a_bill_gets_closer(self):
        self.addCleanup(server.JHARVIS_PANELS.unlink, missing_ok=True)
        self.h.api("POST", "/api/jharvis/panels", {"id": "bill-7", "title": "Your Meralco bill", "lines": ["Due tomorrow"]})
        server.close_needs_card()
        self.assertTrue(self.h.api("GET", "/api/jharvis/home")[1]["closed"])
        self.h.api("POST", "/api/jharvis/panels", {"id": "bill-7", "title": "Your Meralco bill", "lines": ["Due today"]})
        self.assertFalse(self.h.api("GET", "/api/jharvis/home")[1]["closed"])  # same title, new detail: the card is back

    def test_bill_cards_wait_on_you_and_survive_the_panel_limit(self):
        self.addCleanup(server.JHARVIS_PANELS.unlink, missing_ok=True)
        self.h.api("POST", "/api/jharvis/panels", {"id": "bill-7", "title": "Your Meralco bill", "lines": ["Due Friday, P 2,310"]})
        for n in range(7):
            self.h.api("POST", "/api/jharvis/panels", {"id": f"p{n}", "title": f"Panel {n}"})
        ids = [p["id"] for p in self.h.api("GET", "/api/jharvis/panels")[1]["panels"]]
        self.assertIn("bill-7", ids)
        self.assertEqual(len([i for i in ids if not i.startswith("bill-")]), 6)
        home = self.h.api("GET", "/api/jharvis/home")[1]
        bills = [n for n in [home["urgent"], *home["next"]] if n and n["kind"] == "bill"]
        self.assertEqual([(b["title"], b["detail"]) for b in bills], [("Your Meralco bill", "Due Friday, P 2,310")])

    def test_photo_upload_adds_a_chat_message_and_serves_the_file(self):
        png = PNG + b"0" * 64
        status, m = self.h.upload("/api/jharvis/photos", {"client": "pc", "caption": "My Meralco bill"}, "bill.png", png)
        self.assertEqual(status, 200, m)
        self.assertEqual((m["role"], m["text"], m["client"]), ("owner", "My Meralco bill", "pc"))
        self.assertRegex(m["photo"], r"^[0-9a-f]{8,32}\.png$")
        self.assertTrue((self.h.tmp / "data" / "jharvis" / "photos" / m["photo"]).exists())
        self.assertEqual(self.h.request("GET", f"/api/jharvis/photos/{m['photo']}"), (200, png))
        last = self.h.api("GET", "/api/jharvis/messages?limit=1")[1]["messages"][-1]
        self.assertEqual(last["photo"], m["photo"])
        _, m = self.h.upload("/api/jharvis/photos", {"client": "phone"}, "x.jpg", JPG + b"0" * 64)
        self.assertEqual((m["text"], m["photo"][-4:]), ("Photo", ".jpg"))

    def test_photo_upload_refuses_non_images_bad_clients_and_big_files(self):
        self.assertEqual(self.h.upload("/api/jharvis/photos", {"client": "pc"}, "x.png", b"<svg onload=alert(1)>")[0], 400)
        self.assertEqual(self.h.upload("/api/jharvis/photos", {"client": "tv"}, "x.png", PNG + b"0")[0], 400)
        self.assertEqual(self.h.upload("/api/jharvis/photos", {"client": "pc"}, "x.png", PNG + b"0" * (11 * 1024 * 1024))[0], 413)
        for name in ("..%2Fsecrets.png", "abc.svg", "short.png", "DEADBEEF.png"):
            self.assertEqual(self.h.request("GET", f"/api/jharvis/photos/{name}")[0], 404, name)

    def test_message_photo_only_from_the_owner_and_only_a_plain_name(self):
        ok = self.h.api("POST", "/api/jharvis/messages", {"role": "owner", "text": "hi", "photo": "deadbeef.webp"})[1]
        self.assertEqual(ok["photo"], "deadbeef.webp")
        for body in ({"role": "jharvis", "text": "hi", "photo": "deadbeef.png"}, {"role": "owner", "text": "hi", "photo": "../deadbeef.png"},
                     {"role": "owner", "text": "hi", "photo": "deadbeef.png.exe"}):
            self.assertNotIn("photo", self.h.api("POST", "/api/jharvis/messages", body)[1], body)
        self.assertTrue(re.fullmatch(server.PHOTO_NAME, "0123abcd.jpeg"))


if __name__ == "__main__":
    unittest.main()

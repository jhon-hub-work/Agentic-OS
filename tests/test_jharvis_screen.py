"""Jharvis's home screen: the shapes his dots form, and closing the Waiting on you card."""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()

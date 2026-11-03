"""Jhon, 3 Nov ("Ask + dashboard page"): the Jharvis log page reads his audit log through his control UI."""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402

import server  # noqa: E402

ROW = {"at": 1.0, "surface": "slack", "who": "Jhon", "tool": "run_shell", "args": "command: dir", "status": "ran",
       "how": "auto-safe", "result": "exit code 0"}


class JharvisAuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def test_rows_come_from_jharvis_with_the_filters(self):
        with mock.patch.object(server, "jharvis_ui", return_value={"rows": [ROW]}) as ui:
            code, body = self.h.api("GET", "/api/jharvis/audit?day=today&status=ran")
        self.assertEqual((code, body), (200, {"rows": [ROW], "off": False}))
        ui.assert_called_once_with("GET", "/api/audit?day=today&status=ran")
        with mock.patch.object(server, "jharvis_ui", return_value={"rows": []}) as ui:
            self.h.api("GET", "/api/jharvis/audit")
        ui.assert_called_once_with("GET", "/api/audit")

    def test_jharvis_off_is_an_empty_log(self):
        with mock.patch.object(server, "jharvis_ui", side_effect=OSError("refused")):
            self.assertEqual(self.h.api("GET", "/api/jharvis/audit")[1], {"rows": [], "off": True})

    def test_odd_filters_never_reach_jharvis(self):
        with mock.patch.object(server, "jharvis_ui") as ui:
            self.assertEqual(self.h.api("GET", "/api/jharvis/audit?day=today%26limit%3D9")[0], 422)
            self.assertEqual(self.h.api("GET", "/api/jharvis/audit?status=everything")[0], 422)
        ui.assert_not_called()


if __name__ == "__main__":
    unittest.main()

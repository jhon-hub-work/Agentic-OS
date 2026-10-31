"""D: tidy: read-only scan, one card at a time, moves only on approve, undo puts it back."""
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import tidy  # noqa: E402


class TidyTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="tidy-test-"))
        patches = {"ROOT": self.root, "BIN": self.root / "_Tidy Bin", "DRL_HOME": self.root / "Work" / "Dr. Young Edit" / "_sorted",
                   "STATE": self.root / "state.json", "MIN_BYTES": 10}
        for k, v in patches.items():
            p = mock.patch.object(tidy, k, v)
            p.start()
            self.addCleanup(p.stop)
        sched = mock.patch.object(tidy, "scheduled_paths", return_value={str(self.root)[:2].lower() + "/" + "work/claude/posting"})
        sched.start()
        self.addCleanup(sched.stop)
        old = time.time() - 400 * tidy.DAY
        quiet = time.time() - 90 * tidy.DAY
        self.mk("Work/Claude/oldthing/a.bin", old)
        self.mk("Work/Claude/quiet/a.bin", quiet)
        self.mk("Work/Claude/halfway/HANDOFF.md", time.time() - 30 * tidy.DAY)
        self.mk("Work/Claude/fresh/a.bin", time.time())
        self.mk("Downloads/Dr L consult.mp4", time.time())
        self.mk("Keys/secret.txt", old)                       # protected by name
        self.mk("Work/Claude/posting/run.py", old)            # a scheduled task points inside it
        (self.root / "state.json").unlink(missing_ok=True)

    def mk(self, rel, mtime):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"x" * 100)
        os.utime(p, (mtime, mtime))
        for d in [p.parent, *p.parent.parents]:
            if d == self.root:
                break
            os.utime(d, (mtime, mtime))

    def test_scan_sorts_and_protects(self):
        st = tidy.scan()
        kinds = {c["rel"]: c["kind"] for c in st["cards"]}
        self.assertEqual(kinds.get("Work/Claude/oldthing"), "old")
        self.assertEqual(kinds.get("Work/Claude/quiet"), "unused")
        self.assertEqual(kinds.get("Work/Claude/halfway"), "half")
        self.assertEqual(kinds.get("Downloads"), "drl")   # a Dr L file inside: the folder is asked about as Dr L work
        for safe in ("Keys", "Work/Claude/posting", "Work/Claude/fresh"):
            self.assertNotIn(safe, kinds)
        self.assertEqual(tidy.next_card()["kind"], "drl")  # Dr L first, one card at a time

    def test_approve_moves_deny_keeps_undo_restores(self):
        tidy.scan()
        c = tidy.decide("work-claude-oldthing", True)
        self.assertFalse((self.root / "Work/Claude/oldthing").exists())
        self.assertTrue(Path(c["moves"][0][1]).exists())
        with self.assertRaises(KeyError):
            tidy.decide("work-claude-oldthing", True)       # answered once
        tidy.undo("work-claude-oldthing")
        self.assertTrue((self.root / "Work/Claude/oldthing/a.bin").exists())
        tidy.decide("work-claude-quiet", False)
        self.assertTrue((self.root / "Work/Claude/quiet").exists())
        tidy.scan()
        self.assertNotIn("work-claude-quiet", [c["id"] for c in tidy.load()["cards"] if c["status"] == "pending"])  # 90 days
        started = []
        tidy.decide("work-claude-halfway", True, lambda text: started.append(text) or "sess1")
        self.assertIn("halfway", started[0])
        self.assertTrue((self.root / "Work/Claude/halfway").exists())  # a debate, not a move
        self.mk("Downloads/holiday.jpg", time.time())
        tidy.decide("downloads", True)  # only the Dr L file moves; the rest of Downloads stays
        self.assertTrue((self.root / "Work/Dr. Young Edit/_sorted/Downloads/Dr L consult.mp4").exists())
        self.assertTrue((self.root / "Downloads/holiday.jpg").exists())
        tidy.undo("downloads")
        self.assertTrue((self.root / "Downloads/Dr L consult.mp4").exists())


if __name__ == "__main__":
    unittest.main()

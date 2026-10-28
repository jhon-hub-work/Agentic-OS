"""v4.0 feature tests: chat history filter, file uploads, memory graph, diff.

Run with: python3 tests/run_all.py  (or: python3 tests/test_v40.py)
"""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import PROJECT_ROOT, Harness  # noqa: E402
import json  # noqa: E402

import server  # noqa: E402


class ChatHistoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def _seed_message(self, content, agent="opencode"):
        msg = {
            "id": "seed1",
            "role": "user",
            "agent": agent,
            "content": content,
            "timestamp": "2026-08-11T12:00:00Z",
        }
        history = server.load_chat_history()
        history.setdefault("messages", []).append(msg)
        server.save_chat_message(msg)
    def test_history_empty_by_default(self):
        # Clear any messages seeded by prior tests in this class
        server.save_chat_message({"id": "clear", "role": "user", "agent": "opencode",
                                  "content": "", "timestamp": ""})
        history = server.load_chat_history()
        history["messages"] = []
        server.CHAT_HISTORY_FILE.write_text(__import__("json").dumps(history))
        status, data = self.h.api("GET", "/api/chat/history")
        self.assertEqual(status, 200)
        self.assertEqual(data["total"], 0)

    def test_history_search_filters(self):
        self._seed_message("terraform plan complete", "opencode")
        self._seed_message("pod restart completed", "hermes")
        status, data = self.h.api("GET", "/api/chat/history?q=terraform")
        self.assertEqual(status, 200)
        self.assertEqual(data["total"], 1)
        self.assertIn("terraform", data["messages"][0]["content"])

    def test_history_agent_filter(self):
        self._seed_message("deploy the cluster", "opencode")
        self._seed_message("schedule the job", "hermes")
        status, data = self.h.api("GET", "/api/chat/history?agent=hermes")
        self.assertEqual(status, 200)
        self.assertEqual(data["total"], 1)
        self.assertEqual(data["messages"][0]["agent"], "hermes")

    def test_history_limit(self):
        for i in range(5):
            self._seed_message(f"message number {i}")
        status, data = self.h.api("GET", "/api/chat/history?limit=2")
        self.assertEqual(status, 200)
        self.assertEqual(data["total"], 2)


class ChatUploadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def test_upload_valid_file(self):
        with mock.patch.object(server, "execute_agent", return_value="mock reply"):
            status, data = self.h.upload(
                "/api/chat/upload",
                {"agent": "opencode", "message": "review this"},
                "notes.md",
                b"# Notes\n\nsome content here",
            )
        self.assertEqual(status, 200)
        self.assertEqual(data["status"], "ok")
        self.assertEqual(data["file"], "notes.md")
        self.assertEqual(data["response"]["content"], "mock reply")
        # File must be stored in the isolated temp uploads dir
        saved = self.h.tmp / "data" / "uploads"
        self.assertTrue(saved.exists())
        files = list(saved.glob("*.md"))
        self.assertEqual(len(files), 1)
        self.assertIn(b"# Notes", files[0].read_bytes())

    def test_upload_agent_validation(self):
        status, _ = self.h.upload(
            "/api/chat/upload",
            {"agent": "bogus", "message": ""},
            "f.txt",
            b"x",
        )
        self.assertEqual(status, 400)

    def test_upload_invalid_extension(self):
        status, _ = self.h.upload(
            "/api/chat/upload",
            {"agent": "opencode", "message": ""},
            "virus.exe",
            b"x",
        )
        self.assertEqual(status, 400)

    def test_upload_oversized_file(self):
        status, _ = self.h.upload(
            "/api/chat/upload",
            {"agent": "opencode", "message": ""},
            "big.txt",
            b"x" * (2 * 1024 * 1024 + 1),
        )
        self.assertEqual(status, 413)

    def test_upload_traversal_filename_rejected(self):
        status, _ = self.h.upload(
            "/api/chat/upload",
            {"agent": "opencode", "message": ""},
            "..%2F..%2Fevil.txt",
            b"x",
        )
        self.assertEqual(status, 400)


class MemoryGraphTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def test_memory_graph_endpoint(self):
        status, data = self.h.api("GET", "/api/memory/graph")
        self.assertEqual(status, 200)
        self.assertIn("nodes", data)
        self.assertIn("edges", data)
        self.assertIsInstance(data["nodes"], list)
        self.assertIsInstance(data["edges"], list)

    def test_memory_graph_has_nodes(self):
        (self.h.tmp / "brain" / "graph-note.md").write_text(
            "zephyrquadrant graph connectivity notes"
        )
        status, _ = self.h.api("POST", "/api/memory/reindex")
        self.assertEqual(status, 200)
        status, data = self.h.api("GET", "/api/memory/graph")
        self.assertEqual(status, 200)
        self.assertGreater(len(data["nodes"]), 0)


class DiffViewerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def test_diff_missing_file_param(self):
        status, _ = self.h.api("GET", "/api/diff")
        self.assertEqual(status, 400)

    def test_diff_nonexistent_file(self):
        status, _ = self.h.api("GET", "/api/diff?file=no/such/file.md")
        self.assertEqual(status, 404)

    def test_diff_traversal_rejected(self):
        status, _ = self.h.api("GET", "/api/diff?file=../../etc/passwd")
        self.assertEqual(status, 400)

    def test_diff_known_file_ok(self):
        # skills/ exists in the isolated state; git may not report a diff,
        # but the endpoint must still return 200 with changed=false
        status, data = self.h.api("GET", "/api/diff?file=skills/_template/SKILL.md")
        self.assertEqual(status, 200)
        self.assertIn("file", data)
        self.assertIn("changed", data)


if __name__ == "__main__":
    unittest.main()


class TicketFileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()
        cls.patches = [mock.patch.object(server, "TICKETS_FILE", cls.h.tmp / "data" / "tickets" / "tickets.json"),
                       mock.patch.object(server, "TICKET_FILES", cls.h.tmp / "data" / "tickets" / "files")]
        for p in cls.patches:
            p.start()

    @classmethod
    def tearDownClass(cls):
        for p in cls.patches:
            p.stop()
        cls.h.stop()

    def test_attach_and_read_back(self):
        status, t = self.h.api("POST", "/api/tickets", {"title": "t", "severity": "P4", "project": str(self.h.tmp)})
        self.assertEqual(status, 200)
        status, t = self.h.upload(f"/api/tickets/{t['id']}/files", {}, "../../evil name.png", b"\x89PNG data")
        self.assertEqual(status, 200)
        self.assertEqual(t["files"], ["01_evil_name.png"])  # path parts dropped, unsafe characters replaced
        self.assertTrue((self.h.tmp / "data" / "tickets" / "files" / t["id"] / "01_evil_name.png").exists())
        self.assertIn("01_evil_name.png", server._ticket_prompt(t, "plan"))
        status, _ = self.h.request("GET", f"/api/tickets/{t['id']}/files/01_evil_name.png")
        self.assertEqual(status, 200)
        status, _ = self.h.request("GET", f"/api/tickets/{t['id']}/files/tickets.json")  # not listed on the ticket
        self.assertEqual(status, 404)

    def test_bad_ticket_id(self):
        status, _ = self.h.upload("/api/tickets/..%2F..%2Fx/files", {}, "a.png", b"x")
        self.assertEqual(status, 404)


class ChangeJournalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()
        cls.patches = [mock.patch.object(server, "TICKETS_FILE", cls.h.tmp / "data" / "tickets" / "tickets.json"),
                       mock.patch.object(server, "CHANGE_JOURNAL", cls.h.tmp / "data" / "journal")]
        for p in cls.patches:
            p.start()

    @classmethod
    def tearDownClass(cls):
        for p in cls.patches:
            p.stop()
        cls.h.stop()

    def _edit(self, tid, f: Path, text: str):
        """What a fix run does: hook before, the edit, hook after."""
        import subprocess as sp
        hook = [sys.executable, str(PROJECT_ROOT / "journal_hook.py"), str(server.CHANGE_JOURNAL), tid]
        ev = {"tool_name": "Write", "tool_input": {"file_path": str(f)}, "cwd": str(f.parent)}
        sp.run(hook, input=json.dumps({**ev, "hook_event_name": "PreToolUse"}), text=True, check=True)
        f.write_text(text)
        sp.run(hook, input=json.dumps({**ev, "hook_event_name": "PostToolUse"}), text=True, check=True)

    def test_undo_restores_and_moves_new_files(self):
        status, t = self.h.api("POST", "/api/tickets", {"title": "j", "severity": "P4", "project": str(self.h.tmp)})
        old, new = self.h.tmp / "old.txt", self.h.tmp / "new.txt"
        old.write_text("before")
        self._edit(t["id"], old, "after")
        self._edit(t["id"], new, "made by claude")
        status, ch = self.h.api("GET", f"/api/tickets/{t['id']}/changes")
        self.assertEqual(len(ch["changes"]), 2)
        status, _ = self.h.api("POST", f"/api/tickets/{t['id']}/undo")
        self.assertEqual(status, 200)
        self.assertEqual(old.read_text(), "before")
        self.assertFalse(new.exists())  # moved aside, not deleted
        self.assertTrue((server.CHANGE_JOURNAL / "removed" / t["id"] / "new.txt").exists())
        status, ch = self.h.api("GET", f"/api/tickets/{t['id']}/changes")
        self.assertEqual(ch["changes"], [])

    def test_undo_refuses_when_edited_again(self):
        status, t = self.h.api("POST", "/api/tickets", {"title": "k", "severity": "P4", "project": str(self.h.tmp)})
        f = self.h.tmp / "later.txt"
        f.write_text("v1")
        self._edit(t["id"], f, "v2")
        f.write_text("v3 by hand")
        status, _ = self.h.api("POST", f"/api/tickets/{t['id']}/undo")
        self.assertEqual(status, 409)
        self.assertEqual(f.read_text(), "v3 by hand")


class CustomAgentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()
        cls.patches = [mock.patch.object(server, "CUSTOM_AGENTS_FILE", cls.h.tmp / "data" / "agents.json"),
                       mock.patch.object(server, "REAUTH_FILE", cls.h.tmp / "data" / "reauth.json")]
        for p in cls.patches:
            p.start()

    @classmethod
    def tearDownClass(cls):
        for p in cls.patches:
            p.stop()
        server.load_custom_agents()
        cls.h.stop()

    def _add(self, answer):
        body = {"id": "echoer", "label": "Echoer", "role": "test agent", "command": "python -c pass", "relogin": "echo hi"}
        with mock.patch.object(server, "run_custom_agent", return_value=(0, answer, "")), \
             mock.patch.object(server, "find_agent_logo", return_value=None):
            return self.h.api("POST", "/api/agents", body)

    def test_logo_lookup_tries_sources_and_keeps_only_svg(self):
        import io
        tried = []

        def fake_open(url, timeout):
            tried.append(url)
            if "lobehub" in url and "-color" in url:
                raise OSError("404")
            return io.BytesIO(b"<svg xmlns='http://www.w3.org/2000/svg'/>")
        agent = {"id": "codex", "label": "Codex", "command": ["codex", "exec"]}
        with mock.patch.object(server, "AGENT_LOGOS", self.h.tmp / "logos"), \
             mock.patch.object(server.urllib.request, "urlopen", fake_open):
            self.assertEqual(server.find_agent_logo(agent), "codex.svg")
            self.assertTrue((self.h.tmp / "logos" / "codex.svg").read_bytes().startswith(b"<svg"))
        self.assertEqual(len(tried), 2)  # colour version missing, plain one taken
        with mock.patch.object(server.urllib.request, "urlopen", lambda url, timeout: io.BytesIO(b"<html>not a logo</html>")), \
             mock.patch.object(server, "AGENT_LOGOS", self.h.tmp / "logos2"):
            self.assertIsNone(server.find_agent_logo(agent))

    def test_add_needs_a_passing_test_then_joins_everything(self):
        status, _ = self._add("no idea")
        self.assertEqual(status, 400)  # didn't say hello: not saved
        self.assertNotIn("echoer", server.AGENTS)
        status, _ = self._add("hello")
        self.assertEqual(status, 200)
        self.assertIn("echoer", server.AGENTS)
        self.assertEqual(server.TEAM_ROLES["echoer"], "test agent")
        status, r = self.h.api("GET", "/api/reauth")
        self.assertIn("echoer", [a["id"] for a in r["accounts"]])  # has a re-login command: on the weekly card
        status, _ = self.h.api("POST", "/api/agents", {"id": "claude", "label": "x", "role": "x", "command": "python"})
        self.assertEqual(status, 409)
        status, _ = self.h.api("DELETE", "/api/agents/echoer")
        self.assertEqual(status, 200)
        self.assertNotIn("echoer", server.AGENTS)

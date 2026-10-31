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
import tempfile  # noqa: E402
import time  # noqa: E402

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

    def test_reopen_keeps_the_open_chat(self):
        # 29 Oct bug: reopening an old chat rewrote the archive from a copy read before the open chat was filed away
        server.save_chat_message({"id": "ra", "role": "user", "agent": "codex", "content": "chat A line", "timestamp": "2026-10-29T01:00:00Z"})
        a = server.current_chat_id("codex")
        server.clear_chat("codex")
        server.save_chat_message({"id": "rb", "role": "user", "agent": "codex", "content": "chat B line", "timestamp": "2026-10-29T02:00:00Z"})
        server.open_chat("codex", a)
        archived = [m["content"] for m in server._read_archive() if m.get("agent") == "codex"]
        self.assertIn("chat B line", archived)
        self.assertNotIn("chat A line", archived)  # A is open again, not in the archive twice

    def test_new_chat_and_reopen(self):
        # /clear on a non-live agent starts a new chat (never reaches the CLI); the old one is listed and reopens
        self._seed_message("first chat line", "agy")
        status, data = self.h.api("POST", "/api/chat", {"agent": "agy", "message": "/clear"})
        self.assertEqual((status, data["status"]), (200, "cleared"))
        _, data = self.h.api("GET", "/api/chat/history?agent=agy")
        self.assertEqual(data["total"], 0)
        _, data = self.h.api("GET", "/api/chat/list?agent=agy")
        old = [c for c in data["chats"] if c["title"] == "first chat line"]
        self.assertEqual(len(old), 1)
        self.assertTrue(data["chats"][0]["current"])
        server.chat_session_file("claude").write_text("sess-a")
        server.save_chat_message({"id": "c1", "role": "user", "agent": "claude", "content": "claude chat", "timestamp": "2026-10-29T01:00:00Z"})
        server.clear_chat("claude")
        self.assertFalse(server.chat_session_file("claude").exists())
        claude_old = server.list_chats("claude")[1]["id"]
        status, _ = self.h.api("POST", "/api/chat/open", {"agent": "claude", "chat": claude_old})
        self.assertEqual(status, 200)
        self.assertEqual(server.chat_session_file("claude").read_text(), "sess-a")  # its own memory comes back
        status, _ = self.h.api("POST", "/api/chat/open", {"agent": "agy", "chat": old[0]["id"]})
        self.assertEqual(status, 200)
        _, data = self.h.api("GET", "/api/chat/history?agent=agy")
        self.assertEqual([m["content"] for m in data["messages"]], ["first chat line"])
        status, _ = self.h.api("POST", "/api/chat/open", {"agent": "agy", "chat": "../x"})
        self.assertEqual(status, 400)

    def _two_chats(self, agent):
        # an earlier chat about bananas, and the open one about apples
        server.save_chat_message({"id": "b1", "role": "user", "agent": agent, "content": "tell me about bananas", "timestamp": "2026-10-29T01:00:00Z"})
        server.save_chat_message({"id": "b2", "role": "assistant", "agent": agent, "content": "Bananas are rich in potassium.", "timestamp": "2026-10-29T01:01:00Z"})
        server.clear_chat(agent)
        server.save_chat_message({"id": "a1", "role": "user", "agent": agent, "content": "apples please", "timestamp": "2026-10-29T02:00:00Z"})
        chats = server.list_chats(agent)
        return chats[1]["id"], chats[0]["id"]

    def test_chat_search_rename_delete(self):
        old, cur = self._two_chats("gemini")
        # search finds message text, not only titles, with a snippet
        _, data = self.h.api("GET", "/api/chat/list?agent=gemini&q=POTASSIUM")
        self.assertEqual([c["id"] for c in data["chats"]], [old])
        self.assertIn("potassium", data["chats"][0]["snippet"])
        _, data = self.h.api("GET", "/api/chat/list?agent=gemini&q=nothing-like-this")
        self.assertEqual(data["chats"], [])
        # rename: the list shows the name, and search matches it
        status, _ = self.h.api("POST", "/api/chat/rename", {"agent": "gemini", "chat": old, "name": "  Fruit facts "})
        self.assertEqual(status, 200)
        _, data = self.h.api("GET", "/api/chat/list?agent=gemini&q=fruit")
        self.assertEqual([(c["id"], c["title"]) for c in data["chats"]], [(old, "Fruit facts")])
        status, _ = self.h.api("POST", "/api/chat/rename", {"agent": "gemini", "chat": "nope", "name": "x"})
        self.assertEqual(status, 404)
        # delete an earlier chat: gone from the list and archive, kept in chat-deleted.jsonl, its CLI session dropped
        index = server._chat_index()
        index["sessions"][old] = "sess-old"
        server._save_chat_index(index)
        status, _ = self.h.api("POST", "/api/chat/delete", {"agent": "gemini", "chat": old})
        self.assertEqual(status, 200)
        self.assertNotIn(old, [c["id"] for c in server.list_chats("gemini")])
        self.assertNotIn(old, server._chat_index()["sessions"])
        self.assertNotIn(old, server._chat_index()["titles"])
        deleted = [json.loads(l) for l in server.CHAT_HISTORY_FILE.with_name("chat-deleted.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(sorted(m["id"] for m in deleted if m["chat"] == old), ["b1", "b2"])
        self.assertTrue(all(m.get("deleted_at") for m in deleted))
        status, _ = self.h.api("POST", "/api/chat/delete", {"agent": "gemini", "chat": old})
        self.assertEqual(status, 404)
        # delete the open chat: a new empty chat opens, messages saved, CLI session gone
        server.chat_session_file("claude").write_text("sess-cur")
        server.save_chat_message({"id": "c9", "role": "user", "agent": "claude", "content": "open chat line", "timestamp": "2026-10-29T03:00:00Z"})
        cur = server.current_chat_id("claude")
        status, _ = self.h.api("POST", "/api/chat/delete", {"agent": "claude", "chat": cur})
        self.assertEqual(status, 200)
        self.assertNotEqual(server.current_chat_id("claude"), cur)
        self.assertFalse(server.chat_session_file("claude").exists())
        _, data = self.h.api("GET", "/api/chat/history?agent=claude")
        self.assertEqual(data["total"], 0)
        deleted = server.CHAT_HISTORY_FILE.with_name("chat-deleted.jsonl").read_text(encoding="utf-8")
        self.assertIn("open chat line", deleted)


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

    def test_opencode_stuck_service_is_restarted_and_retried_once(self):
        outs = iter([(1, "", "Error: Background service failed to start"), (0, '{"type":"text","part":{"text":"hello"}}', "")])
        with mock.patch.object(server, "run_cli", side_effect=lambda *a, **k: next(outs)), \
             mock.patch.object(server, "restart_opencode_service", return_value=True) as restart:
            self.assertEqual(server._execute_agent("opencode", "hi"), "hello")
            self.assertEqual(restart.call_count, 1)

    def test_model_lines_from_a_cli_models_listing(self):
        out = ["You are logged in with grok.com.", "", "Default model: grok-4.7", "", "Available models:",
               "* grok-4.7 (default)", "- grok-4.7-build-fast", "- grok-4.6", "gpt-6-luna", "openai/gpt-6", "- notes"]
        self.assertEqual([m for m in map(server._model_line, out) if m],
                         ["grok-4.7", "grok-4.7-build-fast", "grok-4.6", "gpt-6-luna", "openai/gpt-6"])

    def test_api_key_agent_tests_saves_key_and_answers_through_the_api(self):
        calls = []
        def fake_call(provider, key, path, body=None, timeout=30):
            calls.append((provider, key, path, (body or {}).get("model")))
            return {"choices": [{"message": {"content": "hello"}}]}
        with mock.patch.object(server, "SECRETS_FILE", self.h.tmp / "config" / "secrets-test.json"), \
             mock.patch.object(server, "_provider_call", side_effect=fake_call), \
             mock.patch.object(server, "find_agent_logo", return_value=None):
            self.assertEqual(self.h.api("POST", "/api/agents/api", {"provider": "nope", "model": "m"})[0], 404)
            self.assertEqual(self.h.api("POST", "/api/agents/api", {"provider": "openrouter", "model": "m"})[0], 400)  # no key yet
            code, a = self.h.api("POST", "/api/agents/api", {"provider": "openrouter", "model": "deepseek/deepseek-v4", "key": "sk-1"})
            self.assertEqual((code, a["id"], a["kind"]), (200, "deepseek-v4", "api"))
            self.assertEqual(server.secret("OPENROUTER_API_KEY"), "sk-1")  # kept only because the test worked
            code, b = self.h.api("POST", "/api/agents/api", {"provider": "openrouter", "model": "deepseek/deepseek-v4"})  # saved key
            self.assertEqual((code, b["id"]), (200, "deepseek-v4-2"))
            self.assertIn("deepseek-v4", server.AGENTS)
            self.assertEqual(server._execute_agent("deepseek-v4", "hi"), "hello")
            self.assertEqual(calls[-1], ("openrouter", "sk-1", "/chat/completions", "deepseek/deepseek-v4"))
            self.assertEqual(server.check_agent("deepseek-v4")["status"], "online")
            listed = {x["id"]: x for x in self.h.api("GET", "/api/agents")[1]["agents"]}
            self.assertEqual(listed["deepseek-v4"]["command"], "OpenRouter · deepseek/deepseek-v4")
            for aid in ("deepseek-v4", "deepseek-v4-2"):
                self.h.api("DELETE", f"/api/agents/{aid}")

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
        self.assertNotIn("echoer", server.TEAM_ROLES)  # rebuilt: a removed agent leaves the roster too

    def test_command_parsing_and_prompt_placement(self):
        self.assertEqual(server.split_command('"C:\\Program Files\\x.exe" --q={prompt}'),
                         ["C:\\Program Files\\x.exe", "--q={prompt}"])
        seen = {}
        with mock.patch.object(server, "run_cli", lambda args, **kw: seen.update(args=args, answer=kw["answer"]) or (0, "", "")):
            server.run_custom_agent({"command": ["python", "--q={prompt}"]}, "hi there")
            self.assertEqual(seen["args"][1:], ["--q=hi there"])  # replaced inside a part
            self.assertIsNone(seen["answer"])  # and then not also sent on stdin
            server.run_custom_agent({"command": ["python", "-"]}, "hi there")
            self.assertEqual(seen["answer"], "hi there")
        with mock.patch.object(server.shutil, "which", return_value="C:/x/tool.cmd"):
            self.assertTrue(server.prompt_in_batch(["tool", "{prompt}"]))
            self.assertFalse(server.prompt_in_batch(["tool", "-"]))
            with self.assertRaises(ValueError):
                server.run_custom_agent({"command": ["tool", "{prompt}"]}, "& calc")


class ModelPickTests(unittest.TestCase):
    """Model + effort per agent: defaults, per chat, per session (28 Oct)."""

    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()
        cls.agents_file = cls.h.tmp / "data" / "agents.json"
        cls.agents_file.write_text(json.dumps([
            {"id": "codexy", "label": "Codexy", "role": "r", "command": ["codex", "exec", "-"],
             "model_args": "-m {model}", "effort_args": "-c model_reasoning_effort={effort}"},
            {"id": "plain", "label": "Plain", "role": "r", "command": ["plain", "-"]}]))  # an entry from before: no templates
        server.load_custom_agents()
        cls.sessions = mock.patch.object(server, "SESSION_DIR", cls.h.tmp / "data" / "sessions")  # not the real data/sessions
        cls.sessions.start()

    @classmethod
    def tearDownClass(cls):
        cls.sessions.stop()
        cls.agents_file.unlink()
        server.load_custom_agents()
        cls.h.stop()

    def test_flags_per_agent(self):
        f = server.model_flags
        self.assertEqual(f("claude", "opus", "high"), ["--model", "opus", "--effort", "high"])
        self.assertEqual(f("agy", "gemini-4-argon-high", ""), ["--model", "gemini-4-argon-high"])
        self.assertEqual(f("opencode", "opencode/x", "high"), ["-m", "opencode/x"])  # no effort offered
        self.assertEqual(f("hermes", "anthropic/claude-sonnet-4", "low"), ["-m", "anthropic/claude-sonnet-4", "--reasoning", "low"])
        self.assertEqual(f("codexy", "gpt-6", "xhigh"), ["-m", "gpt-6", "-c", "model_reasoning_effort=xhigh"])
        self.assertEqual(f("plain", "gpt-6", "high"), [])
        self.assertEqual(f("claude", "", ""), [])  # empty = the CLI's own default

    def test_defaults_then_override_reach_the_cli(self):
        self.assertEqual(self.h.api("PUT", "/api/settings", {"settings": {"models": {"claude": "haiku"}, "efforts": {"claude": "low"}}})[0], 200)
        seen = []
        with mock.patch.object(server, "run_cli", lambda args, **kw: seen.append(args) or (0, json.dumps({"result": "ok"}), "")):
            server.execute_agent("claude", "hi")
            server.execute_agent("claude", "hi", "opus", "max")
            server.execute_agent("codexy", "hi", "gpt-6", "")
        self.assertIn("--model haiku --effort low", " ".join(seen[0]))
        self.assertIn("--model opus --effort max", " ".join(seen[1]))
        self.assertEqual(seen[2][1:4], ["-m", "gpt-6", "exec"])  # right after the program name
        r = self.h.api("GET", "/api/models/claude")[1]
        self.assertEqual((r["current"], r["current_effort"], r["efforts"][-1]), ("haiku", "low", "max"))
        self.assertIsNone(self.h.api("GET", "/api/models/plain")[1]["models"])  # no template: no model choice
        self.h.api("PUT", "/api/settings", {"settings": {"models": {}, "efforts": {}}})

    def test_bad_picks_are_refused(self):
        for model in ("--dangerous", "gpt 5", "a\nb", "-m"):
            self.assertEqual(self.h.api("POST", "/api/chat", {"agent": "claude", "message": "hi", "model": model})[0], 400, model)
        self.assertEqual(self.h.api("POST", "/api/chat", {"agent": "claude", "message": "hi", "effort": "ultra"})[0], 400)
        self.assertEqual(self.h.api("POST", "/api/chat", {"agent": "plain", "message": "hi", "model": "x"})[0], 400)
        self.assertEqual(self.h.api("PUT", "/api/settings", {"settings": {"models": {"claude": "--x"}}})[0], 400)
        self.assertEqual(self.h.api("POST", "/api/sessions", {"message": "q", "agents": ["claude", "hermes"],
                                                               "overrides": {"claude": {"model": "op us"}}})[0], 400)

    def test_session_overrides_reach_every_round_and_patch_waits(self):
        sess = {"id": "abcdef21", "title": "t", "agents": ["claude", "hermes"], "rounds": 2, "round": 0, "working": [],
                "status": "idle", "overrides": {"claude": {"model": "opus", "effort": "high"}},
                "messages": [{"role": "user", "agent": None, "round": 0, "content": "q"}]}
        server.save_session(sess)
        status, s = self.h.api("PATCH", "/api/sessions/abcdef21", {"overrides": {"hermes": {"model": "", "effort": "low"}, "claude": {"model": "opus"}}})
        self.assertEqual(status, 200)
        self.assertEqual(s["overrides"], {"hermes": {"model": "", "effort": "low"}, "claude": {"model": "opus", "effort": ""}})
        calls = []
        with mock.patch.object(server, "execute_agent", lambda a, p, model="", effort="", meta=None: calls.append((a, model, effort)) or "x"):
            server.run_session(server.get_session("abcdef21"))
        self.assertEqual(sorted(calls), sorted([("claude", "opus", ""), ("hermes", "", "low")] * 2))  # both rounds
        server.save_session({**server.get_session("abcdef21"), "status": "running"})
        self.assertEqual(self.h.api("PATCH", "/api/sessions/abcdef21", {"overrides": {}})[0], 409)


class ReviewFixTests(unittest.TestCase):
    """Fixes from the 28 Oct review (A-items)."""

    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()
        cls.patches = [mock.patch.object(server, "TICKETS_FILE", cls.h.tmp / "data" / "tickets" / "tickets.json"),
                       mock.patch.object(server, "CHANGE_JOURNAL", cls.h.tmp / "data" / "journal"),
                       mock.patch.object(server, "SESSION_DIR", cls.h.tmp / "data" / "sessions")]
        for p in cls.patches:
            p.start()

    @classmethod
    def tearDownClass(cls):
        for p in cls.patches:
            p.stop()
        cls.h.stop()

    def _ticket(self):
        return self.h.api("POST", "/api/tickets", {"title": "r", "severity": "P4", "project": str(self.h.tmp)})[1]

    def test_reject_with_a_stop_note_closes_the_ticket_otherwise_replans(self):
        for note, want in (("Dont proceed", "closed"), ("save more credits", "new")):
            t = self._ticket()
            self._set_status(t["id"], "awaiting_approval")
            self.assertEqual(self.h.api("POST", f"/api/tickets/{t['id']}/reject", {"text": note})[1]["status"], want)

    def _set_status(self, tid, status):
        server._update_ticket(tid, lambda t: t.update(status=status))

    def _hook(self, tid, f: Path, phase: str):
        import subprocess as sp
        ev = {"tool_name": "Write", "tool_input": {"file_path": str(f)}, "cwd": str(f.parent), "hook_event_name": phase}
        sp.run([sys.executable, str(PROJECT_ROOT / "journal_hook.py"), str(server.CHANGE_JOURNAL), tid],
               input=json.dumps(ev), text=True, check=True)

    def _edit(self, tid, f: Path, text: str):
        self._hook(tid, f, "PreToolUse")
        f.write_text(text)
        self._hook(tid, f, "PostToolUse")

    def test_write_atomic_concurrent_writers(self):
        import threading
        target = self.h.tmp / "data" / "atomic.json"
        threads = [threading.Thread(target=lambda n=n: [server.write_atomic(target, json.dumps({"n": n, "pad": "x" * 5000}))
                                                        for _ in range(20)]) for n in range(6)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertIn(json.loads(target.read_text())["n"], range(6))  # whole file from one writer
        self.assertEqual(list(target.parent.glob("atomic.json.*.tmp")), [])

    def test_fit_argv_keeps_head_and_tail(self):
        long = "RULES " + "x" * 50000 + " THE ASK"
        cut = server.fit_argv(long)
        self.assertLessEqual(len(cut), server.ARGV_MAX + 100)
        self.assertTrue(cut.startswith("RULES") and cut.endswith("THE ASK"))
        self.assertEqual(server.fit_argv("short"), "short")

    def test_tar_restore_blocks_sibling_prefix(self):
        import io
        import tarfile
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tar:
            info = tarfile.TarInfo(f"../{self.h.tmp.name}-workspace/x")  # passed the old startswith check
            info.size = 1
            tar.addfile(info, io.BytesIO(b"x"))
        buf.seek(0)
        with tarfile.open(fileobj=buf, mode="r:gz") as tar, self.assertRaises(server.HTTPException):
            server.safe_extractall(tar, self.h.tmp)
        self.assertFalse((self.h.tmp.parent / f"{self.h.tmp.name}-workspace").exists())

    def test_audit_skips_torn_lines(self):
        with open(server.BASE_DIR / "audit" / "audit.log", "a") as f:
            f.write('{"action": "half\n')
        server.append_audit({"action": "after_torn"})
        status, data = self.h.api("GET", "/api/audit")
        self.assertEqual(status, 200)
        self.assertEqual(data["entries"][-1]["action"], "after_torn")

    def test_same_name_schedule_is_refused(self):
        body = {"name": "dup check", "cron": "0 9 * * 1", "kind": "agent", "agent": "claude", "prompt": "x"}
        self.assertEqual(self.h.api("POST", "/api/scheduler/jobs", body)[0], 200)
        self.assertEqual(self.h.api("POST", "/api/scheduler/jobs", body)[0], 409)

    def test_replay_rejects_backslash_and_drive(self):
        for bad in ("..%5C..%5Cx", "C:%5Cx", "a%5Cb"):
            self.assertEqual(self.h.api("GET", f"/api/sessions/{bad}/replay")[0], 400, bad)

    def test_handoff_to_dashed_agent_id(self):
        self.assertEqual(server.HANDOFF_RE.findall("done\nHANDOFF: gemini-cli: check it")[0][0], "gemini-cli")

    def test_light_sessions_list(self):
        sess = {"id": "abcdef12", "title": "t", "agents": ["claude", "hermes"], "status": "idle",
                "messages": [{"role": "user", "content": "a"}, {"role": "agent", "content": "b"}]}
        server.save_session(sess)
        status, data = self.h.api("GET", "/api/sessions?light=1&limit=200")
        s = next(x for x in data["sessions"] if x["id"] == "abcdef12")
        self.assertNotIn("messages", s)
        self.assertEqual((s["message_count"], s["last"]["content"]), (2, "b"))
        status, data = self.h.api("GET", "/api/sessions")
        self.assertIn("messages", next(x for x in data["sessions"] if x["id"] == "abcdef12"))  # default unchanged

    def test_session_crash_goes_back_to_idle(self):
        sess = {"id": "abcdef13", "title": "t", "agents": ["claude", "gone-agent"], "rounds": 1, "round": 0,
                "working": [], "status": "running", "messages": [{"role": "user", "agent": None, "content": "a"}]}
        with mock.patch.object(server, "execute_agent", side_effect=RuntimeError("boom")), self.assertRaises(RuntimeError):
            server.run_session(sess)  # also: an unknown (removed) agent's role doesn't KeyError
        self.assertEqual(server.get_session("abcdef13")["status"], "idle")

    def test_new_session_ends_with_one_jharvis_verdict(self):
        self.enterContext(mock.patch.dict(server._resting, {}, clear=True))  # a real background run may have rested a free agent
        sess = {"id": "abcdef14", "title": "t", "agents": ["claude", "opencode"], "rounds": 2, "round": 0, "judge": True,
                "working": [], "status": "running", "messages": [{"role": "user", "agent": None, "round": 0, "content": "q"}]}
        with mock.patch.object(server, "execute_agent", return_value="idea\nDONE"), \
             mock.patch.object(server, "bg_agent", return_value="claude"), \
             mock.patch.object(server, "run_cli", return_value=(0, json.dumps({"result": "Do X. Why: best.", "usage": {}}), "")) as cli:
            server.run_session(sess)
        s = server.get_session("abcdef14")
        self.assertEqual([m["role"] for m in s["messages"]], ["user", "agent", "agent", "verdict"])  # all said DONE: one round
        self.assertEqual(s["messages"][-1]["content"], "Do X. Why: best.")
        self.assertIn("[Answer A]\nidea", cli.call_args.kwargs["answer"])  # the judge read the debate, names hidden
        self.assertNotIn("[claude]", cli.call_args.kwargs["answer"])
        self.assertFalse(s["judging"])
        # Opus fails: a free agent that took no part judges, never a debater (29 Oct: Codex judged its own plan)
        msgs = [{"role": "user", "content": "q"}, {"role": "agent", "agent": "hermes", "content": "a"},
                {"role": "agent", "agent": "codex", "content": "b"}]
        with mock.patch.object(server, "run_cli", side_effect=RuntimeError("limit")), \
             mock.patch.object(server, "bg_agent", return_value="codex"), \
             mock.patch.object(server, "execute_agent", return_value="Do Y.") as ex:
            self.assertEqual(server.session_verdict(msgs), "Do Y.")
        self.assertEqual(ex.call_args.args[0], "opencode")
        self.assertTrue({"hermes", "codex", "claude"} <= ex.call_args.kwargs["skip"])
        msgs[1]["agent"] = "opencode"  # every free agent debated: no judge, the debate stays to read
        with mock.patch.object(server, "run_cli", side_effect=RuntimeError("limit")), \
             mock.patch.object(server, "bg_agent", return_value="codex"), \
             mock.patch.object(server, "execute_agent") as ex:
            self.assertIn("couldn't decide", server.session_verdict(msgs + [{"role": "agent", "agent": "hermes", "content": "c"}]))
        ex.assert_not_called()
        old = {**sess, "id": "abcdef15", "judge": False, "messages": sess["messages"][:1]}
        with mock.patch.object(server, "execute_agent", return_value="idea\nDONE"):
            server.run_session(old)
        self.assertNotIn("verdict", [m["role"] for m in server.get_session("abcdef15")["messages"]])  # older sessions unchanged

    def test_fallback_is_saved_under_the_agent_that_really_ran(self):
        import time
        meta = {}
        with mock.patch.dict(server._resting, {"hermes": time.time() + 999}, clear=True), \
             mock.patch.object(server, "_execute_agent", return_value="real answer") as run:
            self.assertEqual(server.execute_agent("hermes", "q", meta=meta), "real answer")
        self.assertEqual((meta["actual_agent"], run.call_args.args[0]), ("opencode", "opencode"))
        sess = {"id": "abcdef31", "title": "t", "agents": ["hermes", "claude"], "rounds": 1, "round": 0, "working": [],
                "status": "running", "messages": [{"role": "user", "agent": None, "round": 0, "content": "q"}]}
        ex = lambda a, p, meta=None, **kw: (meta.update(actual_agent="opencode" if a == "hermes" else a) or "fine\nDONE")
        with mock.patch.object(server, "execute_agent", side_effect=ex):
            server.run_session(sess)
        first = server.get_session("abcdef31")["messages"][1]
        self.assertEqual((first["agent"], first["requested"]), ("opencode", "hermes"))

    def test_agy_stub_is_flagged_and_left_out_of_the_verdict(self):
        stub = "**agy**\n\nProcessed your query.\n\n**Message:** read the files"
        for agent, out in (("agy", stub), ("hermes", "hermes failed: ...Your request was not processed"), ("agy", ""),
                           ("claude", "⏱ Claude Code timed out after 10 minutes."), ("opencode", "opencode failed: boom")):
            self.assertTrue(server.check_reply(agent, out, "t"), (agent, out))
        self.assertEqual(server.check_reply("agy", "The build failed because of X. " * 40 + "Fix it."), "")  # a real answer passes
        self.assertEqual(server.check_reply("opencode", "Do Y, because Z."), "")
        errs = json.loads(server.ERROR_LOG_FILE.read_text())
        self.assertTrue(any(e["source"] == "agy" and e["category"] == "agent" for e in errs))
        sess = {"id": "abcdef32", "title": "t", "agents": ["agy", "opencode"], "rounds": 1, "round": 0, "judge": True,
                "working": [], "status": "running", "messages": [{"role": "user", "agent": None, "round": 0, "content": "q"}]}
        ex = lambda a, p, meta=None, **kw: stub if a == "agy" else "a real answer\nDONE"
        with mock.patch.object(server, "execute_agent", side_effect=ex), \
             mock.patch.object(server, "run_cli", return_value=(0, json.dumps({"result": "Do X.", "usage": {}}), "")) as cli:
            server.run_session(sess)
        msgs = server.get_session("abcdef32")["messages"]
        self.assertTrue(msgs[1]["unverified"])
        self.assertNotIn("Processed your query", cli.call_args.kwargs["answer"])  # not evidence for the judge
        self.assertIn("a real answer", cli.call_args.kwargs["answer"])
        self.assertIn("No usable answer from: agy", msgs[-1]["content"])
        lines = [json.loads(l) for l in (server.BASE_DIR / "audit" / "audit.log").read_text().splitlines() if l.strip().startswith("{")]
        self.assertTrue(any(l.get("action") == "reply_unverified" and l.get("agent") == "agy" for l in lines))
        only = [{"role": "user", "content": "q"}, {"role": "agent", "agent": "agy", "content": stub, "unverified": "stub"}]
        with mock.patch.object(server, "run_cli") as cli2:
            self.assertIn("No usable answer from: agy", server.session_verdict(only))
        cli2.assert_not_called()

    def test_judge_is_never_a_debater_even_when_a_fallback_swaps_it(self):
        self.enterContext(mock.patch.dict(server._resting, {}, clear=True))  # a real background run may have rested a free agent
        msgs = [{"role": "user", "content": "q"}, {"role": "agent", "agent": "hermes", "content": "a"},
                {"role": "agent", "agent": "codex", "content": "b"}]
        swap = lambda a, p, meta=None, **kw: (meta.update(actual_agent="codex") or "my own plan is best")
        with mock.patch.object(server, "run_cli", side_effect=RuntimeError("limit")), \
             mock.patch.object(server, "bg_agent", return_value="codex"), \
             mock.patch.object(server, "execute_agent", side_effect=swap):
            out = server.session_verdict(msgs)
        self.assertIn("Independent verdict unavailable", out)
        self.assertNotIn("my own plan", out)
        lines = (server.BASE_DIR / "audit" / "audit.log").read_text()
        self.assertIn("judge_is_contributor", lines)
        # Opus stays the judge when Claude debated (Jhon, 30 Oct); the verdict records who judged
        meta = {}
        msgs[1]["agent"] = "claude"
        with mock.patch.object(server, "run_cli", return_value=(0, json.dumps({"result": "Do X.", "usage": {}}), "")):
            self.assertEqual(server.session_verdict(msgs, meta), "Do X.")
        self.assertEqual(meta["judge"], "claude")

    def test_two_incidents_open_one_ticket_waiting_for_jhon(self):
        for _ in range(2):
            server.log_error("agy", "stub or read-denied reply", "agent")
        digest = [r for r in server._error_digest() if r["agent"] == "agy" and r["reason"] == "stub or read-denied reply"]
        self.assertEqual(len(digest), 1)
        self.assertGreaterEqual(digest[0]["count"], 2)
        f = {"title": "agy answers with a stub", "description": "d", "severity": "P3", "category": "bug", "check": "replay the stub"}
        before = len(server._tickets())
        with mock.patch.object(server, "bg_agent", return_value="opencode"), \
             mock.patch.object(server, "execute_agent", return_value=json.dumps({"findings": [f, dict(f)]})) as ex:
            state = server.self_review()
        self.assertIn("agy", ex.call_args.args[1])  # the error log reached the reviewer
        self.assertEqual((len(state["opened"]), len(server._tickets()) - before), (1, 1))
        t = server.get_ticket(state["opened"][0])
        self.assertEqual(t["status"], "new")
        self.assertIn("CHECK: replay the stub", t["description"])
        with mock.patch.object(server, "bg_agent", return_value="opencode"), \
             mock.patch.object(server, "execute_agent", return_value=json.dumps({"findings": [f]})):
            self.assertEqual(server.self_review()["opened"], [])  # still open: no second ticket

    def test_a_workers_own_resolved_never_closes_a_ticket(self):
        for reply, want in (("looked\nCHECK: FAIL (nothing changed)", "waiting_on_you"), ("looked\nCHECK: PASS", "resolved"),
                            ("I looked at it and it seems fine", "waiting_on_you")):  # bad fix, good fix, missing check
            t = self._ticket()  # not from a debate: still checked
            self._set_status(t["id"], "approved")
            with mock.patch.object(server, "_run_ticket_claude", return_value="done\nSTATUS: RESOLVED"), \
                 mock.patch.object(server, "execute_agent", return_value=reply):
                server.ticket_tick()
            self.assertEqual(server.get_ticket(t["id"])["status"], want, reply)
            self._set_status(t["id"], "closed")

    def test_debate_decision_approve_builds_and_checks_deny_goes_another_round(self):
        for x in server._tickets():  # older tests' tickets must not be picked first
            self._set_status(x["id"], "closed")
        def sess(sid):
            s = {"id": sid, "title": "make it better", "agents": ["claude", "codex"], "rounds": 1, "round": 1, "status": "idle",
                 "working": [], "judge": True, "messages": [{"role": "user", "agent": None, "round": 0, "content": "q"},
                                                            {"role": "verdict", "agent": "jharvis", "round": 1, "content": "Do X."}]}
            server.save_session(s)
            return s
        sess("abcdef21")
        with mock.patch.object(server, "ticket_tick"):
            code, s = self.h.api("POST", "/api/sessions/abcdef21/decision", {"approve": True})
        self.assertEqual(code, 200)
        tid = s["messages"][-1]["decision"]["ticket"]
        self.assertEqual(self.h.api("POST", "/api/sessions/abcdef21/decision", {"approve": False})[0], 409)  # decided once
        t = server.get_ticket(tid)
        self.assertEqual((t["preapproved"], t["category"]), ("abcdef21", "change"))
        self.assertIn("Do X.", t["description"])
        with mock.patch.object(server, "_run_ticket_claude", return_value="the plan"):
            server.ticket_tick()
        self.assertEqual(server.get_ticket(tid)["status"], "approved")  # no second Approve
        with mock.patch.object(server, "_run_ticket_claude", return_value="done\nSTATUS: RESOLVED"), \
             mock.patch.object(server, "execute_agent", return_value="looked\nCHECK: FAIL (nothing changed)") as ex:
            server.ticket_tick()
        t = server.get_ticket(tid)
        self.assertEqual(t["status"], "waiting_on_you")  # Claude's own RESOLVED isn't enough
        self.assertIn("CHECK: FAIL", t["check"])
        self.assertIn("claude", ex.call_args.kwargs["skip"])  # the builder never checks itself
        self._set_status(tid, "approved")
        with mock.patch.object(server, "_run_ticket_claude", return_value="done\nSTATUS: RESOLVED"), \
             mock.patch.object(server, "execute_agent", return_value="looked\nCHECK: PASS"):
            server.ticket_tick()
        self.assertEqual(server.get_ticket(tid)["status"], "resolved")
        sess("abcdef22")
        with mock.patch.object(server, "start_session_thread") as start:
            code, s = self.h.api("POST", "/api/sessions/abcdef22/decision", {"approve": False})
        self.assertEqual(code, 200)
        self.assertFalse(s["messages"][1]["decision"]["approved"])
        self.assertIn("one question for me", s["messages"][-1]["content"])  # no note: they ask him
        start.assert_called_once()

    def test_designated_agent_runs_roles_waits_for_first_approvals_then_posts_alone(self):
        import crews
        import ig_carousel
        self.assertEqual(self.h.api("PUT", "/api/secrets/ZERNIO_API_KEY", {"value": "sk-test"})[0], 200)
        keys = self.h.api("GET", "/api/secrets")[1]
        self.assertIn("ZERNIO_API_KEY", keys["set"])
        self.assertNotIn("sk-test", json.dumps(keys))  # write-only
        code, c = self.h.api("POST", "/api/crews", {"name": "IG carousels", "task": "repos for freelancers", "action": "ig_carousel",
                                                    "agent": {"agent": "opencode"}, "advisor": {"agent": "claude", "model": "opus"},
                                                    "helper": {"agent": "hermes"}, "approve_first": 1})
        self.assertEqual(code, 200, c)
        post = {"cover": {"number": "9", "highlight": "GitHub repos", "rest": "for clients"}, "caption": "Hook",
                "slides": [{"name": n, "repo": f"o/{n}"} for n in ("a", "b", "c", "fake")]}
        calls = []
        def fake_exec(agent, prompt, model="", effort=""):
            calls.append((agent, model, prompt))
            return json.dumps(post) if "AGENT" in prompt else f"{agent} says"
        def fake_render(p, out_dir, handle):
            return [out_dir / f"slide{n}.png" for n in range(len(p["slides"]) + 1)]
        def run_now(cid):  # the thread's work, run inline
            with mock.patch.object(crews.threading, "Thread", side_effect=lambda target, daemon, args=(): mock.Mock(start=lambda: target(*args))):
                server.start_crew_run(cid)
        with mock.patch.object(server, "execute_agent", side_effect=fake_exec), \
             mock.patch.object(ig_carousel, "repo_exists", side_effect=lambda r: r != "o/fake"), \
             mock.patch.object(ig_carousel, "account", return_value={"_id": "acc1", "username": "jhon"}), \
             mock.patch.object(ig_carousel, "render", side_effect=fake_render), \
             mock.patch.object(ig_carousel, "render_story"), \
             mock.patch.object(ig_carousel, "popular_repos", return_value="Popular repos: o/a"), \
             mock.patch.object(ig_carousel, "comment_dm", return_value={"id": "auto1"}) as dm,              mock.patch.object(ig_carousel, "post", return_value={"post": {"_id": "zp1"}}) as posted:
            run_now(c["id"])
            self.assertEqual([(a, m) for a, m, _ in calls], [("claude", "opus"), ("hermes", ""), ("opencode", "")])  # advisor, helper, agent
            self.assertIn("claude says", calls[2][2])  # the agent got the advisor's plan
            self.assertIn("Popular repos: o/a", calls[0][2])  # the advisor picks from real, unposted repos
            r = crews.get(c["id"])["runs"][-1]
            self.assertEqual((r["status"], r["slides"], r["post"]["cover"]["number"]), ("awaiting_approval", 4, "3"))  # fake repo dropped
            posted.assert_not_called()  # the first one waits for him
            items = [i for i in self.h.api("GET", "/api/approvals")[1]["approvals"] if i["kind"] == "crew"]
            self.assertEqual(len(items), 1)
            self.assertEqual(self.h.api("POST", f"/api/approvals/{items[0]['id']}", {"decision": "approve"})[0], 200)
            self.assertEqual(crews.get(c["id"])["runs"][-1]["status"], "posted")
            self.assertEqual(posted.call_args.args[:2], ("sk-test", "acc1"))
            self.assertEqual(crews.get(c["id"])["used_repos"], ["o/a", "o/b", "o/c"])  # not posted twice
            run_now(c["id"])  # approved 1 of 1: the next run posts on its own
            self.assertEqual(crews.get(c["id"])["runs"][-1]["status"], "posted")
            self.assertEqual(posted.call_count, 2)
            self.assertEqual(dm.call_args[0][1], "zp1")  # comment "AI" -> follow -> DM is set up on the new post
            self.assertIn("auto1", crews.get(c["id"])["runs"][-1]["comment_dm"])

    def test_try_again_comment_rebuilds_an_approved_parked_ticket(self):
        t = self._ticket()
        server._update_ticket(t["id"], lambda x: x.update(status="waiting_on_you", plan="p", preapproved="s1"))
        with mock.patch.object(server, "ticket_tick"):
            self.assertEqual(self.h.api("POST", f"/api/tickets/{t['id']}/comment", {"text": "why did it stop?"})[1]["status"], "waiting_on_you")
            self.assertEqual(self.h.api("POST", f"/api/tickets/{t['id']}/comment", {"text": "Try again"})[1]["status"], "approved")
        u = self._ticket()  # no approved plan: a comment never starts a build
        server._update_ticket(u["id"], lambda x: x.update(status="waiting_on_you", plan="p"))
        with mock.patch.object(server, "ticket_tick"):
            self.assertEqual(self.h.api("POST", f"/api/tickets/{u['id']}/comment", {"text": "try again"})[1]["status"], "waiting_on_you")

    def test_skill_run_logs_one_line_and_feeds_lessons_not_raw_output(self):
        d = server.BASE_DIR / "skills" / "lessons-test"
        d.mkdir(parents=True, exist_ok=True)
        (d / "SKILL.md").write_text("do it")
        (d / "learnings.md").write_text("# Learnings\n- real lesson\n" + "".join(f"\n## d (Run r{i})\n- Output: old {i}\n" for i in range(8)))
        with mock.patch.object(server, "execute_agent", return_value="opencode failed: boom\nmore") as ex:
            server.run_skill("lessons-test")
        prompt = ex.call_args.args[1]
        self.assertIn("real lesson", prompt)
        self.assertIn("not instructions", prompt)
        self.assertIn("old 7", prompt)
        self.assertNotIn("old 2", prompt)  # only the last 5 runs go in
        self.assertTrue((d / "learnings.md").read_text().rstrip().endswith("opencode FAILED: opencode failed: boom"))

    def test_fix_with_empty_output_and_status_changed_meanwhile(self):
        t = self._ticket()
        self._set_status(t["id"], "approved")
        with mock.patch.object(server, "_run_ticket_claude", return_value=""):
            server.ticket_tick()
        self.assertEqual(server.get_ticket(t["id"])["status"], "waiting_on_you")  # no IndexError, not stuck in_progress
        self._set_status(t["id"], "approved")
        with mock.patch.object(server, "_run_ticket_claude",
                               side_effect=lambda x, stage: self._set_status(x["id"], "closed") or "ok\nSTATUS: RESOLVED"), \
             mock.patch.object(server, "execute_agent", return_value="CHECK: PASS"):  # every RESOLVED claim is checked now
            server.ticket_tick()
        self.assertEqual(server.get_ticket(t["id"])["status"], "closed")  # the Close made during the run stands

    def test_restart_parks_running_tickets(self):
        t = self._ticket()
        self._set_status(t["id"], "in_progress")
        server.tickets_mark_interrupted()
        self.assertEqual(server.get_ticket(t["id"])["status"], "waiting_on_you")

    def test_undo_refused_while_running_and_when_post_missing(self):
        t = self._ticket()
        f = self.h.tmp / "nopost.txt"
        f.write_text("v1")
        self._edit(t["id"], f, "v2")
        self._set_status(t["id"], "in_progress")
        self.assertEqual(self.h.api("POST", f"/api/tickets/{t['id']}/undo")[0], 409)
        self._set_status(t["id"], "waiting_on_you")
        g = self.h.tmp / "nopost2.txt"
        g.write_text("w1")
        self._hook(t["id"], g, "PreToolUse")
        g.write_text("w2")  # the post hook never ran
        ch = {c["file"]: c for c in self.h.api("GET", f"/api/tickets/{t['id']}/changes")[1]["changes"]}
        self.assertTrue(ch[str(g.resolve())]["changed_since"])
        self.assertEqual(self.h.api("POST", f"/api/tickets/{t['id']}/undo")[0], 409)
        self.assertEqual(f.read_text(), "v2")  # refused as a whole: nothing touched

    def test_undo_same_name_new_files_and_partial_failure(self):
        t = self._ticket()
        (self.h.tmp / "a").mkdir()
        (self.h.tmp / "b").mkdir()
        n1, n2, old = self.h.tmp / "a" / "new.txt", self.h.tmp / "b" / "new.txt", self.h.tmp / "keep.txt"
        old.write_text("before")
        self._edit(t["id"], n1, "one")
        self._edit(t["id"], n2, "two")
        self._edit(t["id"], old, "after")
        with mock.patch.object(server.shutil, "copyfile", side_effect=OSError("disk")):
            self.assertEqual(self.h.api("POST", f"/api/tickets/{t['id']}/undo")[0], 500)
        removed = server.CHANGE_JOURNAL / "removed" / t["id"]
        self.assertEqual(sorted(p.read_text() for p in removed.iterdir()), ["one", "two"])  # no overwrite
        left = self.h.api("GET", f"/api/tickets/{t['id']}/changes")[1]["changes"]
        self.assertEqual([c["file"] for c in left], [str(old.resolve())])  # the rest can still be undone
        self.assertEqual(self.h.api("POST", f"/api/tickets/{t['id']}/undo")[0], 200)
        self.assertEqual(old.read_text(), "before")

    # ── TKT-0011: no @YOU post, trust fixes, sessions that wait for Jhon ──
    _POST = {"cover": {"number": "9", "highlight": "GitHub repos", "rest": "for clients"}, "caption": "Hook",
             "slides": [{"name": n, "repo": f"o/{n}"} for n in ("a", "b", "c")]}

    def _ig_crew(self, handle=""):
        import crews
        code, c = self.h.api("POST", "/api/crews", {"name": "IG h", "task": "t", "action": "ig_carousel", "agent": {"agent": "opencode"},
                                                    "advisor": {"agent": "claude"}, "helper": {"agent": "hermes"}, "approve_first": 1})
        self.assertEqual(code, 200, c)
        if handle:
            crews.update(c["id"], lambda x: x.update(ig_handle=handle))
        return c["id"]

    def test_no_instagram_render_without_a_real_handle(self):
        import crews
        import ig_carousel
        for stored, looked_up in (("", None), ("", "you"), ("YOU", "You"), ("", "")):
            cid = self._ig_crew(stored)
            acct = {"_id": "acc1", "username": looked_up} if looked_up is not None else None
            with mock.patch.object(server, "secret", return_value="sk-test"), \
                 mock.patch.object(ig_carousel, "repo_exists", return_value=True), \
                 mock.patch.object(ig_carousel, "account", **({"return_value": acct} if acct else {"side_effect": RuntimeError("no account")})), \
                 mock.patch.object(ig_carousel, "render") as render, mock.patch.object(ig_carousel, "render_story") as story:
                with self.assertRaisesRegex(RuntimeError, "Instagram handle didn't load"):
                    server._crew_action(crews.get(cid), {"id": "r1"}, json.dumps(self._POST))
            render.assert_not_called()
            story.assert_not_called()
        # the whole run: Failed, not on Approvals, and it doesn't count as an approval
        fake_exec = lambda agent, prompt, model="", effort="": json.dumps(self._POST) if "AGENT" in prompt else f"{agent} says"
        with mock.patch.object(server, "execute_agent", side_effect=fake_exec), \
             mock.patch.object(server, "secret", return_value=""), \
             mock.patch.object(ig_carousel, "repo_exists", return_value=True), \
             mock.patch.object(ig_carousel, "popular_repos", return_value=""), \
             mock.patch.object(ig_carousel, "render") as render, \
             mock.patch.object(crews.threading, "Thread", side_effect=lambda target, daemon, args=(): mock.Mock(start=lambda: target(*args))):
            server.start_crew_run(cid)
        render.assert_not_called()
        c = crews.get(cid)
        self.assertEqual((c["runs"][-1]["status"], c["approved"]), ("failed", 0))
        self.assertIn("handle didn't load", c["runs"][-1]["error"])
        self.assertFalse([i for i in self.h.api("GET", "/api/approvals")[1]["approvals"] if i["kind"] == "crew" and cid in json.dumps(i)])

    def test_a_stored_you_is_replaced_by_the_real_username(self):
        import crews
        import ig_carousel
        cid = self._ig_crew("you")
        with mock.patch.object(server, "secret", return_value="sk-test"), \
             mock.patch.object(ig_carousel, "repo_exists", return_value=True), \
             mock.patch.object(ig_carousel, "account", return_value={"_id": "acc9", "username": "jhon"}), \
             mock.patch.object(ig_carousel, "render", return_value=[Path("s1.png")] * 3) as render, mock.patch.object(ig_carousel, "render_story"):
            out = server._crew_action(crews.get(cid), {"id": "r1"}, json.dumps(self._POST))
        self.assertEqual(render.call_args.args[2], "jhon")
        self.assertEqual((out["slides"], crews.get(cid)["ig_handle"], crews.get(cid)["ig_account"]), (3, "jhon", "acc9"))

    def test_a_late_reply_stays_in_its_chat_and_a_swapped_agent_is_named(self):
        agent = "agy"
        server.clear_chat(agent)
        chat_a = server.current_chat_id(agent)

        def slow(a, p, model="", effort="", meta=None, **kw):
            server.clear_chat(agent)  # Jhon opens a new chat while the reply is on its way
            meta["actual_agent"] = "opencode"  # codex was out; another agent answered
            return "late answer"
        with mock.patch.object(server, "execute_agent", side_effect=slow):
            code, data = self.h.api("POST", "/api/chat", {"agent": agent, "message": "hello A"})
        self.assertEqual(code, 200)
        self.assertEqual((data["response"]["chat"], data["response"]["by"]), (chat_a, "opencode"))
        self.assertIn("was out", data["response"]["why"])
        chat_b = server.current_chat_id(agent)
        self.assertNotEqual(chat_a, chat_b)
        shown = [m["content"] for m in self.h.api("GET", f"/api/chat/history?agent={agent}")[1]["messages"]]
        self.assertNotIn("late answer", shown)  # not in chat B
        counts = {c["id"]: c["count"] for c in server.list_chats(agent)}
        self.assertEqual((counts[chat_a], counts[chat_b]), (2, 0))
        server.open_chat(agent, chat_a)  # reopening A brings the late reply back
        shown = [m["content"] for m in self.h.api("GET", f"/api/chat/history?agent={agent}")[1]["messages"]]
        self.assertEqual(shown, ["hello A", "late answer"])

    def test_hermes_limit_notice_is_an_error_not_an_answer(self):
        notice = "Sorry. Your request was not processed: free limit reached"
        self.assertTrue(server.hermes_limit_reason(notice))
        self.assertEqual(server.hermes_limit_reason("The rate limit here is 50 a minute."), "")  # an answer about limits
        self.assertTrue(server.hermes_limit_reason("429 rate limit", code=2))
        delta = lambda t: b"data: " + json.dumps({"choices": [{"delta": {"content": t}}]}).encode() + b"\n"
        resp = mock.MagicMock(headers={})
        resp.__enter__.return_value = resp
        resp.__iter__.return_value = iter([delta(notice), b"data: [DONE]\n"])
        with mock.patch.object(server, "hermes_gateway_ensure", return_value=True), \
             mock.patch.object(server.urllib.request, "urlopen", return_value=resp), \
             mock.patch.object(server, "append_cost") as cost, \
             mock.patch.object(server, "StreamingResponse", side_effect=lambda gen, **kw: gen):
            frames = list(server.chat_stream(server.ChatRequest(agent="hermes", message="hi")))
        self.assertTrue(any('"type": "error"' in f for f in frames))
        saved = [m for m in server.load_chat_history()["messages"] if m["agent"] == "hermes"][-1]
        self.assertTrue(saved["content"].startswith("⚠ Hermes stopped:"))
        cost.assert_not_called()

    def test_both_review_paths_get_the_same_log_tails(self):
        marker = "These tails are the whole log input. Do not open the log files for more."
        with mock.patch.object(server, "bg_agent", return_value="opencode"), \
             mock.patch.object(server, "execute_agent", return_value=json.dumps({"findings": []})) as ex:
            server.self_review()
        free = ex.call_args.args[1]
        reply = json.dumps({"structured_output": {"findings": []}, "usage": {}})
        with mock.patch.object(server, "bg_agent", return_value="claude"), \
             mock.patch.object(server, "run_cli", return_value=(0, reply, "")) as cli:
            server.self_review()
        claude = cli.call_args.kwargs["answer"]
        for prompt in (free, claude):
            self.assertIn(marker, prompt)
            self.assertIn("--- audit.log ---", prompt)
            self.assertIn("--- server.log ---", prompt)
            self.assertIn("--- tickets ---", prompt)

    def _session(self, sid, status="running", rounds=3):
        s = {"id": sid, "title": "t", "agents": ["claude", "codex"], "rounds": rounds, "round": 0, "judge": True, "working": [],
             "status": status, "messages": [{"role": "user", "agent": None, "round": 0, "content": "q"}]}
        server.save_session(s)
        return s

    def test_session_waits_on_a_question_then_continues_after_the_answer(self):
        self.enterContext(mock.patch.dict(server._resting, {}, clear=True))
        sess = self._session("abcdef41")
        asks = lambda a, p, meta=None, **kw: "Idea.\nQUESTION:  Which   repo?" if a == "claude" else "Idea.\nQUESTION: which repo?"
        verdict = (0, json.dumps({"result": "Do X.", "usage": {}}), "")
        with mock.patch.object(server, "execute_agent", side_effect=asks) as ex, mock.patch.object(server, "run_cli", return_value=verdict) as cli:
            server.run_session(sess)
        s = server.get_session("abcdef41")
        self.assertEqual((s["status"], s["round"], s["rounds_left"], len(s["questions"])), ("awaiting_user", 1, 2, 1))  # one question, no 2nd round
        self.assertEqual(ex.call_count, 2)
        self.assertNotIn("verdict", [m["role"] for m in s["messages"]])
        cli.assert_not_called()
        self.assertEqual(self.h.api("POST", "/api/sessions/abcdef41/message", {"message": ""})[0], 400)
        done = lambda a, p, meta=None, **kw: "Got it.\nDONE"
        inline = lambda x: (x.update(status="running"), server.run_session(x))
        with mock.patch.object(server, "execute_agent", side_effect=done) as ex, mock.patch.object(server, "run_cli", return_value=verdict), \
             mock.patch.object(server, "start_session_thread", side_effect=inline):
            code, _ = self.h.api("POST", "/api/sessions/abcdef41/message", {"message": "the main one"})
        s = server.get_session("abcdef41")
        self.assertEqual(code, 200)
        self.assertEqual((s["status"], s["questions"], s["messages"][-1]["role"]), ("idle", [], "verdict"))
        self.assertIn("the main one", ex.call_args.args[1])  # the agents saw his answer
        self.assertEqual(ex.call_count, 2)  # all said DONE in the first round left
        # an ordinary reply while idle still runs the full round count
        self._session("abcdef42", status="idle", rounds=2)
        more = lambda a, p, meta=None, **kw: "more thoughts"
        with mock.patch.object(server, "execute_agent", side_effect=more) as ex, mock.patch.object(server, "run_cli", return_value=verdict), \
             mock.patch.object(server, "start_session_thread", side_effect=inline):
            self.h.api("POST", "/api/sessions/abcdef42/message", {"message": "go"})
        self.assertEqual(ex.call_count, 4)


class TierAndPlanGoneTest(unittest.TestCase):
    def test_heavy_light_models_and_a_gone_plan_sits_out(self):
        self.assertEqual(server.tier_args("heavy", "codex"), {"model": "gpt-6.1-sol", "effort": "high"})
        self.assertEqual(server.tier_args("light", "grok"), {"model": "grok-4.7-build-fast", "effort": "low"})
        self.assertEqual(server.tier_args("heavy", "opencode"), {"model": "", "effort": ""})  # free agents keep their own default
        self.enterContext(mock.patch.dict(server._resting, {}, clear=True))
        self.enterContext(mock.patch.object(server, "append_audit"))
        self.assertFalse(server._limited("codex", "A post about the subscription economy."))  # a real answer never benches it
        self.assertFalse(server._limited("opencode", "opencode failed: subscription required"))  # free agents never sit out for this
        self.assertTrue(server._limited("grok", "Grok failed: your subscription has ended, upgrade to continue"))
        self.assertGreater(server._resting["grok"], time.time())
        with mock.patch.object(server, "_execute_agent", return_value="free answer") as run:
            self.assertEqual(server.execute_agent("grok", "hi"), "free answer")  # grok was skipped for a backup
        self.assertNotEqual(run.call_args.args[0], "grok")
        with mock.patch.object(server, "BASE_DIR", Path(tempfile.mkdtemp())):
            self.assertEqual(server.bg_agent("check"), server.BACKGROUND_DEFAULT)


class MeetingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def test_meeting_flags_only_a_real_idea_and_deny_drops_it(self):
        with mock.patch.object(server, "meeting_agents", return_value=["codex", "opencode"]), \
             mock.patch.object(server, "start_session_thread") as start:
            sid = server.start_meeting()
        sess = start.call_args.args[0]
        self.assertTrue(sess["meeting"])
        self.assertEqual(sess["overrides"], {"codex": {"model": "gpt-6.1-sol", "effort": "high"}, "opencode": {}})  # heavy tier
        self.assertFalse(server._meeting_due())  # once a day
        self.assertLess(len(sess["messages"][0]["content"]), 10000)  # the logs and tickets all fit, none cut off
        self.assertIn("--- tickets ---", sess["messages"][0]["content"])
        with mock.patch.object(server, "claude_guard", return_value="over"), mock.patch.object(server, "bg_agent", return_value="grok"), \
             mock.patch.dict(server._resting, {}, clear=True), mock.patch.object(server, "AGENTS", ["claude", "codex", "grok", "agy", "opencode"]):
            self.assertEqual(server.meeting_agents(), ["codex", "agy", "opencode"])  # grok sits out to judge
        idea = lambda a, p, meta=None, **kw: "Add a retry.\nDONE"
        with mock.patch.object(server, "execute_agent", side_effect=idea), mock.patch.object(server, "claude_guard", return_value="over"), \
             mock.patch.object(server, "run_cli") as cli, mock.patch.object(server, "check_reply", return_value=None):
            server.run_session(sess)
        cli.assert_not_called()  # Claude over its guard: the meeting's verdict doesn't spend Opus
        ids = [i["id"] for i in server.approval_items()]
        self.assertIn(f"meeting:{sid}", ids)
        self.assertEqual(self.h.api("POST", f"/api/approvals/meeting:{sid}", {"decision": "deny"})[0], 200)
        self.assertNotIn(f"meeting:{sid}", [i["id"] for i in server.approval_items()])
        self.assertEqual(server.get_session(sid)["status"], "idle")  # dropped, no new rounds
        # nothing worth doing: no flag at all
        sess2 = dict(sess, id="abcd0002", messages=sess["messages"][:1], round=0)
        with mock.patch.object(server, "execute_agent", side_effect=lambda a, p, meta=None, **kw: "NOTHING TO DO\nDONE"), \
             mock.patch.object(server, "session_verdict", return_value="NOTHING TO DO"):
            server.run_session(sess2)
        self.assertNotIn("meeting:abcd0002", [i["id"] for i in server.approval_items()])
        self.assertTrue(server.get_session("abcd0002")["read"])


class UpworkInviteTests(unittest.TestCase):
    def test_an_invite_is_drafted_even_with_a_low_score_and_other_boards_skip(self):
        from jobs import pipeline
        tmp = Path(tempfile.mkdtemp())
        inv = {"source": "Upwork invite", "url": "https://www.upwork.com/ab/proposals/interviews/9", "company": "Upwork client",
               "title": "Bookkeeper", "location": "Remote", "pay": "", "jd": "An Upwork client invited him.", "invite": True}
        low = lambda jobs, run_agent: [j.update(score=1, reason="off lane") for j in jobs] or True
        with mock.patch.object(pipeline, "BOARD", tmp / "board.json"), mock.patch.object(pipeline, "SEEN", tmp / "seen.json"), \
             mock.patch.object(pipeline, "upwork_offers", return_value=[dict(inv)]), \
             mock.patch.object(pipeline, "hoot_offers", side_effect=AssertionError("full search ran")), \
             mock.patch.object(pipeline, "score_batch", side_effect=low), \
             mock.patch.object(pipeline, "draft", return_value="Hi, thanks for the invite. I edit YouTube videos daily and can start on this today."):
            stats = pipeline.run(lambda a, p: "", log=lambda m: None, invites_only=True)
        j = pipeline.load(tmp / "board.json", {"jobs": []})["jobs"][0]
        self.assertEqual((stats["drafted"], j["status"], j["invite"]), (1, "drafted", True))
        self.assertEqual(pipeline.platform(j["url"]), "Upwork")


class CrewBriefTests(unittest.TestCase):
    def test_next_brief_sets_topic_and_image_once(self):
        import crews
        tmp = Path(tempfile.mkdtemp())
        img = tmp / "pic.png"
        img.write_bytes(b"png")
        with mock.patch.object(crews, "STATE", tmp / "crews.json"), mock.patch.object(crews, "RUNS_DIR", tmp / "runs"):
            c = crews.create({"name": "Li", "task": "AI news", "skills": [], "agent": {"agent": "grok"}, "advisor": None,
                              "helper": None, "action": "linkedin_post", "schedule": "", "approve_first": 3})
            crews.update(c["id"], lambda x: x.update(next_brief={"topic": "Claude Ultra", "image": str(img)}))
            seen = []
            rid = crews.run(c["id"], lambda a, p, m, e: seen.append(p) or "Post text", server._crew_action)
            for _ in range(50):
                r = next(x for x in crews.get(c["id"])["runs"] if x["id"] == rid)
                if r["status"] != "running":
                    break
                time.sleep(0.05)
            self.assertIn("Claude Ultra", seen[0])
            self.assertEqual((r["status"], r["slides"]), ("awaiting_approval", 1))
            self.assertTrue((tmp / "runs" / c["id"] / rid / "slide0.png").exists())
            self.assertNotIn("next_brief", crews.get(c["id"]))


class PostGuardTests(unittest.TestCase):
    def test_owner_name_and_agent_notes_block_auto_post(self):
        with mock.patch.object(server.owner, "NAME", "Jhon Doe"):
            self.assertIn("Jhon", server.post_problem("Jhon, I'll check the write-up. Your AI isn't..."))
            self.assertIn("notes", server.post_problem("Original post is up. I'll read the numbers.\nYour AI..."))
            self.assertEqual(server.post_problem("Max 20x is now Ultra. Does a weekly budget change how you run agents?"), "")


class IndeedAddTests(unittest.TestCase):
    def test_title_before_the_link_is_dropped(self):
        from jobs import pipeline
        seen = []
        with mock.patch.object(pipeline, "add_link", lambda link, run: seen.append(link) or {"title": "x", "score": 1}), \
             mock.patch.object(server, "append_audit", lambda *_: None):
            server.jobs_add({"url": "Generative AI Editor https://ph.indeed.com/viewjob?jk=0123456789abcdef"})
            with self.assertRaises(server.HTTPException):
                server.jobs_add({"url": "Generative AI Editor"})
        self.assertEqual(seen, ["https://ph.indeed.com/viewjob?jk=0123456789abcdef"])

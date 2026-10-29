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
        with mock.patch.object(server, "execute_agent", lambda a, p, model="", effort="": calls.append((a, model, effort)) or "x"):
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
        sess = {"id": "abcdef14", "title": "t", "agents": ["claude", "opencode"], "rounds": 2, "round": 0, "judge": True,
                "working": [], "status": "running", "messages": [{"role": "user", "agent": None, "round": 0, "content": "q"}]}
        with mock.patch.object(server, "execute_agent", return_value="idea\nDONE"), \
             mock.patch.object(server, "run_cli", return_value=(0, json.dumps({"result": "Do X. Why: best.", "usage": {}}), "")) as cli:
            server.run_session(sess)
        s = server.get_session("abcdef14")
        self.assertEqual([m["role"] for m in s["messages"]], ["user", "agent", "agent", "verdict"])  # all said DONE: one round
        self.assertEqual(s["messages"][-1]["content"], "Do X. Why: best.")
        self.assertIn("[claude]\nidea", cli.call_args.kwargs["answer"])  # the judge read the debate
        self.assertFalse(s["judging"])
        old = {**sess, "id": "abcdef15", "judge": False, "messages": sess["messages"][:1]}
        with mock.patch.object(server, "execute_agent", return_value="idea\nDONE"):
            server.run_session(old)
        self.assertNotIn("verdict", [m["role"] for m in server.get_session("abcdef15")["messages"]])  # older sessions unchanged

    def test_fix_with_empty_output_and_status_changed_meanwhile(self):
        t = self._ticket()
        self._set_status(t["id"], "approved")
        with mock.patch.object(server, "_run_ticket_claude", return_value=""):
            server.ticket_tick()
        self.assertEqual(server.get_ticket(t["id"])["status"], "waiting_on_you")  # no IndexError, not stuck in_progress
        self._set_status(t["id"], "approved")
        with mock.patch.object(server, "_run_ticket_claude",
                               side_effect=lambda x, stage: self._set_status(x["id"], "closed") or "ok\nSTATUS: RESOLVED"):
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

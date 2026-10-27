"""Security hardening tests — path-traversal / injection guards (v4.0).

Validates the strict allowlist validation added in commit 4af5ea3:
skill names, kanban task ids, journal dates, scheduler job names, backup files.
"""
import sys
import unittest
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Harness  # noqa: E402


class SecurityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = Harness()
        cls.h.start()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    # ── traversal payloads (URL-encoded so they stay single-segment) ─
    TRAVERSALS = [
        quote("../../etc/passwd"),
        quote("..%2F..%2Fetc%2Fpasswd"),
        quote("....//....//etc/passwd"),
        quote("%2e%2e/settings"),
        quote("..%5c..%5csettings"),
    ]

    def test_skill_traversal_rejected(self):
        for evil in self.TRAVERSALS:
            status, _ = self.h.api("GET", f"/api/skills/{evil}")
            self.assertNotEqual(status, 200, f"skill traversal slipped through: {evil}")

    def test_skill_run_traversal_rejected(self):
        for evil in [quote("../../settings"), quote("..%2F..%2Fdata%2Fsettings")]:
            status, _ = self.h.api("POST", f"/api/skills/{evil}/run",
                                   {"agent": "hermes", "input": "x"})
            self.assertNotEqual(status, 200, f"skill run traversal slipped: {evil}")

    def test_skill_eval_traversal_rejected(self):
        for evil in [quote("../../settings"), quote("..%2F..%2Fdata")]:
            status, _ = self.h.api("GET", f"/api/skills/{evil}/eval")
            self.assertNotEqual(status, 200, f"skill eval traversal slipped: {evil}")

    def test_skill_invalid_chars_rejected(self):
        for evil in ["heartbeat.", ".hidden", quote("a b"), "a*b", quote("a\u00e9")]:
            status, _ = self.h.api("GET", f"/api/skills/{evil}")
            self.assertNotEqual(status, 200, f"invalid skill name accepted: {evil}")

    def test_skill_valid_name_works(self):
        status, data = self.h.api("GET", "/api/skills/heartbeat")
        self.assertEqual(status, 200)
        self.assertEqual(data.get("name"), "heartbeat")

    def test_kanban_traversal_rejected(self):
        for evil in ["../../settings", "..%2F..%2Fdata%2Fsettings"]:
            status, _ = self.h.api("GET", f"/api/kanban/tasks/{evil}")
            self.assertNotEqual(status, 200, f"kanban traversal slipped: {evil}")

    def test_kanban_invalid_id_rejected(self):
        for evil in ["..", ".", "a/b", "a\\b", ""]:
            status, _ = self.h.api("GET", f"/api/kanban/tasks/{evil}")
            self.assertNotEqual(status, 200, f"invalid kanban id accepted: {evil}")

    def test_journal_traversal_rejected(self):
        status, _ = self.h.api("GET", "/api/journal/entries/../../settings")
        self.assertNotEqual(status, 200)
        status, _ = self.h.api("GET", "/api/journal/entries/2026-06-05%2F..%2F..%2Fetc%2Fpasswd")
        self.assertNotEqual(status, 200)

    def test_journal_bad_date_format_rejected(self):
        for evil in ["2026-6-5", quote("2026/06/05"), "2026.06.05", "06-05-2026", "20260605"]:
            status, _ = self.h.api("GET", f"/api/journal/entries/{evil}")
            # 400 = validator rejection, 404 = slash decoded before routing
            self.assertIn(status, (400, 404), f"bad date accepted: {evil}")

    def test_journal_valid_date_works(self):
        status, _ = self.h.api("GET", "/api/journal/entries/2026-06-05")
        self.assertEqual(status, 200)

    def test_scheduler_job_traversal_rejected(self):
        status, _ = self.h.api(
            "POST", "/api/scheduler/jobs",
            {"name": "../../evil", "skill": "heartbeat", "cron": "* * * * *"},
        )
        self.assertEqual(status, 400)

    def test_scheduler_job_invalid_name_rejected(self):
        status, _ = self.h.api(
            "POST", "/api/scheduler/jobs",
            {"name": "evil/../x", "skill": "heartbeat", "cron": "* * * * *"},
        )
        self.assertEqual(status, 400)

    def test_scheduler_job_valid_name_works(self):
        status, data = self.h.api(
            "POST", "/api/scheduler/jobs",
            {"name": "test-job-v4", "kind": "skill", "skill": "heartbeat", "cron": "0 0 * * *"},
        )
        self.assertEqual(status, 200)
        self.assertEqual(data.get("name"), "test-job-v4")
        # cleanup
        self.h.api("DELETE", f"/api/scheduler/jobs/{data['id']}")

    def test_backup_restore_traversal_rejected(self):
        for evil in ["../../etc/passwd", "agentic-os.tar.gz", "..%2F..%2Fsettings"]:
            status, _ = self.h.api("POST", "/api/backup/restore", {"file": evil})
            self.assertEqual(status, 400, f"backup traversal slipped: {evil}")

    def test_brain_file_traversal_rejected(self):
        for evil in ["../../etc/passwd", "..%2F..%2Fserver.py"]:
            status, _ = self.h.api("GET", f"/api/brain/{evil}")
            self.assertNotEqual(status, 200, f"brain traversal slipped: {evil}")

    def test_brain_file_windows_paths_rejected(self):
        for evil in [quote(r"C:\Windows\win.ini", safe=""), quote(r"..\..\server.py", safe=""),
                     quote(r"\\host\share\x.md", safe=""), quote(r"C:\x\evil.md", safe=""), "notes.txt"]:
            status, _ = self.h.api("GET", f"/api/brain/{evil}")
            self.assertEqual(status, 400, f"brain path accepted: {evil}")
            status, _ = self.h.api("PUT", f"/api/brain/{evil}", {"content": "x"})
            self.assertEqual(status, 400, f"brain write accepted: {evil}")

    def test_brain_file_valid_name_works(self):
        status, data = self.h.api("GET", "/api/brain/memory.md")
        self.assertEqual(status, 200)
        self.assertEqual(data.get("name"), "memory.md")

    def test_diff_ref_option_injection_rejected(self):
        target = self.h.tmp / "pwned.txt"
        for evil in [f"--output={target}", "-p", "--no-index", "HEAD;rm", "HEAD x"]:
            status, _ = self.h.api("GET", f"/api/diff?file=server.py&ref={quote(evil, safe='')}")
            self.assertEqual(status, 400, f"diff ref accepted: {evil}")
        self.assertFalse(target.exists())

    def test_diff_valid_ref_passes_validation(self):
        # no server.py in the temp state dir: 404 means the ref itself was accepted
        status, _ = self.h.api("GET", "/api/diff?file=missing.py&ref=HEAD~1")
        self.assertEqual(status, 404)

    def test_foreign_host_rejected(self):
        for host in ["evil.example", "evil.example:8810", "127.0.0.1.evil.example", "localhost.evil.example"]:
            status, _ = self.h.api("GET", "/api/status", headers={"Host": host})
            self.assertEqual(status, 403, f"host accepted: {host}")

    def test_local_ip_and_tailscale_hosts_allowed(self):
        for host in ["127.0.0.1:8810", "localhost:8810", "[::1]:8810", "192.168.1.5:8810",
                     "100.64.0.7", "pc.tail1234.ts.net", "pc.tail1234.ts.net:8810"]:
            status, _ = self.h.api("GET", "/api/status", headers={"Host": host})
            self.assertEqual(status, 200, f"host rejected: {host}")

    def test_cross_site_write_rejected(self):
        for origin in ["http://evil.example", "null", "http://127.0.0.1.evil.example"]:
            status, _ = self.h.api("POST", "/api/agents/health/refresh", headers={"Origin": origin})
            self.assertEqual(status, 403, f"cross-site write accepted from {origin}")

    def test_same_origin_and_originless_writes_allowed(self):
        for headers in [{}, {"Origin": self.h.base_url}, {"Origin": "http://127.0.0.1:8810"}]:
            status, _ = self.h.api("POST", "/api/agents/health/refresh", headers=headers)
            self.assertEqual(status, 200, f"write rejected with {headers}")
        # reads with a foreign Origin still answer (CORS keeps the response from the page)
        status, _ = self.h.api("GET", "/api/status", headers={"Origin": "http://evil.example"})
        self.assertEqual(status, 200)

    def test_connections_write_needs_loopback(self):
        import server
        from types import SimpleNamespace
        req = server.ConnectionRequest(name="x", command="evil.exe", agents=["claude"])
        with self.assertRaises(server.HTTPException) as ctx:
            server.add_connection(req, SimpleNamespace(client=SimpleNamespace(host="100.64.0.7")))
        self.assertEqual(ctx.exception.status_code, 403)

    def test_token_redacted_in_access_log(self):
        import logging
        import server
        rec = logging.LogRecord("uvicorn.access", logging.INFO, "", 0, '%s - "%s %s HTTP/%s" %d',
                                ("1.2.3.4:5", "GET", f"/?token={server.AUTH_TOKEN}&x=1", "1.1", 200), None)
        server.RedactTokenFilter().filter(rec)
        self.assertNotIn(server.AUTH_TOKEN, rec.getMessage())
        self.assertIn("token=REDACTED&x=1", rec.getMessage())

    def test_job_approve_only_from_approvable_states(self):
        import jobs.pipeline as pipeline
        for s in ["applied", "applying", "approved", "skipped", "queued"]:
            pipeline.save(pipeline.BOARD, {"jobs": [{"id": "abcdef0123", "title": "t", "company": "c", "status": s}],
                                           "runs": []})
            status, _ = self.h.api("POST", "/api/jobs/abcdef0123/decision", {"decision": "approve"})
            self.assertEqual(status, 409, f"approve allowed from {s}")


if __name__ == "__main__":
    unittest.main(verbosity=2)

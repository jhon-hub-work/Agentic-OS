"""Billing's "All agents" panel: parsing each agent's limit source from fake inputs (no network, no real files)."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import server  # noqa: E402


class AgentLimitParsing(unittest.TestCase):
    def test_openrouter_free_window(self):
        w = server.openrouter_free_window({"data": {"free_model_daily_requests": {"used": 53, "limit": 50, "remaining": 0}}})
        self.assertEqual((w["used"], w["limit"]), (53, 50))
        self.assertEqual(w["resets_at"] % 86400, 0)  # next 00:00 UTC
        self.assertIsNone(server.openrouter_free_window({"data": {"usage": 0}}))

    def test_codex_windows_last_reading_and_rollover(self):
        rl = lambda p5, reset5: {"rate_limits": {"primary": {"used_percent": p5, "window_minutes": 300, "resets_at": reset5},
                                                 "secondary": {"used_percent": 3.0, "window_minutes": 10080, "resets_at": 2000}}}
        text = json.dumps(rl(10.0, 2000)) + "\n" + json.dumps({"payload": rl(40.0, 2000)}) + "\n"
        w = server.codex_windows(text, now=1000)
        self.assertEqual([(x["name"], x["used_pct"]) for x in w], [("5-hour window", 40.0), ("This week", 3.0)])
        self.assertEqual(server.codex_windows(json.dumps(rl(40.0, 500)), now=1000)[0]["used_pct"], 0.0)  # reset passed
        self.assertIsNone(server.codex_windows('{"rate_limits":null}', now=1000))
        self.assertIsNone(server.codex_windows('{"type":"session_meta"}', now=1000))

    def test_opencode_limit_hit(self):
        tail = ('timestamp=2026-10-29T20:00:00.000Z level=INFO message="cli starting"\n'
                'timestamp=2026-10-29T21:10:09.907Z level=ERROR cause="AI.Error: Free usage limit reached. Connect"\n')
        self.assertAlmostEqual(server.opencode_limit_hit(tail), 1793308209.907, places=2)
        self.assertIsNone(server.opencode_limit_hit('timestamp=2026-10-29T20:00:00Z level=INFO ok\n'))

    def test_calls_since(self):
        e = [{"agent": "hermes", "timestamp": "2026-10-29T20:06:13+00:00"}, {"agent": "hermes", "timestamp": "2026-10-28T20:00:00+00:00"},
             {"agent": "claude", "timestamp": "2026-10-29T21:00:00+00:00"}]
        self.assertEqual(server.calls_since(e, "hermes", 1793232000), 1)  # since 2026-10-29 00:00 UTC


if __name__ == "__main__":
    unittest.main()

"""One follow-up in an OnlineJobs application thread, in Hoot's signed-in Brave (Jhon 31 Oct: "it should follow up
automatically"). Called by apply_runner.py (mode "followup"); system Python, Playwright.

Messages > Sent > the thread "Application: <title>" (apply_runner's subject) > type the follow-up > SEND MESSAGE.
If anyone but him wrote in the thread, nothing is sent: they replied.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import owner  # noqa: E402

sys.path.insert(0, str(owner.path("hoot_dir") or "hoot"))

ME = re.compile(r"buerano|bueranojhon", re.I)  # his name on OnlineJobs; every other sender is the employer


def send(job: dict, text: str, dry_run: bool = False) -> dict:
    from apply import CDP_PORT, ensure_brave
    from playwright.sync_api import sync_playwright
    subject = f"Application: {job['title']}"[:120]
    ensure_brave()
    with sync_playwright() as p:
        ctx = p.chromium.connect_over_cdp(f"http://127.0.0.1:{CDP_PORT}").contexts[0]
        page = ctx.new_page()
        try:
            page.goto("https://www.onlinejobs.ph/message", wait_until="domcontentloaded", timeout=60000)
            page.wait_for_timeout(4000)
            if "login" in page.url:
                return {"outcome": "handoff", "reason": "signed out of OnlineJobs in Hoot's Brave"}
            page.get_by_text("Sent", exact=True).locator("visible=true").first.click()
            page.wait_for_timeout(3000)
            thread = page.get_by_text(subject, exact=True).locator("visible=true")
            if not thread.count():
                return {"outcome": "handoff", "reason": f"no '{subject}' thread under Sent"}
            thread.first.click()
            page.wait_for_timeout(4000)
            # each message's header button starts with the sender's name
            senders = [s.split("\n")[0] for s in page.locator("button[id^='radix'][class*='flex-1']").all_inner_texts()]
            if any(s.strip() and not ME.search(s) for s in senders):
                return {"outcome": "replied", "reason": "the employer wrote in the thread"}
            box = page.locator("trix-editor").first
            box.click()
            box.fill(text)
            if dry_run:
                return {"outcome": "dry_run", "reason": f"filled, not sent ({len(senders)} messages in the thread)"}
            page.get_by_role("button", name="SEND MESSAGE").click()
            page.wait_for_timeout(5000)
            if page.get_by_text(text.split("\n")[0][:60], exact=False).count() < 1:
                return {"outcome": "handoff", "reason": "pressed SEND MESSAGE but the follow-up didn't show in the thread"}
            return {"outcome": "followed_up", "reason": "follow-up sent in the OnlineJobs thread"}
        finally:
            if not dry_run:
                page.close()


if __name__ == "__main__":  # dry run on one job: python jobs/olj_followup.py "<title>" "<text>"
    import json
    print(json.dumps(send({"title": sys.argv[1]}, sys.argv[2], dry_run=True)))

"""Send one Reddit DM to a [Hiring] poster from the owner's Reddit account, signed in inside Hoot's Brave.
Called by apply_runner.py (system Python, Playwright) for approved or auto-approved Reddit jobs.

Guards: one DM per poster ever, at most DAILY_CAP a day (Reddit suspends accounts that DM in bulk), never a
login, CAPTCHA or password: anything like that is handed back to the owner with the filled form left open.
"""

import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

LOG = Path(__file__).resolve().parent.parent / "data" / "jobs" / "reddit_sent.json"
DAILY_CAP = 5


def poster(company: str) -> str:
    """'Reddit /u/name' (sources.reddit) -> 'name', '' if it doesn't look like a Reddit username."""
    m = re.fullmatch(r"Reddit\s+(?:/?u/)?([A-Za-z0-9_-]{3,20})", (company or "").strip())
    return m.group(1) if m else ""


def blocked(user: str, log: list, followup: bool = False) -> str:
    today = datetime.now(timezone.utc).date().isoformat()
    if any(e["user"].lower() == user.lower() and bool(e.get("followup")) == followup for e in log):
        return f"already sent u/{user} a {'follow-up' if followup else 'DM'}"
    if sum(e["at"].startswith(today) for e in log) >= DAILY_CAP:
        return f"daily Reddit DM cap ({DAILY_CAP}) reached; it goes out tomorrow or you send it"
    return ""


def boxes(page) -> list:
    """Every textarea's text (one is the hidden reCAPTCHA token, so match on the message itself)."""
    t = page.locator("textarea")
    return [t.nth(i).input_value() for i in range(t.count())]


def send(job: dict, dry_run: bool = False, followup: bool = False) -> dict:
    """The first DM, or (followup=True) one follow-up when the poster hasn't replied in the Reddit inbox."""
    import apply as applier  # Hoot's Brave helpers (hoot_dir is on sys.path via apply_runner)
    from playwright.sync_api import sync_playwright

    user = poster(job.get("company", ""))
    if not user:
        return {"outcome": "handoff", "reason": "couldn't tell who posted it; DM them from the post"}
    log = json.loads(LOG.read_text(encoding="utf-8")) if LOG.exists() else []
    why = blocked(user, log, followup)
    if why and not dry_run:
        return {"outcome": "handoff", "reason": why}
    text = job.get("followup_draft" if followup else "draft", "")
    subject = re.sub(r"\s+", " ", f"Re: {job.get('title', '')}")[:95]
    url = (f"https://www.reddit.com/message/compose/?to={quote(user)}&subject={quote(subject)}"
           f"&message={quote(text)}")
    applier.ensure_brave()
    with sync_playwright() as p:
        browser = p.chromium.connect_over_cdp(f"http://127.0.0.1:{applier.CDP_PORT}")
        ctx = browser.contexts[0] if browser.contexts else browser.new_context()
        page = ctx.new_page()
        if followup:  # a reply from them ends follow-ups
            page.goto("https://old.reddit.com/message/inbox/", wait_until="domcontentloaded", timeout=60000)
            page.wait_for_timeout(2000)
            if "login" in page.url:
                return {"outcome": "handoff", "reason": "Reddit signed out in Hoot's Brave: sign in"}
            if user.lower() in {a.strip().lower() for a in page.locator("a.author").all_inner_texts()}:
                page.close()
                return {"outcome": "replied"}
        page.goto(url, wait_until="domcontentloaded", timeout=60000)
        page.wait_for_timeout(5000)
        if "login" in page.url:
            return {"outcome": "handoff", "reason": "Reddit signed out in Hoot's Brave: sign in, then approve again"}
        body = page.inner_text("body", timeout=5000)
        if re.search(r"unable to send a message", body, re.I):
            page.close()
            return {"outcome": "handoff", "reason": f"u/{user} doesn't accept messages; comment on the post instead"}
        if any(page.locator(sel).nth(i).is_visible() for sel in ("iframe[src*='captcha']", ".g-recaptcha")
               for i in range(page.locator(sel).count())):
            return {"outcome": "handoff", "reason": "Reddit asked for a CAPTCHA; the DM is filled in Hoot's Brave, press send"}
        button = page.get_by_role("button", name="Send", exact=True)
        if button.count() != 1 or not button.is_enabled() or text not in boxes(page):
            return {"outcome": "handoff", "reason": "Reddit's message form wasn't ready; it's open in Hoot's Brave"}
        if dry_run:
            page.close()
            return {"outcome": "dry_run", "user": user}
        button.click()
        page.wait_for_timeout(5000)
        if "/message/compose" in page.url and text in boxes(page):
            return {"outcome": "handoff", "reason": "Reddit didn't confirm the DM; check the tab in Hoot's Brave"}
        page.close()
    LOG.parent.mkdir(parents=True, exist_ok=True)
    LOG.write_text(json.dumps(log + [{"user": user, "at": datetime.now(timezone.utc).isoformat(), "url": job["url"], "followup": followup}],
                              indent=1), encoding="utf-8")
    return {"outcome": "followed_up" if followup else "submitted", "reason": f"{'follow-up' if followup else 'DM'} sent to u/{user}"}


if __name__ == "__main__":
    assert poster("Reddit /u/Some_Editor-1") == "Some_Editor-1" and poster("Reddit u/ab") == "" and poster("Pavago") == ""
    today = datetime.now(timezone.utc).isoformat()
    assert blocked("Bob", [{"user": "bob", "at": "2020-01-01"}]).startswith("already")
    assert blocked("new", [{"user": f"u{i}", "at": today} for i in range(DAILY_CAP)]).startswith("daily")
    assert blocked("new", []) == "" and blocked("bob", [{"user": "bob", "at": "2020-01-01"}], followup=True) == ""
    print("reddit_dm selftest ok")

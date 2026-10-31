"""Upwork invites and offers from the emails Upwork sends the owner (Gmail, same login as indeed_alerts). Upwork is
never scraped or signed in to: a bot on his account breaks Upwork's terms. Approving one hands it back to him to send
the proposal on Upwork himself.
"""

import html
import re

from . import indeed_alerts

WANTED = re.compile(r"invit|offer|wants to (hire|work)|interested in (hiring|working)", re.I)
LINK = re.compile(r"https://www\.upwork\.com/[^\s\"'<>)]+")


def text_of(body: str) -> str:
    body = re.sub(r"(?is)<(style|script).*?</\1>", " ", body.replace("=\r\n", "").replace("=\n", "").replace("=3D", "="))
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", body))).strip()


def offer(subject: str, body: str):
    """One invite or offer as a job offer dict, or None for Upwork's other mail (receipts, tips, messages)."""
    if not WANTED.search(subject):
        return None
    links = [u.replace("&amp;", "&") for u in LINK.findall(body.replace("=\r\n", "").replace("=\n", "").replace("=3D", "="))]
    url = next((u for u in links if re.search(r"invit|proposal|offer|/jobs/", u)), links[0] if links else "")
    if not url:
        return None
    title = re.sub(r"(?i)^.*?(invit\w*|offer)( to interview)?( for)?[:\s-]*", "", subject).strip(" :-\"") or subject
    return {"source": "Upwork invite", "url": url, "company": "Upwork client", "title": title[:160], "location": "Remote",
            "pay": "", "jd": "An Upwork client invited him to this job. Their email:\n" + text_of(body)[:5000], "invite": True}


def offers(log=print) -> list:
    out = [o for o in (offer(s, b) for s, b in indeed_alerts.mails("upwork.com", days=3)) if o]
    log(f"source Upwork invites: {len(out)}")
    return out


if __name__ == "__main__":
    o = offer("You've been invited to interview for: AI video editor for YouTube",
              '<p>Hi Jhon, love your reels.</p><a href="https://www.upwork.com/ab/proposals/interviews/123?x=1&amp;y=2">View</a>'
              '<a href="https://www.upwork.com/nx/settings">Settings</a>')
    assert o["title"] == "AI video editor for YouTube", o["title"]
    assert o["url"] == "https://www.upwork.com/ab/proposals/interviews/123?x=1&y=2" and "love your reels" in o["jd"]
    assert offer("Your weekly invoice", "https://www.upwork.com/x") is None
    print("upwork_invites selftest ok")

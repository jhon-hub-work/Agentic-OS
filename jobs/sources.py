# Adapted from Hoot (a job-application agent) for the Agentic OS job pipeline.
"""Card-only public job sources.

Read-only public feeds, no login, no account: Reddit r/forhire and r/VideoEditing (RSS), Remote OK (JSON API),
We Work Remotely (RSS), Himalayas (JSON API) and YT Jobs (RSS). Every offer they return is card_only: Hoot scores
it and shows the owner a card with the link, but never applies, fills or sends anything on these sites.
Each source is best effort: a source that fails is logged and skipped, the others still run.
"""

import html as htmllib
import json
import re
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

try:
    from . import lanes
except ImportError:  # run as a script (python jobs/sources.py)
    import lanes

UA = "windows:hoot-job-agent:1.0 (personal job search)"     # Reddit blocks generic agents
MAX_AGE_DAYS = 14
PER_SOURCE = 40

VIDEO_T = re.compile(r"\bvideo\b|\beditor\b|\bediting\b|\breels?\b|\bshorts\b|youtube|tiktok|capcut|premiere|after effects|motion graphics", re.I)
AI_T = re.compile(r"\b(ai|automation|n8n|zapier|make\.com|agents?|llm|chatbot|workflows?)\b", re.I)
HIRING = re.compile(r"\[\s*hiring\s*\]", re.I)
VE_HIRING = re.compile(r"\bhiring\b|looking for (?:an? )?(?:video )?editor|paid (?:gig|job|work)\b", re.I)


def _get(url: str, tries: int = 3) -> str:
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json, application/rss+xml, text/xml, */*"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            if e.code != 429 or i == tries - 1:     # Reddit rate-limits the second feed; wait it out
                raise
            time.sleep(15 * 2 ** i)
        except OSError:
            if i == tries - 1:
                raise
            time.sleep(4 * 2 ** i)
    return ""


def _text(s: str) -> str:
    s = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", " ", s or "")
    s = re.sub(r"(?i)<br\s*/?>|</p>|</li>|</h\d>|</div>", "\n", s)
    s = htmllib.unescape(re.sub(r"<[^>]+>", " ", s))
    return re.sub(r"[ \t]+", " ", re.sub(r"\n\s*\n+", "\n\n", s)).strip()


def _fresh(when) -> bool:
    if when is not None and when.tzinfo is None:  # "-0000" RSS dates parse naive; comparing them raised TypeError
        when = when.replace(tzinfo=timezone.utc)
    return when is None or when >= datetime.now(timezone.utc) - timedelta(days=MAX_AGE_DAYS)


def _relevant(title: str) -> bool:
    return bool(VIDEO_T.search(title or "") or AI_T.search(title or "") or lanes.DEV.search(title or "")
                or lanes.SOCIAL.search(title or ""))


def _offer(source, url, company, title, location, jd, pay=""):
    return {"url": url, "company": (company or "").strip()[:60], "title": (title or "").strip()[:140],
            "location": (location or "Remote").strip()[:80], "pay": pay, "jd": jd[:20000],
            "source": source, "card_only": True}


def reddit(sub: str) -> list:
    root = ET.fromstring(_get(f"https://www.reddit.com/r/{sub}/new/.rss?limit=100"))
    ns = {"a": "http://www.w3.org/2005/Atom"}
    out = []
    for e in root.findall("a:entry", ns):
        title = (e.findtext("a:title", "", ns) or "").strip()
        hiring = HIRING.search(title) or (sub.lower() == "videoediting" and VE_HIRING.search(title))
        if not hiring:
            continue
        clean = HIRING.sub("", title).strip(" -:|")
        if not _relevant(clean):
            continue
        link = e.find("a:link", ns)
        url = link.get("href") if link is not None else ""
        try:
            when = datetime.fromisoformat((e.findtext("a:updated", "", ns) or "").replace("Z", "+00:00"))
        except ValueError:
            when = None
        if not url or not _fresh(when):
            continue
        body = _text(e.findtext("a:content", "", ns) or "")
        body = re.sub(r"submitted by .*$", "", body, flags=re.S).strip()
        author = (e.findtext("a:author/a:name", "", ns) or "").strip()
        out.append(_offer(f"reddit r/{sub}", url, f"Reddit {author}", clean, "Remote", f"{clean}\n\n{body}"))
    return out[:PER_SOURCE]


def remoteok() -> list:
    data = json.loads(_get("https://remoteok.com/api"))
    out = []
    for d in data:
        if not isinstance(d, dict) or not d.get("position"):
            continue
        if not _relevant(d["position"]):
            continue
        try:
            when = datetime.fromisoformat(d.get("date", ""))
        except ValueError:
            when = None
        if not _fresh(when):
            continue
        lo, hi = int(d.get("salary_min") or 0), int(d.get("salary_max") or 0)
        pay = f"${lo:,} - ${hi:,} per year" if hi else ""
        jd = f"{d['position']}\n\nCompany: {d.get('company', '')}\nLocation: {d.get('location', '')}\n" + \
             (f"Salary: {pay}\n" if pay else "") + "\n" + _text(d.get("description", ""))
        out.append(_offer("Remote OK", d.get("url") or d.get("apply_url"), d.get("company"), d["position"],
                          d.get("location") or "Remote", jd, pay))
    return out[:PER_SOURCE]


def _rss_items(xml: str):
    root = ET.fromstring(xml)
    for it in root.iter("item"):
        when = None
        try:
            when = parsedate_to_datetime(it.findtext("pubDate") or "")
        except (TypeError, ValueError):
            pass
        yield it, when


def weworkremotely() -> list:
    out = []
    for it, when in _rss_items(_get("https://weworkremotely.com/remote-jobs.rss")):
        raw = (it.findtext("title") or "").strip()
        company, _, title = raw.partition(": ")
        title = title or raw
        if not _relevant(title) or not _fresh(when):
            continue
        region = (it.findtext("region") or "").strip()
        jd = f"{title}\n\nCompany: {company}\nRegion: {region}\nType: {it.findtext('type') or ''}\n\n{_text(it.findtext('description') or '')}"
        out.append(_offer("We Work Remotely", (it.findtext("link") or "").strip(), company, title, region or "Remote", jd))
    return out[:PER_SOURCE]


def himalayas() -> list:
    out = []
    for q in ("video editor", "automation"):
        try:
            data = json.loads(_get("https://himalayas.app/jobs/api/search?q=" + urllib.request.quote(q) + "&limit=50"))
        except (urllib.error.HTTPError, ValueError):
            data = json.loads(_get("https://himalayas.app/jobs/api?limit=100"))
        for d in data.get("jobs") or []:
            if not _relevant(d.get("title", "")):
                continue
            try:
                when = datetime.fromtimestamp(int(d.get("pubDate") or 0), timezone.utc)
            except (TypeError, ValueError):
                when = None
            if not _fresh(when):
                continue
            locs = d.get("locationRestrictions") or []
            loc = ", ".join(locs) if locs else "Worldwide"
            pay = ""
            if d.get("minSalary") or d.get("maxSalary"):
                pay = f"{d.get('currency') or 'USD'} {d.get('minSalary') or ''} - {d.get('maxSalary') or ''} {d.get('salaryPeriod') or ''}".strip()
            jd = f"{d['title']}\n\nCompany: {d.get('companyName', '')}\nLocation: {loc}\nType: {d.get('employmentType', '')}\n" + \
                 (f"Salary: {pay}\n" if pay else "") + "\n" + _text(d.get("description") or d.get("excerpt") or "")
            url = d.get("guid") or d.get("applicationLink")
            if url and url not in {o["url"] for o in out}:
                out.append(_offer("Himalayas", url, d.get("companyName"), d["title"], loc, jd, pay))
    return out[:PER_SOURCE]


def ytjobs() -> list:
    """YT Jobs' feed has titles and links only; Hoot reads the posting page itself when it scores one."""
    out = []
    for it, when in _rss_items(_get("https://app.ytjobs.co/feeds")):
        title = (it.findtext("title") or "").strip()
        if not _relevant(title) or not _fresh(when):
            continue
        out.append(_offer("YT Jobs", (it.findtext("link") or "").strip(), it.findtext("author") or "", title, "Remote", ""))
    return out[:PER_SOURCE]


SOURCES = {"reddit r/forhire": lambda: reddit("forhire"), "reddit r/VideoEditing": lambda: reddit("VideoEditing"),
           "Remote OK": remoteok, "We Work Remotely": weworkremotely, "Himalayas": himalayas, "YT Jobs": ytjobs}


def fetch_all(log=print) -> list:
    offers, seen = [], set()
    for name, fn in SOURCES.items():
        try:
            got = [o for o in fn() if o["url"] and o["url"] not in seen]
        except Exception as err:  # noqa: BLE001 - one source down must not stop the rest
            log(f"source {name} failed: {err!r}")
            continue
        seen.update(o["url"] for o in got)
        offers += got
        log(f"source {name}: {len(got)} postings")
        time.sleep(3)                                # Reddit answers 429 to back-to-back feeds
    return offers


def _selftest():
    assert _relevant("Short-form video editor for YouTube") and _relevant("n8n automation builder") and not _relevant("Accountant")
    assert HIRING.search("[Hiring] Video Editor") and not HIRING.search("[For Hire] Video Editor")
    o = _offer("x", "https://u", "Co", "Video Editor", "", "jd")
    assert o["card_only"] and o["location"] == "Remote"
    assert _fresh(parsedate_to_datetime(datetime.now(timezone.utc).strftime("%a, %d %b %Y %H:%M:%S -0000")))
    assert _text("<p>a&amp;b</p><br>c") == "a&b\n\nc" or "a&b" in _text("<p>a&amp;b</p>")
    print("sources selftest ok")


if __name__ == "__main__":
    import sys
    if "--live" in sys.argv:
        for o in fetch_all():
            print(o["source"], "|", o["company"], "|", o["title"], "|", o["location"])
    else:
        _selftest()

"""Job pipeline: find -> filter (Hoot's rules) -> score (OpenCode) -> draft (Claude) -> board for the owner.

Nothing here applies or sends anything. Drafts wait on the board until the owner approves them in
Slack (Jharvis) or the dashboard; outward actions always need the owner's approval.
"""

import hashlib
import json
import re
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import owner

from . import lanes, pay, sources

BASE = Path(__file__).resolve().parent.parent
BOARD = BASE / "data" / "jobs" / "board.json"
SEEN = BASE / "data" / "jobs" / "seen.json"
# The owner's CV and standing answers stay where they are (config/owner.json); read, never copied here.
CV = owner.path("cv_path") or Path("cv.md")
ANSWERS = owner.path("answers_path") or Path("answers.md")
MAX_SCORE = 30   # postings scored per run
MAX_DRAFT = 6    # applications drafted per run
BATCH = 8
# Decisions, apply results and a run's results all write board.json; without this lock one overwrote another.
BOARD_LOCK = threading.RLock()

# Names that must never reach an employer (his client). Kept in a gitignored file so they never go public.
_TERMS = Path(__file__).with_name("private_terms.txt")
PRIVATE = "|".join(rf"\b{t.strip()}\b" for t in _TERMS.read_text(encoding="utf-8").splitlines() if t.strip()) if _TERMS.exists() else ""

DRAFT_RULES = f"""Rules for every application (the owner's standing rules):
- Use only facts from the CV and answers below. Never invent experience, numbers, clients or tools.
- Never mention automation, bots, AI agents or any tool that applies to jobs for them.
- Never name private clients or their brands; call one "{owner.OWNER.get('private_client') or 'a client'}".
- Don't claim anything is attached. Link the portfolio instead.
- Where a detail is unknown, leave a blank like [ADD: hourly rate?] instead of guessing.
- If salary is asked: "{owner.OWNER.get('salary_line') or '[ADD: expected salary]'}".
- Australian/neutral English, warm and short: 120-180 words, no clichés, no "I am writing to apply".
"""


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def job_id(url: str) -> str:
    return hashlib.sha1(url.encode()).hexdigest()[:10]


def same_job(j: dict) -> str:
    """Boards list one opening under several links (Pavago x3 on 26 Oct): match on company + title."""
    return re.sub(r"\W+", " ", f"{j.get('company', '')}|{j.get('title', '')}".lower()).strip()


def load(path: Path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def save(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    for attempt in range(10):  # Windows refuses the replace while the dashboard has board.json open
        try:
            tmp.replace(path)
            break
        except PermissionError:
            if attempt == 9:
                raise
            time.sleep(0.05)


SEEN_DAYS = 90  # a posting not fetched again for this long may come back as new


def load_seen(path: Path) -> dict:
    """{job id: last fetched (iso) or "keep"}. The old file was a plain list of ids."""
    data = load(path, {})
    return dict.fromkeys(data, now()) if isinstance(data, list) else data


def save_seen(path: Path, seen: dict, board_jobs: list) -> None:
    """Board jobs are kept for good: one that falls off the 300-job board must never come back as new."""
    seen.update(dict.fromkeys((j["id"] for j in board_jobs), "keep"))
    cutoff = (datetime.now(timezone.utc) - timedelta(days=SEEN_DAYS)).isoformat()
    save(path, {k: v for k, v in seen.items() if v == "keep" or v >= cutoff})


def mark_seen(seen: dict, ids) -> None:
    stamp = now()
    for i in ids:
        if seen.get(i) != "keep":
            seen[i] = stamp


# Jhon, 2026-10-28: (hourly, monthly) floors in USD per lane; other lanes use config/owner.json below.
LANE_PAY = {"dev": (10, 1600), "social": (8, 0)}


def pay_ok(job: dict) -> bool:
    """Pay floors from config/owner.json: hourly (lower on OnlineJobs.ph), monthly, and per clip."""
    jd = job.get("jd") or ""
    lane = lanes.kind_of(job.get("title"))
    if lane in LANE_PAY:
        hourly, monthly = LANE_PAY[lane]
        h = pay.hourly_usd(job.get("pay") or "", jd, minimum=hourly)
        # "$1600 a month OR $10 an hour": a monthly figure is judged by the monthly floor only
        return (h["ok"] or (monthly and "(monthly" in h["basis"])) and pay.monthly_floor_ok(job.get("pay") or "", jd, minimum=monthly)
    piece = pay.per_piece_low(jd)
    floor = pay.MIN_HOURLY_USD if "onlinejobs.ph" in (job.get("url") or "") else pay.MIN_HOURLY_OTHER_USD  # $8 OLJ, $15 elsewhere
    return (pay.hourly_usd(job.get("pay") or "", jd, minimum=floor)["ok"]
            and pay.monthly_floor_ok(job.get("pay") or "", jd)
            and (piece is None or piece >= pay.MIN_PIECE_USD))


def first_json(text: str, opener: str):
    """The first JSON array/object in an agent's reply (they often wrap it in prose or fences)."""
    # Decode from each opener in turn: cutting at the last closer broke on prose like "scores [1-5]" after the array.
    dec, start = json.JSONDecoder(), text.find(opener)
    while start >= 0:
        try:
            val = dec.raw_decode(text, start)[0]
            if opener == "{" or all(isinstance(x, dict) for x in val):  # skip footnotes like "[1]"
                return val
        except ValueError:
            pass
        start = text.find(opener, start + 1)
    return None


def score_batch(jobs: list, run_agent) -> int:
    listing = "\n\n".join(
        f"#{i} | {j['title']} | {j['company']} | {j.get('pay') or 'pay not stated'}\n{(j.get('jd') or '')[:1200]}"
        for i, j in enumerate(jobs)
    )
    prompt = (
        f"Score each job posting for {owner.NAME}, {owner.OWNER.get('profile_summary') or 'a remote worker'}.\n"
        "Score 1-5 for fit and quality (5 = apply today). Set needs_you=true when applying needs something only "
        f"{owner.NAME} can do: a test task, a video/audio recording of themselves, a call, or an account login.\n"
        "Reply with ONLY a JSON array: [{\"i\": 0, \"score\": 4.0, \"reason\": \"...\", \"needs_you\": false, "
        "\"needs_why\": \"\"}]\n\n" + listing
    )
    return apply_scores(jobs, first_json(run_agent("opencode", prompt), "[") or [])


def apply_scores(jobs: list, result: list) -> int:
    """Put an agent's score rows on the jobs; how many jobs got a score."""
    for r in result:
        if isinstance(r, dict) and isinstance(r.get("i"), int) and 0 <= r["i"] < len(jobs):
            jobs[r["i"]].update(score=float(r.get("score") or 0), reason=str(r.get("reason") or "")[:300],
                                needs_you=bool(r.get("needs_you")), needs_why=str(r.get("needs_why") or "")[:200])
    return sum("score" in j for j in jobs)


def draft(job: dict, run_agent, cv: str, answers: str) -> str:
    prompt = (
        f"Write {owner.NAME}'s application message for this job. Reply with only the message text.\n\n{DRAFT_RULES}"
        + ("- This goes straight to an OnlineJobs.ph employer once approved: NO [ADD: ...] blanks or brackets. "
           "Leave out anything you don't know instead.\n" if "onlinejobs.ph" in job.get("url", "") else "")
        + ("- This is a Reddit DM to the poster, not a cover letter: 60-90 words (overrides the length rule above), "
           "casual and direct, no subject line or sign-off block, end with the portfolio link.\n"
           if (job.get("source") or "").startswith("reddit") else "") + "\n"
        f"# Job\n{job['title']} at {job['company']} ({job['url']})\nPay: {job.get('pay') or 'not stated'}\n"
        f"{(job.get('jd') or '')[:3000]}\n\n# CV\n{cv}\n\n# Standing answers\n{answers}"
    )
    return run_agent("claude", prompt).strip()


def leaks(text: str) -> str:
    """Why a draft must not go out, or ''. Same spirit as Hoot's leaks() guard."""
    if re.search(r"\b(auto[- ]?appl\w*|bot|automated application|hoot|agentic os)\b", text, re.I):
        return "mentions automation"
    if PRIVATE and re.search(PRIVATE, text, re.I):
        return "names the clinic client"
    if re.search(r"\battached\b", text, re.I):
        return "claims an attachment"
    if text.startswith(("⏱", "⚠")) or len(text) < 60:
        return "draft failed"
    return ""


OWNER_ONLY = re.compile(
    r"\b(voice (?:recording|memo|note|sample|clip)|audio (?:recording|sample|clip)|record(?:ing)? (?:yourself|your voice"
    r"|a (?:short )?(?:video|loom))|video (?:intro\w*|of yourself|recording of you)|loom|vocaroo|selfie|test task"
    r"|trial task|paid trial|skills? test|sample (?:edit|task)|interview call|zoom call)\b", re.I)


def needs_owner(job: dict) -> str:
    """Why an application can't go out on CV + answers + profile alone, or ''. The owner does these himself."""
    m = OWNER_ONLY.search(f"{job.get('jd') or ''}\n{job.get('needs_why') or ''}")
    if m:
        return f"asks for: {m.group(0)}"
    if re.search(r"\[[A-Z]+[:\]]", job.get("draft") or ""):  # [ADD: ...]-style placeholders
        return "draft has blanks only you can fill"
    return ""


def hoot_offers(log=print) -> list:
    """OnlineJobs.ph + company boards via Hoot's scanner (system Python). These can be applied to."""
    import os
    import subprocess
    py = os.environ.get("HOOT_PYTHON", owner.OWNER.get("system_python") or "python")
    try:
        r = subprocess.run([py, str(Path(__file__).with_name("hoot_scan.py")), "25"], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", stdin=subprocess.DEVNULL, timeout=1800)
        got = json.loads((r.stdout or "[]").strip().splitlines()[-1])
        log(f"source Hoot scan (OnlineJobs + company boards): {len(got)} postings")
        return got
    except Exception as e:  # noqa: BLE001 - the public boards still run
        log(f"source Hoot scan failed: {e!r}")
        return []


def run(run_agent, log=print, after=None) -> dict:
    """One full pass. run_agent(agent, prompt) -> text is the OS's execute_agent. after() runs once the drafts are on
    the board (auto-apply) and returns how many went out; "finished" is stamped after it, so reports include the sends."""
    board = load(BOARD, {"jobs": [], "runs": []})
    seen = load_seen(SEEN)
    stats = {"started": now(), "fetched": 0, "new": 0, "kept": 0, "scored": 0, "drafted": 0, "needs_you": 0, "errors": []}

    note = lambda m: log(m) or (stats["errors"].append(m) if "failed" in m else None)
    offers = hoot_offers(log=note) + sources.fetch_all(log=note) + indeed_alert_offers(seen, log=note)  # applyable ones first
    stats["fetched"] = len(offers)
    known = {same_job(j) for j in board["jobs"]}
    fresh = []
    for o in offers:
        if job_id(o["url"]) not in seen and same_job(o) not in known:
            known.add(same_job(o))
            fresh.append(o)
    stats["new"] = len(fresh)

    # Only offers that got an answer (rejected here, scored, a duplicate, or seen before: re-stamped so a posting
    # still listed doesn't age out) are marked seen; the rest
    # (past MAX_SCORE, or in a batch whose reply didn't parse) come back next run.
    kept, done = [], []
    for o in fresh:
        why = lanes.gate(o, o.get("jd") or "") or ("" if pay_ok(o) else "pays under the pay floor")
        (done if why else kept).append(o)
    stats["kept"] = len(kept)

    to_score = kept[:MAX_SCORE]
    for i in range(0, len(to_score), BATCH):
        try:
            if not score_batch(to_score[i:i + BATCH], run_agent):
                note(f"scoring failed: batch {i // BATCH + 1} got no scores back")
        except Exception as e:  # noqa: BLE001 - one bad batch must not stop the run
            stats["errors"].append(f"scoring failed: {e}")
    scored = [j for j in to_score if "score" in j]
    stats["scored"] = len(scored)
    fresh_ids = {id(o) for o in fresh}
    mark_seen(seen, [job_id(o["url"]) for o in done + scored + [o for o in offers if id(o) not in fresh_ids]])

    cv = CV.read_text(encoding="utf-8") if CV.exists() else ""
    answers = ANSWERS.read_text(encoding="utf-8") if ANSWERS.exists() else ""
    drafted_queue, new_items = {}, []
    for j in sorted((j for j in scored if j["score"] >= lanes.bar(j)), key=lambda j: j["score"]):
        item = {k: j.get(k) for k in ("source", "url", "company", "location", "pay", "score", "reason", "needs_you", "needs_why")}
        item.update(id=job_id(j["url"]), title=re.sub(r"\s*<.*$", "", j.get("title") or "").strip(),  # scraped titles can carry HTML
                    lane=lanes.kind_of(j["title"]), found=now(), draft="", jd=(j.get("jd") or "")[:3000],
                    status="needs_you" if j.get("needs_you") else "queued")
        stats["needs_you"] += item["status"] == "needs_you"
        new_items.insert(0, item)
    # Draft the best MAX_DRAFT of this run's and earlier queued matches, ones the OS can apply to first.
    applyable = lambda j: "onlinejobs.ph" in j["url"] or any(h in j["url"] for h in ("greenhouse.io", "lever.co", "ashbyhq.com"))
    queue = [j for j in board["jobs"] if j["status"] == "queued"] + [j for j in new_items if j["status"] == "queued"]
    for item in sorted(queue, key=lambda j: (applyable(j), j.get("score") or 0), reverse=True)[:MAX_DRAFT]:
        try:
            text = draft(item, run_agent, cv, answers)
        except RuntimeError as e:  # the OS's Claude guard: the rest stay queued for the next run
            stats["errors"].append(f"drafting: {e}")
            break
        problem = leaks(text)
        result = dict(draft=text, status="draft_blocked" if problem else "drafted", problem=problem)
        if item in new_items:
            item.update(result)
        else:
            drafted_queue[item["id"]] = result
        stats["drafted"] += not problem

    with BOARD_LOCK:  # merge into the board as it is NOW: the owner may have approved things during the run
        board = load(BOARD, {"jobs": [], "runs": []})
        for j in board["jobs"]:
            if j["id"] in drafted_queue and j["status"] == "queued":
                j.update(drafted_queue[j["id"]])
        all_jobs = new_items + board["jobs"]
        board["jobs"] = all_jobs[:300]
        board["runs"] = ([stats] + board["runs"])[:50]
        save(BOARD, board)
    save_seen(SEEN, seen, all_jobs)
    stats["auto_sent"] = after() if after else 0
    stats["finished"] = now()
    with BOARD_LOCK:
        board = load(BOARD, {"jobs": [], "runs": []})
        board["runs"][0].update(auto_sent=stats["auto_sent"], finished=stats["finished"])
        save(BOARD, board)
    return stats


def read_indeed(link: str, source: str = "Indeed") -> dict:
    """One Indeed job read in Hoot's Brave (system Python has Playwright), as an offer. Raises ValueError."""
    import os
    import subprocess
    py = os.environ.get("HOOT_PYTHON", owner.OWNER.get("system_python") or "python")
    r = subprocess.run([py, str(Path(__file__).with_name("indeed_read.py")), link], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", stdin=subprocess.DEVNULL, timeout=300)
    got = json.loads((r.stdout or '{"error": "reader crashed"}').strip().splitlines()[-1])
    if got.get("error"):
        raise ValueError(got["error"])
    return {"source": source, "url": got["url"], "company": "Indeed", "title": got["title"], "location": "",
            "pay": "", "jd": got["text"]}


INDEED_MAX = 10  # alert jobs read per run (one Brave page each)


def indeed_alert_offers(seen: set, log=print) -> list:
    """Jobs from Indeed's job-alert emails to the owner. Only links Indeed mailed him: Indeed is never searched."""
    from . import indeed_alerts
    try:
        urls = [u for u in indeed_alerts.links() if job_id(u) not in seen][:INDEED_MAX]
    except Exception as e:  # noqa: BLE001 - the other boards still run
        log(f"source Indeed alerts failed: {e!r}")
        return []
    out = []
    for u in urls:
        try:
            out.append(read_indeed(u, "Indeed alert"))
        except ValueError as e:
            log(f"Indeed alert job skipped ({u}): {e}")
    log(f"source Indeed alerts: {len(out)} postings")
    return out


def add_link(link: str, run_agent) -> dict:
    """One Indeed job the owner pasted: read it in Hoot's Brave, score, draft, and put it on the board whatever the
    score. Approving it hands it back to him (apply_runner can't fill Indeed), so he finishes the apply there."""
    j = read_indeed(link)
    with BOARD_LOCK:
        if any(x["id"] == job_id(j["url"]) for x in load(BOARD, {"jobs": []})["jobs"]):
            raise ValueError("that job is already on the board")
    score_batch([j], run_agent)
    text = draft(j, run_agent, CV.read_text(encoding="utf-8") if CV.exists() else "",
                 ANSWERS.read_text(encoding="utf-8") if ANSWERS.exists() else "")
    problem = leaks(text)
    item = {k: j.get(k) for k in ("source", "url", "company", "location", "pay", "score", "reason", "needs_you", "needs_why")}
    item.update(id=job_id(j["url"]), title=j["title"], lane=lanes.kind_of(j["title"]), found=now(), jd=j["jd"][:3000],
                draft=text, problem=problem, status="draft_blocked" if problem else "needs_you" if j.get("needs_you") else "drafted")
    with BOARD_LOCK:
        board = load(BOARD, {"jobs": [], "runs": []})
        board["jobs"] = ([item] + board["jobs"])[:300]
        save(BOARD, board)
    return item


FOLLOW_AFTER_H = 48


def followups_due() -> list:
    """Applied jobs with no reply FOLLOW_AFTER_H hours on and no follow-up yet (one follow-up per job)."""
    cutoff = (datetime.now(timezone.utc) - timedelta(hours=FOLLOW_AFTER_H)).isoformat()
    return [j for j in load(BOARD, {"jobs": []})["jobs"] if j["status"] == "applied" and (j.get("applied_at") or "z") < cutoff
            and not j.get("followed_up_at") and not j.get("follow_up")]


def draft_followup(job: dict, run_agent) -> str:
    prompt = (f"Write {owner.NAME}'s short follow-up to an application sent {FOLLOW_AFTER_H}+ hours ago with no reply. "
              "Reply with only the message text.\n\n" + DRAFT_RULES +
              "- 30-60 words (overrides the length rule above): friendly nudge, still keen, one line on fit, no guilt.\n\n"
              f"# Job\n{job['title']} at {job['company']}\n\n# The first message\n{job.get('draft') or '(not saved)'}")
    return run_agent("claude", prompt).strip()


def platform(url: str) -> str:
    for name, host in (("OnlineJobs", "onlinejobs.ph"), ("Reddit", "reddit.com"), ("Indeed", "indeed.com"), ("X", "x.com"),
                       ("Instagram", "instagram.com"), ("Facebook", "facebook.com"), ("LinkedIn", "linkedin.com")):
        if host in (url or ""):
            return name
    return "Other boards"


EXPORT_COLS = ("platform", "title", "company", "status", "score", "pay", "found", "applied_at", "followed_up_at",
               "replied_at", "apply_result", "url")


def export_rows(path: Path = BOARD) -> list:
    return [{c: platform(j.get("url")) if c == "platform" else j.get(c, "") for c in EXPORT_COLS}
            for j in load(path, {"jobs": []})["jobs"]]


APPROVABLE = ("drafted", "needs_you", "handoff")  # approving twice, or while applying, would send twice


class StateError(Exception):
    pass


def decide(jid: str, decision: str, path: Path = BOARD) -> dict:
    """approve / skip / applied. Approving starts the apply step (server.apply_approved)."""
    if decision in ("replied", "followed_up"):  # tracking marks from the owner; status stays readable in exports
        with BOARD_LOCK:
            board = load(path, {"jobs": [], "runs": []})
            for j in board["jobs"]:
                if j["id"] == jid:
                    j.update({"status": "replied", "replied_at": now()} if decision == "replied"
                             else {"followed_up_at": now(), "follow_up": "done"})
                    save(path, board)
                    return j
        raise KeyError(jid)
    if decision not in ("approve", "skip", "applied"):
        raise ValueError("decision must be approve, skip or applied")
    with BOARD_LOCK:
        board = load(path, {"jobs": [], "runs": []})
        for j in board["jobs"]:
            if j["id"] == jid:
                if decision == "approve" and j["status"] not in APPROVABLE:
                    raise StateError(f"can't approve a job that is {j['status']}")
                j["status"] = {"approve": "approved", "skip": "skipped", "applied": "applied"}[decision]
                j["decided"] = now()
                save(path, board)
                return j
    raise KeyError(jid)


def set_fields(jid: str, path: Path = BOARD, **fields) -> None:
    with BOARD_LOCK:
        board = load(path, {"jobs": [], "runs": []})
        for j in board["jobs"]:
            if j["id"] == jid:
                j.update(fields)
        save(path, board)


def _selftest():
    assert first_json('sure:\n```json\n[{"i":0,"score":4}]\n```', "[") == [{"i": 0, "score": 4}]
    assert first_json('see [1]: [{"i":0,"score":4}]\nscores are [1-5]', "[") == [{"i": 0, "score": 4}]
    assert first_json("no json here [1-5]", "[") is None
    assert leaks("Hi, I built this with my auto-apply bot " + "x" * 80) == "mentions automation"
    assert leaks("I edit short videos for a client, about twenty reels a week. " * 2) == ""
    if PRIVATE:
        first = _TERMS.read_text(encoding="utf-8").split()[0]
        assert leaks(f"{first} is my client " + "x" * 80) == "names the clinic client"
    assert not pay_ok({"pay": "$5/hr", "jd": ""}) and pay_ok({"pay": "", "jd": ""})
    assert pay_ok({"pay": "$10/hr", "jd": "", "url": "https://www.onlinejobs.ph/jobseekers/job/x"}) and not pay_ok({"pay": "$10/hr", "jd": ""})
    fs, sm = {"title": "Full Stack Developer"}, {"title": "Social Media Manager"}
    head = lambda w, kind="Full Time": f"Type Of Work: {kind}\nWage / Salary: {w}\n"
    assert pay_ok({**fs, "jd": head("$1600 / month")}) and not pay_ok({**fs, "jd": head("$1200 / month")})
    assert pay_ok({**fs, "pay": "$12/hr", "jd": ""}) and not pay_ok({**fs, "pay": "$9/hr", "jd": ""})
    assert pay_ok({**sm, "pay": "$8/hr", "jd": ""}) and not pay_ok({**sm, "pay": "$6/hr", "jd": ""})
    assert not pay_ok({**sm, "jd": head("$1000 / month")}) and pay_ok({**sm, "jd": head("$1500 / month")})
    assert same_job({"company": "Pavago", "title": "Video Editor"}) == same_job({"company": "pavago", "title": "Video  Editor!"})
    print("pipeline selftest ok")


if __name__ == "__main__":
    _selftest()

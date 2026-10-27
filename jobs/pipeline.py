"""Job pipeline: find -> filter (Hoot's rules) -> score (OpenCode) -> draft (Claude) -> board for the owner.

Nothing here applies or sends anything. Drafts wait on the board until the owner approves them in
Slack (Jharvis) or the dashboard; outward actions always need the owner's approval.
"""

import hashlib
import json
import re
import threading
from datetime import datetime, timezone
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
    tmp.replace(path)


def pay_ok(job: dict) -> bool:
    """Pay floors from config/owner.json: hourly (lower on OnlineJobs.ph), monthly, and per clip."""
    jd = job.get("jd") or ""
    piece = pay.per_piece_low(jd)
    floor = pay.MIN_HOURLY_USD if "onlinejobs.ph" in (job.get("url") or "") else pay.MIN_HOURLY_OTHER_USD  # $8 OLJ, $15 elsewhere
    return (pay.hourly_usd(job.get("pay") or "", jd, minimum=floor)["ok"]
            and pay.monthly_floor_ok(job.get("pay") or "", jd)
            and (piece is None or piece >= pay.MIN_PIECE_USD))


def first_json(text: str, opener: str):
    """The first JSON array/object in an agent's reply (they often wrap it in prose or fences)."""
    start = text.find(opener)
    if start < 0:
        return None
    closer = "]" if opener == "[" else "}"
    end = text.rfind(closer)
    try:
        return json.loads(text[start:end + 1])
    except ValueError:
        return None


def score_batch(jobs: list, run_agent) -> None:
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
    result = first_json(run_agent("opencode", prompt), "[") or []
    for r in result:
        if isinstance(r, dict) and isinstance(r.get("i"), int) and 0 <= r["i"] < len(jobs):
            jobs[r["i"]].update(score=float(r.get("score") or 0), reason=str(r.get("reason") or "")[:300],
                                needs_you=bool(r.get("needs_you")), needs_why=str(r.get("needs_why") or "")[:200])


def draft(job: dict, run_agent, cv: str, answers: str) -> str:
    prompt = (
        f"Write {owner.NAME}'s application message for this job. Reply with only the message text.\n\n{DRAFT_RULES}"
        + ("- This goes straight to an OnlineJobs.ph employer once approved: NO [ADD: ...] blanks or brackets. "
           "Leave out anything you don't know instead.\n" if "onlinejobs.ph" in job.get("url", "") else "") + "\n"
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
    seen = set(load(SEEN, []))
    stats = {"started": now(), "fetched": 0, "new": 0, "kept": 0, "scored": 0, "drafted": 0, "needs_you": 0, "errors": []}

    note = lambda m: log(m) or (stats["errors"].append(m) if "failed" in m else None)
    offers = hoot_offers(log=note) + sources.fetch_all(log=note)  # applyable ones first
    stats["fetched"] = len(offers)
    known = {same_job(j) for j in board["jobs"]}
    fresh = []
    for o in offers:
        if job_id(o["url"]) not in seen and same_job(o) not in known:
            known.add(same_job(o))
            fresh.append(o)
    stats["new"] = len(fresh)
    seen.update(job_id(o["url"]) for o in offers)

    kept = []
    for o in fresh:
        why = lanes.gate(o, o.get("jd") or "") or ("" if pay_ok(o) else "pays under the pay floor")
        if not why:
            kept.append(o)
    stats["kept"] = len(kept)

    to_score = kept[:MAX_SCORE]
    for i in range(0, len(to_score), BATCH):
        try:
            score_batch(to_score[i:i + BATCH], run_agent)
        except Exception as e:  # noqa: BLE001 - one bad batch must not stop the run
            stats["errors"].append(f"scoring failed: {e}")
    scored = [j for j in to_score if "score" in j]
    stats["scored"] = len(scored)

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
        text = draft(item, run_agent, cv, answers)
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
        board["jobs"] = (new_items + board["jobs"])[:300]
        board["runs"] = ([stats] + board["runs"])[:50]
        save(BOARD, board)
    save(SEEN, sorted(seen)[-5000:])
    stats["auto_sent"] = after() if after else 0
    stats["finished"] = now()
    with BOARD_LOCK:
        board = load(BOARD, {"jobs": [], "runs": []})
        board["runs"][0].update(auto_sent=stats["auto_sent"], finished=stats["finished"])
        save(BOARD, board)
    return stats


def decide(jid: str, decision: str) -> dict:
    """approve / skip / applied. Approving starts the apply step (server.apply_approved)."""
    if decision not in ("approve", "skip", "applied"):
        raise ValueError("decision must be approve, skip or applied")
    with BOARD_LOCK:
        board = load(BOARD, {"jobs": [], "runs": []})
        for j in board["jobs"]:
            if j["id"] == jid:
                j["status"] = {"approve": "approved", "skip": "skipped", "applied": "applied"}[decision]
                j["decided"] = now()
                save(BOARD, board)
                return j
    raise KeyError(jid)


def set_fields(jid: str, **fields) -> None:
    with BOARD_LOCK:
        board = load(BOARD, {"jobs": [], "runs": []})
        for j in board["jobs"]:
            if j["id"] == jid:
                j.update(fields)
        save(BOARD, board)


def _selftest():
    assert first_json('sure:\n```json\n[{"i":0,"score":4}]\n```', "[") == [{"i": 0, "score": 4}]
    assert leaks("Hi, I built this with my auto-apply bot " + "x" * 80) == "mentions automation"
    assert leaks("I edit short videos for a client, about twenty reels a week. " * 2) == ""
    if PRIVATE:
        first = _TERMS.read_text(encoding="utf-8").split()[0]
        assert leaks(f"{first} is my client " + "x" * 80) == "names the clinic client"
    assert not pay_ok({"pay": "$5/hr", "jd": ""}) and pay_ok({"pay": "", "jd": ""})
    assert pay_ok({"pay": "$10/hr", "jd": "", "url": "https://www.onlinejobs.ph/jobseekers/job/x"}) and not pay_ok({"pay": "$10/hr", "jd": ""})
    assert same_job({"company": "Pavago", "title": "Video Editor"}) == same_job({"company": "pavago", "title": "Video  Editor!"})
    print("pipeline selftest ok")


if __name__ == "__main__":
    _selftest()

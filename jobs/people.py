"""Other people the OS job-hunts for, each with their own board. Who they are lives in config/owner.json "people"
(gitignored): {"id": {"name", "cv_path", "profile_summary", "min_hourly_usd", "prefer", "brave_profile", "cdp_port",
"auto_apply"}}. Bookkeeping on OnlineJobs.ph for now (Hoot's people/bookkeeping search, via person_scan.py).

Same rules as the owner's pipeline: no time tracker, pay floor, facts only from their CV, never mention automation,
nothing only they can do (test task, recording, call) goes out by itself. Sends go from their own Brave
(person_send.py), where they signed in to OnlineJobs themselves.
"""

import json
import os
import re
import subprocess
from pathlib import Path

import owner

from . import lanes, pay, pipeline

PEOPLE: dict = owner.OWNER.get("people") or {}
MIN_SCORE = 3.0
MAX_SCORE = 30
MAX_DRAFT = 6
# "Preferably Australian clients": these rank higher, the rest are still kept.
AU = re.compile(r"\baustralia\w*|\baussie\b|\b(?:sydney|melbourne|brisbane|perth|adelaide)\b", re.I)
AU_CODES = re.compile(r"\b(?:BAS|ATO|AEST|AEDT|AUD)\b|A\$")
AU_BONUS = 0.5


def board(pid: str) -> Path:
    return pipeline.BASE / "data" / "jobs" / "people" / pid / "board.json"


def _seen(pid: str) -> Path:
    return board(pid).with_name("seen.json")


def australian(job: dict) -> bool:
    text = f"{job.get('title')}\n{job.get('company')}\n{job.get('jd')}"
    return bool(AU.search(text) or AU_CODES.search(text))


def rules(p: dict) -> str:
    return f"""Rules for every application ({p['name']}'s standing rules):
- Use only facts from the CV below. Never invent experience, numbers, clients, certificates or tools.
- Never mention automation, bots, AI agents or any tool that applies to jobs for them.
- Don't claim anything is attached.
- This goes straight to an OnlineJobs.ph employer: NO [ADD: ...] blanks or brackets. Leave out anything you don't know.
- If salary is asked, leave it out.
- Australian/neutral English, warm and short: 120-180 words, no clichés, no "I am writing to apply".
"""


def scan(p: dict, seen: set, log=print) -> list:
    """OnlineJobs bookkeeping postings with jd and pay, read with system Python (Hoot's search lives there)."""
    py = os.environ.get("HOOT_PYTHON", owner.OWNER.get("system_python") or "python")
    seen_file = pipeline.BASE / "data" / "jobs" / "people" / "_seen_arg.json"
    pipeline.save(seen_file, sorted(seen))
    try:
        r = subprocess.run([py, str(Path(__file__).with_name("person_scan.py")), str(p.get("min_hourly_usd", 8)), str(seen_file), "25"],
                           capture_output=True, text=True, encoding="utf-8", errors="replace", stdin=subprocess.DEVNULL, timeout=2400)
        got = json.loads((r.stdout or "[]").strip().splitlines()[-1])
        log(f"source OnlineJobs bookkeeping: {len(got)} postings")
        return got
    except Exception as e:  # noqa: BLE001 - a failed search is a run with nothing new
        log(f"source OnlineJobs bookkeeping failed: {e!r}")
        return []


def score_batch(p: dict, jobs: list, run_agent) -> None:
    listing = "\n\n".join(f"#{i} | {j['title']} | {j['company']} | {j.get('pay') or 'pay not stated'}\n{(j.get('jd') or '')[:1200]}"
                          for i, j in enumerate(jobs))
    prompt = (f"Score each job posting for {p['name']}, {p.get('profile_summary') or 'a bookkeeper'}.\n"
              "Score 1-5 for fit and quality (5 = apply today). Set needs_you=true when applying needs something only "
              f"{p['name']} can do: a test task, a video/audio recording of themselves, a call, or an account login.\n"
              "Reply with ONLY a JSON array: [{\"i\": 0, \"score\": 4.0, \"reason\": \"...\", \"needs_you\": false, "
              "\"needs_why\": \"\"}]\n\n" + listing)
    for r in pipeline.first_json(run_agent("opencode", prompt), "[") or []:
        if isinstance(r, dict) and isinstance(r.get("i"), int) and 0 <= r["i"] < len(jobs):
            jobs[r["i"]].update(score=float(r.get("score") or 0), reason=str(r.get("reason") or "")[:300],
                                needs_you=bool(r.get("needs_you")), needs_why=str(r.get("needs_why") or "")[:200])


def draft(p: dict, job: dict, run_agent, cv: str) -> str:
    prompt = (f"Write {p['name']}'s application message for this job. Reply with only the message text.\n\n{rules(p)}\n"
              f"# Job\n{job['title']} at {job['company']} ({job['url']})\nPay: {job.get('pay') or 'not stated'}\n"
              f"{(job.get('jd') or '')[:3000]}\n\n# CV\n{cv}")
    return run_agent("claude", prompt).strip()


def run(pid: str, run_agent, log=print) -> dict:
    p, path = PEOPLE[pid], board(pid)
    seen = set(pipeline.load(_seen(pid), []))
    stats = {"started": pipeline.now(), "fetched": 0, "new": 0, "kept": 0, "scored": 0, "drafted": 0, "needs_you": 0, "errors": []}
    note = lambda m: log(m) or (stats["errors"].append(m) if "failed" in m else None)
    offers = scan(p, seen, note)
    stats["fetched"] = len(offers)
    fresh = [o for o in offers if pipeline.job_id(o["url"]) not in seen]
    stats["new"] = len(fresh)
    seen.update(pipeline.job_id(o["url"]) for o in offers)

    floor = float(p.get("min_hourly_usd", 8))
    kept = [o for o in fresh if not lanes.tracker(o.get("jd") or "")
            and pay.hourly_usd(o.get("pay") or "", o.get("jd") or "", minimum=floor)["ok"]]
    stats["kept"] = len(kept)
    to_score = kept[:MAX_SCORE]
    for i in range(0, len(to_score), pipeline.BATCH):
        try:
            score_batch(p, to_score[i:i + pipeline.BATCH], run_agent)
        except Exception as e:  # noqa: BLE001 - one bad batch must not stop the run
            stats["errors"].append(f"scoring failed: {e}")
    scored = [j for j in to_score if "score" in j]
    stats["scored"] = len(scored)
    for j in scored:
        if australian(j):
            j["score"] = min(5.0, j["score"] + AU_BONUS)
            j["reason"] = (j.get("reason") or "") + " Australian client (ranked higher)."

    new_items = []
    for j in sorted((j for j in scored if j["score"] >= MIN_SCORE), key=lambda j: j["score"], reverse=True):
        item = {k: j.get(k) for k in ("source", "url", "company", "location", "pay", "score", "reason", "needs_you", "needs_why")}
        item.update(id=pipeline.job_id(j["url"]), title=j.get("title") or "", lane="bookkeeping", found=pipeline.now(), draft="",
                    jd=(j.get("jd") or "")[:3000], person=pid, status="needs_you" if j.get("needs_you") else "queued")
        stats["needs_you"] += item["status"] == "needs_you"
        new_items.append(item)

    cv_path = Path(p.get("cv_path") or "")
    cv = cv_path.read_text(encoding="utf-8") if cv_path.is_file() else ""
    with pipeline.BOARD_LOCK:
        b = pipeline.load(path, {"jobs": [], "runs": []})
    queue = [j for j in new_items + b["jobs"] if j["status"] == "queued"]
    drafted = {}
    for item in sorted(queue, key=lambda j: j.get("score") or 0, reverse=True)[:MAX_DRAFT]:
        text = draft(p, item, run_agent, cv) if cv else ""
        problem = pipeline.leaks(text) if cv else "no CV found"
        drafted[item["id"]] = dict(draft=text, status="draft_blocked" if problem else "drafted", problem=problem)
        stats["drafted"] += not problem

    with pipeline.BOARD_LOCK:  # merge into the board as it is NOW: someone may have approved things during the run
        b = pipeline.load(path, {"jobs": [], "runs": []})
        for j in new_items + b["jobs"]:
            if j["id"] in drafted and j["status"] == "queued":
                j.update(drafted[j["id"]])
        b["jobs"] = (new_items + b["jobs"])[:300]
        stats["finished"] = pipeline.now()
        b["runs"] = ([stats] + b["runs"])[:50]
        pipeline.save(path, b)
    pipeline.save(_seen(pid), sorted(seen)[-5000:])
    return stats


def _selftest():
    assert australian({"title": "Bookkeeper for Sydney firm"}) and australian({"jd": "Lodge BAS each quarter"})
    assert australian({"jd": "Pay: A$12/hr"}) and not australian({"jd": "basic bookkeeping, US client"})
    assert not pay.hourly_usd("", "Type Of Work: Full Time\nWage / Salary: $600 / month\n", minimum=8)["ok"]
    assert pay.hourly_usd("", "Type Of Work: Full Time\nWage / Salary: $1500 / month\n", minimum=8)["ok"]
    assert pay.hourly_usd("", "Type Of Work: Part Time\nWage / Salary: $10/hr\n", minimum=8)["ok"]
    print("people selftest ok")


if __name__ == "__main__":
    _selftest()

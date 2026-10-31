"""Designated agents (Jhon, 30 Oct): an agent with its own task, skills, model, and an optional advisor and helper.

One run: advisor says what to make -> helper checks facts -> agent does the work. Every role is a read-only worker
(the OS's rules); anything outward (a post) is done by the OS from the agent's output, after Jhon approves. The first
`approve_first` runs wait for his Approve; after that the crew runs on its own.
"""

import json
import os
import re
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path

BASE = Path(__file__).resolve().parent
STATE = BASE / "data" / "crews.json"
RUNS_DIR = BASE / "data" / "crews"
LOCK = threading.RLock()
ROLES = ("advisor", "helper", "agent")
SKILL_DIRS = (BASE / "skills", Path.home() / ".claude" / "skills")


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def load() -> list:
    return json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else []


def save(items: list):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(items, indent=2), encoding="utf-8")
    os.replace(tmp, STATE)


def get(cid: str) -> dict:
    c = next((x for x in load() if x["id"] == cid), None)
    if not c:
        raise KeyError(cid)
    return c


def update(cid: str, fn) -> dict:
    with LOCK:
        items = load()
        c = next((x for x in items if x["id"] == cid), None)
        if not c:
            raise KeyError(cid)
        fn(c)
        save(items)
        return c


def create(data: dict) -> dict:
    c = {"id": str(uuid.uuid4())[:8], "created": now(), "approved": 0, "runs": [], **data}
    with LOCK:
        save(load() + [c])
    return c


def update_run(cid: str, rid: str, **fields) -> dict:
    def fn(c):
        next(r for r in c["runs"] if r["id"] == rid).update(fields)
    return update(cid, fn)


def skill_text(names: list) -> str:
    out = []
    for n in names:
        if not re.fullmatch(r"[A-Za-z0-9_:-]+", n):
            continue
        f = next((d / n.split(":")[-1] / "SKILL.md" for d in SKILL_DIRS if (d / n.split(":")[-1] / "SKILL.md").exists()), None)
        if f:
            text = f.read_text(encoding="utf-8", errors="replace")
            # the ig-* skills carry Dr L's brand rules (#DrYoungEdit, her site); they must not reach other accounts
            text = re.sub(r"^## DYE overrides.*?(?=^## |\Z)", "", text, flags=re.S | re.M)
            out.append(f"## Skill: {n}\n{text[:6000]}")
    return "\n\n".join(out)


def run(cid: str, execute, action=None, after=None, suffix: str = "", context=None) -> str:
    """execute(agent, prompt, model, effort) -> text. action(crew, run, output) -> extra fields once the agent is done
    (e.g. the rendered slides). after(cid, rid): called once it waits for approval (the crew may approve itself).
    Returns the run id; the work happens in a thread."""
    c = get(cid)
    brief = c.get("next_brief")  # Jhon 31 Oct: a one-off topic (+ image) for the next run only
    r = {"id": str(uuid.uuid4())[:8], "at": now(), "status": "running", "step": "advisor" if c.get("advisor") else "agent",
         **({"brief": brief} if brief else {})}
    update(cid, lambda x: x["runs"].append(r) or x.__setitem__("runs", x["runs"][-30:]) or x.pop("next_brief", None))

    def work():
        try:
            # 30 Oct: the OS's "you plan, you change nothing" rule made the agent write a plan instead of the post
            base = (f"You are part of {c['name']}, the owner's designated crew. Your reply itself is the deliverable: "
                    "writing it changes nothing, and the OS shows it to the owner to approve before anything is published. "
                    "Don't plan or hand work to others; do your part now. Search the web yourself when it needs current facts.\n\n")
            base += f"Your crew's job: {c['task']}\n\n" + (skill_text(c.get("skills", [])) + "\n\n" if c.get("skills") else "")
            if brief:
                base += f"This run's topic is set by the owner; use it instead of picking one:\n{brief['topic']}\n\n"
            base += (context(c) + "\n\n") if context else ""  # e.g. popular repos not posted yet
            advice = help_ = ""
            if c.get("advisor"):
                advice = execute(c["advisor"]["agent"], base + "You are the ADVISOR. Decide what this run should make and "
                                 "why, in a few lines: pick the actual topic now (a specific story, list or idea, not rules for "
                                 "choosing one), the angle, and what makes it worth posting. Don't write the final piece.",
                                 c["advisor"].get("model", ""), c["advisor"].get("effort", ""))
                update_run(cid, r["id"], advice=advice, step="helper" if c.get("helper") else "agent")
            if c.get("helper"):
                help_ = execute(c["helper"]["agent"], base + f"Advisor's plan:\n{advice}\n\nYou are the HELPER. Check the facts "
                                "the agent will need (names, links, numbers); say which are right, wrong or unsure. Short.",
                                c["helper"].get("model", ""), c["helper"].get("effort", ""))
                update_run(cid, r["id"], help=help_, step="agent")
            out = execute(c["agent"]["agent"], base + (f"Advisor's plan:\n{advice}\n\n" if advice else "")
                          + (f"Helper's checks:\n{help_}\n\n" if help_ else "") + "You are the AGENT. Do the work now.\n" + suffix,
                          c["agent"].get("model", ""), c["agent"].get("effort", ""))
            extra = action(c, r, out) if action else {}
            update_run(cid, r["id"], output=out, step="", status="awaiting_approval", **extra)
        except Exception as e:  # noqa: BLE001 - the run says what went wrong
            update_run(cid, r["id"], status="failed", step="", error=str(e)[:1000])
            return
        if after:
            after(cid, r["id"])
    threading.Thread(target=work, daemon=True).start()
    return r["id"]

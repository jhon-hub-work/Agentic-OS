"""D: tidy (Jhon, 30 Oct): Jharvis scans the drive read-only once a week and asks about one folder at a time.

Kinds: drl (Dr L files outside her folders: sort them in), half (a project with a HANDOFF/PLAN gone quiet: plan how to
finish), old (untouched 180+ days: delete?), unused (untouched 60+ days: still need it?). Nothing moves until Jhon
approves the card. "Delete" moves into D:\\_Tidy Bin (same drive, instant, fully undoable), never a real delete.
No model reads the names here (Dr L patient files are on D:): the scan, sorting and recommendations are plain code.
"""

import json
import os
import re
import shutil
import subprocess
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(os.environ.get("TIDY_ROOT", "D:\\"))
BIN = ROOT / "_Tidy Bin"
DRL_HOME = ROOT / "Work" / "Dr. Young Edit" / "_sorted"
# folders whose children are looked at one by one (everything else is one card)
CONTAINERS = ("", "Work", "Work/Claude")
# never moved: installed software, system, keys, VMs, the OS and its agents, Dr L's own folders, the 28 Oct keep list
PROTECT = ("$RECYCLE.BIN", "System Volume Information", "Recovery", "Program Files", "Programs", "Apps", "Tools",
           "Games", "Riot Games", "Models", "Ollama", "Caches", "Temp", "UserData", "Android", "VMs", "VA", "Keys",
           ".venv", ".pnpm-store", "_Tidy Bin", "RazerCortexGameClip", "Work/Keys", "Work/Dr. Young Edit",
           "Work/Claude/agentic-os", "Work/Claude/agentic-os-workspace", "Work/Claude/.venvs", "Work/Claude/videos",
           "Work/Claude/CLAUDE.md", "Work/Claude/HANDOFF.md", "Work/Claude/AGENTS.md")
DRL_RE = re.compile(r"\bdr\.?\s?l\b|lauren|young.?edit|\bdye\b|qderm|dryoungedit|(?<![a-z0-9])(?:melanoma|mohs)(?![a-z])", re.I)  # not inside a hash like 4a9mohsce
# only her media and documents move on their own; a project's logos, code and notes stay (moving them breaks it)
DRL_EXT = {".mp4", ".mov", ".m4v", ".jpg", ".jpeg", ".png", ".heic", ".pdf", ".docx", ".doc", ".xlsx", ".pptx"}
ASSET_DIRS = {"public", "assets", "src", "brand", "node_modules", "target", "build", "dist", "out", ".git"}
DAY = 86400
MIN_BYTES = 10 * 1024 * 1024  # old/unused cards only: small leftovers aren't worth a question
STATE = Path(__file__).resolve().parent / "data" / "tidy.json"
LOCK = threading.RLock()


def load() -> dict:
    return json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {"cards": [], "last_scan": None}


def save(st: dict):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(st, indent=2), encoding="utf-8")
    os.replace(tmp, STATE)


def _rel(p: Path) -> str:
    return p.relative_to(ROOT).as_posix()


def _stats(p: Path) -> tuple:
    """(bytes, files, newest mtime, has HANDOFF/PLAN at the top, Dr L-named files inside) without following links."""
    if p.is_file():
        st = p.stat()
        return st.st_size, 1, st.st_mtime, False, []
    size = files = 0
    newest = p.stat().st_mtime
    handoff = any((p / n).exists() for n in ("HANDOFF.md", "PLAN.md"))
    drl = []
    for d, dirs, names in os.walk(p, onerror=lambda e: None):
        dirs[:] = [x for x in dirs if not os.path.islink(os.path.join(d, x))]
        for n in names:
            try:
                st = os.stat(os.path.join(d, n), follow_symlinks=False)
            except OSError:
                continue
            size += st.st_size
            files += 1
            newest = max(newest, st.st_mtime)
            if (DRL_RE.search(n) and len(drl) < 200 and os.path.splitext(n)[1].lower() in DRL_EXT
                    and not ASSET_DIRS & {x.lower() for x in Path(d).relative_to(p).parts}):
                drl.append(os.path.join(d, n))
    return size, files, newest, handoff, drl


def scheduled_paths() -> set:
    """Top-level units the Windows scheduled tasks point into: moving those would break a job (IG post, OS start...)."""
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", "Get-ScheduledTask | % { $_.Actions | % { "
                              "$_.Execute; $_.Arguments; $_.WorkingDirectory } }"],
                             capture_output=True, text=True, timeout=60, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
    except (OSError, subprocess.TimeoutExpired):
        return set()
    drive = str(ROOT)[:2].lower()
    return {m.replace("\\", "/").rstrip("/").lower() for m in re.findall(rf"{re.escape(drive)}\\[^\"'\r\n]+", out, re.I)}


def _protected(rel: str, sched: set) -> bool:
    low = rel.lower()
    if any(low == x.lower() or low.startswith(x.lower() + "/") for x in PROTECT):
        return True
    full = (str(ROOT)[:2] + "/" + rel).lower()
    return any(s == full or s.startswith(full + "/") for s in sched)  # a scheduled task uses something inside it


def _units():
    for c in CONTAINERS:
        base = ROOT / c
        for p in sorted(base.iterdir()) if base.is_dir() else []:
            rel = _rel(p)
            if rel in CONTAINERS or p.is_symlink() or p.suffix.lower() in (".lnk", ".url", ".log", ".tmp"):
                continue
            yield p, rel


def classify(rel: str, size: int, newest: float, handoff: bool, now: float, drl=()):
    """(kind, why, pick) or None. Dr L first: her files get sorted, not deleted."""
    age = (now - newest) / DAY
    if DRL_RE.search(rel) and not rel.startswith("Work/Claude/"):  # a project folder there is never moved whole
        return "drl", "Looks like Dr L work outside her folders.", f"Move it into {DRL_HOME}"
    if drl:
        return "drl", f"{len(drl)} Dr L files in here, e.g. {Path(drl[0]).name}.", f"Move those files into {DRL_HOME}"
    if handoff and 14 <= age < 180:
        return "half", f"Has a HANDOFF/PLAN but nobody touched it for {age:.0f} days.", "Start a debate on how to finish it"
    if size < MIN_BYTES:
        return None
    if age >= 180:
        return "old", f"Untouched for {age:.0f} days.", "Move it to the Tidy Bin (undo any time)"
    if age >= 60:
        return "unused", f"Not used for {age:.0f} days.", "Move it to the Tidy Bin if you don't need it"
    return None


def scan() -> dict:
    """Read-only. Keeps decided cards (kept ones aren't asked again for 90 days); replaces the pending ones."""
    now, sched = time.time(), scheduled_paths()
    found = []
    for p, rel in _units():
        if _protected(rel, sched):
            continue
        try:
            size, files, newest, handoff, drl = _stats(p)
        except OSError:
            continue
        hit = classify(rel, size, newest, handoff, now, [] if (p / ".git").exists() else drl)  # nothing leaves a git project
        if hit:
            found.append({"only": drl if hit[2].startswith("Move those") else [], "id": re.sub(r"[^a-z0-9]+", "-", rel.lower()).strip("-")[:80], "path": str(p), "rel": rel,
                          "kind": hit[0], "why": hit[1], "pick": hit[2], "bytes": size, "files": files,
                          "newest": datetime.fromtimestamp(newest, timezone.utc).isoformat(), "status": "pending"})
    with LOCK:
        st = load()
        recent = {c["id"] for c in st["cards"] if c["status"] != "pending" and now - c.get("decided_ts", 0) < 90 * DAY}
        order = {"drl": 0, "half": 1, "old": 2, "unused": 3}
        st["cards"] = [c for c in st["cards"] if c["status"] != "pending"] + sorted(
            (c for c in found if c["id"] not in recent), key=lambda c: (order[c["kind"]], -c["bytes"]))
        st["last_scan"] = datetime.now(timezone.utc).isoformat()
        save(st)
        return st


def due() -> bool:
    last = load().get("last_scan")
    return not last or datetime.fromisoformat(last).timestamp() < time.time() - 7 * DAY


def next_card():
    """One card at a time: the first pending one."""
    return next((c for c in load()["cards"] if c["status"] == "pending"), None)


def _move(src: Path, dest_dir: Path) -> Path:
    dest = dest_dir / src.name
    if dest.exists():
        dest = dest_dir / f"{src.stem} ({datetime.now():%Y-%m-%d %H%M}){src.suffix}"
    dest_dir.mkdir(parents=True, exist_ok=True)
    shutil.move(str(src), str(dest))  # same drive: a rename, nothing copied
    return dest


def decide(cid: str, approve: bool, start_debate=None) -> dict:
    """Approve = do the pick; deny = keep it as it is. start_debate(text) -> session id, for 'half' cards."""
    with LOCK:
        st = load()
        c = next((x for x in st["cards"] if x["id"] == cid and x["status"] == "pending"), None)
        if not c:
            raise KeyError(cid)
        src = Path(c["path"])
        if approve and c["kind"] != "half":
            if not src.exists():
                raise FileNotFoundError(c["path"])
            if _protected(c["rel"], scheduled_paths()):  # something started using it since the scan
                raise PermissionError(c["rel"])
            if c.get("only"):  # just the Dr L files inside, each keeping its subfolder under _sorted/<unit>
                c["moves"] = [[f, str(_move(Path(f), DRL_HOME / src.name / Path(f).parent.relative_to(src)))]
                              for f in c["only"] if Path(f).exists()]
            else:
                c["moves"] = [[str(src), str(_move(src, DRL_HOME if c["kind"] == "drl" else BIN / f"{datetime.now():%Y-%m-%d}"))]]
            c["status"] = "done"
        elif approve:
            c["session"] = start_debate(f"Plan how to finish the half-done project at {c['path']}. Read its HANDOFF.md "
                                        "or PLAN.md first, say what's done and what's left, then the smallest plan to finish it.")
            c["status"] = "done"
        else:
            c["status"] = "kept"
        c["decided_ts"] = time.time()
        save(st)
        return c


def undo(cid: str) -> dict:
    with LOCK:
        st = load()
        c = next((x for x in st["cards"] if x["id"] == cid and x.get("moves")), None)
        if not c:
            raise KeyError(cid)
        if any(Path(src).exists() for src, _ in c["moves"]):
            raise FileExistsError(c["path"])
        for src, dest in c.pop("moves"):
            Path(src).parent.mkdir(parents=True, exist_ok=True)
            shutil.move(dest, src)
        c["status"] = "undone"
        save(st)
        return c

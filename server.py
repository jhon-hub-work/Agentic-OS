#!/usr/bin/env python3
"""
Agentic OS — FastAPI Backend
Multi-agent orchestration server for opencode, Hermes, agy CLI
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import tarfile
import threading

import owner
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Query, UploadFile, File, Form
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

_scheduler_instance = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global _scheduler_instance
    try:
        from scheduler.scheduler import CronScheduler
        _scheduler_instance = CronScheduler()
        _scheduler_instance.start()
        from scheduler.scheduler import on_event
        on_event(handle_scheduler_event)
        mark_interrupted_runs()
        print("Event-driven scheduler started")
    except Exception as e:
        print(f"Scheduler not available: {e}")
    yield
    if _scheduler_instance:
        try:
            _scheduler_instance.stop()
        except Exception:
            pass

app = FastAPI(title="Agentic OS", version="4.0", lifespan=lifespan)

# Load OpenRouter API key from Hermes .env
HERMES_ENV = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))) / ".env"
if HERMES_ENV.exists():
    for line in HERMES_ENV.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            if k == "OPENROUTER_API_KEY":
                os.environ[k] = v  # last value wins (matches shell sourcing)

# CORS for local dev
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://127.0.0.1:8810", "http://localhost:8810"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# No-cache for dashboard assets — SPA JS is loaded on demand and updated
# frequently during development (v4.0). Prevents stale-cached pages.
from starlette.middleware.base import BaseHTTPMiddleware

class NoCacheMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        response = await call_next(request)
        path = request.url.path
        if path.startswith("/dashboard") or path in ("/", "/index.html"):
            response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
            response.headers["Pragma"] = "no-cache"
            response.headers["Expires"] = "0"
        return response

app.add_middleware(NoCacheMiddleware)

# Phone/remote access needs a token; the PC itself (loopback) does not.
# Open http://<pc>:8810/?token=<data/auth-token> once on the phone to set the cookie.
AUTH_TOKEN_FILE = Path(__file__).parent / "data" / "auth-token"
if not AUTH_TOKEN_FILE.exists():
    import secrets
    AUTH_TOKEN_FILE.parent.mkdir(exist_ok=True)
    AUTH_TOKEN_FILE.write_text(secrets.token_urlsafe(32))
AUTH_TOKEN = AUTH_TOKEN_FILE.read_text().strip()

class TokenAuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        if request.client and request.client.host in ("127.0.0.1", "::1"):
            return await call_next(request)
        given = (request.query_params.get("token")
                 or request.cookies.get("aos_token")
                 or request.headers.get("authorization", "").removeprefix("Bearer "))
        if not secrets_compare(given, AUTH_TOKEN):
            return Response("Unauthorized", status_code=401)
        response = await call_next(request)
        if request.query_params.get("token"):
            response.set_cookie("aos_token", AUTH_TOKEN, httponly=True, samesite="strict",
                                max_age=60 * 60 * 24 * 365)
        return response

def secrets_compare(a: str, b: str) -> bool:
    import hmac
    return bool(a) and hmac.compare_digest(a.encode(), b.encode())

app.add_middleware(TokenAuthMiddleware)

BASE_DIR = Path(__file__).parent.resolve()

# ─── Models ───────────────────────────────────────────────────────

class BrainUpdate(BaseModel):
    content: str

class SkillRunRequest(BaseModel):
    input: Optional[str] = ""
    agent: Optional[str] = "auto"

class ScheduleJobRequest(BaseModel):
    name: str
    cron: str
    kind: str = "agent"          # agent | team | skill
    agent: str = "claude"
    prompt: str = ""
    skill: Optional[str] = None
    enabled: bool = True

class SettingsUpdate(BaseModel):
    settings: dict

class BackupRestoreRequest(BaseModel):
    file: str

class ChatRequest(BaseModel):
    agent: str
    message: str

# ─── Helper Functions ─────────────────────────────────────────────

def read_file(path: Path):
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8")

def write_file(path: Path, content: str):
    path.write_text(content, encoding="utf-8")
    return True

def list_dir(path: Path):
    if not path.exists():
        return []
    return sorted([p.name for p in path.iterdir() if not p.name.startswith(".") and p.is_file()])

def get_timestamp():
    return datetime.now(timezone.utc).isoformat()

def append_audit(entry: dict):
    audit_file = BASE_DIR / "audit" / "audit.log"
    entry["timestamp"] = get_timestamp()
    entry["id"] = str(uuid.uuid4())[:8]
    with open(audit_file, "a") as f:
        f.write(json.dumps(entry) + "\n")

def safe_resolve(base: Path, user_path: str) -> Path:
    """Resolve a user-supplied path relative to base, preventing traversal."""
    resolved = (base / user_path).resolve()
    if not str(resolved).startswith(str(base.resolve())):
        raise HTTPException(400, "Invalid path")
    return resolved

def safe_extractall(tar: tarfile.TarFile, path: Path):
    """Extract tar archive with path traversal protection."""
    for member in tar.getmembers():
        member_path = (path / member.name).resolve()
        if not str(member_path).startswith(str(path.resolve())):
            raise HTTPException(400, f"Blocked path traversal: {member.name}")
    tar.extractall(path=path)

def validate_identifier(value: str, pattern: str, label: str = "name") -> str:
    """Reject path separators / traversal before using a value in a filesystem path."""
    if not value or not re.fullmatch(pattern, value):
        raise HTTPException(400, f"Invalid {label}")
    return value

# ─── Security Headers Middleware ─────────────────────────────────

class SecurityHeadersMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def send_with_headers(message):
            if message["type"] == "http.response.start":
                headers = message.get("headers", [])
                extra = [
                    (b"x-content-type-options", b"nosniff"),
                    (b"x-frame-options", b"DENY"),
                    (b"x-xss-protection", b"1; mode=block"),
                    (b"strict-transport-security", b"max-age=31536000; includeSubDomains"),
                    (b"referrer-policy", b"strict-origin-when-cross-origin"),
                ]
                # Only add CSP for non-API routes (dashboard HTML)
                path = scope.get("path", "")
                if not path.startswith("/api/"):
                    csp = (
                        b"default-src 'self'; "
                        b"script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
                        b"style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; "
                        b"font-src 'self' https://fonts.gstatic.com; "
                        b"img-src 'self' data:; "
                        b"connect-src 'self' http://127.0.0.1:* http://localhost:*; "
                        b"frame-ancestors 'none'"
                    )
                    extra.append((b"content-security-policy", csp))
                message["headers"] = list(headers) + extra
            await send(message)

        await self.app(scope, receive, send_with_headers)

app.add_middleware(SecurityHeadersMiddleware)

# ─── Agent Discovery (instant filesystem checks) ────────────────────

AGENTS = ["claude", "opencode", "hermes", "agy"]

def check_agent(name: str) -> dict:
    """Instant filesystem-based check. No subprocess needed."""
    status = "online" if name in AGENTS and shutil.which(name) else "offline"
    return {"name": name, "status": status}

# ─── Routes: Status ───────────────────────────────────────────────

@app.get("/api/status")
def get_status():
    agents = [check_agent(a) for a in AGENTS]
    skills_dir = BASE_DIR / "skills"
    skills = [d.name for d in skills_dir.iterdir()
              if d.is_dir() and not d.name.startswith("_")] if skills_dir.exists() else []
    return {
        "status": "healthy",
        "agents": agents,
        "skills_count": len(skills),
        "uptime": time.time(),
    }

# ─── Routes: Brain ────────────────────────────────────────────────

@app.get("/api/brain")
def list_brain():
    brain_dir = BASE_DIR / "brain"
    if not brain_dir.exists():
        return {}
    files = sorted([p.name for p in brain_dir.iterdir() if p.name.endswith(".md") and p.is_file()])
    brain_data = {}
    for f in files:
        path = brain_dir / f
        brain_data[f] = read_file(path)
    return brain_data

@app.get("/api/brain/{file_name}")
def get_brain_file(file_name: str):
    if ".." in file_name or "/" in file_name:
        raise HTTPException(400, "Invalid file name")
    path = BASE_DIR / "brain" / file_name
    if not path.exists() or path.is_dir():
        raise HTTPException(404, "File not found")
    return {"name": file_name, "content": read_file(path)}

@app.put("/api/brain/{file_name}")
def update_brain_file(file_name: str, data: BrainUpdate):
    if ".." in file_name or "/" in file_name:
        raise HTTPException(400, "Invalid file name")
    path = BASE_DIR / "brain" / file_name
    write_file(path, data.content)
    append_audit({"action": "brain_update", "file": file_name})
    return {"status": "ok", "file": file_name}

# ─── Routes: Skills ───────────────────────────────────────────────

@app.get("/api/skills")
def list_skills():
    skills = []
    for d in sorted((BASE_DIR / "skills").iterdir()):
        if d.is_dir() and not d.name.startswith("_"):
            skill_md = read_file(d / "SKILL.md")
            learnings = read_file(d / "learnings.md")
            eval_data = {}
            eval_path = d / "eval.json"
            if eval_path.exists():
                eval_data = json.loads(eval_path.read_text())
            score_history = []
            score_path = d / "score-history.json"
            if score_path.exists():
                score_history = json.loads(score_path.read_text())
            skills.append({
                "name": d.name,
                "description": skill_md[:200] if skill_md else "",
                "has_learnings": bool(learnings),
                "eval_criteria": eval_data.get("criteria", []),
                "scores": score_history,
            })
    return skills

@app.get("/api/skills/{name}")
def get_skill(name: str):
    validate_identifier(name, r"^[a-zA-Z0-9_-]+$", "skill name")
    path = BASE_DIR / "skills" / name
    if not path.exists():
        raise HTTPException(404, "Skill not found")
    return {
        "name": name,
        "skill": read_file(path / "SKILL.md"),
        "learnings": read_file(path / "learnings.md"),
        "eval": json.loads((path / "eval.json").read_text()) if (path / "eval.json").exists() else {},
        "score_history": json.loads((path / "score-history.json").read_text()) if (path / "score-history.json").exists() else [],
        "context": [f.name for f in (path / "context").iterdir()] if (path / "context").exists() else [],
    }

@app.post("/api/skills/{name}/run")
def run_skill(name: str, req: Optional[SkillRunRequest] = None):
    validate_identifier(name, r"^[a-zA-Z0-9_-]+$", "skill name")
    path = BASE_DIR / "skills" / name
    if not path.exists():
        raise HTTPException(404, "Skill not found")

    agent_choice = req.agent if req else "auto"
    skill_input = req.input if req else ""

    # Read skill files
    skill_md = read_file(path / "SKILL.md")
    learnings = read_file(path / "learnings.md")

    # Determine which agent based on skill type
    if agent_choice == "auto":
        devops_keywords = ["devops", "audit", "deploy", "k8s", "gcp", "infra", "terraform"]
        research_keywords = ["research", "synthesis", "analyze", "search", "compare"]
        if any(k in name for k in devops_keywords):
            agent_choice = "opencode"
        elif any(k in name for k in research_keywords):
            agent_choice = "agy"
        else:
            # Check SKILL.md for explicit agent assignment
            for line in skill_md.split('\n'):
                line = line.strip()
                if "Primary:" in line:
                    candidate = line.split(":")[-1].strip().lower()
                    if candidate in AGENTS:
                        agent_choice = candidate
                        break
            if agent_choice == "auto":
                agent_choice = "opencode"

    # Build prompt from skill instructions + learnings + user input
    prompt = f"Execute the '{name}' skill.\n\n"
    if skill_md:
        prompt += f"## Skill Instructions\n{skill_md}\n\n"
    if learnings and learnings.strip():
        prompt += f"## Past Learnings\n{learnings[-6000:]}\n\n"  # newest only: the prompt is a CLI argument (Windows caps it at 32K)
    if skill_input:
        prompt += f"## User Input\n{skill_input}"

    run_id = str(uuid.uuid4())[:8]

    # Execute via agent
    try:
        response_text = execute_agent(agent_choice, prompt)
    except subprocess.TimeoutExpired:
        response_text = f"⏱ Skill '{name}' timed out on agent '{agent_choice}'."
    except FileNotFoundError:
        response_text = f"⚠ Agent '{agent_choice}' CLI not installed. Install it and try again."
    except Exception as e:
        response_text = f"⚠ Error executing skill: {str(e)}"

    # Save output to learnings.md
    timestamp = get_timestamp()[:10]
    existing = read_file(path / "learnings.md")
    new_entry = (
        f"\n## {timestamp} (Run {run_id})\n"
        f"- Agent: {agent_choice}\n"
        f"- Input: {skill_input or '(none)'}\n"
        f"- Output: {response_text[:500]}\n"
    )
    write_file(path / "learnings.md", existing + new_entry)

    # Log execution
    append_audit({
        "action": "skill_run",
        "skill": name,
        "agent": agent_choice,
        "run_id": run_id,
        "output_preview": response_text[:100],
    })

    return {
        "status": "completed",
        "run_id": run_id,
        "skill": name,
        "agent": agent_choice,
        "output": response_text,
        "message": f"Skill '{name}' completed via {agent_choice}",
    }

@app.get("/api/skills/{name}/eval")
def get_skill_eval(name: str):
    validate_identifier(name, r"^[a-zA-Z0-9_-]+$", "skill name")
    path = BASE_DIR / "skills" / name / "score-history.json"
    if not path.exists():
        return {"scores": []}
    return {"scores": json.loads(path.read_text())}

# ─── Routes: Scheduler ────────────────────────────────────────────

@app.get("/api/scheduler/jobs")
def list_jobs():
    jobs_dir = BASE_DIR / "scheduler" / "jobs"
    jobs = []
    for f in sorted(jobs_dir.glob("*.json")):
        jobs.append(json.loads(f.read_text()))
    return jobs

@app.post("/api/scheduler/jobs")
def create_job(job: ScheduleJobRequest):
    jobs_dir = BASE_DIR / "scheduler" / "jobs"
    jobs_dir.mkdir(parents=True, exist_ok=True)
    validate_identifier(job.name, r"^[a-zA-Z0-9 _-]+$", "job name")
    if job.kind not in ("agent", "team", "skill", "jobs"):
        raise HTTPException(400, "kind must be agent, team, skill or jobs")
    if job.kind in ("agent", "team") and (job.agent not in AGENTS or not job.prompt.strip()):
        raise HTTPException(400, "An agent and a prompt are required")
    if job.kind == "skill" and not job.skill:
        raise HTTPException(400, "Pick a skill")
    try:
        from apscheduler.triggers.cron import CronTrigger
        CronTrigger.from_crontab(job.cron)
    except Exception:
        raise HTTPException(400, "Cron must look like '0 9 * * 1-5' (min hour day month weekday)")
    job_data = {
        "id": str(uuid.uuid4())[:8],
        "name": job.name,
        "kind": job.kind,
        "agent": job.agent,
        "prompt": job.prompt,
        "skill": job.skill,
        "cron": job.cron,
        "enabled": job.enabled,
        "created": get_timestamp(),
        "last_run": None,
        "next_run": None,
    }
    (jobs_dir / f"{job.name.replace(' ', '_')}.json").write_text(
        json.dumps(job_data, indent=2)
    )
    append_audit({"action": "job_created", "job": job.name})
    return job_data

@app.delete("/api/scheduler/jobs/{job_id}")
def delete_job(job_id: str):
    jobs_dir = BASE_DIR / "scheduler" / "jobs"
    for f in jobs_dir.glob("*.json"):
        data = json.loads(f.read_text())
        if data.get("id") == job_id:
            f.unlink()
            append_audit({"action": "job_deleted", "job_id": job_id})
            return {"status": "deleted"}
    raise HTTPException(404, "Job not found")

@app.patch("/api/scheduler/jobs/{job_id}")
def toggle_job(job_id: str, data: dict):
    for f in (BASE_DIR / "scheduler" / "jobs").glob("*.json"):
        job = json.loads(f.read_text())
        if job.get("id") == job_id:
            job["enabled"] = bool(data.get("enabled", not job.get("enabled", True)))
            f.write_text(json.dumps(job, indent=2))
            append_audit({"action": "job_toggled", "job_id": job_id, "enabled": job["enabled"]})
            return job
    raise HTTPException(404, "Job not found")

def mark_interrupted_runs():
    """Team runs and sessions live in threads; a restart kills them, so don't leave them 'running' forever."""
    for folder, field in ((BASE_DIR / "data" / "team", "active"), (BASE_DIR / "data" / "sessions", "working")):
        for f in folder.glob("*.json") if folder.exists() else []:
            run = json.loads(f.read_text())
            if run.get("status") == "running":
                run["status"] = "done" if field == "active" else "idle"
                run[field] = None if field == "active" else []
                run["interrupted"] = True
                f.write_text(json.dumps(run, indent=2))

def handle_scheduler_event(event: dict):
    if event.get("type") == "job_run":
        threading.Thread(target=run_scheduled, args=(event["job"], event.get("trigger", "cron")), daemon=True).start()

def run_scheduled(job: dict, trigger: str):
    from scheduler.scheduler import emit_event
    kind = job.get("kind", "skill")
    try:
        if kind == "jobs":
            out = json.dumps(run_job_pipeline())
        elif kind == "agent":
            out = execute_agent(job["agent"], job["prompt"])
        elif kind == "team":
            out = f"Team run {start_team(TeamRequest(message=job['prompt'], agent=job.get('agent', 'claude')))['id']} started"
        else:
            out = run_skill(job["skill"], SkillRunRequest(input=job.get("prompt", ""), agent=job.get("agent", "auto")))["output"]
    except Exception as e:
        out = f"Failed: {e}"
    emit_event({"type": "job_done", "job_id": job.get("id"), "name": job.get("name"), "trigger": trigger, "output": out[:2000]})
    append_audit({"action": "job_run", "job": job.get("name"), "agent": job.get("agent"), "output_preview": out[:100]})
    for f in (BASE_DIR / "scheduler" / "jobs").glob("*.json"):
        data = json.loads(f.read_text())
        if data.get("id") == job.get("id"):
            data["last_run"] = get_timestamp()
            f.write_text(json.dumps(data, indent=2))

# ─── Routes: Job pipeline (find, filter, score, draft; the owner approves) ─

_jobs_lock = threading.Lock()

def run_job_pipeline() -> dict:
    from jobs import pipeline
    if not _jobs_lock.acquire(blocking=False):
        return {"skipped": "a job run is already going"}
    try:
        stats = pipeline.run(execute_agent, log=lambda m: print(f"[jobs] {m}"),
                             after=auto_apply_clean if owner.OWNER.get("auto_apply") else None)
        append_audit({"action": "jobs_run", "output_preview": f"{stats['drafted']} drafted, {stats['auto_sent']} sent, {stats['needs_you']} need {owner.NAME}"})
        return stats
    finally:
        _jobs_lock.release()

@app.post("/api/jobs/run")
def jobs_run():
    threading.Thread(target=run_job_pipeline, daemon=True).start()
    return {"status": "started"}

@app.get("/api/jobs")
def jobs_board():
    from jobs import pipeline
    board = pipeline.load(pipeline.BOARD, {"jobs": [], "runs": []})
    board["running"] = _jobs_lock.locked()
    return board

@app.post("/api/jobs/{jid}/decision")
def jobs_decision(jid: str, data: dict):
    from jobs import pipeline
    validate_identifier(jid, r"^[a-f0-9]{10}$", "job id")
    try:
        job = pipeline.decide(jid, str(data.get("decision", "")))
    except KeyError:
        raise HTTPException(404, "Job not found")
    except ValueError as e:
        raise HTTPException(400, str(e))
    append_audit({"action": "job_decision", "job": job["title"], "decision": job["status"]})
    if job["status"] == "approved":  # the owner's Approve is the go-ahead: submit it the way Hoot does
        threading.Thread(target=apply_approved, args=(jid,), daemon=True).start()
    return job

HOOT_PYTHON = os.environ.get("HOOT_PYTHON", owner.OWNER.get("system_python") or "python")

def apply_approved(jid: str):
    """Run jobs/apply_runner.py (system Python has Playwright) and record the outcome on the board."""
    import tempfile
    from jobs import pipeline
    board = pipeline.load(pipeline.BOARD, {"jobs": [], "runs": []})
    job = next((j for j in board["jobs"] if j["id"] == jid), None)
    if not job:
        return
    pipeline.set_fields(jid, status="applying")
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(job, f)
    try:
        r = subprocess.run([HOOT_PYTHON, str(BASE_DIR / "jobs" / "apply_runner.py"), f.name], capture_output=True,
                           text=True, encoding="utf-8", errors="replace", stdin=subprocess.DEVNULL, timeout=1200,
                           env={k: v for k, v in os.environ.items() if k not in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")})
        res = json.loads((r.stdout or "").strip().splitlines()[-1])
    except Exception as e:  # noqa: BLE001
        res = {"outcome": "handoff", "reason": f"apply step crashed: {type(e).__name__}"}
    finally:
        os.unlink(f.name)
    status = {"submitted": "applied", "closed": "closed"}.get(res.get("outcome"), "handoff")
    pipeline.set_fields(jid, status=status, apply_result=res.get("reason") or res.get("outcome"), applied_at=pipeline.now())
    append_audit({"action": "job_apply", "job": job["title"], "output_preview": f"{status}: {res.get('reason', '')}"[:100]})
    return status

def auto_apply_clean() -> int:
    """owner.json auto_apply: drafts answerable from the CV, answers and profile go out without a tap, one at a
    time (one Brave). Voice/video recordings, test tasks and calls move to needs_you for the owner."""
    from jobs import pipeline
    sent = 0
    for j in pipeline.load(pipeline.BOARD, {"jobs": []})["jobs"]:
        if j["status"] != "drafted" and "signed out" not in str(j.get("apply_result") or ""):
            continue  # an OnlineJobs send that failed only because Brave was signed out is retried
        why = pipeline.needs_owner(j)
        if why:
            pipeline.set_fields(j["id"], status="needs_you", needs_you=True, needs_why=why)
            continue
        pipeline.decide(j["id"], "approve")
        sent += apply_approved(j["id"]) == "applied"
    return sent

_jharvis_seen = {"at": 0.0}
JHARVIS_MSGS = BASE_DIR / "data" / "jharvis" / "messages.json"
_msgs_lock = threading.Lock()

class JharvisMessage(BaseModel):
    text: str
    role: str = "owner"   # "owner" from the dashboard, "jharvis" from Jharvis (its Slack posts are mirrored here)

@app.get("/api/jharvis/messages")
def jharvis_messages(limit: int = Query(60, le=300)):
    msgs = json.loads(JHARVIS_MSGS.read_text()) if JHARVIS_MSGS.exists() else []
    return {"messages": msgs[-limit:]}

@app.post("/api/jharvis/messages")
def jharvis_post(msg: JharvisMessage):
    """The dashboard backup channel: the owner talks to Jharvis here too, in case Slack fails."""
    if msg.role not in ("owner", "jharvis") or not msg.text.strip() or len(msg.text) > 8000:
        raise HTTPException(400, "role must be owner or jharvis, text 1-8000 characters")
    with _msgs_lock:
        msgs = json.loads(JHARVIS_MSGS.read_text()) if JHARVIS_MSGS.exists() else []
        item = {"id": str(uuid.uuid4())[:8], "role": msg.role, "text": msg.text.strip(), "timestamp": get_timestamp()}
        msgs = (msgs + [item])[-500:]
        JHARVIS_MSGS.parent.mkdir(parents=True, exist_ok=True)
        JHARVIS_MSGS.write_text(json.dumps(msgs, indent=2))
    return item

@app.get("/api/jharvis/home")
def jharvis_home():
    """The Jharvis home page: the single most urgent thing for the owner, what's next, and whether Jharvis is watching."""
    inbox = jharvis_inbox(touch=False)
    drafts, needs, problems = inbox["drafts"], inbox["needs_you"], inbox["problems"]
    board_jobs = jobs_board()["jobs"]
    queued = [j for j in board_jobs if j["status"] == "queued"]
    approved = [j for j in board_jobs if j["status"] == "handoff"]  # approved, but the OS couldn't finish it
    items = (
        [{"kind": "problem", "title": p["text"][:140], "action": "Check the OS", "link": "#/activity"} for p in problems]
        + ([{"kind": "needs_you", "title": f"{len(needs)} job{'s' if len(needs) != 1 else ''} need{'' if len(needs) != 1 else 's'} you",
             "detail": f"{needs[0]['title']} at {needs[0]['company']}: {needs[0].get('needs_why') or 'a step only you can do'}",
             "action": "Open jobs", "link": "#/jobs"}] if needs else [])
        + ([{"kind": "drafts", "title": f"{len(drafts)} job draft{'s are' if len(drafts) != 1 else ' is'} ready for your Approve",
             "detail": (lambda b: f"Best match: {b['title']} at {b['company']}, score {b.get('score')}.")(max(drafts, key=lambda j: j.get('score') or 0)),
             "action": "Review drafts", "link": "#/jobs"}] if drafts else [])
        + ([{"kind": "approved", "title": f"{len(approved)} approved application{'s' if len(approved) != 1 else ''} to finish on the PC",
             "detail": "These sites need you to submit (no form I can fill). Open each, paste the draft, then tap I applied. "
                       + ", ".join(f"{j['company']}" for j in approved[:4]) + ".",
             "action": "Submit them", "link": "#/jobs"}] if approved else [])
    )
    # quick wins first: approving drafts, then submitting approved ones; needs-you is real work
    items.sort(key=lambda i: {"problem": 0, "drafts": 1, "approved": 2, "needs_you": 3}[i["kind"]])
    blanks = sum("[ADD" in (j.get("draft") or "") for j in drafts)
    suggestion = ("Add the answers the drafts keep asking for (like US hours) to your standing answers once; future drafts stop asking."
                  if blanks >= 2 else "All clear. I'll message you in Slack when something needs you.")
    jobs_cron = next((j for j in list_jobs() if j.get("kind") == "jobs" and j.get("enabled")), None)
    return {
        "owner": {"name": owner.NAME, "timezone": owner.OWNER.get("timezone") or "UTC"},
        "online": time.time() - _jharvis_seen["at"] < 420,
        "urgent": items[0] if items else None,
        "next": items[1:4] + ([{"kind": "queued", "title": f"{len(queued)} good matches queued for the next run"}] if queued else []),
        "next_run": jobs_cron["cron"] if jobs_cron else None,
        "agents": [check_agent(a) for a in AGENTS],
        "suggestion": suggestion,
        "last_run": inbox["last_run"],
    }

@app.get("/api/jharvis/inbox")
def jharvis_inbox(touch: bool = True):
    """What Jharvis should tell the owner about: job drafts waiting, jobs needing him, and anything broken."""
    from jobs import pipeline
    if touch:  # Jharvis's watcher polls this every few minutes: that is its heartbeat
        _jharvis_seen["at"] = time.time()
    from scheduler.scheduler import get_history
    board = pipeline.load(pipeline.BOARD, {"jobs": [], "runs": []})
    problems = []
    for a in AGENTS:
        if check_agent(a)["status"] != "online":
            problems.append({"id": f"agent-offline-{a}", "text": f"{a} is not installed or not on PATH"})
    for e in get_history(200):
        out = str(e.get("output", ""))
        if e.get("type") == "job_done" and re.match(r"(Failed|⏱|⚠|opencode failed)", out):
            problems.append({"id": f"job-{e.get('name')}-{str(e.get('timestamp', ''))[:10]}", "text": f"Schedule '{e.get('name')}' failed: {out[:200]}"})
    last = board["runs"][0] if board["runs"] else None
    if last and last.get("errors") and not last.get("fetched"):  # one feed being rate-limited is normal
        problems.append({"id": f"run-{last['started']}", "text": "Job run problems: " + "; ".join(last["errors"])[:400]})
    return {
        "applied": [j for j in board["jobs"] if j["status"] in ("applied", "handoff", "closed") and j.get("applied_at")],
        "drafts": [j for j in board["jobs"] if j["status"] == "drafted"],
        "needs_you": [j for j in board["jobs"] if j["status"] == "needs_you"],
        "last_run": last,
        "problems": problems,
        "dashboard": os.environ.get("AGENTIC_OS_DASHBOARD", owner.OWNER.get("dashboard_url") or "http://127.0.0.1:8810") + "/#/jobs",
    }

# ─── Routes: Audit ────────────────────────────────────────────────

@app.get("/api/audit")
def get_audit(limit: int = Query(100, le=500)):
    audit_file = BASE_DIR / "audit" / "audit.log"
    if not audit_file.exists():
        return {"entries": []}
    lines = audit_file.read_text().strip().split("\n")
    entries = [json.loads(l) for l in lines if l.strip()]
    return {"entries": entries[-limit:]}

# ─── Routes: Cost Analytics ───────────────────────────────────────

@app.get("/api/cost")
def get_cost():
    cost_file = BASE_DIR / "data" / "cost-history.json"
    if not cost_file.exists():
        return {"entries": [], "daily_totals": {}, "monthly_projection": 0, "free_tier_alerts": []}
    return json.loads(cost_file.read_text())

_cost_lock = threading.Lock()

def append_cost(agent: str, tokens: int, cost: float, model: str):
    with _cost_lock:
        _append_cost(agent, tokens, cost, model)

def _append_cost(agent: str, tokens: int, cost: float, model: str):
    cost_file = BASE_DIR / "data" / "cost-history.json"
    cost_data = json.loads(cost_file.read_text()) if cost_file.exists() else \
        {"entries": [], "daily_totals": {}, "monthly_projection": 0, "free_tier_alerts": []}
    cost_data["entries"].append({
        "timestamp": get_timestamp(),
        "agent": agent,
        "tokens": tokens,
        "cost": cost,
        "model": model,
    })
    cost_file.write_text(json.dumps(cost_data, indent=2))

@app.post("/api/cost/record")
def record_cost(data: dict):
    append_cost(data.get("agent", "unknown"), data.get("tokens", 0),
                data.get("cost", 0.0), data.get("model", "unknown"))
    return {"status": "recorded"}

# ─── Routes: Registry/Plugins ─────────────────────────────────────

@app.get("/api/plugins")
def list_plugins():
    reg_file = BASE_DIR / "registry" / "plugins.json"
    if not reg_file.exists():
        return {"plugins": []}
    return json.loads(reg_file.read_text())

@app.post("/api/plugins/install")
def install_plugin(data: dict):
    name = data.get("name", "").strip()
    if not name:
        raise HTTPException(400, "Plugin name required")
    reg_file = BASE_DIR / "registry" / "plugins.json"
    reg = json.loads(reg_file.read_text()) if reg_file.exists() else {"plugins": []}
    if any(p["name"] == name for p in reg["plugins"]):
        return {"status": "already_installed"}
    reg["plugins"].append({
        "name": name,
        "installed": get_timestamp(),
        "version": "1.0",
    })
    reg_file.write_text(json.dumps(reg, indent=2))
    append_audit({"action": "plugin_installed", "plugin": name})
    return {"status": "installed", "plugin": name}

# ─── Routes: Backup ───────────────────────────────────────────────

@app.get("/api/backups")
def list_backups():
    backup_dir = BASE_DIR / "backups"
    backups = []
    for f in sorted(backup_dir.glob("*.tar.gz"), reverse=True):
        backups.append({
            "name": f.name,
            "size": f.stat().st_size,
            "created": datetime.fromtimestamp(f.stat().st_mtime).isoformat(),
        })
    return backups

@app.post("/api/backup")
def create_backup():
    backup_dir = BASE_DIR / "backups"
    backup_dir.mkdir(exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_file = backup_dir / f"agentic-os-{ts}.tar.gz"
    with tarfile.open(backup_file, "w:gz") as tar:
        for dir_name in ["brain", "skills", "agents", "registry", "standards", "prompts"]:
            d = BASE_DIR / dir_name
            if d.exists():
                tar.add(d, arcname=dir_name)
    append_audit({"action": "backup_created", "file": backup_file.name})
    return {"status": "ok", "file": backup_file.name, "size": backup_file.stat().st_size}

@app.post("/api/backup/restore")
def restore_backup(data: BackupRestoreRequest):
    validate_identifier(data.file, r"^agentic-os-\d{8}_\d{6}\.tar\.gz$", "backup file")
    backup_file = BASE_DIR / "backups" / data.file
    if not backup_file.exists():
        raise HTTPException(404, "Backup file not found")
    with tarfile.open(backup_file, "r:gz") as tar:
        safe_extractall(tar, BASE_DIR)
    append_audit({"action": "backup_restored", "file": data.file})
    return {"status": "restored"}

# ─── Routes: Prompts ──────────────────────────────────────────────

@app.get("/api/prompts")
def list_prompts():
    prompts_dir = BASE_DIR / "prompts"
    prompts = {}
    for f in sorted(prompts_dir.glob("*.md")):
        prompts[f.stem] = read_file(f)
    return prompts

# ─── Routes: Settings ─────────────────────────────────────────────

@app.get("/api/settings")
def get_settings():
    sf = BASE_DIR / "data" / "settings.json"
    if not sf.exists():
        return {}
    data = json.loads(sf.read_text())
    # Mask sensitive values
    if "api_keys" in data:
        data["api_keys"] = {k: v[:4] + "****" if len(v) > 8 else "****" for k, v in data["api_keys"].items()}
    return data

@app.put("/api/settings")
def update_settings(data: SettingsUpdate):
    sf = BASE_DIR / "data" / "settings.json"
    # Merge with existing
    existing = json.loads(sf.read_text()) if sf.exists() else {}
    existing.update(data.settings)
    sf.write_text(json.dumps(existing, indent=2))
    append_audit({"action": "settings_updated"})
    return {"status": "ok"}

# ─── Routes: Webhooks & Scheduler Events (v3.0) ─────────────────

@app.post("/api/webhook")
def webhook_receiver(data: dict):
    """Generic webhook receiver — triggers skill execution by event type."""
    event_type = data.get("event", data.get("type", "unknown"))
    skill_name = data.get("skill", "")
    payload = data.get("payload", {})
    if skill_name:
        from scheduler.scheduler import run_skill
        result = run_skill(skill_name, trigger=f"webhook:{event_type}", input_text=json.dumps(payload))
        append_audit({"action": "webhook_received", "event": event_type, "skill": skill_name})
        return {"status": "processed", "event": event_type, "skill": skill_name, "result": result}
    append_audit({"action": "webhook_received", "event": event_type})
    return {"status": "received", "event": event_type}

@app.get("/api/scheduler/events")
def get_scheduler_events(limit: int = Query(50, le=200)):
    from scheduler.scheduler import get_history
    return {"events": get_history(limit=limit)}

@app.post("/api/scheduler/trigger/{job_id}")
def trigger_job(job_id: str):
    from scheduler.scheduler import get_job_by_id, run_job
    job = get_job_by_id(job_id)
    if not job:
        raise HTTPException(404, "Job not found")
    result = run_job(job, trigger="manual")
    append_audit({"action": "job_triggered", "job_id": job_id, "skill": job["skill"]})
    return result

@app.post("/api/webhook/generic")
def generic_webhook(data: dict):
    """Catch-all webhook receiver for external tool integrations."""
    source = data.get("source", "unknown")
    event = data.get("event", data.get("action", "trigger"))
    skill = data.get("skill", "")
    if skill:
        from scheduler.scheduler import run_skill
        run_skill(skill, trigger=f"webhook:{source}:{event}")
        append_audit({"action": "generic_webhook", "source": source, "event": event, "skill": skill})
    return {"status": "ok", "source": source, "event": event}

# ─── Routes: Memory Search & Auto-Skill Generator (v3.0) ─────────

@app.get("/api/memory/search")
def memory_search(q: str = Query(""), limit: int = Query(20, le=100)):
    from brain.memory_search import search, extract_entities
    results = search(q, limit) if q else []
    entities = extract_entities(q) if q else []
    return {"results": results, "entities": entities, "query": q}

@app.post("/api/memory/reindex")
def memory_reindex():
    from brain.memory_search import reindex_all
    reindex_all()
    append_audit({"action": "memory_reindexed"})
    return {"status": "reindexed"}

@app.get("/api/memory/entities")
def list_entities(entity_type: str = "", limit: int = Query(50, le=200)):
    from brain.memory_search import get_entities
    return {"entities": get_entities(entity_type=entity_type, limit=limit)}

@app.get("/api/memory/graph")
def memory_graph():
    """Knowledge graph of memory files, skills, and extracted entities (v4.0)."""
    try:
        from brain.memory_search import build_graph
        graph = build_graph()
        append_audit({"action": "memory_graph_viewed", "nodes": graph["stats"]["nodes"]})
        return graph
    except Exception as e:
        return {"nodes": [], "edges": [], "stats": {"nodes": 0, "edges": 0}, "error": str(e)}

@app.post("/api/skills/generate")
def generate_skill(data: dict):
    """Auto-generate a SKILL.md from a natural language description."""
    name = data.get("name", "").strip().lower().replace(" ", "-")
    description = data.get("description", "").strip()
    if not name or not description:
        raise HTTPException(400, "Both 'name' and 'description' are required")
    if not re.match(r'^[a-z0-9-]+$', name):
        raise HTTPException(400, "Skill name must be alphanumeric with hyphens")
    skill_dir = BASE_DIR / "skills" / name
    if skill_dir.exists():
        raise HTTPException(409, "Skill already exists")
    skill_dir.mkdir(parents=True)
    (skill_dir / "context").mkdir(exist_ok=True)
    instructions = data.get("instructions", "").strip() or description
    agent = data.get("agent", "claude")
    if agent not in AGENTS:
        raise HTTPException(400, f"Agent must be one of: {', '.join(AGENTS)}")
    skill_md = f"""---
name: {name}
description: {description}
---

# {name.replace('-', ' ').title()}

{instructions}

## Primary: {agent}
"""
    (skill_dir / "SKILL.md").write_text(skill_md)
    (skill_dir / "learnings.md").write_text(f"# {name}\n\nAuto-generated skill.\n")
    eval_data = {"criteria": ["completeness", "accuracy", "efficiency"], "weights": [0.4, 0.3, 0.3]}
    (skill_dir / "eval.json").write_text(json.dumps(eval_data, indent=2))
    (skill_dir / "score-history.json").write_text("[]")
    append_audit({"action": "skill_generated", "name": name, "description": description})
    return {"status": "created", "name": name, "skill": skill_md}

# ─── Routes: Error Tracking (v3.0) ───────────────────────────────

ERROR_LOG_FILE = BASE_DIR / "data" / "error-log.json"

def log_error(source: str, message: str, category: str = "general", details: dict = None):
    errors = []
    if ERROR_LOG_FILE.exists():
        errors = json.loads(ERROR_LOG_FILE.read_text())
    errors.append({
        "id": str(uuid.uuid4())[:8],
        "source": source,
        "message": message,
        "category": category,
        "details": details or {},
        "timestamp": get_timestamp(),
    })
    if len(errors) > 500:
        errors = errors[-500:]
    ERROR_LOG_FILE.write_text(json.dumps(errors, indent=2))

@app.get("/api/errors")
def get_errors(limit: int = Query(50, le=200), category: str = ""):
    if not ERROR_LOG_FILE.exists():
        return {"errors": []}
    errors = json.loads(ERROR_LOG_FILE.read_text())
    if category:
        errors = [e for e in errors if e.get("category") == category]
    return {"errors": errors[-limit:]}

@app.delete("/api/errors")
def clear_errors():
    if ERROR_LOG_FILE.exists():
        ERROR_LOG_FILE.write_text("[]")
    return {"status": "cleared"}

@app.post("/api/errors/report")
def report_error(data: dict):
    log_error(
        source=data.get("source", "unknown"),
        message=data.get("message", ""),
        category=data.get("category", "general"),
        details=data.get("details"),
    )
    return {"status": "reported"}

# ─── Circuit Breaker (v3.0) ──────────────────────────────────────

CIRCUIT_BREAKER_FILE = BASE_DIR / "data" / "circuit-breaker.json"

def _get_circuit_state() -> dict:
    if CIRCUIT_BREAKER_FILE.exists():
        return json.loads(CIRCUIT_BREAKER_FILE.read_text())
    return {"agents": {}, "threshold": 3, "recovery_timeout": 300}

def _save_circuit_state(state: dict):
    CIRCUIT_BREAKER_FILE.write_text(json.dumps(state, indent=2))

@app.get("/api/circuit-breaker")
def get_circuit_breaker():
    state = _get_circuit_state()
    now = time.time()
    for agent, cb in state.get("agents", {}).items():
        if cb.get("state") == "open" and now - cb.get("opened_at", 0) > state.get("recovery_timeout", 300):
            cb["state"] = "half-open"
    return state

@app.post("/api/circuit-breaker/trip")
def trip_circuit_breaker(data: dict):
    agent = data.get("agent", "")
    if agent not in AGENTS:
        raise HTTPException(400, "Invalid agent")
    state = _get_circuit_state()
    if agent not in state["agents"]:
        state["agents"][agent] = {"state": "closed", "failures": 0, "opened_at": None}
    cb = state["agents"][agent]
    cb["failures"] = cb.get("failures", 0) + 1
    if cb["failures"] >= state["threshold"]:
        cb["state"] = "open"
        cb["opened_at"] = time.time()
    _save_circuit_state(state)
    append_audit({"action": "circuit_tripped", "agent": agent, "failures": cb["failures"]})
    return {"agent": agent, "state": cb["state"], "failures": cb["failures"]}

@app.post("/api/circuit-breaker/reset")
def reset_circuit_breaker(data: dict):
    agent = data.get("agent", "")
    if agent not in AGENTS:
        raise HTTPException(400, "Invalid agent")
    state = _get_circuit_state()
    state["agents"][agent] = {"state": "closed", "failures": 0, "opened_at": None}
    _save_circuit_state(state)
    return {"agent": agent, "state": "closed"}

# ─── Routes: Standards ────────────────────────────────────────────

@app.get("/api/standards")
def list_standards():
    std_dir = BASE_DIR / "standards"
    if not std_dir.exists():
        return {"standards": []}
    standards = []
    index_file = std_dir / "index.yml"
    index_content = read_file(index_file)
    for f in std_dir.glob("*.md"):
        standards.append({
            "name": f.stem,
            "content": read_file(f),
        })
    return {"standards": standards, "index": index_content}

@app.post("/api/standards/discover")
def discover_standards():
    # Stub: scans codebase for patterns
    append_audit({"action": "standards_discovery_run"})
    return {"status": "discovery_started", "message": "Scanning codebase for patterns..."}

# ─── Routes: Chat ─────────────────────────────────────────────────

CHAT_HISTORY_FILE = BASE_DIR / "data" / "chat-history.json"

def load_chat_history():
    if not CHAT_HISTORY_FILE.exists():
        return {"messages": []}
    try:
        data = json.loads(CHAT_HISTORY_FILE.read_text())
        if isinstance(data, dict) and "messages" in data:
            return data
    except (json.JSONDecodeError, TypeError):
        pass
    return {"messages": []}

def save_chat_message(msg: dict):
    history = load_chat_history()
    history.setdefault("messages", []).append(msg)
    if len(history["messages"]) > 200:
        history["messages"] = history["messages"][-200:]
    CHAT_HISTORY_FILE.write_text(json.dumps(history, indent=2))

def resolve_cli(name: str) -> str:
    # npm gives opencode a .cmd wrapper, which would pass the message through cmd.exe parsing; call the real exe.
    if name == "opencode":
        exe = Path(os.environ.get("APPDATA", "")) / "npm" / "node_modules" / "@opencode" / "cli" / "bin" / "opencode.exe"
        if exe.exists():
            return str(exe)
    return name

# Agents work here, never inside the OS code (on 26 Oct OpenCode committed into this repo from a session).
AGENT_WORKDIR = Path(os.environ.get("AGENT_WORKDIR", str(BASE_DIR.parent / "agentic-os-workspace")))
# Workers may READ the owner's projects (their plan modes block writes); Hermes gets no file tools (they can't be read-only).
READ_ROOT = os.environ.get("AGENT_READ_ROOT", owner.OWNER.get("read_root") or str(BASE_DIR.parent))
AGENT_RULES = (
    "Rules: you plan and scope; you do not change anything. Don't create, edit or delete files, don't run "
    f"git, and don't copy {owner.NAME}'s personal files anywhere. You may read files under {READ_ROOT}. Propose the plan and the exact "
    f"changes; Jharvis makes them only after {owner.NAME} approves. If you need information from "
    f"{owner.NAME}, write each question on its own line starting with 'QUESTION:' and don't repeat a question a "
    "teammate already asked.\n\n"
)

def run_cli(args: list, timeout: int = 30, cwd: Optional[Path] = None) -> tuple:
    args = [resolve_cli(args[0]), *args[1:]]
    # stdin closed: opencode run otherwise waits on it and times out.
    if cwd:
        cwd.mkdir(parents=True, exist_ok=True)
    # Claude Code prefers an API key when one is set; drop it so the owner's subscription is used, never API credit.
    env = {k: v for k, v in os.environ.items() if k not in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")}
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace",
                       stdin=subprocess.DEVNULL, timeout=timeout, cwd=cwd, env=env)
    return r.returncode, r.stdout, r.stderr

def clean_hermes_output(raw: str) -> str:
    """Strip CLI metadata from Hermes output, returning only the AI response."""
    if not raw:
        return ""
    lines = raw.split('\n')
    in_box = False
    content_lines = []
    for line in lines:
        if '╭─' in line:
            in_box = True
            continue
        if '╰─' in line:
            in_box = False
            continue
        if in_box:
            # Remove ANSI escape codes and leading whitespace
            cleaned = line.strip()
            if cleaned:
                content_lines.append(cleaned)
    if content_lines:
        return '\n'.join(content_lines)
    # Fallback: if no box found, return last non-metadata line
    non_meta = [l.strip() for l in lines if l.strip() and not l.startswith(('Query:', 'Initializing', '──', 'Resume', 'Session:', 'Duration:', 'Messages:'))]
    return '\n'.join(non_meta[-5:]) or raw

def agent_model(agent: str, default: str) -> str:
    sf = BASE_DIR / "data" / "settings.json"
    settings = json.loads(sf.read_text()) if sf.exists() else {}
    return settings.get("models", {}).get(agent, default)

# Workers are read-only (plan modes / safe toolsets): only Jharvis makes changes, with the owner's approval where needed.
def execute_agent(agent: str, message: str) -> str:
    out = _execute_agent(agent, message)
    # OpenCode Zen's free tier runs out; Claude (owner's subscription) picks the work up instead of failing the run.
    if agent == "opencode" and out.startswith("opencode failed") and re.search(r"free.?(usage.?)?limit|429", out, re.I):
        print("[agents] opencode free limit reached, using claude")
        return _execute_agent("claude", message)
    return out

def _execute_agent(agent: str, message: str) -> str:
    message = AGENT_RULES + message
    try:
        if agent == "claude":
            # Unmodified Claude Code on the owner's own subscription; the Agent SDK may not use it.
            model = agent_model("claude", "sonnet")
            try:
                code, out, err = run_cli(["claude", "-p", message, "--output-format", "json",
                                          "--model", model, "--permission-mode", "plan", "--add-dir", READ_ROOT], timeout=600, cwd=AGENT_WORKDIR)
            except subprocess.TimeoutExpired:
                return "⏱ Claude Code timed out after 10 minutes."
            try:
                result = json.loads(out)
            except json.JSONDecodeError:
                return (err or out or f"claude returned exit code {code}").strip()
            usage = result.get("usage", {})
            tokens = sum(usage.get(k, 0) for k in ("input_tokens", "output_tokens",
                         "cache_creation_input_tokens", "cache_read_input_tokens"))
            # Subscription: cost is what the API would have charged, not money spent.
            append_cost("claude", tokens, result.get("total_cost_usd", 0.0), model)
            return result.get("result", "").strip() or "**claude**" + chr(10)*2 + "Empty response."

        elif agent == "opencode":
            model = agent_model("opencode", "opencode/deepseek-v4.1-flash-free")
            try:
                code, out, err = run_cli(["opencode", "run", "--format", "json", "--agent", "plan", "-m", model, message], timeout=420, cwd=Path(READ_ROOT))
            except subprocess.TimeoutExpired:
                return f"⏱ Agent 'opencode' timed out.\n\nOpenCode's model is taking too long. Try running `opencode run \"{message[:60]}\"` directly in your terminal.\n\n**Message:** {message[:100]}"
            if code == 0:
                response_text = ""
                for line in (out or "").split('\n'):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        event = json.loads(line)
                        if event.get("type") == "text":
                            text = event.get("part", {}).get("text", "")
                            if text:
                                response_text += text + "\n"
                    except (json.JSONDecodeError, KeyError):
                        continue
                if response_text:
                    # shortcut: opencode run JSON has no token/cost fields, so only the call is logged.
                    append_cost("opencode", 0, 0.0, model)
                    return response_text.strip()
                return f"**opencode**\n\nProcessed your message.\n\n**Message:** {message[:100]}"
            err_msg = (err or "").strip()
            for line in (out or "").splitlines():
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if event.get("type") == "error" or "error" in event:
                    err_msg = err_msg or json.dumps(event.get("error", event))[:400]
            return f"opencode failed: {err_msg}" if err_msg else f"opencode returned exit code {code} with no message"

        elif agent == "hermes":
            try:
                code, out, err = run_cli(["hermes", "-z", message, "-t", "web,todo,memory,session_search"], timeout=420, cwd=AGENT_WORKDIR)
            except subprocess.TimeoutExpired:
                return f"⏱ Hermes timed out.\n\nThe model took too long to respond. Try a shorter query or check your OpenRouter rate limits.\n\n**Message:** {message[:100]}"
            if code == 0:
                append_cost("hermes", 0, 0.0, agent_model("hermes", "hermes default"))
                cleaned = clean_hermes_output(out or "")
                if cleaned:
                    return cleaned
                # Empty response from model - return useful fallback
                return f"**Hermes**\n\nReceived your message but the model returned an empty response. Try rephrasing your query.\n\n**Message:** {message}"
            err_msg = (err or "").strip()
            if "invalid choice" in err_msg or "usage:" in err_msg:
                return f"**Hermes needs setup**\n\nRun `hermes setup` or check your config.\n\n**Details:** {err_msg[:200]}"
            return err_msg or f"hermes returned exit code {code}"

        elif agent == "agy":
            try:
                code, out, err = run_cli(["agy", "--print", message, "--mode", "plan", "--add-dir", READ_ROOT], timeout=420, cwd=AGENT_WORKDIR)
            except subprocess.TimeoutExpired:
                return f"**agy timed out.**\n\nTry running `agy --print \"{message[:60]}\"` directly."
            combined = ((err or "") + " " + (out or "")).strip()
            if code == 0:
                return (out or "").strip() or f"**agy**\n\nProcessed your query."
            if "auth" in combined.lower() or "login" in combined.lower() or "api key" in combined.lower():
                return f"**agy needs auth**\n\nRun `agy login` to authenticate.\n\n**Details:** {combined[:200]}"
            return combined or f"agy returned exit code {code}"

        else:
            return f"Unknown agent: {agent}"
    except subprocess.TimeoutExpired:
        return f"⏱ Agent '{agent}' timed out.\n\nRun `{agent} --help` in your terminal for CLI usage.\n\n**Message:** {message[:100]}"
    except FileNotFoundError:
        return f"⚠ Agent '{agent}' CLI not installed. Install it and try again."
    except Exception as e:
        return f"⚠ Error communicating with {agent}: {str(e)}"

@app.post("/api/chat")
def chat(req: ChatRequest):
    agent = req.agent.lower().strip()
    if agent not in AGENTS:
        raise HTTPException(400, f"Agent must be one of: {', '.join(AGENTS)}")
    message = (req.message or "").strip()
    if not message:
        raise HTTPException(400, "Message cannot be empty")
    if len(message) > 10000:
        raise HTTPException(400, "Message too long (max 10000 characters)")

    user_msg = {
        "id": str(uuid.uuid4())[:8],
        "role": "user",
        "agent": agent,
        "content": message,
        "timestamp": get_timestamp(),
    }
    # Each CLI call starts fresh, so give the agent the recent turns of this chat.
    recent = [m for m in load_chat_history().get("messages", []) if m.get("agent") == agent][-10:]
    save_chat_message(user_msg)
    context = "\n\n".join(f"[{owner.NAME if m['role'] == 'user' else agent}]\n{m['content']}" for m in recent)
    prompt = f"Earlier in this chat:\n{context}\n\n{owner.NAME} now says:\n{message}" if context else message

    response_text = execute_agent(agent, prompt)

    agent_msg = {
        "id": str(uuid.uuid4())[:8],
        "role": "assistant",
        "agent": agent,
        "content": response_text,
        "timestamp": get_timestamp(),
    }
    save_chat_message(agent_msg)

    append_audit({"action": "chat_message", "agent": agent, "msg_preview": message[:50]})

    return {"status": "ok", "response": agent_msg}

# ─── Routes: Sessions (agents working at the same time) ──────────

SESSION_DIR = BASE_DIR / "data" / "sessions"

class SessionRequest(BaseModel):
    message: str
    agents: list[str]
    rounds: int = 2

class SessionMessage(BaseModel):
    message: str

def session_prompt(agent: str, others: list, messages: list) -> str:
    transcript = "\n\n".join(f"[{m.get('agent') or owner.NAME}]\n{m['content']}" for m in messages)
    return (
        f"You are '{agent}' ({TEAM_ROLES[agent]}) in a live session with "
        + ", ".join(f"{o} ({TEAM_ROLES[o]})" for o in others) + f", all answering {owner.NAME} at the same time. Stay in your role.\n"
        "Build on your teammates' latest points instead of repeating them, and say so when one is wrong. "
        "Stay focused and concise. When the work is complete, end with a line that says only DONE.\n\n"
        f"Conversation:\n{transcript}\n\nYour turn:"
    )

def save_session(sess: dict):
    SESSION_DIR.mkdir(parents=True, exist_ok=True)
    (SESSION_DIR / f"{sess['id']}.json").write_text(json.dumps(sess, indent=2))

def run_session(sess: dict):
    from concurrent.futures import ThreadPoolExecutor
    agents = sess["agents"]
    for _ in range(sess["rounds"]):
        sess["round"] += 1
        sess["working"] = list(agents)
        save_session(sess)
        snapshot = list(sess["messages"])
        with ThreadPoolExecutor(len(agents)) as pool:
            outs = list(pool.map(lambda a: (a, execute_agent(a, session_prompt(a, [o for o in agents if o != a], snapshot))), agents))
        all_done = True
        for agent, out in outs:
            done = out.rstrip().endswith("DONE")
            all_done &= done
            sess["messages"].append({"role": "agent", "agent": agent, "round": sess["round"],
                                     "content": out.rstrip().removesuffix("DONE").rstrip(), "timestamp": get_timestamp()})
        sess["working"] = []
        save_session(sess)
        if all_done:
            break
    sess["status"] = "idle"
    save_session(sess)
    append_audit({"action": "session_round", "session": sess["id"], "agents": ",".join(agents)})

def start_session_thread(sess: dict):
    sess["status"] = "running"
    save_session(sess)
    threading.Thread(target=run_session, args=(sess,), daemon=True).start()

@app.post("/api/sessions")
def create_session(req: SessionRequest):
    agents = list(dict.fromkeys(a.lower().strip() for a in req.agents))
    if len(agents) < 2 or any(a not in AGENTS for a in agents):
        raise HTTPException(400, f"Pick at least two of: {', '.join(AGENTS)}")
    message = req.message.strip()
    if not message or len(message) > 10000:
        raise HTTPException(400, "Message must be 1-10000 characters")
    sess = {"id": str(uuid.uuid4())[:8], "title": message[:80], "agents": agents,
            "rounds": max(1, min(req.rounds, 4)), "round": 0, "working": [], "created": get_timestamp(),
            "messages": [{"role": "user", "agent": None, "round": 0, "content": message, "timestamp": get_timestamp()}]}
    start_session_thread(sess)
    return sess

@app.post("/api/sessions/{session_id}/message")
def session_message(session_id: str, req: SessionMessage):
    sess = get_session(session_id)
    if sess["status"] == "running":
        raise HTTPException(409, "Agents are still answering; wait for this round to finish")
    if not req.message.strip():
        raise HTTPException(400, "Message cannot be empty")
    sess["messages"].append({"role": "user", "agent": None, "round": sess["round"], "content": req.message.strip(), "timestamp": get_timestamp()})
    start_session_thread(sess)
    return sess

@app.get("/api/sessions")
def list_sessions(limit: int = Query(30, le=200)):
    if not SESSION_DIR.exists():
        return {"sessions": []}
    files = sorted(SESSION_DIR.glob("*.json"), key=lambda f: f.stat().st_mtime, reverse=True)[:limit]
    return {"sessions": [json.loads(f.read_text()) for f in files]}

@app.get("/api/sessions/list")
def list_replay_sessions():
    """Session archive used by the Session Replay page (must stay above
    /api/sessions/{session_id} or the dynamic route swallows it)."""
    try:
        sessions = []
        sessions_dir = Path.home() / ".local" / "share" / "opencode"
        log_dir = sessions_dir / "log"
        if log_dir.exists():
            for f in sorted(log_dir.glob("*.log"), reverse=True)[:20]:
                sessions.append({
                    "id": f.stem,
                    "name": f.stem,
                    "size": f.stat().st_size,
                    "modified": datetime.fromtimestamp(f.stat().st_mtime).isoformat(),
                    "source": "opencode",
                })
        hermes_sessions = Path.home() / ".hermes" / "sessions.json"
        if hermes_sessions.exists():
            sessions.append({
                "id": "hermes-sessions",
                "name": "Hermes Session Archive",
                "size": hermes_sessions.stat().st_size,
                "modified": datetime.fromtimestamp(hermes_sessions.stat().st_mtime).isoformat(),
                "source": "hermes",
            })
        return {"sessions": sessions}
    except Exception as e:
        return {"sessions": [], "error": str(e)}

@app.get("/api/sessions/{session_id}")
def get_session(session_id: str):
    validate_identifier(session_id, r"^[a-f0-9]{8}$", "session id")
    f = SESSION_DIR / f"{session_id}.json"
    if not f.exists():
        raise HTTPException(404, "Session not found")
    return json.loads(f.read_text())

# ─── Routes: Connections (MCP servers, per agent) ────────────────

def mcp_add_args(agent: str, name: str, url: str, command: list) -> list:
    if url:
        return {"claude": ["claude", "mcp", "add", "--transport", "http", "--scope", "user", name, url],
                "opencode": ["opencode", "mcp", "add", "--global", name, "--url", url],
                "hermes": ["hermes", "mcp", "add", name, "--url", url],
                "agy": ["agy", "mcp", "add", "--type", "http", name, url]}[agent]
    return {"claude": ["claude", "mcp", "add", "--scope", "user", name, "--", *command],
            "opencode": ["opencode", "mcp", "add", "--global", name, "--", *command],
            "hermes": ["hermes", "mcp", "add", name, "--command", command[0], *(["--args", *command[1:]] if command[1:] else [])],
            "agy": ["agy", "mcp", "add", name, *command]}[agent]

class ConnectionRequest(BaseModel):
    name: str
    url: str = ""
    command: str = ""
    agents: list[str]

@app.get("/api/connections")
def list_connections():
    from concurrent.futures import ThreadPoolExecutor
    def one(agent):
        try:
            code, out, err = run_cli([agent, "mcp", "list"], timeout=90)
            return agent, {"ok": code == 0, "text": (out or err).strip()}
        except Exception as e:
            return agent, {"ok": False, "text": str(e)}
    with ThreadPoolExecutor(len(AGENTS)) as pool:
        return dict(pool.map(one, AGENTS))

@app.post("/api/connections")
def add_connection(req: ConnectionRequest):
    import shlex
    validate_identifier(req.name, r"^[a-zA-Z0-9_-]{1,40}$", "connection name")
    url, command = req.url.strip(), shlex.split(req.command.strip(), posix=False)
    if bool(url) == bool(command):
        raise HTTPException(400, "Give either a URL (remote server) or a command (local server)")
    if url and not re.match(r"^https?://", url):
        raise HTTPException(400, "URL must start with http:// or https://")
    results = {}
    for agent in req.agents:
        if agent not in AGENTS:
            raise HTTPException(400, f"Unknown agent: {agent}")
        code, out, err = run_cli(mcp_add_args(agent, req.name, url, command), timeout=120)
        results[agent] = {"ok": code == 0, "text": (out or err).strip()[-400:]}
    append_audit({"action": "connection_added", "name": req.name, "agents": ",".join(req.agents)})
    return results

@app.get("/api/models/{agent}")
def list_models(agent: str):
    if agent == "claude":
        return {"models": ["sonnet", "opus", "haiku"], "current": agent_model("claude", "sonnet")}
    if agent == "opencode":
        code, out, _ = run_cli(["opencode", "models"], timeout=60)
        zen = [m.strip() for m in out.splitlines() if m.strip().startswith("opencode/")]
        return {"models": zen, "current": agent_model("opencode", "opencode/deepseek-v4.1-flash-free")}
    raise HTTPException(404, "Model choice is only offered for claude and opencode")

# ─── Routes: Team (agent-to-agent handoffs) ──────────────────────

TEAM_DIR = BASE_DIR / "data" / "team"
TEAM_ROLES = {
    "claude": "careful writing and hard reasoning",
    "opencode": "code, checks and quick bulk work",
    "hermes": "scheduling, reminders and messaging the owner's phone",
    "agy": "web research",
}
HANDOFF_RE = re.compile(r"^\s*HANDOFF:\s*(\w+)\s*:\s*(.+)$", re.MULTILINE)

class TeamRequest(BaseModel):
    message: str
    agent: str = "claude"
    max_hops: int = 5

def team_prompt(agent: str, task: str, steps: list) -> str:
    roster = "\n".join(f"- {a}: {r}" for a, r in TEAM_ROLES.items() if a != agent)
    history = "\n\n".join(f"[{s['agent']}]\n{s['output']}" for s in steps)
    return (
        f"You are '{agent}' on {owner.NAME}'s Agentic OS team. Teammates:\n{roster}\n\n"
        "Do your part of the task. If a teammate should continue, end with exactly one line:\n"
        "HANDOFF: <teammate>: <what they should do>\n"
        "Leave that line out when the task is finished. Never send anything outside this OS yourself.\n\n"
        + (f"Work so far:\n{history}\n\n" if history else "")
        + f"Your task: {task}"
    )

def save_thread(thread: dict):
    TEAM_DIR.mkdir(parents=True, exist_ok=True)
    (TEAM_DIR / f"{thread['id']}.json").write_text(json.dumps(thread, indent=2))

def run_team(thread: dict, max_hops: int):
    agent, task = thread["start_agent"], thread["message"]
    for _ in range(max_hops):
        thread["active"] = agent
        save_thread(thread)
        output = execute_agent(agent, team_prompt(agent, task, thread["steps"]))
        handoff = HANDOFF_RE.findall(output)
        nxt = handoff[-1] if handoff else None
        thread["steps"].append({"agent": agent, "task": task, "output": HANDOFF_RE.sub("", output).strip(),
                                "handoff_to": nxt[0].lower() if nxt else None, "timestamp": get_timestamp()})
        if not nxt or nxt[0].lower() not in TEAM_ROLES or nxt[0].lower() == agent:
            break
        agent, task = nxt[0].lower(), nxt[1].strip()
    thread["active"] = None
    thread["status"] = "done"
    save_thread(thread)
    append_audit({"action": "team_run", "thread": thread["id"], "hops": len(thread["steps"])})

@app.post("/api/team")
def start_team(req: TeamRequest):
    import threading
    agent = req.agent.lower().strip()
    if agent not in AGENTS:
        raise HTTPException(400, f"Agent must be one of: {', '.join(AGENTS)}")
    message = req.message.strip()
    if not message or len(message) > 10000:
        raise HTTPException(400, "Message must be 1-10000 characters")
    thread = {"id": str(uuid.uuid4())[:8], "message": message, "start_agent": agent,
              "status": "running", "active": agent, "steps": [], "created": get_timestamp()}
    save_thread(thread)
    threading.Thread(target=run_team, args=(thread, max(1, min(req.max_hops, 5))), daemon=True).start()
    return thread

@app.get("/api/team")
def list_team(limit: int = Query(30, le=200)):
    if not TEAM_DIR.exists():
        return {"threads": []}
    files = sorted(TEAM_DIR.glob("*.json"), key=lambda f: f.stat().st_mtime, reverse=True)[:limit]
    return {"threads": [json.loads(f.read_text()) for f in files]}

@app.get("/api/team/{thread_id}")
def get_team(thread_id: str):
    validate_identifier(thread_id, r"^[a-f0-9]{8}$", "thread id")
    f = TEAM_DIR / f"{thread_id}.json"
    if not f.exists():
        raise HTTPException(404, "Thread not found")
    return json.loads(f.read_text())

@app.get("/api/chat/history")
def get_chat_history(q: str = Query(""), agent: str = Query(""), limit: int = Query(200, le=1000)):
    """Chat history with optional search/filter (v4.0)."""
    history = load_chat_history()
    messages = history.get("messages", [])
    if q:
        ql = q.lower()
        messages = [m for m in messages if ql in m.get("content", "").lower()]
    if agent:
        messages = [m for m in messages if m.get("agent") == agent]
    if limit:
        messages = messages[-limit:]
    return {"messages": messages, "total": len(messages), "query": q, "agent": agent}

# ─── Routes: Chat File Attachments (v4.0) ─────────────────────────

UPLOAD_DIR = BASE_DIR / "data" / "uploads"
UPLOAD_MAX_BYTES = 2 * 1024 * 1024  # 2 MB
UPLOAD_TTL_HOURS = 24
ALLOWED_UPLOAD_EXTENSIONS = {
    ".txt", ".md", ".log", ".json", ".yml", ".yaml", ".csv", ".py",
    ".js", ".ts", ".sh", ".toml", ".ini", ".env", ".cfg", ".xml",
    ".html", ".css", ".go", ".rs", ".sql", ".tsx", ".jsx",
}

def _cleanup_uploads(force: bool = False):
    """Delete upload files older than the TTL (24h)."""
    if not UPLOAD_DIR.exists():
        return
    now = time.time()
    for f in UPLOAD_DIR.glob("*"):
        try:
            if force or now - f.stat().st_mtime > UPLOAD_TTL_HOURS * 3600:
                f.unlink(missing_ok=True)
        except OSError:
            pass

@app.post("/api/chat/upload")
async def chat_upload(
    agent: str = Form(...),
    message: str = Form(""),
    file: UploadFile = File(...),
):
    """Chat with an optional file attachment (multipart/form-data, v4.0)."""
    agent = agent.lower().strip()
    if agent not in AGENTS:
        raise HTTPException(400, f"Agent must be one of: {', '.join(AGENTS)}")

    raw = await file.read(UPLOAD_MAX_BYTES + 1)
    if len(raw) > UPLOAD_MAX_BYTES:
        raise HTTPException(413, "File too large (max 2 MB)")
    filename = (file.filename or "attachment.txt").strip().replace("\\", "/").split("/")[-1]
    if not re.match(r"^[A-Za-z0-9._-]+$", filename):
        raise HTTPException(400, "Invalid file name")
    ext = Path(filename).suffix.lower()
    if ext not in ALLOWED_UPLOAD_EXTENSIONS:
        raise HTTPException(400, f"File type .{ext} not allowed")

    _cleanup_uploads()
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    safe_name = f"{uuid.uuid4().hex[:8]}_{filename}"
    upload_path = UPLOAD_DIR / safe_name
    upload_path.write_bytes(raw)
    append_audit({"action": "chat_upload", "file": filename, "size": len(raw)})

    # Prepend file content to the message so the agent can read it
    try:
        text = raw.decode("utf-8", errors="replace")[:50000]
    except Exception:
        text = "[binary file — content not readable as text]"
    attachment_block = f"--- File: {filename} ---\n{text}\n--- End {filename} ---"
    message = (message or "").strip()
    full_message = f"{attachment_block}\n\n{message}" if message else attachment_block

    # Reuse the standard chat flow
    user_msg = {
        "id": str(uuid.uuid4())[:8],
        "role": "user",
        "agent": agent,
        "content": full_message,
        "timestamp": get_timestamp(),
    }
    save_chat_message(user_msg)
    response_text = execute_agent(agent, full_message)
    agent_msg = {
        "id": str(uuid.uuid4())[:8],
        "role": "assistant",
        "agent": agent,
        "content": response_text,
        "timestamp": get_timestamp(),
    }
    save_chat_message(agent_msg)
    append_audit({"action": "chat_message", "agent": agent, "msg_preview": (message or filename)[:50]})
    return {"status": "ok", "response": agent_msg, "file": filename, "saved_as": safe_name}

# ═══════════════════════════════════════════════════════════════════
# v2.0 — New Feature Endpoints
# ═══════════════════════════════════════════════════════════════════

# ─── Models ─────────────────────────────────────────────────────

class KanbanTaskCreate(BaseModel):
    title: str
    body: str = ""
    status: str = "triage"
    priority: str = "medium"
    assignee: str = ""

class KanbanTaskUpdate(BaseModel):
    title: Optional[str] = None
    body: Optional[str] = None
    status: Optional[str] = None
    priority: Optional[str] = None
    assignee: Optional[str] = None

class KanbanComplete(BaseModel):
    summary: str = ""

class KanbanBlock(BaseModel):
    reason: str = ""

class KanbanCommentCreate(BaseModel):
    message: str

class KanbanLinkCreate(BaseModel):
    parent_id: str
    child_id: str

class GoalCreate(BaseModel):
    title: str
    description: str = ""
    category: str = "general"
    target_date: str = ""

class GoalUpdate(BaseModel):
    title: Optional[str] = None
    description: Optional[str] = None
    category: Optional[str] = None
    target_date: Optional[str] = None
    progress: Optional[int] = None
    status: Optional[str] = None

class JournalSave(BaseModel):
    content: str

class RouterSuggest(BaseModel):
    task: str

class RouterRoute(BaseModel):
    task: str
    agent: str

# ─── Data Helpers ───────────────────────────────────────────────

KANBAN_DIR = BASE_DIR / "data" / "kanban"
GOALS_FILE = BASE_DIR / "data" / "goals.json"
JOURNAL_DIR = BASE_DIR / "brain" / "journal"

def ensure_dir(d: Path):
    d.mkdir(parents=True, exist_ok=True)

def load_kanban_tasks():
    ensure_dir(KANBAN_DIR)
    tasks = []
    for f in sorted(KANBAN_DIR.glob("*.json")):
        tasks.append(json.loads(f.read_text()))
    return tasks

def save_kanban_task(task: dict):
    ensure_dir(KANBAN_DIR)
    validate_identifier(str(task["id"]), r"^[a-zA-Z0-9_-]+$", "task id")
    (KANBAN_DIR / f"{task['id']}.json").write_text(json.dumps(task, indent=2))

def load_goals():
    if GOALS_FILE.exists():
        return json.loads(GOALS_FILE.read_text())
    return []

def save_goals(goals: list):
    GOALS_FILE.write_text(json.dumps(goals, indent=2))

# ─── Routes: Kanban Board (13 endpoints) ────────────────────────

@app.get("/api/kanban/board")
def kanban_board(status: Optional[str] = None):
    try:
        tasks = load_kanban_tasks()
        if status:
            tasks = [t for t in tasks if t.get("status") == status]
        columns = {"triage": [], "todo": [], "ready": [], "in_progress": [], "blocked": [], "done": []}
        for t in tasks:
            s = t.get("status", "triage")
            if s in columns:
                columns[s].append(t)
        return {"columns": columns, "total": len(tasks)}
    except Exception as e:
        return {"error": str(e), "columns": {}, "total": 0}

@app.get("/api/kanban/tasks/{task_id}")
def kanban_get_task(task_id: str):
    validate_identifier(task_id, r"^[a-zA-Z0-9_-]+$", "task id")
    path = KANBAN_DIR / f"{task_id}.json"
    if not path.exists():
        raise HTTPException(404, "Task not found")
    return json.loads(path.read_text())

@app.post("/api/kanban/tasks")
def kanban_create_task(data: KanbanTaskCreate):
    try:
        task = {
            "id": str(uuid.uuid4())[:8],
            "title": data.title,
            "body": data.body,
            "status": data.status,
            "priority": data.priority,
            "assignee": data.assignee,
            "comments": [],
            "links": [],
            "created": get_timestamp(),
            "updated": get_timestamp(),
        }
        save_kanban_task(task)
        append_audit({"action": "kanban_task_created", "title": data.title})
        return task
    except Exception as e:
        raise HTTPException(500, str(e))

@app.patch("/api/kanban/tasks/{task_id}")
def kanban_update_task(task_id: str, data: KanbanTaskUpdate):
    validate_identifier(task_id, r"^[a-zA-Z0-9_-]+$", "task id")
    path = KANBAN_DIR / f"{task_id}.json"
    if not path.exists():
        raise HTTPException(404, "Task not found")
    task = json.loads(path.read_text())
    for field in ["title", "body", "status", "priority", "assignee"]:
        val = getattr(data, field, None)
        if val is not None:
            task[field] = val
    task["updated"] = get_timestamp()
    save_kanban_task(task)
    append_audit({"action": "kanban_task_updated", "task_id": task_id})
    return task

@app.post("/api/kanban/tasks/{task_id}/complete")
def kanban_complete_task(task_id: str, data: KanbanComplete):
    validate_identifier(task_id, r"^[a-zA-Z0-9_-]+$", "task id")
    path = KANBAN_DIR / f"{task_id}.json"
    if not path.exists():
        raise HTTPException(404, "Task not found")
    task = json.loads(path.read_text())
    task["status"] = "done"
    task["summary"] = data.summary
    task["completed_at"] = get_timestamp()
    task["updated"] = get_timestamp()
    save_kanban_task(task)
    append_audit({"action": "kanban_task_completed", "task_id": task_id})
    return task

@app.post("/api/kanban/tasks/{task_id}/block")
def kanban_block_task(task_id: str, data: KanbanBlock):
    validate_identifier(task_id, r"^[a-zA-Z0-9_-]+$", "task id")
    path = KANBAN_DIR / f"{task_id}.json"
    if not path.exists():
        raise HTTPException(404, "Task not found")
    task = json.loads(path.read_text())
    task["status"] = "blocked"
    task["block_reason"] = data.reason
    task["updated"] = get_timestamp()
    save_kanban_task(task)
    append_audit({"action": "kanban_task_blocked", "task_id": task_id})
    return task

@app.post("/api/kanban/tasks/{task_id}/unblock")
def kanban_unblock_task(task_id: str):
    validate_identifier(task_id, r"^[a-zA-Z0-9_-]+$", "task id")
    path = KANBAN_DIR / f"{task_id}.json"
    if not path.exists():
        raise HTTPException(404, "Task not found")
    task = json.loads(path.read_text())
    task["status"] = "ready"
    task["block_reason"] = ""
    task["updated"] = get_timestamp()
    save_kanban_task(task)
    append_audit({"action": "kanban_task_unblocked", "task_id": task_id})
    return task

@app.post("/api/kanban/tasks/{task_id}/comments")
def kanban_add_comment(task_id: str, data: KanbanCommentCreate):
    validate_identifier(task_id, r"^[a-zA-Z0-9_-]+$", "task id")
    path = KANBAN_DIR / f"{task_id}.json"
    if not path.exists():
        raise HTTPException(404, "Task not found")
    task = json.loads(path.read_text())
    comment = {
        "id": str(uuid.uuid4())[:8],
        "message": data.message,
        "timestamp": get_timestamp(),
    }
    task.setdefault("comments", []).append(comment)
    task["updated"] = get_timestamp()
    save_kanban_task(task)
    return task

@app.post("/api/kanban/links")
def kanban_add_link(data: KanbanLinkCreate):
    for tid in [data.parent_id, data.child_id]:
        validate_identifier(tid, r"^[a-zA-Z0-9_-]+$", "task id")
        path = KANBAN_DIR / f"{tid}.json"
        if not path.exists():
            raise HTTPException(404, f"Task {tid} not found")
        t = json.loads(path.read_text())
        t.setdefault("links", [])
        link = {"parent": data.parent_id, "child": data.child_id}
        if link not in t["links"]:
            t["links"].append(link)
        t["updated"] = get_timestamp()
        save_kanban_task(t)
    append_audit({"action": "kanban_link_added", "parent": data.parent_id, "child": data.child_id})
    return {"status": "linked"}

@app.delete("/api/kanban/links")
def kanban_remove_link(parent_id: str = Query(...), child_id: str = Query(...)):
    for tid in [parent_id, child_id]:
        validate_identifier(tid, r"^[a-zA-Z0-9_-]+$", "task id")
        path = KANBAN_DIR / f"{tid}.json"
        if path.exists():
            t = json.loads(path.read_text())
            t.setdefault("links", [])
            t["links"] = [l for l in t["links"] if not (l.get("parent") == parent_id and l.get("child") == child_id)]
            t["updated"] = get_timestamp()
            save_kanban_task(t)
    return {"status": "unlinked"}

@app.post("/api/kanban/dispatch")
def kanban_dispatch():
    append_audit({"action": "kanban_dispatch_triggered"})
    return {"status": "dispatch_triggered", "message": "Dispatcher notified"}

@app.post("/api/kanban/tasks/{task_id}/specify")
def kanban_specify_task(task_id: str):
    validate_identifier(task_id, r"^[a-zA-Z0-9_-]+$", "task id")
    path = KANBAN_DIR / f"{task_id}.json"
    if not path.exists():
        raise HTTPException(404, "Task not found")
    task = json.loads(path.read_text())
    if task.get("status") == "triage":
        task["status"] = "todo"
        task["updated"] = get_timestamp()
        save_kanban_task(task)
    return task

@app.post("/api/kanban/tasks/{task_id}/decompose")
def kanban_decompose_task(task_id: str):
    validate_identifier(task_id, r"^[a-zA-Z0-9_-]+$", "task id")
    path = KANBAN_DIR / f"{task_id}.json"
    if not path.exists():
        raise HTTPException(404, "Task not found")
    task = json.loads(path.read_text())
    children = []
    for i, subtask in enumerate(task.get("body", "").split("\n")):
        subtask = subtask.strip().lstrip("-* ")
        if subtask:
            child = {
                "id": str(uuid.uuid4())[:8],
                "title": subtask[:80],
                "body": subtask,
                "status": "todo",
                "priority": task.get("priority", "medium"),
                "assignee": "",
                "comments": [],
                "links": [{"parent": task_id, "child": ""}],
                "created": get_timestamp(),
                "updated": get_timestamp(),
            }
            child["links"][0]["child"] = child["id"]
            save_kanban_task(child)
            children.append(child)
    return {"parent": task_id, "children": children}

# ─── Routes: Goals (4 endpoints) ─────────────────────────────────

@app.get("/api/goals")
def list_goals():
    try:
        return {"goals": load_goals()}
    except Exception as e:
        return {"goals": [], "error": str(e)}

@app.post("/api/goals")
def create_goal(data: GoalCreate):
    try:
        goals = load_goals()
        goal = {
            "id": str(uuid.uuid4())[:8],
            "title": data.title,
            "description": data.description,
            "category": data.category,
            "target_date": data.target_date,
            "status": "active",
            "progress": 0,
            "created": get_timestamp(),
            "updated": get_timestamp(),
        }
        goals.append(goal)
        save_goals(goals)
        # Auto-sync to brain/active-projects.md
        active_path = BASE_DIR / "brain" / "active-projects.md"
        if active_path.exists():
            existing = active_path.read_text()
            existing += f"\n- [{goal['title']}](goal:{goal['id']}) — {goal['description'][:80]}\n"
            active_path.write_text(existing)
        append_audit({"action": "goal_created", "title": data.title})
        return goal
    except Exception as e:
        raise HTTPException(500, str(e))

@app.put("/api/goals/{goal_id}")
def update_goal(goal_id: str, data: GoalUpdate):
    try:
        goals = load_goals()
        for g in goals:
            if g["id"] == goal_id:
                for field in ["title", "description", "category", "target_date", "progress", "status"]:
                    val = getattr(data, field, None)
                    if val is not None:
                        g[field] = val
                g["updated"] = get_timestamp()
                save_goals(goals)
                return g
        raise HTTPException(404, "Goal not found")
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))

@app.delete("/api/goals/{goal_id}")
def delete_goal(goal_id: str):
    try:
        goals = load_goals()
        goals = [g for g in goals if g["id"] != goal_id]
        save_goals(goals)
        append_audit({"action": "goal_deleted", "goal_id": goal_id})
        return {"status": "deleted"}
    except Exception as e:
        raise HTTPException(500, str(e))

# ─── Routes: Journal (4 endpoints) ───────────────────────────────

@app.get("/api/journal/entries")
def list_journal_entries():
    try:
        ensure_dir(JOURNAL_DIR)
        entries = []
        for f in sorted(JOURNAL_DIR.glob("*.md"), reverse=True):
            entries.append({
                "date": f.stem,
                "preview": f.read_text()[:200],
                "modified": datetime.fromtimestamp(f.stat().st_mtime).isoformat(),
            })
        return {"entries": entries}
    except Exception as e:
        return {"entries": [], "error": str(e)}

@app.get("/api/journal/entries/{entry_date}")
def get_journal_entry(entry_date: str):
    validate_identifier(entry_date, r"^\d{4}-\d{2}-\d{2}$", "date")
    try:
        path = JOURNAL_DIR / f"{entry_date}.md"
        ensure_dir(JOURNAL_DIR)
        content = path.read_text() if path.exists() else ""
        return {"date": entry_date, "content": content}
    except Exception as e:
        return {"date": entry_date, "content": "", "error": str(e)}

@app.put("/api/journal/entries/{entry_date}")
def save_journal_entry(entry_date: str, data: JournalSave):
    validate_identifier(entry_date, r"^\d{4}-\d{2}-\d{2}$", "date")
    try:
        ensure_dir(JOURNAL_DIR)
        path = JOURNAL_DIR / f"{entry_date}.md"
        path.write_text(data.content)
        append_audit({"action": "journal_saved", "date": entry_date})
        return {"status": "saved", "date": entry_date}
    except Exception as e:
        raise HTTPException(500, str(e))

@app.get("/api/journal/search")
def search_journal(q: str = Query("")):
    try:
        ensure_dir(JOURNAL_DIR)
        if not q:
            return {"results": []}
        results = []
        for f in JOURNAL_DIR.glob("*.md"):
            content = f.read_text()
            if q.lower() in content.lower():
                results.append({"date": f.stem, "preview": content[:200]})
        return {"results": results, "query": q}
    except Exception as e:
        return {"results": [], "error": str(e)}

# ─── Routes: Agent Health (3 endpoints) ──────────────────────────

@app.get("/api/agents/health")
def get_agent_health():
    try:
        agents = []
        for name in AGENTS:
            info = check_agent(name)
            info["uptime"] = 0
            info["success_rate"] = 100
            info["last_seen"] = get_timestamp()
            agents.append(info)
        return {"agents": agents, "updated": get_timestamp()}
    except Exception as e:
        return {"agents": [], "error": str(e), "updated": get_timestamp()}

@app.get("/api/agents/{name}/stats")
def get_agent_stats(name: str):
    try:
        if name not in AGENTS:
            raise HTTPException(400, "Invalid agent")
        info = check_agent(name)
        return {
            "name": name,
            "status": info["status"],
            "total_runs": 0,
            "successful_runs": 0,
            "failed_runs": 0,
            "avg_response_time": 0,
            "last_seen": get_timestamp(),
        }
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))

@app.post("/api/agents/health/refresh")
def refresh_agent_health():
    try:
        agents = []
        for name in AGENTS:
            info = check_agent(name)
            agents.append(info)
        append_audit({"action": "agent_health_refreshed"})
        return {"agents": agents, "updated": get_timestamp()}
    except Exception as e:
        return {"agents": [], "error": str(e)}

# ─── Routes: Smart Router (2 endpoints) ─────────────────────────

ROUTER_RULES = {
    "opencode": ["code", "devops", "deploy", "git", "file", "terraform", "docker", "test", "build", "infra", "script"],
    "hermes": ["memory", "schedule", "channel", "skill", "cron", "reminder", "brain", "plugin", "backup"],
    "agy": ["research", "analyze", "search", "compare", "explain", "study", "learn", "document", "report", "review"],
}

@app.post("/api/router/suggest")
def router_suggest(data: RouterSuggest):
    try:
        task_lower = data.task.lower()
        scores = {}
        for agent, keywords in ROUTER_RULES.items():
            scores[agent] = sum(1 for k in keywords if k in task_lower)
        best = max(scores, key=scores.get)
        confidence = "high" if scores[best] >= 2 else "medium" if scores[best] == 1 else "low"
        return {
            "suggested_agent": best,
            "confidence": confidence,
            "scores": scores,
            "task": data.task,
        }
    except Exception as e:
        return {"suggested_agent": "opencode", "confidence": "low", "error": str(e)}

@app.post("/api/router/route")
def router_route(data: RouterRoute):
    try:
        agent = data.agent.lower()
        if agent not in AGENTS:
            return {"status": "error", "message": f"Invalid agent: {agent}"}
        append_audit({"action": "task_routed", "agent": agent, "task_preview": data.task[:50]})
        return {
            "status": "routed",
            "agent": agent,
            "task": data.task,
            "message": f"Task routed to {agent}",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}

# ─── Routes: Learning Analytics (2 endpoints) ───────────────────

@app.get("/api/analytics/skills")
def get_skill_analytics():
    try:
        skills_dir = BASE_DIR / "skills"
        analytics = []
        for d in sorted(skills_dir.iterdir()):
            if d.is_dir() and not d.name.startswith("_"):
                eval_path = d / "eval.json"
                score_path = d / "score-history.json"
                scores = json.loads(score_path.read_text()) if score_path.exists() else []
                eval_data = json.loads(eval_path.read_text()) if eval_path.exists() else {}
                avg_score = sum(s.get("score", 0) for s in scores) / len(scores) if scores else 0
                analytics.append({
                    "name": d.name,
                    "total_runs": len(scores),
                    "avg_score": round(avg_score, 1),
                    "last_score": scores[-1].get("score", 0) if scores else 0,
                    "trend": "up" if len(scores) >= 2 and scores[-1].get("score", 0) > scores[-2].get("score", 0) else "down" if len(scores) >= 2 else "stable",
                })
        return {"skills": sorted(analytics, key=lambda x: x["total_runs"], reverse=True)}
    except Exception as e:
        return {"skills": [], "error": str(e)}

@app.get("/api/analytics/trends")
def get_trend_analytics():
    try:
        skills_dir = BASE_DIR / "skills"
        trends = []
        for d in sorted(skills_dir.iterdir()):
            if d.is_dir() and not d.name.startswith("_"):
                score_path = d / "score-history.json"
                scores = json.loads(score_path.read_text()) if score_path.exists() else []
                if scores:
                    trends.append({
                        "name": d.name,
                        "scores": [s.get("score", 0) for s in scores[-10:]],
                        "labels": [s.get("date", "") for s in scores[-10:]],
                    })
        return {"trends": trends}
    except Exception as e:
        return {"trends": [], "error": str(e)}

# ─── Routes: Session Replay (2 endpoints) ───────────────────────
# NOTE: GET /api/sessions/list lives next to the other /api/sessions
# routes on purpose — FastAPI matches routes in registration order, so a
# static "list" path must be registered before /api/sessions/{session_id}.

MAX_SESSION_CONTENT = 2000

@app.get("/api/sessions/{session_id}/replay")
def get_session_replay(session_id: str):
    if ".." in session_id or "/" in session_id:
        raise HTTPException(400, "Invalid session ID")
    try:
        sessions_dir = Path.home() / ".local" / "share" / "opencode"
        log_file = sessions_dir / "log" / f"{session_id}.log"
        if log_file.exists():
            content = log_file.read_text()
            lines = content.split("\n")
            messages = []
            for line in lines:
                if "user:" in line.lower() or "assistant:" in line.lower():
                    messages.append(line)
            return {
                "session_id": session_id,
                "lines": len(lines),
                "messages": messages[:50],
                "content": content[:MAX_SESSION_CONTENT],
            }
        return {"session_id": session_id, "messages": [], "content": "Session log not found"}
    except Exception as e:
        return {"session_id": session_id, "messages": [], "error": str(e)}

# ─── Routes: Code Diff Viewer (v4.0) ─────────────────────────────

DIFF_ALLOWED_PREFIXES = (
    "brain/", "skills/", "server.py", "scheduler/", "dashboard/",
    "prompts/", "standards/", "agents/", "data/", "registry/", "tests/",
)

@app.get("/api/diff")
def get_diff(file: str = Query(""), ref: str = Query("HEAD")):
    """Unified git diff for a file in the repo (v4.0)."""
    try:
        if not file:
            raise HTTPException(400, "Query parameter 'file' is required")
        # Prevent traversal — allow only repo-relative paths
        resolved = (BASE_DIR / file).resolve()
        if not str(resolved).startswith(str(BASE_DIR.resolve()) + os.sep) and resolved != BASE_DIR:
            raise HTTPException(400, "Invalid file path")
        if not resolved.exists():
            raise HTTPException(404, "File not found")
        rel = str(resolved.relative_to(BASE_DIR))
        code, out, err = run_cli(["git", "-C", str(BASE_DIR), "diff", ref, "--", rel], timeout=10)
        if code == 0 and not out.strip():
            # no diff against ref — try working tree vs index
            return {"file": rel, "diff": "", "changed": False, "ref": ref}
        return {"file": rel, "diff": out or err, "changed": bool(out.strip()), "ref": ref}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))

# ─── Routes: Dashboard Static Files ──────────────────────────────

dashboard_dir = BASE_DIR / "dashboard"
if dashboard_dir.exists():
    app.mount("/dashboard", StaticFiles(directory=str(dashboard_dir)), name="dashboard")

WEB_DIST = BASE_DIR / "web" / "dist"
if (WEB_DIST / "assets").exists():
    app.mount("/assets", StaticFiles(directory=str(WEB_DIST / "assets")), name="web-assets")
if (WEB_DIST / "agents").exists():
    app.mount("/agents", StaticFiles(directory=str(WEB_DIST / "agents")), name="web-agent-logos")

@app.get("/", response_class=HTMLResponse)
def web_index():
    # React dashboard (web/, built with `npm run build`); upstream's old dashboard only if it isn't built.
    if (WEB_DIST / "index.html").exists():
        return HTMLResponse((WEB_DIST / "index.html").read_text())
    return index()

def index():
    html_file = BASE_DIR / "dashboard" / "index.html"
    if html_file.exists():
        content = html_file.read_text()
        # Version-agnostic rewrite: handle any ?v= suffix (or none) so both
        # freshly-served and previously-cached index.html resolve correctly.
        content = re.sub(r'href="(styles\.css)(\?v=[0-9.]+)?"',
                         r'href="/dashboard/styles.css\2"', content)
        for name in ("utils.js", "api.js", "app.js"):
            content = re.sub(rf'src="{name}(?:\?v=[0-9.]+)?"',
                             rf'src="/dashboard/{name}"', content)
        content = content.replace('pages/', '/dashboard/pages/')
        return HTMLResponse(content=content)
    return HTMLResponse("<h1>Agentic OS</h1><p>Dashboard not built yet. Run <code>./install.sh</code> first.</p>")

# Root-level fallbacks so stale cached index.html (which references
# root-relative core assets) still resolves even when /dashboard rewrite
# hasn't been seen by the browser (v0.4.1).
for _asset in ("styles.css", "utils.js", "api.js", "app.js"):

    def _serve_asset(asset=_asset):
        f = BASE_DIR / "dashboard" / asset
        if not f.exists():
            raise HTTPException(404, "Not found")
        media = "text/css" if asset.endswith(".css") else "application/javascript"
        return Response(content=f.read_bytes(), media_type=media)

    app.add_api_route(f"/{_asset}", _serve_asset)

# ─── Favicon ──────────────────────────────────────────────────────

FAVICON_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><defs><linearGradient id="chrome" gradientUnits="userSpaceOnUse" x1="28" y1="28" x2="228" y2="228"><stop offset="0" stop-color="#5B6F86"/><stop offset=".55" stop-color="#1F2D44"/><stop offset="1" stop-color="#0E1726"/></linearGradient><linearGradient id="ice" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="256" y2="256"><stop offset="0" stop-color="#B9ECFF"/><stop offset="1" stop-color="#3FB6EC"/></linearGradient></defs><path d="M188.8 73.5A82 82 0 1 1 155 50.6" fill="none" stroke="url(#chrome)" stroke-width="18" stroke-linecap="round"/><circle cx="210" cy="128" r="12" fill="url(#chrome)"/><circle cx="128" cy="210" r="16" fill="url(#chrome)"/><circle cx="46" cy="128" r="20" fill="url(#chrome)"/><circle cx="198" cy="58" r="22" fill="url(#ice)"/></svg>'  # Orbit logo

@app.get("/favicon.ico")
def favicon():
    return Response(content=FAVICON_SVG, media_type="image/svg+xml")

@app.get("/logo.svg")
def logo_svg():
    return Response(content=FAVICON_SVG, media_type="image/svg+xml")

@app.get("/favicon.svg")
def favicon_svg():
    return Response(content=FAVICON_SVG, media_type="image/svg+xml")

# ─── PWA Support (v3.0) ──────────────────────────────────────────

MANIFEST_JSON = {
    "name": "Agentic OS",
    "short_name": "AgenticOS",
    "description": "Multi-agent orchestration platform",
    "start_url": "/",
    "display": "standalone",
    "background_color": "#E6EEF4",
    "theme_color": "#E6EEF4",
    "icons": [
        {"src": "/favicon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any maskable"},
    ],
}

@app.get("/manifest.json")
def manifest():
    return JSONResponse(content=MANIFEST_JSON)

SERVICE_WORKER_JS = """
self.addEventListener('install', (e) => {
  self.skipWaiting();
});
self.addEventListener('activate', (e) => {
  e.waitUntil(clients.claim());
});
self.addEventListener('fetch', (e) => {
  e.respondWith(fetch(e.request).catch(() => new Response('Offline', {status: 503})));
});
"""

@app.get("/sw.js")
def service_worker():
    return Response(content=SERVICE_WORKER_JS, media_type="application/javascript")

# ─── Main ─────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8810)
    parser.add_argument("--host", type=str, default="127.0.0.1")
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port)

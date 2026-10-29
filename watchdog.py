#!/usr/bin/env python3
"""Watchdog: brings the Agentic OS server, Hermes's gateway and VoiceStudio back when they die.

Runs hidden at login (Startup\\Agentic OS watchdog.vbs, pythonw). Checks every minute, restarts a service only
after it failed several checks in a row, and waits longer after each restart that didn't take. Does nothing at
all while D:\\Work\\focus.json exists (Jhon is live or recording). Log: data/watchdog.log (small, rotated).

    pythonw watchdog.py              run forever (one copy only)
    python watchdog.py --once        one check, then exit
    python watchdog.py --dry-run     log what it would do, restart nothing (add --once for a single pass)
"""
import argparse
import json
import logging
import logging.handlers
import msvcrt
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import owner

BASE_DIR = Path(__file__).resolve().parent
LOG_FILE = BASE_DIR / "data" / "watchdog.log"
LOCK_FILE = BASE_DIR / "data" / "watchdog.lock"
FOCUS_FLAG = Path(r"D:\Work\focus.json")  # the Prompter's live/recording flag: hands off the PC while it exists
HERMES_HOME = Path(os.environ.get("HERMES_HOME") or owner.OWNER.get("hermes_home") or str(Path.home() / ".hermes"))
VOICESTUDIO = Path.home() / "AppData" / "Local" / "Programs" / "VoiceStudio" / "VoiceStudio.exe"
NO_WINDOW = subprocess.CREATE_NO_WINDOW  # pythonw has no console, so every child would otherwise open a terminal window

EVERY = 60          # seconds between checks
MAX_WAIT = 3600     # backoff ceiling: never more than one restart an hour per service
STABLE = 1800       # up this long and earlier failed restarts are forgiven (a flapping service keeps its backoff)
# name: (checks down in a row before acting, grace after a restart in seconds; doubles after each one that didn't take)
RULES = {
    "os": (3, 300),       # start.ps1 can take 1-2 min (web rebuild); 5 min before judging it
    "hermes": (3, 180),   # the gateway answers within a minute (server.py waits 60 s for it)
    "voice": (10, 900),   # 503 while loading models on start; only a long outage is the dead-engine fault
}

log = logging.getLogger("watchdog")


# ─── Probes ──────────────────────────────────────────────────────────────────────

def http_get(url: str) -> tuple[int, bytes]:
    """(status, body); status 0 = nothing answered."""
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            return r.status, r.read(4096)
    except urllib.error.HTTPError as e:
        return e.code, b""
    except OSError:
        return 0, b""


def os_up() -> bool:
    return http_get("http://127.0.0.1:8810/logo.svg")[0] == 200


def hermes_up() -> bool:
    return http_get("http://127.0.0.1:8642/v1/models")[0] in (200, 401)  # 401 = up, just wants its key


def voice_up() -> bool:
    code, body = http_get("http://127.0.0.1:3900/health")
    try:
        return code == 200 and json.loads(body).get("status") == "ok"
    except ValueError:
        return False


def running(pattern: str) -> bool:
    """Any process whose command line matches the regex (PowerShell: Windows 11 no longer ships wmic)."""
    ps = "@(Get-CimInstance Win32_Process | ? { $_.ProcessId -ne $PID -and $_.CommandLine -match $env:WATCH_PATTERN }).Count"
    # The pattern goes in through the environment: PowerShell's command-line parsing mangles backslashes.
    out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True, timeout=60,
                         env={**os.environ, "WATCH_PATTERN": pattern}, creationflags=NO_WINDOW).stdout.strip()
    return out not in ("", "0")


# ─── Restarts ────────────────────────────────────────────────────────────────────

def start_os():
    """Same command as Startup\\Agentic OS server.vbs."""
    subprocess.Popen([shutil.which("pwsh") or "pwsh", "-NoProfile", "-WindowStyle", "Hidden", "-File", str(BASE_DIR / "start.ps1")],
                     cwd=BASE_DIR, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     creationflags=NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)


def start_hermes():
    """Same command as hermes_gateway_ensure in server.py."""
    out = open(HERMES_HOME / "logs" / "gateway-os.log", "ab")
    exe = shutil.which("hermes") or str(Path.home() / "AppData" / "Local" / "hermes" / "bin" / "hermes.exe")
    subprocess.Popen([exe, "gateway", "run"], cwd=HERMES_HOME, stdin=subprocess.DEVNULL, stdout=out, stderr=out,
                     env={**os.environ, "HERMES_HOME": str(HERMES_HOME)},
                     creationflags=NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)


def restart_voice():
    """The known fault: window open, engine dead. Close the whole app (engine included), open it again."""
    subprocess.run(["taskkill", "/IM", "VoiceStudio.exe", "/T", "/F"], capture_output=True, creationflags=NO_WINDOW)
    time.sleep(5)
    subprocess.Popen(["explorer.exe", str(VOICESTUDIO)])  # through the shell: a normal GUI start, outside any app container


# ─── Decision ────────────────────────────────────────────────────────────────────

def new_state() -> dict:
    return {"fails": 0, "tries": 0, "until": 0.0, "up_since": None}


def decide(s: dict, up: bool, now: float, need: int, grace: float) -> str:
    """One check of one service. Returns 'up', 'back' (up again after an outage), 'down' or 'restart'."""
    if up:
        back = s["fails"] >= need
        s["fails"] = 0
        s["up_since"] = s["up_since"] or now
        if now - s["up_since"] >= STABLE:
            s["tries"] = 0
        return "back" if back else "up"
    s["fails"] += 1
    s["up_since"] = None
    if s["fails"] < need or now < s["until"]:
        return "down"
    s["tries"] += 1
    s["until"] = now + min(grace * 2 ** (s["tries"] - 1), MAX_WAIT)
    return "restart"


# ─── Loop ────────────────────────────────────────────────────────────────────────

SERVICES = {
    "os": (os_up, start_os),
    "hermes": (hermes_up, start_hermes),
    "voice": (voice_up, restart_voice),
}


def check(states: dict, dry: bool, verbose: bool):
    now = time.time()
    status = {}
    for name, (probe, restart) in SERVICES.items():
        if name == "hermes" and not status.get("os"):
            continue  # the OS server starts Hermes itself when it comes up
        up = probe()
        status[name] = up
        need, grace = RULES[name]
        s = states[name]
        verdict = decide(s, up, now, need, grace)
        if verbose:
            log.info(f"{name}: {'up' if up else 'down'} ({verdict}, {s['fails']} failed checks)")
        if verdict == "back":
            log.info(f"{name}: back up")
        elif verdict == "down" and s["fails"] == need:
            log.info(f"{name}: down {need} checks in a row; still waiting after the last restart")
        elif verdict == "restart":
            # Guards that cost a process list: only asked when a restart is due.
            if name == "os" and running(r"agentic-os\\start\.ps1|server\.py --port 8810"):
                s["tries"] -= 1  # not a failed restart, just not ours to start
                log.info("os: not answering but start.ps1/server.py is running; not starting a second copy")
                continue
            if name == "voice" and not running(r"VoiceStudio\\VoiceStudio\.exe"):
                s["tries"] -= 1
                log.info("voice: app is closed; left alone (only a dead engine behind an open window gets restarted)")
                continue
            wait = int(s["until"] - now) // 60
            if dry:
                log.info(f"{name}: DRY RUN would restart (attempt {s['tries']}, next allowed in {wait} min)")
                continue
            log.info(f"{name}: down {s['fails']} checks; restarting (attempt {s['tries']}, next allowed in {wait} min)")
            try:
                restart()
            except Exception as e:  # noqa: BLE001 - log it and try again after the backoff
                log.info(f"{name}: restart failed: {e}")


def setup_log():
    LOG_FILE.parent.mkdir(exist_ok=True)
    fmt = logging.Formatter("%(asctime)s %(message)s", "%Y-%m-%d %H:%M:%S")
    fh = logging.handlers.RotatingFileHandler(LOG_FILE, maxBytes=200_000, backupCount=1, encoding="utf-8")
    fh.setFormatter(fmt)
    log.addHandler(fh)
    if sys.stderr:  # None under pythonw
        sh = logging.StreamHandler()
        sh.setFormatter(fmt)
        log.addHandler(sh)
    log.setLevel(logging.INFO)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--once", action="store_true", help="one check, then exit")
    ap.add_argument("--dry-run", action="store_true", help="log what it would do, restart nothing")
    args = ap.parse_args()
    setup_log()
    if not args.dry_run:
        lock = open(LOCK_FILE, "a")  # held open for the life of the process; Windows drops the lock when it exits
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            log.info("another watchdog is already running; exiting")
            return 0
    log.info(f"watchdog started{' (dry run)' if args.dry_run else ''}{' (once)' if args.once else ''}")
    states = {name: new_state() for name in SERVICES}
    paused = False
    while True:
        if FOCUS_FLAG.exists():
            if not paused:
                log.info(f"focus mode on ({FOCUS_FLAG}); paused, touching nothing")
            paused = True
        else:
            if paused:
                log.info("focus mode off; watching again")
                states = {name: new_state() for name in SERVICES}  # outages seen before the pause don't count
            paused = False
            try:
                check(states, args.dry_run, verbose=args.dry_run or args.once)
            except Exception as e:  # noqa: BLE001 - the watchdog itself must not die
                log.info(f"check failed: {e}")
        if args.once:
            return 0
        time.sleep(EVERY)


if __name__ == "__main__":
    sys.exit(main())

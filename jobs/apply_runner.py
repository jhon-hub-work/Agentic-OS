"""Submit one APPROVED application the way Hoot does. Run with system Python (Playwright lives there):

    python jobs/apply_runner.py job.json      -> prints one JSON line {"outcome": "submitted"|"handoff", ...}

Only ever called after the owner taps Approve. Reuses Hoot's form filler (hoot/apply.py, path in config/owner.json):
Greenhouse / Lever / Ashby forms in Hoot's Brave, never logins, CAPTCHAs, passwords or account creation; it
stops and hands the tab to the owner on anything only they can answer. Form answers come from Claude Code on their
subscription (Hoot's own answerer used OpenCode Go, which is gone).
"""

import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import owner  # noqa: E402

HOOT = owner.path("hoot_dir") or Path("hoot")
sys.path.insert(0, str(HOOT))
RESUME = owner.path("resume_pdf") or Path("resume.pdf")
ATS = re.compile(r"https?://(?:boards|job-boards)\.greenhouse\.io/[^\s\"'<>]+|https?://jobs\.lever\.co/[^\s\"'<>]+"
                 r"|https?://jobs\.ashbyhq\.com/[^\s\"'<>]+")


def ats_url(url: str) -> str:
    """The posting's own Greenhouse/Lever/Ashby form, or ''. Boards like Himalayas link out to it."""
    if ATS.match(url):
        return url
    # end-to-end test only: a local copy of a form, never set in normal use
    if os.environ.get("APPLY_ALLOW_LOCAL") == "1" and url.startswith("file:") and "greenhouse.io" in url:
        return url
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (personal job search)"})
        html = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
    except OSError:
        return ""
    m = ATS.search(html.replace("\\/", "/"))
    return m.group(0).rstrip(".,);") if m else ""


def claude_json(system: str, user: str) -> dict:
    env = {k: v for k, v in os.environ.items() if k not in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")}
    r = subprocess.run([shutil.which("claude") or "claude", "-p", "--model", "sonnet", "--tools", "",
                        "--strict-mcp-config", "--setting-sources", "", "--no-session-persistence",
                        "--output-format", "json", "--system-prompt", system],
                       input=user, capture_output=True, text=True, encoding="utf-8", timeout=600, env=env)
    text = json.loads(r.stdout or "{}").get("result", "")
    start, end = text.find("{"), text.rfind("}")
    return json.loads(text[start:end + 1]) if start >= 0 else {}


def main(path: str) -> dict:
    import apply as applier
    import hoot

    job = json.loads(Path(path).read_text(encoding="utf-8"))
    if hoot.is_olj(job["url"]):
        # OnlineJobs.ph: Hoot's Brave (the owner signed in) fills the reply and presses SEND EMAIL once.
        subject = f"Application: {job['title']}"[:120]
        why = hoot.sendable(subject, job.get("draft", ""))
        if why:
            return {"outcome": "handoff", "reason": f"not sent: {why}"}
        res = applier.send_olj(job["url"], subject, job["draft"])
        return {"outcome": "submitted" if res["outcome"] == "sent" else res["outcome"], **{k: v for k, v in res.items() if k != "outcome"}}
    form = ats_url(job["url"])
    if not form:
        return {"outcome": "handoff", "reason": "this site isn't a form the OS can fill (no Greenhouse/Lever/Ashby link); apply on the site"}

    def answer_fn(_job: dict, fields: list) -> dict:
        user = json.dumps({"role": job["title"], "company": job["company"], "job_location": job.get("location", ""),
                           "job_description": (job.get("jd") or "")[:6000], "cover_letter": job.get("draft", ""),
                           "profile": hoot.profile(), "cv_md": hoot.cv_text(),
                           "fields": [{k: f.get(k) for k in ("id", "type", "label", "required", "options")} for f in fields]},
                          ensure_ascii=False)
        answers = claude_json(hoot.ANSWER_SYSTEM, user)
        for a in answers.values():  # anything naming the auto-apply tool or the client is left for the owner
            if isinstance(a, dict) and hoot.leaks(str(a.get("value"))):
                a["value"], a["needs_confirmation"] = "", True
        return answers

    target = {"id": job["id"], "url": form, "title": job["title"], "company": job["company"],
              "location": job.get("location", ""), "cover_txt": job.get("draft", ""),
              "cv_pdf": str(RESUME) if RESUME.exists() else None}
    res = applier.apply_ats(target, hoot.profile(), answer_fn, hoot.form_blockers)
    res["form"] = form
    return res


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        assert ATS.match("https://jobs.lever.co/acme/123") and ATS.match("https://boards.greenhouse.io/acme/jobs/9")
        assert not ATS.match("https://himalayas.app/companies/x/jobs/y")
        print("apply_runner selftest ok")
    else:
        try:
            print(json.dumps(main(sys.argv[1])))
        except Exception as e:  # noqa: BLE001 - always answer the OS with one JSON line
            print(json.dumps({"outcome": "handoff", "reason": f"apply step failed: {type(e).__name__}: {e}"[:300]}))

"""Send one approved OnlineJobs reply for another person (jobs/people.py) from THEIR Brave profile, where they signed
in to OnlineJobs themselves. Same filler and guards as the owner's sends (Hoot's apply.send_olj). System Python:

    python jobs/person_send.py job.json   -> prints one JSON line {"outcome": "submitted"|"closed"|"handoff", ...}
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import owner  # noqa: E402

sys.path.insert(0, str(owner.path("hoot_dir") or "hoot"))


def main(path: str) -> dict:
    import apply as applier
    import hoot

    job = json.loads(Path(path).read_text(encoding="utf-8"))
    p = owner.OWNER["people"][job["person"]]
    if not hoot.is_olj(job["url"]):
        return {"outcome": "handoff", "reason": "only OnlineJobs replies are sent from here; apply on the site"}
    subject = f"Application: {job['title']}"[:120]
    why = hoot.sendable(subject, job.get("draft", ""))
    if why:
        return {"outcome": "handoff", "reason": f"not sent: {why}"}
    # Point Hoot's Brave helpers at this person's own profile (never the owner's, signed in as him).
    applier.CDP_PORT, applier.PROFILE = int(p["cdp_port"]), Path(p["brave_profile"])
    unfreeze = applier.unfreeze
    applier.unfreeze = lambda port=None: unfreeze(port or applier.CDP_PORT)   # its default is bound to the owner's port
    res = applier.send_olj(job["url"], subject, job["draft"])
    return {"outcome": "submitted" if res["outcome"] == "sent" else res["outcome"], **{k: v for k, v in res.items() if k != "outcome"}}


if __name__ == "__main__":
    try:
        print(json.dumps(main(sys.argv[1])))
    except Exception as e:  # noqa: BLE001 - always answer the OS with one JSON line
        print(json.dumps({"outcome": "handoff", "reason": f"send failed: {type(e).__name__}: {e}"[:300]}))

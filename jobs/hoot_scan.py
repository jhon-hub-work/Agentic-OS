"""Hoot's own job discovery (career-ops scan.mjs: OnlineJobs.ph searches + company Greenhouse/Lever/Ashby boards),
for the OS pipeline. These are the postings the OS can actually apply to. Run with system Python:

    python jobs/hoot_scan.py [max]     -> prints a JSON list of offers with jd and pay filled in

--dry-run: nothing is written to career-ops' tracker; the OS dedupes with its own seen list.
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import owner  # noqa: E402

sys.path.insert(0, str(owner.path("hoot_dir") or "hoot"))


def main(limit: int) -> list:
    import hoot
    import lanes

    offers = hoot.scan(True)
    # Titles first (cheap): only lanes Hoot works, best first, so the slow OnlineJobs page fetches stay few.
    offers = [o for o in offers if lanes.wanted(o)]
    offers.sort(key=hoot.priority, reverse=True)
    out = []
    for o in offers[:limit]:
        jd = hoot.fetch_jd(o)
        if jd:
            out.append({"source": "OnlineJobs.ph" if hoot.is_olj(o["url"]) else "company board", "url": o["url"],
                        "company": o.get("company", ""), "title": o.get("title", ""), "location": o.get("location", ""),
                        "jd": jd, "pay": o.get("pay", "")})
    return out


if __name__ == "__main__":
    print(json.dumps(main(int(sys.argv[1]) if len(sys.argv) > 1 else 25)))

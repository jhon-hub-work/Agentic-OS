"""OnlineJobs.ph bookkeeping postings for another person (jobs/people.py), with Hoot's people/bookkeeping filters:
about bookkeeping, not senior, not male-only, pay at or over the floor (unreadable pay is kept). Run with system Python:

    python jobs/person_scan.py <min_hourly_usd> <seen.json> [max]   -> prints a JSON list of offers with jd and pay
"""

import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import owner  # noqa: E402

HOOT = owner.path("hoot_dir") or Path("hoot")
sys.path[:0] = [str(HOOT / "people" / "bookkeeping"), str(HOOT)]


def main(floor: float, seen: set, limit: int) -> list:
    import hoot
    import leads_scan

    leads_scan.MIN_HOURLY_USD = floor          # judge() reads it at call time
    out = []
    for row in leads_scan.search():
        url = row.get("url") or ""
        if not url or hashlib.sha1(url.encode()).hexdigest()[:10] in seen or leads_scan.judge(row) is None:
            continue
        o = {"source": "OnlineJobs.ph", "url": url, "company": row.get("company") or "OnlineJobs.ph",
             "title": row.get("title") or "", "location": "Remote", "pay": leads_scan.facts(row.get("description"))[0]}
        o["jd"] = hoot.fetch_jd(o)             # also fills o["pay"] from the page's Wage / Salary line
        if o["jd"]:
            out.append(o)
        if len(out) >= limit:
            break
    return out


if __name__ == "__main__":
    print(json.dumps(main(float(sys.argv[1]), set(json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))),
                          int(sys.argv[3]) if len(sys.argv) > 3 else 25)))

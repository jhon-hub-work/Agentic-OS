"""Which OnlineJobs postings the employer has closed, read in Hoot's signed-in Brave (hoot/apply.py olj_closed).
Run with system Python (Playwright lives there):

    python jobs/olj_closed.py url [url ...]   -> prints a JSON list of the closed ones

Signed out of OnlineJobs, no page says "closed", so the list comes back empty and nothing is marked.
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import owner  # noqa: E402

sys.path.insert(0, str(owner.path("hoot_dir") or "hoot"))

if __name__ == "__main__":
    import apply
    print(json.dumps(sorted(apply.olj_closed(sys.argv[1:]))))

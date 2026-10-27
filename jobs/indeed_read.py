"""Read one Indeed job the owner pasted, in Hoot's Brave (where he is signed in to Indeed). Run with system Python:

    python jobs/indeed_read.py <indeed link>   -> one JSON line {url, title, text} or {error}

Only links the owner hands over, one page at a time: never search or crawl Indeed.
"""

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import owner  # noqa: E402

sys.path.insert(0, str(owner.path("hoot_dir") or "hoot"))


def main(link: str) -> dict:
    import apply as applier

    jk = applier.indeed_key(link)
    if not jk:
        return {"error": "not an Indeed job link (it needs jk= or vjk= in it)"}
    url = f"https://ph.indeed.com/viewjob?jk={jk}"
    title, text = applier.indeed_jd(url)
    if not text or "moment" in (title or "").lower():
        return {"error": "Indeed asked for a check: open the link once in Hoot's Brave, then try again"}
    return {"url": url, "title": re.sub(r"(\s+-\s+[^-]+)?\s+-\s+Indeed\.com$", "", title).strip(), "text": text[:20000]}


if __name__ == "__main__":
    try:
        print(json.dumps(main(sys.argv[1])))
    except Exception as e:  # noqa: BLE001 - always answer the OS with one JSON line
        print(json.dumps({"error": f"{type(e).__name__}: {e}"[:300]}))

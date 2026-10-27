"""Indeed job links from the job-alert emails Indeed sends the owner. Read over Gmail IMAP with an app password
the owner puts in config/gmail.json (gitignored): {"user": "...", "app_password": "..."}. Mails are only peeked,
so they stay unread. The OS never searches Indeed; it only follows links Indeed mailed him.
"""

import email
import imaplib
import json
import re
from datetime import date, timedelta
from pathlib import Path

CONF = Path(__file__).resolve().parent.parent / "config" / "gmail.json"
JK = re.compile(r"[?&;]v?jk=([0-9a-f]{16})")
DAYS = 2


def keys(body: str) -> list:
    """Unique Indeed job keys in an alert mail, in order (quoted-printable '=3D' is undone first)."""
    found = JK.findall(body.replace("=3D", "=").replace("&amp;", "&").replace("=\r\n", "").replace("=\n", ""))
    return list(dict.fromkeys(found))


def links() -> list:
    """View links of jobs in the last DAYS days of alert mails; [] when Gmail isn't set up."""
    if not CONF.exists():
        return []
    conf = json.loads(CONF.read_text(encoding="utf-8"))
    since = (date.today() - timedelta(days=DAYS)).strftime("%d-%b-%Y")
    out = []
    with imaplib.IMAP4_SSL("imap.gmail.com") as m:
        m.login(conf["user"], conf["app_password"])
        m.select("INBOX", readonly=True)
        _, ids = m.search(None, f'(FROM "indeed" SINCE {since})')
        for i in ids[0].split()[-20:]:
            _, data = m.fetch(i, "(BODY.PEEK[])")
            msg = email.message_from_bytes(data[0][1])
            parts = [p.get_payload(decode=True) or b"" for p in msg.walk() if p.get_content_type() in ("text/html", "text/plain")]
            out += keys(b"\n".join(parts).decode("utf-8", errors="replace"))
    return [f"https://ph.indeed.com/viewjob?jk={k}" for k in dict.fromkeys(out)]


if __name__ == "__main__":
    assert keys('<a href="https://ph.indeed.com/rc/clk/dl?jk=b8c7ff6b35e8ed77&amp;from=ja">x</a> '
                'https://ph.indeed.com/viewjob?jk=3D0dae7790f53de047 again jk=b8c7ff6b35e8ed77') == \
        ["b8c7ff6b35e8ed77", "0dae7790f53de047"]
    print("indeed_alerts selftest ok")

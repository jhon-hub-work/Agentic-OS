"""Indeed job links from the job-alert emails Indeed sends the owner. Read over Gmail IMAP with an app password
the owner puts in config/gmail.json (gitignored): {"user": "...", "app_password": "..."}. Mails are only peeked,
so they stay unread. The OS never searches Indeed; it only follows links Indeed mailed him.
"""

import base64
import email
import email.header
import gzip
import imaplib
import json
import re
from datetime import date, timedelta
from pathlib import Path

CONF = Path(__file__).resolve().parent.parent / "config" / "gmail.json"
SECRETS = CONF.with_name("secrets.json")  # gitignored; keys he typed on the Agents page
JK = re.compile(r"[?&;]v?jk=([0-9a-f]{16})")
DAYS = 2


CTS = re.compile(r"https://cts\.indeed\.com/v3/([A-Za-z0-9_-]+)")


def view_links(body: str) -> list:
    """Indeed's newer mails wrap each job in cts.indeed.com/v3/<gzip+base64 JSON>; the "viewjob" ones hold a
    pagead/clk link that only a real browser can follow (plain HTTP gets 403), so Hoot's Brave opens it."""
    out = []
    for blob in CTS.findall(body.replace("=\r\n", "").replace("=\n", "")):
        try:
            d = json.loads(gzip.decompress(base64.urlsafe_b64decode(blob + "=" * (-len(blob) % 4))))
        except (ValueError, OSError):
            continue
        if (d.get("m") or {}).get("clickType") == "viewjob" and "/pagead/clk" in d.get("u", ""):
            out.append(d["u"])
    return list(dict.fromkeys(out))


def keys(body: str) -> list:
    """Unique Indeed job keys in an alert mail, in order (quoted-printable '=3D' is undone first)."""
    found = JK.findall(body.replace("=3D", "=").replace("&amp;", "&").replace("=\r\n", "").replace("=\n", ""))
    return list(dict.fromkeys(found))


def _creds():
    """(user, app password) from config/gmail.json, else the GMAIL_USER / GMAIL_APP_PASSWORD keys he typed in the OS."""
    if CONF.exists():
        conf = json.loads(CONF.read_text(encoding="utf-8"))
        return conf["user"], conf["app_password"]
    sec = json.loads(SECRETS.read_text(encoding="utf-8")) if SECRETS.exists() else {}
    return sec.get("GMAIL_USER", ""), sec.get("GMAIL_APP_PASSWORD", "")


def mails(sender: str, days: int = DAYS, limit: int = 20) -> list:
    """(subject, body text) of the newest mails from sender in the last days, peeked so they stay unread; [] when
    Gmail isn't set up."""
    user, pw = _creds()
    if not (user and pw):
        return []
    since = (date.today() - timedelta(days=days)).strftime("%d-%b-%Y")
    out = []
    with imaplib.IMAP4_SSL("imap.gmail.com") as m:
        m.login(user, pw)
        m.select('"[Gmail]/All Mail"', readonly=True)  # his Indeed alerts skip the inbox (filtered/archived, 31 Oct)
        _, ids = m.search(None, f'(FROM "{sender}" SINCE {since})')
        for i in ids[0].split()[-limit:]:
            _, data = m.fetch(i, "(BODY.PEEK[])")
            msg = email.message_from_bytes(data[0][1])
            parts = [p.get_payload(decode=True) or b"" for p in msg.walk() if p.get_content_type() in ("text/html", "text/plain")]
            subject = str(email.header.make_header(email.header.decode_header(msg.get("Subject", ""))))
            out.append((subject, b"\n".join(parts).decode("utf-8", errors="replace")))
    return out


def links() -> list:
    """View links of jobs in the last DAYS days of alert mails; [] when Gmail isn't set up."""
    out = []
    for _, body in mails("indeed"):
        out += [f"https://ph.indeed.com/viewjob?jk={k}" for k in keys(body)] + view_links(body)
    return list(dict.fromkeys(out))


if __name__ == "__main__":
    assert keys('<a href="https://ph.indeed.com/rc/clk/dl?jk=b8c7ff6b35e8ed77&amp;from=ja">x</a> '
                'https://ph.indeed.com/viewjob?jk=3D0dae7790f53de047 again jk=b8c7ff6b35e8ed77') == \
        ["b8c7ff6b35e8ed77", "0dae7790f53de047"]
    wrap = lambda d: "https://cts.indeed.com/v3/" + base64.urlsafe_b64encode(gzip.compress(json.dumps(d).encode())).decode().rstrip("=")
    job = {"u": "https://ph.indeed.com/pagead/clk?from=x&jrtk=1", "m": {"clickType": "viewjob", "aggJobId": "1"}}
    other = {"u": "https://match.indeed.com/invitations/match-rating/1", "m": {"clickType": "feedback"}}
    assert view_links(f'<a href="{wrap(job)}">a</a> <a href="{wrap(other)}">b</a> {wrap(job)}') == [job["u"]]
    print("indeed_alerts selftest ok")

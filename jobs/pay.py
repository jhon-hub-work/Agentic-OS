# Adapted from Hoot (a job-application agent) for the Agentic OS job pipeline.
"""Hourly pay in USD for a posting, from what the board says about it.

The minimums come from config/owner.json. Only a clear figure under them rules a posting out;
pay that is missing, per video, per project, open ended ("$1140+") or with no hours to divide by
is kept (part time and project based work counts) and shown as unknown.

OnlineJobs.ph postings carry three header lines that make this reliable:
    Type Of Work: Full Time | Part Time | Gig
    Wage / Salary: $1800 - $2400 / month      (a bare number there is US dollars a month)
    Hours Per Week: 40
Company-board postings only have the loose 'pay' text Hoot stored.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from owner import OWNER  # noqa: E402

_PAY = OWNER.get("pay") or {}
MIN_HOURLY_USD = float(_PAY.get("min_hourly_onlinejobs_usd", 8))   # OnlineJobs.ph floor
MIN_HOURLY_OTHER_USD = float(_PAY.get("min_hourly_usd", 15))       # every other platform
WEEKS_PER_MONTH = 4.33
PHP_PER_USD = 58
FX = {"usd": 1.0, "gbp": 1.27, "eur": 1.08, "aud": 0.65, "cad": 0.73, "sgd": 0.74, "php": 1 / PHP_PER_USD}
BARE_PESOS = 10_000      # a bare figure this large is pesos a month (Filipino employers), not dollars
ABSURD = 2_000_000       # a typo, not a wage

CUR = r"(?:US\$|A\$|C\$|USD|AUD|CAD|GBP|EUR|SGD|PHP|\$|£|€|₱)"
AMOUNT = re.compile(rf"(?P<pre>{CUR})?\s?(?P<n>\d[\d,]*(?:\.\d+)?)\s?(?P<k>k)?\s?(?P<post>USD|AUD|CAD|GBP|EUR|SGD|PHP|\$|£|€|₱)?(?!\w)", re.I)
RANGE_GAP = re.compile(r"\s*(?:-|–|—|~|to)\s*", re.I)
HEADER = {
    "wage": re.compile(r"^\s*(?:Wage\s*/\s*Salary|Salary/Pay|Wage|Salary|Pay)\s*:\s*(.+)$", re.I | re.M),
    "hours": re.compile(r"^\s*Hours\s+Per\s+Week\s*:\s*(\d+(?:\.\d+)?)", re.I | re.M),
    "type": re.compile(r"^\s*Type\s+Of\s+Work\s*:\s*(.+)$", re.I | re.M),
}
PERIODS = {   # the one named nearest after the amount wins
    "piece": re.compile(r"per\s+(?:[\w-]+\s+){0,3}(?:video|clip|project|task|piece|reel|post|article|edit|short|launch|package|batch|ad)s?\b|/\s*(?:video|clip|project|task|reel|post)\b|/\s*\d+\s*mins?\b|per\s+\d+\s*mins?\b|pay\s+per\s+project", re.I),
    "hourly": re.compile(r"per\s*hou?rs?\b|\ban?\s+hou?r\b|\bhourly\b|/\s*(?:hou?rs?|h)\b|\bper\s*hrs?\b|\bhrs?\b|\bp/h\b", re.I),
    "weekly": re.compile(r"per\s*week|/\s*(?:week|wk)\b|\bweekly\b|\ba\s+week\b|\d\s*week\b", re.I),
    "daily": re.compile(r"per\s*day|/\s*day\b|\bdaily\b|\ba\s+day\b", re.I),
    "yearly": re.compile(r"per\s*(?:year|annum)|/\s*(?:year|yr)\b|\bannual(?:ly)?\b|\bp\.?a\.?\b", re.I),
    "monthly": re.compile(r"per\s*month|/\s*(?:month|mo|m)\b|\bmonthly\b|\ba\s+month\b|\bmo\b", re.I),
}
SYMBOLS = {"$": "usd", "us$": "usd", "usd": "usd", "a$": "aud", "aud": "aud", "c$": "cad", "cad": "cad", "£": "gbp", "gbp": "gbp",
           "€": "eur", "eur": "eur", "sgd": "sgd", "₱": "php", "php": "php"}


def header_fields(jd):
    out = {}
    for key, rx in HEADER.items():
        m = rx.search(jd or "")
        if m:
            out[key] = m.group(1).strip()
    return out


def first_range(text):
    """The first money figure in a wage line and the figures joined to it by a dash or 'to'
    ('$5-$8', 'AUD 3-5k', '1500-2000'). Returns (amounts, currency, start, end) or None."""
    ms = list(AMOUNT.finditer(text or ""))
    if not ms:
        return None
    first = next((m for m in ms if m.group("pre") or m.group("post")), ms[0])
    seg = [first]
    for m in ms[ms.index(first) + 1:]:
        if RANGE_GAP.fullmatch(text[seg[-1].end():m.start()]):
            seg.append(m)
        else:
            break
    nums = [float(m.group("n").replace(",", "")) * (1000 if m.group("k") else 1) for m in seg]
    if len(seg) > 1 and seg[-1].group("k") and not seg[0].group("k") and nums[0] < 1000:
        nums[0] *= 1000                       # '3-5k' is 3k to 5k
    cur = next((SYMBOLS.get((m.group("pre") or m.group("post") or "").lower()) for m in seg if m.group("pre") or m.group("post")), None)
    last = seg[-1]
    return nums, cur, first.start(), last.end("k") if last.group("k") else last.end("n")   # where the digits stop


def period_after(text, start, default=None):
    best = None
    for name, rx in PERIODS.items():
        m = rx.search(text, start)
        if m and (best is None or m.start() < best[0]):
            best = (m.start(), name)
    return best[1] if best else default


def hourly_usd(pay_text, jd="", minimum=MIN_HOURLY_USD):
    """{'hourly': float | None, 'basis': str, 'ok': bool}. ok is False only for a clear figure under the minimum
    (the owner's OnlineJobs minimum unless another minimum is passed)."""
    head = header_fields(jd)
    wage = head.get("wage") or pay_text or ""
    seg = first_range(wage)
    unknown = lambda why: {"hourly": None, "basis": why, "ok": True}
    if seg is None:
        return unknown("no figure")
    nums, cur, start, end = seg
    top = max(nums)
    if top > ABSURD:
        return unknown("figure not credible")
    assumed = cur is None and top >= BARE_PESOS
    cur = cur or ("php" if assumed else "usd")
    if cur not in FX:
        return unknown("unknown currency")
    per = period_after(wage, start)
    if per is None:
        per = "hourly" if top < 50 else "monthly"      # '$5-$8' is an hourly rate; '1000' is a month
    kind = (head.get("type") or "").lower()
    hours = float(head["hours"]) if "hours" in head else None
    if hours is None and "full" in kind:
        hours = 40.0
    upto = re.search(r"up\s+to\s+(?:US)?\$?\s?(\d[\d,]*(?:\.\d+)?)", wage, re.I)
    if upto:
        top = max(top, float(upto.group(1).replace(",", "")))
    usd = top * FX[cur]
    open_ended = wage[end:end + 1] == "+" or bool(re.search(r"\bstarting\b|\band\s+up\b", wage, re.I))   # "$800+", not "$1200 + benefits"
    if per == "piece":
        return unknown("per piece")
    if per == "hourly":
        hourly = usd
    elif per == "daily":
        hourly = usd / 8
    elif per == "weekly" and hours:
        hourly = usd / hours
    elif per == "monthly" and hours:
        hourly = usd / (hours * WEEKS_PER_MONTH)
    elif per == "yearly" and hours:
        hourly = usd / (hours * 52)
    else:
        return unknown(f"{per}, hours unknown")
    basis = f"{wage.strip()[:40]} ({per}" + (f", {hours:g} h/week" if hours and per in ("monthly", "weekly", "yearly") else "") + ")"
    # the less sure the figure, the further under the minimum it has to be before a posting is ruled out
    margin = min(0.9 if assumed else 1.0, 0.75 if "gig" in kind else 1.0, 0.6 if open_ended else 1.0)
    low = round(hourly * min(nums) / top, 2) if len(nums) > 1 and top and not upto else None      # the start of a range, for display
    return {"hourly": round(hourly, 2), "low": low, "basis": basis, "ok": hourly >= minimum * margin}


MIN_MONTHLY_USD = float(_PAY.get("min_monthly_usd", 1000))   # A range is judged by its LOW end.


def monthly_floor_ok(pay_text, jd="", minimum=MIN_MONTHLY_USD):
    """False only when an OnlineJobs posting clearly pays under the monthly minimum (stated monthly, weekly or yearly,
    judged at the low end of a range). Hourly, daily, per-piece or missing pay is left to the other rules."""
    wage = header_fields(jd).get("wage") or pay_text or ""
    seg = first_range(wage)
    if seg is None:
        return True
    nums, cur, start, end = seg
    top = max(nums)
    if top > ABSURD:
        return True
    if len(nums) == 1 and re.search(r"up\s+to", wage, re.I):
        return True                                   # 'up to $2,750' says nothing about the low end
    cur = cur or ("php" if top >= BARE_PESOS else "usd")
    if cur not in FX:
        return True
    per = period_after(wage, start) or ("hourly" if top < 50 else "monthly")
    low = min(nums)
    monthly = {"monthly": low, "weekly": low * WEEKS_PER_MONTH, "yearly": low / 12}.get(per)
    return True if monthly is None else monthly * FX[cur] >= minimum


MIN_PIECE_USD = float(_PAY.get("min_per_piece_usd", 3))   # a posting that pays under this per clip, video or edit is skipped
PIECE_AFTER = re.compile(r"(?:\s*(?:-|–|—|to)\s*\$?\s?\d+(?:\.\d+)?)?\s*(?:USD)?\s*(?:per|/|each|a|for\s+(?:each|every|your\s+(?:first|next)|the\s+(?:first|next)))\s*(?:\d+\s+)?(?:[\w-]+\s+){0,3}?(?:video|clip|short|reel|edit|post)s?\b", re.I)
DOLLARS = re.compile(r"(?<![\w.])\$\s?(\d+(?:\.\d+)?)(?![\d,])")


def per_piece_low(jd):
    """The lowest dollar rate the posting names per clip, video, short, reel, edit or post (None if it names none)."""
    rates = [float(m.group(1)) for m in DOLLARS.finditer(jd or "") if PIECE_AFTER.match((jd or "")[m.end():m.end() + 70])]
    return min(rates) if rates else None


def _selftest():
    pay_block = "PAY: Per approved clip:\n- $2.00 for your first 50 clips\n- $2.50 for the next 50\n- $3.00 after that\nPlus a $15/week base fee for availability"
    assert per_piece_low(pay_block) == 2.0
    assert per_piece_low("$25 per edit") == 25 and per_piece_low("$3 per video (15-60 seconds)") == 3.0
    assert per_piece_low("Rate: $25-35 USD per finished reel") == 25 and per_piece_low("$0.50 per clip") == 0.5
    assert per_piece_low("$1,000/month, 17 hours a week. $10 weekly bonus. $15/week base fee") is None
    assert per_piece_low("Edit 6 to 7 shorts per week at $25 USD per short") == 25 and per_piece_low("no pay stated") is None
    olj = lambda wage, hours, kind="Full Time": f"X\n\nType Of Work: {kind}\nWage / Salary: {wage}\nHours Per Week: {hours}\n"
    h = lambda pay, jd="": hourly_usd(pay, jd)
    assert h("$8/hour")["ok"] and not h("$7/hour")["ok"] and not h("4/hour")["ok"]
    assert h("Salary/Pay: $5–$8 USD/hour")["ok"] and not h("$5-$6/hr")["ok"]            # a range counts at its top
    assert h("$6–8/hour full-time")["ok"] and h("$5-10/Hr")["hourly"] == 10             # the second figure has no symbol
    assert h("5$ to 10")["hourly"] == 10 and h("$12.14")["ok"] and not h("$5")["ok"]     # no unit under 50 = hourly
    r = hourly_usd("", olj("$1800 - $2400 / month", 40)); assert r["ok"] and 13 < r["hourly"] < 14.5, r
    r = hourly_usd("", olj("$350–450 per month", 20, "Part Time")); assert not r["ok"] and 5 < r["hourly"] < 5.4, r
    r = hourly_usd("", olj("US$700–900/month", 20, "Part Time")); assert r["ok"] and 10 < r["hourly"] < 10.6, r
    r = hourly_usd("", olj("1000", 40)); assert not r["ok"] and 5 < r["hourly"] < 6, r   # bare 1000 = dollars a month
    r = hourly_usd("", olj("30000-40000", 40)); assert not r["ok"] and 3.5 < r["hourly"] < 4.5, r   # bare 30000 = pesos
    r = hourly_usd("", olj("PHP 55,000 – 85,000 monthly", 40)); assert r["ok"] and 8 < r["hourly"] < 9, r
    r = hourly_usd("", olj("AUD 3–5k (~PHP 130–217k)/month", 40)); assert r["ok"] and 18 < r["hourly"] < 19.5, r
    r = hourly_usd("", olj("$4/hour (~$320/month at 20 hrs/week)", 20, "Part Time")); assert not r["ok"] and r["hourly"] == 4, r
    r = hourly_usd("", olj("200 Week", 40)); assert not r["ok"] and r["hourly"] == 5, r
    r = hourly_usd("", olj("$1140+ / month", 40)); assert r["ok"] and 6 < r["hourly"] < 7, r         # open ended: kept (6.58 > 4.8)
    r = hourly_usd("", olj("$800.00+", 40)); assert not r["ok"], r                                   # but 4.6 is far under
    r = hourly_usd("", olj("$1200 + Benefits", 40)); assert not r["ok"], r                           # extras, not open ended
    r = hourly_usd("", olj("1000", 30, "Gig")); assert r["ok"] and 7 < r["hourly"] < 8, r            # a gig is judged with a margin
    r = hourly_usd("", olj("$5-7/h to start (up to $12/hour FT)", 20, "Part Time")); assert r["ok"] and r["hourly"] == 12, r
    r = hourly_usd("", olj("60,000 - 76,000", 40)); assert r["ok"], r                                # 7.57 assumed pesos: within the margin
    r = hourly_usd("", olj("US$90/week; US$30/video package", None, "Part Time")); assert r["ok"] and r["hourly"] is None, r
    r = hourly_usd("", olj("$4-$6 / 10mins of finished video", 20, "Part Time")); assert r["ok"] and r["hourly"] is None, r
    r = hourly_usd("", olj("1,000.", 40)); assert not r["ok"], r
    assert h("4usd per hour")["hourly"] == 4 and h("100000000")["ok"] and h("N/A")["ok"] and h("")["ok"] and h("TBD")["ok"]
    for piece in ("Pay per Project", "$3 per video (15-60 seconds)", "£20 per video - 3 x per week", "$50 trial; $75/video; $3,000/month"):
        r = h(piece); assert r["ok"] and r["hourly"] is None, (piece, r)
    r = h("1000"); assert r["hourly"] is None and r["ok"], r         # monthly with no hours: kept
    assert hourly_usd("$3/hour", "", 4)["ok"] is False and hourly_usd("$4/hour", "", 4)["ok"] and hourly_usd("$5/hour")["ok"] is False
    m = lambda wage, hours=40, kind="Full Time": monthly_floor_ok("", olj(wage, hours, kind), minimum=1600)
    assert m("$1800 - $2400 / month") and not m("$1,500 - $2,500 / month") and not m("PHP 55,000 - 85,000 monthly")
    assert m("\u20b1100,000/Month") and m("Up to 2750/month") and m("$12 - $15/hour") and m("") and m("$50 / video")
    assert m("$400/week") and not m("$300 - $500/week") and m("$24,000 per year") and not m("$12,000 - $30,000 per year")
    assert monthly_floor_ok("$50000 USD per month", minimum=1600) and not monthly_floor_ok("$100 USD per month", minimum=1600)
    print("pay selftest ok")


if __name__ == "__main__":
    _selftest()

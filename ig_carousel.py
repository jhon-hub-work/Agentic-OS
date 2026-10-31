"""Instagram carousel action for a crew: the agent writes JSON, this renders 1080x1350 slides and posts via Zernio.

Format (Jhon's reference, @adovionlabs 30 Oct; the format only, not their brand): cover with a big number and a
highlight pill, then one slide per GitHub repo (#N title, one-line benefit, 4-step row, quote pill, link pill, the
repo's card image), pastel backgrounds. Repos that don't exist on GitHub are dropped before rendering.
"""

import html
import json
import re
import subprocess
import time
import urllib.request
from pathlib import Path

# Edge, not Brave (Brave headless hangs on --screenshot here, 30 Oct) and never Chrome (Dr L's)
EDGE = Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
ZERNIO = "https://zernio.com/api/v1"
PASTELS = ("#FDE2EC", "#E3F0FF", "#E6F7EC", "#FFF1DC", "#EFE7FF", "#E0F7F6", "#FFE8E0", "#F1F5D9")

FORMAT = (
    "Reply with ONLY this JSON (no other text):\n"
    '{"cover": {"number": "7", "highlight": "GitHub repos", "rest": "for high paying clients"},\n'
    ' "slides": [{"name": "Huginn", "repo": "huginn/huginn", "benefit": "one line, what it does for the reader",\n'
    '             "steps": ["4 short steps", "of how", "it works", "for them"], "quote": "short punchy line"}],\n'
    ' "caption": "hook line, 2 short paragraphs, then a numbered list: name - what it does (no links), '
    'then the line Comment AI and I\'ll DM you every link, then a blank line and 3 to 5 hashtags (Instagram caps posts at 5), '
    'most specific first, e.g. #github #opensource #freelancetools."}\n'
    "5 to 8 slides. Only real, public GitHub repos you are sure exist (owner/name exactly)."
)


def parse(out: str) -> dict:
    m = re.search(r"\{.*\}", out, re.S)
    if not m:
        raise ValueError("The agent didn't return the carousel JSON")
    post = json.loads(m.group(0))
    if not post.get("slides") or not post.get("caption") or not post.get("cover"):
        raise ValueError("The carousel JSON is missing cover, slides or caption")
    if "#" not in post["caption"]:  # 30 Oct: the first post went out with none
        post["caption"] = post["caption"].rstrip() + "\n\n" + DEFAULT_TAGS
    return post


DEFAULT_TAGS = "#github #opensource #freelancetools #aitools #productivity"
REPO_TOPICS = ("self-hosted", "automation", "ai-agents", "productivity", "crm", "web-scraping")


def popular_repos(used: list, cache: Path) -> str:
    """Popular, maintained GitHub repos for the advisor to pick from, minus ones already posted. Cached a day."""
    if not cache.exists() or time.time() - cache.stat().st_mtime > 86400:
        found = {}
        for t in REPO_TOPICS:  # 6 searches: under GitHub's 10 a minute without a token
            try:
                req = urllib.request.Request(f"https://api.github.com/search/repositories?q=topic:{t}+stars:%3E3000+pushed:%3E2026-07-01"
                                             "&sort=stars&per_page=15", headers={"User-Agent": "agentic-os"})
                with urllib.request.urlopen(req, timeout=30) as r:
                    for it in json.load(r)["items"]:
                        found[it["full_name"]] = {"stars": it["stargazers_count"], "about": (it.get("description") or "")[:120], "topic": t}
            except OSError:
                continue
        if found:
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_text(json.dumps(found), encoding="utf-8")
    repos = json.loads(cache.read_text(encoding="utf-8")) if cache.exists() else {}
    left = sorted((k for k in repos if k.lower() not in {u.lower() for u in used}), key=lambda k: -repos[k]["stars"])[:60]
    if not left:
        return ""
    return ("Popular, maintained GitHub repos not posted yet (pick from these or other real ones; never repeat a posted repo):\n"
            + "\n".join(f"- {k} ({repos[k]['stars'] // 1000}k stars, {repos[k]['topic']}): {repos[k]['about']}" for k in left)
            + (f"\nAlready posted, don't use: {', '.join(used[-80:])}" if used else ""))


def repo_exists(repo: str) -> bool:
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", repo or ""):
        return False
    try:
        req = urllib.request.Request(f"https://api.github.com/repos/{repo}", headers={"User-Agent": "agentic-os"})
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status == 200
    except OSError:
        return False


def _page(body: str, bg: str) -> str:
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;width:1080px;height:1350px;overflow:hidden}}
body{{background:{bg};font-family:'Segoe UI',Arial,sans-serif;color:#1d1d24;display:flex;flex-direction:column;
align-items:center;padding:70px 70px 0;box-sizing:border-box}}
.brand{{letter-spacing:6px;font-size:22px;font-weight:600;opacity:.7}}
h1{{font-family:Georgia,serif;font-size:96px;margin:30px 0 10px;font-weight:700}}
.benefit{{font-size:40px;text-align:center;margin:0 0 36px;line-height:1.25}}
.steps{{display:flex;gap:18px;margin-bottom:36px}}
.step{{width:215px;font-size:24px;line-height:1.3}}.step b{{display:inline-block;width:40px;height:40px;border-radius:50%;
background:#d6336c;color:#fff;text-align:center;line-height:40px;margin-bottom:8px}}
.pill{{background:#fff;border-radius:60px;padding:16px 34px;font-size:30px;margin-bottom:22px}}
.link{{border:2px solid #1d1d24;border-radius:60px;padding:12px 30px;font-size:26px;font-family:Consolas,monospace}}
.card{{margin-top:auto;width:100%;border-radius:24px 24px 0 0;box-shadow:0 -6px 30px rgba(0,0,0,.12)}}
.nocard{{justify-content:center;gap:10px}}
.cover{{justify-content:center;align-items:flex-start;padding:110px}}
.num{{font-size:300px;font-weight:800;line-height:.9}}
.hl{{background:#bfe0ff;border-radius:22px;padding:4px 22px;font-size:96px;font-weight:700;display:inline-block}}
.rest{{font-size:96px;font-weight:700;line-height:1.05;margin-top:10px}}
</style></head><body class="{'cover' if 'class="num"' in body else '' if 'class="card"' in body else 'nocard'}">{body}</body></html>"""


def _card(repo: str, f: Path) -> str:
    """The repo's GitHub card, downloaded first (loading it during the screenshot left a broken image, 30 Oct); none if it fails."""
    for wait in (0, 5, 15):  # GitHub answers 429 to a quick burst of these
        time.sleep(wait)
        try:
            req = urllib.request.Request(f"https://opengraph.githubassets.com/1/{repo}", headers={"User-Agent": "agentic-os"})
            with urllib.request.urlopen(req, timeout=30) as r:
                f.write_bytes(r.read())
            return f'<img class="card" src="{f.name}">'
        except OSError:
            continue
    return ""


def render(post: dict, out_dir: Path, handle: str) -> list:
    """Slides as PNGs (Edge headless, its own temp profile). Returns their paths, cover first."""
    out_dir.mkdir(parents=True, exist_ok=True)
    e = html.escape
    c = post["cover"]
    pages = [_page(f'<div class="num">{e(str(c.get("number", "")))}</div><div class="hl">{e(c.get("highlight", ""))}</div>'
                   f'<div class="rest">{e(c.get("rest", ""))}</div><div class="brand" style="margin-top:60px">@{e(handle)}</div>',
                   PASTELS[1])]
    for i, s in enumerate(post["slides"], 1):
        steps = "".join(f'<div class="step"><b>{n}</b><br>{e(t)}</div>' for n, t in enumerate(s.get("steps", [])[:4], 1))
        pages.append(_page(
            f'<div class="brand">@{e(handle).upper()}</div><h1>#{i} {e(s["name"])}</h1><p class="benefit">{e(s.get("benefit", ""))}</p>'
            f'<div class="steps">{steps}</div><div class="pill">{e(s.get("quote", ""))}</div>'
            f'<div class="link">github.com/{e(s["repo"])}</div>' + _card(s["repo"], out_dir / f"card{i}.png"), PASTELS[i % len(PASTELS)]))
    paths = []
    for n, page in enumerate(pages):
        paths.append(_shot(page, out_dir, f"slide{n}", 1080, 1350))
    return paths


def _shot(page: str, out_dir: Path, name: str, w: int, h: int) -> Path:
    src, png = out_dir / f"{name}.html", out_dir / f"{name}.png"
    src.write_text(page, encoding="utf-8")
    subprocess.run([str(EDGE), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run", "--virtual-time-budget=8000",
                    f"--user-data-dir={out_dir / '_profile'}", f"--window-size={w},{h}", f"--screenshot={png}",
                    src.as_uri()], capture_output=True, timeout=120)
    if not png.exists():
        raise RuntimeError(f"{name} didn't render")
    return png


def render_story(out_dir: Path, handle: str) -> Path:
    """A 1080x1920 Story pointing at the new post: its cover, 'New post', the handle (the API can't add link stickers)."""
    page = (f'<!doctype html><html><head><meta charset="utf-8"><style>html,body{{margin:0;width:1080px;height:1920px;overflow:hidden}}'
            f'body{{background:{PASTELS[0]};font-family:"Segoe UI",Arial,sans-serif;color:#1d1d24;display:flex;flex-direction:column;'
            f'align-items:center;justify-content:center;gap:50px}}h1{{font-size:110px;margin:0}}img{{width:880px;border-radius:36px;'
            f'box-shadow:0 20px 60px rgba(0,0,0,.18)}}p{{font-size:46px;margin:0}}</style></head><body><h1>New post</h1>'
            f'<img src="slide0.png"><p>Swipe through on my profile</p><p><b>@{html.escape(handle)}</b></p></body></html>')
    return _shot(page, out_dir, "story", 1080, 1920)


def _api(key: str, method: str, path: str, body=None) -> dict:
    req = urllib.request.Request(ZERNIO + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:  # a carousel post can take minutes to answer
        return json.loads(r.read() or b"{}")


def account(key: str, platform: str) -> dict:
    """The connected account for a platform in Zernio: {"_id", "username"}."""
    accts = _api(key, "GET", f"/accounts?platform={platform}&status=connected")["accounts"]
    if not accts:
        raise RuntimeError(f"No connected {platform} account in Zernio")
    return accts[0]


def instagram_account(key: str) -> dict:
    return account(key, "instagram")


def post(key: str, account_id: str, slides: list, caption: str, platform: str = "instagram", story: bool = False) -> dict:
    """Upload each slide (presign -> PUT), then publish now. No slides = a text post (LinkedIn). story: an IG Story."""
    urls = []
    for p in slides:
        data = Path(p).read_bytes()
        pre = _api(key, "POST", "/media/presign", {"filename": Path(p).name, "contentType": "image/png", "size": len(data)})
        put = urllib.request.Request(pre["uploadUrl"], data=data, method="PUT", headers={"Content-Type": "image/png"})
        urllib.request.urlopen(put, timeout=120).close()
        urls.append(pre["publicUrl"])
    target = {"platform": platform, "accountId": account_id, **({"platformSpecificData": {"contentType": "story"}} if story else {})}
    body = {"content": caption, "platforms": [target], "publishNow": True}
    if urls:
        body["mediaItems"] = [{"type": "image", "url": u} for u in urls]
    return _api(key, "POST", "/posts", body)


def comment_dm(key: str, zernio_post_id: str, handle: str, slides: list, keyword: str = "AI") -> dict:
    """Jhon 29 Oct: comment the keyword -> must follow first -> then a DM with the post's repo links.
    Zernio's own comment automation does the gate: unknown follow status gets an "I'm following" button, which lets it
    check live (Instagram only tells follow status once someone has messaged you). It fails open: a few may slip through."""
    for _ in range(20):  # the post's Instagram id appears once Instagram has published it
        got = _api(key, "GET", f"/posts/{zernio_post_id}")
        p = next((x for x in got.get("post", got).get("platforms", []) if x.get("platform") == "instagram"), {})
        if p.get("platformPostId"):
            break
        time.sleep(15)
    else:
        raise RuntimeError("Instagram hadn't published the post after 5 minutes")
    links = [f"https://github.com/{s['repo']}" for s in slides]
    acct = p["accountId"]["_id"] if isinstance(p["accountId"], dict) else p["accountId"]
    return _api(key, "POST", "/comment-automations", {
        "profileId": p["profileId"], "accountId": acct, "name": f"{keyword} links {p['platformPostId']}"[:60],
        "trigger": "comment", "platformPostId": p["platformPostId"], "keywords": [keyword], "matchMode": "word",
        "dmMessage": ("Here are the repos from the post:\n" + "\n".join(f"{i}. {s['name']}: {u}" for i, (s, u) in
                      enumerate(zip(slides, links), 1)))[:950],
        "buttons": [{"type": "url", "title": s["name"][:20], "url": u} for s, u in zip(slides[:3], links)],
        "audience": {"followerStatus": "follower", "whenUnknown": "verify"},
        "followGate": {"message": f"Follow @{handle} first, then tap the button and I'll send you the links.",
                       "buttonLabel": "I'm following", "profileButtonLabel": "Open profile",
                       "notFollowingMessage": f"Not following yet. Follow @{handle}, then tap again."},
        "commentReply": "Sent you a DM 📩", "repeatPolicy": {"mode": "once"}})


LINKEDIN_FORMAT = ("Reply with ONLY the LinkedIn post text, ready to publish: a strong first line, short paragraphs, "
                   "a plain takeaway, at most 3 hashtags at the end. Under 1,300 characters. No preamble, no quotes around it.")

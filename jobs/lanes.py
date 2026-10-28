# Adapted from Hoot (a job-application agent) for the Agentic OS job pipeline.
"""Which kinds of jobs Hoot works on right now, in one place.

Default lanes: video editing (part time and project based included), and AI automation ONLY when the schedule
is flexible (project based, async, set your own hours); fixed hours or shifts are skipped. HARD RULE for every lane: a job that uses a time tracker or
activity monitoring (Hubstaff, Time Doctor, screenshots...) is skipped. gate() is the one check for all of it. Hoot applies by itself
when nothing needs the owner: on OnlineJobs.ph it fills the reply in and sends it, on company sites it
submits the form. Change a value here to change Hoot.
"""

import re

ON = {"video": True, "ai": True, "dev": True, "social": True}   # which lanes Hoot offers (ai: flexible schedule only, see ai_gate)
MIN_SCORE = {"video": 3.0, "ai": 4.0, "dev": 3.0, "social": 3.0}  # the score a posting needs in its lane
AUTO_APPLY = True                               # apply right away when nothing needs the owner
AUTO_SEND = True                                # press SEND EMAIL on OnlineJobs for a clean, approved reply

# OnlineJobs.ph searches Hoot runs while the video lane is on (the AI ones come back with that lane).
VIDEO_KEYWORDS = [
    "video editor", "video editing", "short form video editor", "reels editor", "youtube video editor",
    "tiktok video editor", "capcut", "part time video editor", "freelance video editor",
    "project based video editor", "motion graphics", "after effects", "premiere pro",
    "ugc video editor", "podcast video editor", "vsl video editor",
]

# Jhon, 2026-10-28: full stack and social media manager roles too (pay floors in the pipeline's pay_ok).
DEV_KEYWORDS = ["full stack developer", "full stack web developer", "web developer", "react developer",
                "next.js developer", "node.js developer", "mern stack developer"]
SOCIAL_KEYWORDS = ["social media manager", "social media management", "social media specialist", "social media marketing"]
DEV = re.compile(r"full[- ]?stack|web (?:developer|dev)\b|software (?:developer|engineer)|front[- ]?end|back[- ]?end|\breact\b"
                 r"|next\.?js|node\.?js|\bmern\b|(?:javascript|typescript) developer", re.I)
SOCIAL = re.compile(r"social media (?:manager|management|marketing|marketer|specialist|strategist|coordinator|assistant)"
                    r"|community manager", re.I)

BUILD = re.compile(r"\b(automation|agents?|developer|engineer|backend|builder|vibecoder|n8n|zapier|apis?)\b", re.I)
VIDEO = re.compile(r"\b(video|editor|editing|reels?|tiktok|capcut|youtube|shorts|motion|content creator)\b", re.I)


def kind_of(title):
    """'ai' for build and automation roles, 'video' for editing roles. A title naming both reads as
    the build role ('AI Video Editor Tool Builder' makes tools); anything else without video words is 'ai'."""
    t = title or ""
    if DEV.search(t) and not AI_WORDS.search(t):  # 'Full Stack AI Engineer' stays an AI role (flexible only)
        return "dev"
    if BUILD.search(t):
        return "ai"
    if SOCIAL.search(t) and not re.search(r"video|editor|editing", t, re.I):  # 'Social Media Video Editor' is video
        return "social"
    return "video" if VIDEO.search(title or "") else "ai"


def wanted(job):
    return ON[kind_of(job.get("title"))]


def bar(job):
    return MIN_SCORE[kind_of(job.get("title"))]


AI_WORDS = re.compile(r"\b(ai|a\.i\.|automations?|automate|n8n|zapier|make\.com|agents?|llms?|gpt|claude|chatbots?|workflows?|integrations?)\b", re.I)
FLEX = re.compile(r"project[- ]based|per project|\bfreelance\b|\bcontract(?:or)?\b|\basync(?:hronous(?:ly)?)?\b|flexible (?:hours|schedule|time|working hours)"
                  r"|(?:set|choose|pick) your own (?:hours|schedule)|your own (?:hours|pace|schedule)|work (?:whenever|anytime|any time)"
                  r"|no fixed (?:hours|schedule)|output[- ]based|results[- ]based|deliverables?[- ]based|type of work:\s*gig", re.I)
FIXED = re.compile(r"\b(?:graveyard|night|day|morning|evening|mid|rotating) shifts?\b|\bshift (?:schedule|hours|starts?)\b|fixed (?:hours|schedule|shift)"
                   r"|must be (?:online|available|logged in) (?:from|between|during)|core hours|(?:EST|EDT|PST|PDT|CST|CDT|AEST|US|UK) (?:business|office|working) hours"
                   r"|\b\d{1,2}(?::\d{2})?\s?(?:am|pm)\s*(?:-|\u2013|to)\s*\d{1,2}(?::\d{2})?\s?(?:am|pm)\b", re.I)
TRACKER = re.compile(r"hubstaff|time ?doctor|insightful\.io|activtrak|teramind|desk ?time|everhour|clockify|toggl|screenshot monitoring"
                     r"|time[- ]?track(?:er|ing)(?: (?:app|software|tool))?|tracking (?:app|software|tool)|activity (?:monitor(?:ing)?|track(?:er|ing)|level)"
                     r"|screen (?:monitoring|recording software)|(?:random|periodic) screenshots|takes? screenshots|screenshots? (?:are|will be) taken", re.I)
NEGATION = re.compile(r"\b(?:no|not|without|never|don't|do not|zero|free from)\b[^.\n]{0,25}$", re.I)


def tracker(jd):
    """'' or why: the posting uses a time tracker or activity monitoring ('no time tracker' does not count)."""
    for m in TRACKER.finditer(jd or ""):
        if not NEGATION.search((jd or "")[max(0, m.start() - 40):m.start()]):
            return f"uses a time tracker or activity monitoring ({m.group(0)})"
    return ""


def ai_gate(jd, title=""):
    """'' when an AI job may go ahead (AI automation work, flexible schedule), else why not. Fails closed."""
    if title and not AI_WORDS.search(title):
        return "not an AI automation role"
    if FIXED.search(jd or ""):
        return f"fixed schedule or shift ({FIXED.search(jd).group(0)})"
    if not FLEX.search(jd or ""):
        return "schedule not stated as flexible"
    return ""


def gate(job, jd):
    """'' when Hoot may work on this job, else why not. Every lane: no time tracker. AI lane: flexible only."""
    k = kind_of(job.get("title"))
    if not ON[k]:
        return f"{k} lane is off"
    why = tracker(jd)
    if why:
        return why
    return ai_gate(jd, job.get("title") or "") if k == "ai" else ""


def allowed(job, jd):
    return gate(job, jd) == ""


def search_keywords(original):
    """The OnlineJobs.ph keywords for Hoot's scan: the video ones, plus the original list when AI is on."""
    words = list(VIDEO_KEYWORDS) + (DEV_KEYWORDS if ON["dev"] else []) + (SOCIAL_KEYWORDS if ON["social"] else [])
    if ON["ai"]:                                  # only the AI automation searches, not every developer keyword
        words += [w for w in original if w not in words and AI_WORDS.search(w)]
    return words


def _selftest():
    for title, want in [("AI Video Editor Tool Builder (Vibecoder)", "ai"), ("Short-Form Video Editor: Entertainment Content (Long-Term)", "video"),
                        ("Social Media Content Creator + Video Editor | Education Brand", "video"), ("Senior Technical AI Solutions (Automations)", "ai"),
                        ("AI Operations Lead", "ai"), ("AI VIDEO EDITOR FOR FACEBOOK ADS", "video"), ("AI Automation Expert", "ai"),
                        ("Short-Form Video Specialist", "video"), ("Part-Time TikTok / CapCut Video Editor", "video")]:
        assert kind_of(title) == want, (title, kind_of(title))
    assert wanted({"title": "Video Editor"}) and wanted({"title": "n8n Developer"})
    assert bar({"title": "Video Editor"}) == 3.0
    assert search_keywords(["n8n", "full stack developer"]) == VIDEO_KEYWORDS + DEV_KEYWORDS + SOCIAL_KEYWORDS + ["n8n"]
    for title, want in [("Full Stack Developer", "dev"), ("React / Next.js Web Developer", "dev"), ("Full Stack AI Engineer", "ai"),
                        ("Social Media Manager", "social"), ("Social Media Video Editor & Marketing Assistant", "video"),
                        ("n8n Developer", "ai"), ("Backend Developer (Node.js)", "dev")]:
        assert kind_of(title) == want, (title, kind_of(title))
    assert gate({"title": "Full Stack Developer"}, "Full time, 9am-5pm EST.") == ""   # fixed hours are fine outside AI
    V, A = {"title": "Short-Form Video Editor"}, {"title": "AI Automation Specialist"}
    assert gate(V, "Edit reels. Full time.") == ""
    assert "tracker" in gate(V, "We use Hubstaff to track hours.") and "tracker" in gate(V, "Random screenshots are taken via our time tracker.")
    assert gate(V, "No time tracker, we judge output.") == ""
    assert gate(A, "Project-based, async, set your own hours.") == ""
    assert "fixed" in gate(A, "Flexible role but you must work the night shift.")
    assert "fixed" in gate(A, "Freelance. Must be online between 9am-5pm EST.") and "fixed" in gate(A, "Contract role, 9am - 5pm PST")
    assert "flexible" in gate(A, "Full time, build n8n workflows.")
    assert "tracker" in gate(A, "Freelance, async. Time Doctor required.")
    assert gate(A, "Type Of Work: Gig\nBuild zaps") == ""
    print("lanes selftest ok")


if __name__ == "__main__":
    _selftest()

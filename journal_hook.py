"""Claude Code hook for ticket fixes: keeps a copy of every file before Claude edits it, so the OS can undo the change.

server.py adds it to each fix run: python journal_hook.py <journal dir> <ticket id>, hook event JSON on stdin.
Idea from Octopus-SI-OS's change journal (SHA-256 before-copies). If the copy can't be saved, the edit is blocked.
"""
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path


def sha(p: Path):
    return hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else None


def main():
    root, tid = Path(sys.argv[1]), sys.argv[2]
    ev = json.load(sys.stdin)
    args = ev.get("tool_input") or {}
    path = args.get("file_path") or args.get("notebook_path")
    if not path:
        return
    f = Path(path) if Path(path).is_absolute() else Path(ev.get("cwd", ".")) / path
    f = f.resolve()
    pre = ev.get("hook_event_name") == "PreToolUse"
    try:
        h = sha(f)
    except OSError:
        if pre:
            raise  # no before-copy: block the edit
        h = "unknown"  # edited but unreadable now: the line still goes in, so Undo refuses this file instead of skipping it
    if pre and h:
        blob = root / "blobs" / h
        if not blob.exists():
            blob.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(f, blob)
    root.mkdir(parents=True, exist_ok=True)
    with open(root / f"{tid}.jsonl", "a", encoding="utf-8") as out:
        out.write(json.dumps({"at": time.time(), "phase": "pre" if pre else "post", "file": str(f), "sha": h}) + "\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        print(f"Change journal failed, edit blocked: {e}", file=sys.stderr)
        sys.exit(2)  # exit 2 on PreToolUse = Claude Code blocks the edit and shows this message

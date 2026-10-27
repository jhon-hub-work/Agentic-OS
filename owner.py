"""The person this OS works for. Personal details live in config/owner.json (gitignored);
config/owner.example.json shows the fields. Nothing personal is hard-coded in the repo."""

import json
from pathlib import Path

_DIR = Path(__file__).resolve().parent / "config"
_FILE = _DIR / "owner.json" if (_DIR / "owner.json").exists() else _DIR / "owner.example.json"
OWNER: dict = json.loads(_FILE.read_text(encoding="utf-8"))
NAME: str = OWNER.get("name") or "the owner"


def path(key: str) -> Path | None:
    v = OWNER.get(key) or ""
    return Path(v) if v else None

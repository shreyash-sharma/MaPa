"""Where finished translations, locks, progress, rate limits and the token budget live.

Translations are JSON files at <root>/<catalog version>/<lang>.json, so a change to the English copy starts a fresh
set while old versions are simply no longer read. Locally they sit on disk; on Modal, on a Volume, with a Modal Dict
for the small shared counters.
"""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any, Protocol


class Store(Protocol):
    def get(self, version: str, lang: str) -> dict | None: ...
    def previous(self, version: str, lang: str) -> dict | None: ...
    def put(self, version: str, lang: str, data: dict) -> None: ...
    def lock(self, version: str, lang: str) -> bool: ...
    def unlock(self, version: str, lang: str) -> None: ...
    def progress(self, version: str, lang: str) -> dict | None: ...
    def set_progress(self, version: str, lang: str, done: int, total: int) -> None: ...
    def allow_start(self, client: str, per_hour: int) -> bool: ...
    def add_tokens(self, tokens: int) -> None: ...
    def tokens_this_month(self) -> int: ...


LOCK_TTL = 30 * 60


def _newest_other(root: Path, version: str, lang: str) -> dict | None:
    """The most recent translation of this language made for an earlier version of the English copy."""
    found = sorted((p for p in root.glob(f"*/{lang}.json") if p.parent.name != version), key=lambda p: p.stat().st_mtime)
    return json.loads(found[-1].read_text(encoding="utf-8")) if found else None


def _hour() -> str:
    return time.strftime("%Y%m%d%H", time.gmtime())


def _month() -> str:
    return time.strftime("%Y%m", time.gmtime())


class _Counters:
    """Shared get/put semantics over a dict-like backend."""

    def __init__(self, kv: Any):
        self.kv = kv

    def lock(self, version: str, lang: str) -> bool:
        key = f"lock:{version}:{lang}"
        now = time.time()
        if self._put_new(key, now):
            return True
        started = self.kv.get(key)
        if started is not None and now - float(started) > LOCK_TTL:
            self._set(key, now)
            return True
        return False

    def unlock(self, version: str, lang: str) -> None:
        self.kv.pop(f"lock:{version}:{lang}", None)
        self.kv.pop(f"progress:{version}:{lang}", None)

    def progress(self, version: str, lang: str) -> dict | None:
        if self.kv.get(f"lock:{version}:{lang}") is None:
            return None
        return self.kv.get(f"progress:{version}:{lang}") or {"done": 0, "total": 0}

    def set_progress(self, version: str, lang: str, done: int, total: int) -> None:
        self._set(f"progress:{version}:{lang}", {"done": done, "total": total})

    def allow_start(self, client: str, per_hour: int) -> bool:
        key = f"rate:{client}:{_hour()}"
        count = int(self.kv.get(key) or 0)
        if count >= per_hour:
            return False
        self._set(key, count + 1)
        return True

    def add_tokens(self, tokens: int) -> None:
        key = f"tokens:{_month()}"
        self._set(key, int(self.kv.get(key) or 0) + int(tokens))

    def tokens_this_month(self) -> int:
        return int(self.kv.get(f"tokens:{_month()}") or 0)

    def _put_new(self, key: str, value: Any) -> bool:
        raise NotImplementedError

    def _set(self, key: str, value: Any) -> None:
        raise NotImplementedError


class LocalStore(_Counters):
    def __init__(self, root: Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self._mutex = threading.Lock()
        super().__init__({})

    def _put_new(self, key: str, value: Any) -> bool:
        with self._mutex:
            if key in self.kv:
                return False
            self.kv[key] = value
            return True

    def _set(self, key: str, value: Any) -> None:
        self.kv[key] = value

    def get(self, version: str, lang: str) -> dict | None:
        path = self.root / version / f"{lang}.json"
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None

    def previous(self, version: str, lang: str) -> dict | None:
        return _newest_other(self.root, version, lang)

    def put(self, version: str, lang: str, data: dict) -> None:
        path = self.root / version / f"{lang}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


class ModalStore(_Counters):
    def __init__(self, volume: Any, state: Any, mount: str = "/data"):
        self.volume = volume
        self.mount = Path(mount)
        super().__init__(state)

    def _put_new(self, key: str, value: Any) -> bool:
        return bool(self.kv.put(key, value, skip_if_exists=True))

    def _set(self, key: str, value: Any) -> None:
        self.kv.put(key, value)

    def get(self, version: str, lang: str) -> dict | None:
        path = self.mount / version / f"{lang}.json"
        if not path.exists():
            # Another container may have just written it.
            self.volume.reload()
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None

    def previous(self, version: str, lang: str) -> dict | None:
        self.volume.reload()
        return _newest_other(self.mount, version, lang)

    def put(self, version: str, lang: str, data: dict) -> None:
        path = self.mount / version / f"{lang}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        self.volume.commit()

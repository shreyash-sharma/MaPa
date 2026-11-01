"""Translate MaPa's site copy with Meta's Muse Spark Open, served by Baseten.

Shared by the Modal service, the local dev server and the build-time bundle step. Input is always the site's own
English catalog, never visitor text. Every translation is checked before it is kept; a string that fails stays
English on the page.
"""

from __future__ import annotations

import json
import os
import re
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable

MODEL = os.environ.get("MAPA_MODEL", "meta-models/muse-spark-open-1026")
BASE_URL = os.environ.get("MAPA_MODEL_BASE_URL", "https://inference.baseten.co/v1")

PLACEHOLDER = re.compile(r"\{\d+\}")
TAG = re.compile(r"</?\d+/?>")
THINK = re.compile(r"<think>.*?</think>", re.S)
KEEP = ["MaPa", "Muse Spark", "Pixel 6a", "ExecuTorch", "LiteRT", "Gemini Nano", "anime.js", "Instrument Serif"]

BATCH = 40
RETRY_BATCH = 8

LANGUAGES_FILE = Path(os.environ.get("MAPA_LANGUAGES_FILE") or Path(__file__).resolve().parent.parent / "src" / "i18n" / "languages.json")
LANGUAGES = json.loads(LANGUAGES_FILE.read_text(encoding="utf-8"))

# (messages, max_tokens) -> (reply text, tokens used)
Call = Callable[[list[dict], int], tuple[str, int]]


def language(code: str) -> dict | None:
    """A language we translate into. Only the bundled and live lists are switched on; add codes there to grow."""
    enabled = set(LANGUAGES["bundled"]) | set(LANGUAGES.get("live", []))
    return LANGUAGES["languages"].get(code) if code in enabled else None


def system_prompt(code: str) -> str:
    lang = LANGUAGES["languages"][code]
    name, native = lang["name"], lang["native"]
    return f"""You translate the website of MaPa into {name} ({native}).
MaPa is a privacy-first app for families who live apart from an ageing parent. A small model on the parent's own phone
learns their daily rhythm, and the family gets a calm daily note, or an alert when a morning is much quieter than usual.

Write natural, warm, plain {name}, the way a thoughtful native speaker would write for families. Not word for word.

Rules:
1. Reply with one JSON object that has exactly the same keys as the input, each mapped to its {name} translation. No other text.
2. Keep placeholders such as {{0}} and {{1}} exactly. They stand for numbers and times. You may move them to suit the grammar.
3. Keep markup tags such as <0>, </0> and <1/> exactly, in the same order, around the matching words.
4. Never translate these names: {", ".join(KEEP)}, Meta, Android.
5. "Mummy" and "Papa" are what a child calls their parent. Use the everyday word a child uses in {name}.
6. The app's states are names; translate them the same way every time: Active, Quiet, Unusual, Monitoring unavailable, Paused by parent.
7. Add nothing. No medical, health, safety or emergency claims that the English does not make. Never make a reassurance stronger.
8. Keep the calm tone. Keep short labels short."""


def check(src: str, out: object) -> str | None:
    """Why a translation is unsafe to show, or None when it is fine."""
    if not isinstance(out, str) or not out.strip():
        return "empty"
    if sorted(PLACEHOLDER.findall(src)) != sorted(PLACEHOLDER.findall(out)):
        return "placeholders changed"
    if TAG.findall(src) != TAG.findall(out):
        return "tags changed"
    for term in KEEP:
        if term in src and term not in out:
            return f"{term} was translated"
    ratio = len(out) / max(1, len(src))
    if ratio < 0.15 or ratio > 6:
        return "length out of range"
    return None


def parse_reply(text: str) -> dict:
    text = THINK.sub("", text or "").strip()
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("no JSON object in reply")
    data = json.loads(text[start : end + 1])
    if not isinstance(data, dict):
        raise ValueError("reply is not an object")
    return data


def translate_batch(code: str, batch: dict[str, str], call: Call) -> tuple[dict[str, str], int]:
    messages = [
        {"role": "system", "content": system_prompt(code)},
        {"role": "user", "content": json.dumps(batch, ensure_ascii=False)},
    ]
    reply, tokens = call(messages, 16000)
    try:
        data = parse_reply(reply)
    except (ValueError, json.JSONDecodeError):
        return {}, tokens
    good = {k: data[k] for k, v in batch.items() if k in data and check(v, data[k]) is None}
    return good, tokens


def translate_catalog(
    strings: dict[str, str],
    code: str,
    call: Call,
    on_progress: Callable[[int, int], None] | None = None,
    workers: int = 4,
) -> tuple[dict[str, str], dict]:
    """Translate every catalog string. Failures are retried once in smaller batches, then left out."""
    if language(code) is None:
        raise ValueError(f"unsupported language: {code}")
    items = list(strings.items())
    batches = [dict(items[i : i + BATCH]) for i in range(0, len(items), BATCH)]
    done: dict[str, str] = {}
    tokens = 0

    def run(batches: list[dict[str, str]]) -> None:
        nonlocal tokens
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for good, used in pool.map(lambda b: translate_batch(code, b, call), batches):
                done.update(good)
                tokens += used
                if on_progress:
                    on_progress(len(done), len(strings))

    run(batches)
    missing = [(k, v) for k, v in items if k not in done]
    if missing:
        run([dict(missing[i : i + RETRY_BATCH]) for i in range(0, len(missing), RETRY_BATCH)])
    return done, {"tokens": tokens, "translated": len(done), "total": len(strings)}


def baseten_call(api_key: str | None = None) -> Call:
    from openai import OpenAI

    client = OpenAI(base_url=BASE_URL, api_key=api_key or os.environ["BASETEN_API_KEY"], timeout=240)

    def call(messages: list[dict], max_tokens: int) -> tuple[str, int]:
        response = client.chat.completions.create(model=MODEL, messages=messages, max_tokens=max_tokens)
        usage = response.usage.total_tokens if response.usage else 0
        return response.choices[0].message.content or "", usage

    return call


ACCENTS = str.maketrans("aeiouAEIOU", "áéíóúÁÉÍÓÚ")
PROTECTED = re.compile("(" + "|".join([PLACEHOLDER.pattern, TAG.pattern] + [re.escape(t) for t in KEEP]) + ")")


def fake_call(messages: list[dict], max_tokens: int) -> tuple[str, int]:
    """A stand-in model for local tests: accents the vowels and keeps placeholders, tags and names intact."""
    batch = json.loads(messages[-1]["content"])
    out = {k: "".join(p if PROTECTED.fullmatch(p) else p.translate(ACCENTS) for p in PROTECTED.split(v)) for k, v in batch.items()}
    return json.dumps(out, ensure_ascii=False), 0

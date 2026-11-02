"""MaPa's landing site and live translation on Modal.

    modal deploy server/modal_app.py                 # site + /api/i18n, after `npm run build`
    modal run server/modal_app.py::bundle            # translate the bundled languages into public/i18n/

Needs a Modal secret named "baseten" holding BASETEN_API_KEY. Run both commands from landing_site/.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import modal
from limrun_demo import LimrunDemoSessions

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

SITE = ROOT / "out"
CATALOG = ROOT / "public" / "i18n" / "en.json"
LANGUAGES = ROOT / "src" / "i18n" / "languages.json"

app = modal.App("mapa-web")

# Without the Baseten key the site still deploys: English and any stored translations are served, and new
# languages answer "not available" until the secret exists and the app is deployed again.
translation_secrets = []
try:
    _baseten = modal.Secret.from_name("baseten")
    _baseten.hydrate()
    translation_secrets.append(_baseten)
except Exception:
    pass
try:
    _limrun = modal.Secret.from_name("limrun-demo")
    limrun_secrets = [_limrun]
except Exception:
    limrun_secrets = []
TRANSLATE = "1" if translation_secrets else "0"

image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install("fastapi[standard]>=0.115", "openai>=1.40", "limrun-api==0.10.0")
    .env({
        "MAPA_LANGUAGES_FILE": "/config/languages.json",
        "MAPA_CATALOG": "/config/en.json",
        "MAPA_TRANSLATE": TRANSLATE,
        "MAPA_LIMRUN_PARENT_ASSET": "mapa-parent-demo.apk",
        "MAPA_LIMRUN_FAMILY_ASSET": "mapa-family-demo.apk",
    })
    .add_local_file(LANGUAGES, "/config/languages.json")
    .add_local_file(CATALOG, "/config/en.json")
    .add_local_python_source("i18n_core", "stores", "web", "limrun_demo")
)
site_image = image.add_local_dir(SITE, "/site") if SITE.exists() else image

volume = modal.Volume.from_name("mapa-i18n", create_if_missing=True)
state = modal.Dict.from_name("mapa-i18n-state", create_if_missing=True)


def _store():
    from stores import ModalStore

    return ModalStore(volume, state)


def _catalog() -> dict:
    return json.loads(Path("/config/en.json").read_text(encoding="utf-8"))


@app.function(image=image, volumes={"/data": volume}, secrets=translation_secrets, timeout=1800)
def translate_job(lang: str) -> dict:
    """One language, start to finish. Started by the web app on a visitor's first request."""
    import i18n_core
    from web import run_job

    return run_job(_store(), _catalog(), lang, i18n_core.baseten_call())


@app.function(image=site_image, volumes={"/data": volume}, secrets=limrun_secrets, timeout=300, max_containers=1)
@modal.concurrent(max_inputs=40)
@modal.asgi_app()
def web():
    from web import make_app

    import os

    demo_sessions = None
    if os.environ.get("LIM_API_KEY"):
        from limrun_api import AsyncLimrun

        demo_sessions = LimrunDemoSessions(
            AsyncLimrun(api_key=os.environ["LIM_API_KEY"]),
            {
                "parent": os.environ["MAPA_LIMRUN_PARENT_ASSET"],
                "family": os.environ["MAPA_LIMRUN_FAMILY_ASSET"],
            },
            os.environ.get("MAPA_DEMO_ACCESS_CODE", ""),
        )

    return make_app(
        catalog=_catalog(),
        store=_store(),
        start_job=lambda lang: translate_job.spawn(lang),
        site_dir=Path("/site"),
        enabled=os.environ.get("MAPA_TRANSLATE") == "1",
        demo_sessions=demo_sessions,
    )


@app.function(image=image, secrets=translation_secrets, timeout=600)
def sample(lang: str, count: int) -> dict:
    """A few catalog strings through the real model: checks the key, the reply format and the time it takes."""
    import time

    import i18n_core

    strings = dict(list(_catalog()["strings"].items())[:count])
    t0 = time.time()
    done, stats = i18n_core.translate_catalog(strings, lang, i18n_core.baseten_call(), workers=1)
    return {"seconds": round(time.time() - t0, 1), "stats": stats, "pairs": [[strings[k], done.get(k)] for k in strings]}


@app.local_entrypoint()
def smoke(lang: str = "hi", count: int = 5):
    """modal run server/modal_app.py::smoke --lang hi"""
    result = sample.remote(lang, count)
    print(f"{lang}: {result['stats']} in {result['seconds']} s")
    for en, out in result["pairs"]:
        print(f"  {en}\n  -> {out}")


@app.local_entrypoint()
def bundle(langs: str = ""):
    """Translate the bundled languages on Modal and write them to public/i18n/, so they ship with the site."""
    config = json.loads(LANGUAGES.read_text(encoding="utf-8"))
    codes = [c.strip() for c in langs.split(",") if c.strip()] or config["bundled"]
    out_dir = ROOT / "public" / "i18n"
    for code, data in zip(codes, translate_job.map(codes)):
        data["source"] = "bundled"
        (out_dir / f"{code}.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        stats = data["stats"]
        print(f"{code}: {stats['translated']}/{stats['total']} strings, {stats['tokens']} tokens")

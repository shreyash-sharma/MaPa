"""The site and its translation API in one app, so the page and /api share an origin.

GET /api/i18n/{lang} answers with the finished translation (200), or starts one and answers 202 with progress
until it is ready. Only languages on the allowlist are accepted, each visitor can start a few new languages per hour,
and new work stops once the monthly token budget is spent; cached languages keep being served.
"""

from __future__ import annotations

import json
import hashlib
import os
import time
from pathlib import Path
from typing import Callable

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.exceptions import HTTPException

import i18n_core
from limrun_demo import LimrunDemoSessions
from stores import Store

STARTS_PER_HOUR = int(os.environ.get("MAPA_STARTS_PER_HOUR", "4"))
# About $0.85 a month at most at Muse Spark Open's output price ($4.25 per 1M tokens): two or three new languages.
MONTHLY_TOKENS = int(os.environ.get("MAPA_MONTHLY_TOKENS", "200000"))


def _segment_file(path: str) -> str | None:
    """Next.js prefetches `docs/__next.docs.__PAGE__.txt` but exports it as `docs/__next.docs/__PAGE__.txt`."""
    head, _, name = path.replace("\\", "/").rpartition("/")
    if not (name.startswith("__next.") and name.endswith(".txt")):
        return None
    parts = name[len("__next.") : -len(".txt")].split(".")
    if len(parts) < 2:
        return None
    rel = "/".join([f"__next.{parts[0]}", *parts[1:-1], f"{parts[-1]}.txt"])
    return f"{head}/{rel}" if head else rel


class SiteFiles(StaticFiles):
    """The static export. Next.js puts a content hash in every /_next/static name, so those can be kept for a year."""

    async def get_response(self, path, scope):
        mapped = _segment_file(path)
        try:
            response = await super().get_response(path, scope)
        except HTTPException as error:
            # Without a 404.html, a missing file raises instead of returning the not-found page.
            if error.status_code != 404 or not mapped:
                raise
            return await super().get_response(mapped, scope)
        if response.status_code == 404 and mapped:
            return await super().get_response(mapped, scope)
        if path.replace("\\", "/").startswith("_next/static/"):
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        elif path.endswith((".png", ".jpg", ".svg", ".woff2")):
            response.headers["Cache-Control"] = "public, max-age=86400"
        else:
            # Pages, prefetch data and translations change on every deploy; the files carry a 1970 timestamp on Modal,
            # so without this browsers would guess they can keep them for years.
            response.headers["Cache-Control"] = "no-cache"
        return response


def load_catalog(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def run_job(store: Store, catalog: dict, lang: str, call: i18n_core.Call) -> dict:
    """Translate the whole catalog into one language and keep the result. Always releases the lock."""
    version = catalog["version"]
    try:
        # A string's id is a hash of its English text, so a translation made for an earlier copy is still right
        # for every string that has not changed. Only the new or edited strings go to the model.
        before = (store.previous(version, lang) or {}).get("strings", {})
        kept = {k: before[k] for k in catalog["strings"] if k in before}
        todo = {k: v for k, v in catalog["strings"].items() if k not in kept}
        total = len(catalog["strings"])
        fresh, stats = i18n_core.translate_catalog(
            todo, lang, call, on_progress=lambda done, _: store.set_progress(version, lang, len(kept) + done, total)
        ) if todo else ({}, {"tokens": 0, "translated": 0, "total": 0})
        strings = {**kept, **fresh}
        stats = {**stats, "reused": len(kept), "translated": len(strings), "total": total}
        data = {
            "version": version,
            "lang": lang,
            "source": "live",
            "model": i18n_core.MODEL,
            "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "status": "machine",
            "stats": stats,
            "strings": strings,
        }
        store.put(version, lang, data)
        store.add_tokens(stats["tokens"])
        return data
    finally:
        store.unlock(version, lang)


def make_app(
    *,
    catalog: dict,
    store: Store,
    start_job: Callable[[str], None],
    site_dir: Path | None = None,
    cors: list[str] | None = None,
    enabled: bool = True,
    demo_sessions: LimrunDemoSessions | None = None,
) -> FastAPI:
    app = FastAPI(title="MaPa site", docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(GZipMiddleware, minimum_size=1000)
    if cors:
        app.add_middleware(CORSMiddleware, allow_origins=cors, allow_methods=["GET"], allow_headers=["*"])
    version = catalog["version"]

    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True, "version": version, "strings": len(catalog["strings"]), "model": i18n_core.MODEL, "live_translation": enabled}

    @app.post("/api/demo/start")
    async def start_demo(request: Request) -> dict:
        if demo_sessions is None:
            return JSONResponse({"error": "The cloud demo is not configured yet."}, status_code=503)
        try:
            body = await request.json()
        except Exception:
            return JSONResponse({"error": "Invalid demo request."}, status_code=400)
        role = body.get("role") if isinstance(body, dict) else None
        owner = body.get("owner") if isinstance(body, dict) else None
        if not isinstance(role, str) or not isinstance(owner, str):
            return JSONResponse({"error": "Invalid demo request."}, status_code=400)
        return await demo_sessions.start(request, role, owner)

    @app.post("/api/demo/stop")
    async def stop_demo(request: Request) -> dict:
        if demo_sessions is None:
            return {"ok": True}
        try:
            body = await request.json()
        except Exception:
            return JSONResponse({"error": "Invalid stop request."}, status_code=400)
        instance_id = body.get("id") if isinstance(body, dict) else None
        session_key = body.get("sessionKey") if isinstance(body, dict) else None
        if not isinstance(instance_id, str) or not isinstance(session_key, str):
            return JSONResponse({"error": "Invalid stop request."}, status_code=400)
        return await demo_sessions.stop(request, instance_id, session_key)

    @app.get("/api/i18n/{lang}")
    def translation(lang: str, request: Request) -> JSONResponse:
        lang = lang.lower()
        if i18n_core.language(lang) is None:
            return JSONResponse({"error": "unsupported language"}, status_code=404)
        done = store.get(version, lang)
        if done:
            return JSONResponse(done, headers={"Cache-Control": "public, max-age=3600, stale-while-revalidate=86400"})
        progress = store.progress(version, lang)
        if progress is not None:
            return JSONResponse({"status": "pending", **progress}, status_code=202, headers={"Cache-Control": "no-store"})
        if not enabled:
            return JSONResponse({"error": "live translation is not set up yet"}, status_code=503)
        if store.tokens_this_month() >= MONTHLY_TOKENS:
            return JSONResponse({"error": "translation budget reached"}, status_code=503)
        client = (request.headers.get("x-forwarded-for") or (request.client.host if request.client else "?")).split(",")[0].strip()
        if not store.allow_start(client, STARTS_PER_HOUR):
            return JSONResponse({"error": "too many new languages, try again later"}, status_code=429)
        if store.lock(version, lang):
            store.set_progress(version, lang, 0, len(catalog["strings"]))
            start_job(lang)
        return JSONResponse({"status": "pending", "done": 0, "total": len(catalog["strings"])}, status_code=202, headers={"Cache-Control": "no-store"})

    @app.post("/api/app-translate/{lang}")
    async def app_translation(lang: str, request: Request) -> JSONResponse:
        """Translate fixed parent-app interface copy. The app sends no names or telemetry."""
        lang = lang.lower()
        if lang in {"en", "hi"} or i18n_core.language(lang, parent_app=True) is None:
            return JSONResponse({"error": "unsupported language"}, status_code=404)
        if not enabled or store.tokens_this_month() >= MONTHLY_TOKENS:
            return JSONResponse({"error": "translation is unavailable"}, status_code=503)
        client = (request.headers.get("x-forwarded-for") or (request.client.host if request.client else "?")).split(",")[0].strip()
        if not store.allow_start(client, STARTS_PER_HOUR):
            return JSONResponse({"error": "too many translation requests"}, status_code=429)
        try:
            body = await request.json()
            values = body.get("strings") if isinstance(body, dict) else None
            if not isinstance(values, list) or not 1 <= len(values) <= 300:
                raise ValueError("invalid strings")
            if any(not isinstance(value, str) or not value.strip() or len(value) > 500 for value in values):
                raise ValueError("invalid strings")
        except Exception:
            return JSONResponse({"error": "invalid translation request"}, status_code=400)
        source = {str(index): value for index, value in enumerate(dict.fromkeys(values))}
        cache_key = "parent-ui-" + hashlib.sha256((lang + json.dumps(source, ensure_ascii=False, sort_keys=True)).encode("utf-8")).hexdigest()
        cached = store.get(cache_key, lang)
        if cached:
            return JSONResponse({"translations": cached.get("translations", {})}, headers={"Cache-Control": "no-store"})
        try:
            translated, stats = i18n_core.translate_catalog(
                source, lang, i18n_core.baseten_call(), workers=2, parent_app=True
            )
            store.add_tokens(stats["tokens"])
            store.put(cache_key, lang, {"status": "complete", "translations": translated})
            return JSONResponse({"translations": translated}, headers={"Cache-Control": "no-store"})
        except Exception:
            return JSONResponse({"error": "translation failed"}, status_code=502)

    if site_dir and Path(site_dir).exists():
        app.mount("/", SiteFiles(directory=site_dir, html=True), name="site")
    return app

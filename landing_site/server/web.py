"""The site and its translation API in one app, so the page and /api share an origin.

GET /api/i18n/{lang} answers with the finished translation (200), or starts one and answers 202 with progress
until it is ready. Only languages on the allowlist are accepted, each visitor can start a few new languages per hour,
and new work stops once the monthly token budget is spent; cached languages keep being served.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Callable

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

import i18n_core
from stores import Store

STARTS_PER_HOUR = int(os.environ.get("MAPA_STARTS_PER_HOUR", "4"))
# About $0.85 a month at most at Muse Spark Open's output price ($4.25 per 1M tokens): two or three new languages.
MONTHLY_TOKENS = int(os.environ.get("MAPA_MONTHLY_TOKENS", "200000"))


class SiteFiles(StaticFiles):
    """The static export. Next.js puts a content hash in every /_next/static name, so those can be kept for a year."""

    async def get_response(self, path, scope):
        response = await super().get_response(path, scope)
        if path.startswith("_next/static/"):
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        elif path.endswith((".png", ".jpg", ".svg", ".woff2")):
            response.headers["Cache-Control"] = "public, max-age=86400"
        return response


def load_catalog(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def run_job(store: Store, catalog: dict, lang: str, call: i18n_core.Call) -> dict:
    """Translate the whole catalog into one language and keep the result. Always releases the lock."""
    version = catalog["version"]
    try:
        strings, stats = i18n_core.translate_catalog(
            catalog["strings"], lang, call, on_progress=lambda done, total: store.set_progress(version, lang, done, total)
        )
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
) -> FastAPI:
    app = FastAPI(title="MaPa site", docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(GZipMiddleware, minimum_size=1000)
    if cors:
        app.add_middleware(CORSMiddleware, allow_origins=cors, allow_methods=["GET"], allow_headers=["*"])
    version = catalog["version"]

    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True, "version": version, "strings": len(catalog["strings"]), "model": i18n_core.MODEL, "live_translation": enabled}

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

    if site_dir and Path(site_dir).exists():
        app.mount("/", SiteFiles(directory=site_dir, html=True), name="site")
    return app

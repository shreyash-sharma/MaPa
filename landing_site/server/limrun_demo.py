"""One-at-a-time Limrun Android demo sessions for the public judge page."""

from __future__ import annotations

import asyncio
import hmac
import secrets
import time
from dataclasses import dataclass
from typing import Any

from fastapi import HTTPException, Request


@dataclass
class ActiveDemo:
    instance_id: str
    role: str
    owner: str
    session_key: str
    websocket_url: str
    token: str


class LimrunDemoSessions:
    """Owns at most one public demo emulator in this single-container service."""

    def __init__(self, client: Any, assets: dict[str, str], access_code: str):
        self.client = client
        self.assets = assets
        self.access_code = access_code
        self._lock = asyncio.Lock()
        self._active: ActiveDemo | None = None
        self._starts: dict[str, list[float]] = {}

    async def start(self, request: Request, role: str, owner: str, access_code: str) -> dict[str, str]:
        if not self.access_code or not hmac.compare_digest(access_code, self.access_code):
            raise HTTPException(status_code=403, detail="Enter the judge access code to start the demo.")
        if role not in self.assets:
            raise HTTPException(status_code=400, detail="Unknown demo app.")
        if len(owner) < 16 or len(owner) > 100:
            raise HTTPException(status_code=400, detail="Invalid demo session.")
        _require_same_origin(request)
        client_ip = _client_ip(request)

        async with self._lock:
            await self._discard_terminated()
            if self._active:
                if self._active.owner == owner and self._active.role == role:
                    return self._response(self._active)
                raise HTTPException(status_code=409, detail="A demo emulator is already in use. Stop it before starting another.")

            now = time.monotonic()
            recent = [stamp for stamp in self._starts.get(client_ip, []) if now - stamp < 600]
            if len(recent) >= 4:
                self._starts[client_ip] = recent
                raise HTTPException(status_code=429, detail="Demo start limit reached. Please wait a few minutes and try again.")

            try:
                instance = await self.client.android_instances.create(
                    wait=True,
                    reuse_if_exists=False,
                    metadata={
                        "display_name": "MaPa Judge Demo",
                        "labels": {"app": "mapa-judge-demo", "role": role},
                    },
                    spec={
                        "inactivity_timeout": "3m",
                        "hard_timeout": "10m",
                        "clues": [
                            {"kind": "OSVersion", "os_version": "15"},
                            *([{"kind": "ClientIP", "client_ip": client_ip}] if client_ip else []),
                        ],
                        "initial_assets": [
                            {"kind": "App", "source": "AssetName", "asset_name": self.assets[role]},
                        ],
                    },
                )
            except Exception as error:
                # Do not put credentials, full SDK response bodies, or device tokens in logs or public errors.
                print(f"Limrun demo start failed ({type(error).__name__}).")
                raise HTTPException(status_code=503, detail="The cloud demo could not start. Please try again shortly.") from None

            active = ActiveDemo(
                instance_id=instance.metadata.id,
                role=role,
                owner=owner,
                session_key=secrets.token_urlsafe(24),
                websocket_url=instance.status.endpoint_web_socket_url,
                token=instance.status.token,
            )
            self._active = active
            recent.append(now)
            self._starts[client_ip] = recent
            return self._response(active)

    async def stop(self, request: Request, instance_id: str, session_key: str) -> dict[str, bool]:
        _require_same_origin(request)
        async with self._lock:
            active = self._active
            if not active or active.instance_id != instance_id or not hmac.compare_digest(active.session_key, session_key):
                return {"ok": True}
            try:
                await self.client.android_instances.delete(active.instance_id)
            except Exception as error:
                print(f"Limrun demo stop failed ({type(error).__name__}).")
                raise HTTPException(status_code=503, detail="The emulator is stopping. It will also expire automatically.") from None
            self._active = None
            return {"ok": True}

    async def _discard_terminated(self) -> None:
        if not self._active:
            return
        try:
            current = await self.client.android_instances.get(self._active.instance_id)
        except Exception:
            self._active = None
            return
        if current.status.state != "ready":
            self._active = None

    @staticmethod
    def _response(active: ActiveDemo) -> dict[str, str]:
        return {
            "id": active.instance_id,
            "role": active.role,
            "sessionKey": active.session_key,
            "endpointWebSocketUrl": active.websocket_url,
            "token": active.token,
        }


def _client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "").split(",", 1)[0].strip()
    if forwarded:
        return forwarded[:64]
    return request.client.host if request.client else "unknown"


def _require_same_origin(request: Request) -> None:
    origin = request.headers.get("origin")
    if not origin:
        raise HTTPException(status_code=403, detail="Open the demo from the MaPa site.")
    host = request.headers.get("x-forwarded-host") or request.headers.get("host") or ""
    origin_host = origin.removeprefix("https://").removeprefix("http://").split("/", 1)[0]
    local = origin_host.startswith(("localhost:", "127.0.0.1:"))
    if origin_host.lower() != host.lower() or (not origin.startswith("https://") and not local):
        raise HTTPException(status_code=403, detail="Open the demo from the MaPa site.")

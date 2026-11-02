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
    """Owns one concurrent emulator per demo app in this single-container service."""

    def __init__(self, client: Any, assets: dict[str, str]):
        self.client = client
        self.assets = assets
        self._lock = asyncio.Lock()
        self._active: dict[str, ActiveDemo] = {}
        self._starts: dict[str, list[float]] = {}

    async def start(self, request: Request, role: str, owner: str) -> dict[str, str]:
        if role not in self.assets:
            raise HTTPException(status_code=400, detail="Unknown demo app.")
        if len(owner) < 16 or len(owner) > 100:
            raise HTTPException(status_code=400, detail="Invalid demo session.")
        _require_same_origin(request)
        client_ip = _client_ip(request)

        async with self._lock:
            await self._discard_terminated()
            current = self._active.get(role)
            if current:
                if current.owner == owner:
                    return self._response(current)
                raise HTTPException(status_code=409, detail="This app already has an active demo session. Stop it before starting another.")
            if len(self._active) >= len(self.assets):
                raise HTTPException(status_code=429, detail="All demo emulators are in use. Stop one and try again.")

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
            self._active[role] = active
            recent.append(now)
            self._starts[client_ip] = recent
            return self._response(active)

    async def stop(self, request: Request, instance_id: str, session_key: str) -> dict[str, bool]:
        _require_same_origin(request)
        async with self._lock:
            role = next((role for role, item in self._active.items() if item.instance_id == instance_id), None)
            active = self._active.get(role) if role else None
            if not active or not hmac.compare_digest(active.session_key, session_key):
                return {"ok": True}
            try:
                await self.client.android_instances.delete(active.instance_id)
            except Exception as error:
                print(f"Limrun demo stop failed ({type(error).__name__}).")
                raise HTTPException(status_code=503, detail="The emulator is stopping. It will also expire automatically.") from None
            self._active.pop(active.role, None)
            return {"ok": True}

    async def _discard_terminated(self) -> None:
        for role, active in list(self._active.items()):
            try:
                current = await self.client.android_instances.get(active.instance_id)
            except Exception:
                continue
            if current.status.state != "ready":
                self._active.pop(role, None)

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

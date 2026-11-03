"""Upload release-signed judge-demo APKs to Limrun Asset Storage.

Usage from landing_site/: modal run server/upload_limrun_demo_assets.py --parent-apk PATH --family-apk PATH
The LIM_API_KEY stays in the Modal `custom-secret`; APK bytes are passed to the short-lived function.
"""

from __future__ import annotations

import hashlib
import os
from pathlib import Path

import httpx
import modal

app = modal.App("mapa-limrun-demo-assets")
image = modal.Image.debian_slim(python_version="3.11").pip_install("limrun-api==0.10.0", "httpx>=0.27")


@app.function(image=image, secrets=[modal.Secret.from_name("custom-secret")], timeout=600)
def upload_asset(name: str, content: bytes, checksum: str) -> dict[str, str | int | bool]:
    from limrun_api import Limrun

    client = Limrun(api_key=os.environ["LIM_API_KEY"])
    asset = client.assets.get_or_create(name=name)
    if asset.md5 and asset.md5.lower() == checksum.lower():
        return {"name": name, "id": asset.id, "bytes": len(content), "unchanged": True}
    response = httpx.put(
        asset.signed_upload_url,
        content=content,
        headers={"Content-Type": "application/vnd.android.package-archive"},
        timeout=300,
    )
    response.raise_for_status()
    verified = client.assets.get(asset.id)
    return {"name": name, "id": asset.id, "bytes": len(content), "md5": verified.md5 or "", "unchanged": False}


@app.local_entrypoint()
def main(parent_apk: str, family_apk: str):
    for name, path in (
        ("mapa-parent-judge-demo-2026-11-03.apk", Path(parent_apk)),
        ("mapa-family-judge-demo-2026-11-03.apk", Path(family_apk)),
    ):
        content = path.read_bytes()
        digest = hashlib.md5(content).hexdigest()
        print(upload_asset.remote(name, content, digest))

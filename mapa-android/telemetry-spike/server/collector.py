#!/usr/bin/env python3
"""Tiny heartbeat receiver for the MaPa telemetry spike. Python 3 stdlib only.

  python3 collector.py                 # listens on 0.0.0.0:8787
  MAPA_KEY=secret python3 collector.py # phones must post to /v1/heartbeat?key=secret

POST /v1/heartbeat   JSON body from the app, appended to heartbeats.jsonl with receivedAt
GET  /               per-device freshness table (auto-refresh), the "family view" of the test
GET  /v1/devices     same data as JSON
"""
import html
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("MAPA_PORT", "8787"))
LOG = os.environ.get("MAPA_LOG", "heartbeats.jsonl")
KEY = os.environ.get("MAPA_KEY", "")
# Mirrors the product state machine: older than this, a family viewer must see "monitoring unavailable".
FRESH_MIN, STALE_MIN = 30, 60

latest = {}
if os.path.exists(LOG):
    with open(LOG) as f:
        for line in f:
            try:
                rec = json.loads(line)
                latest[rec.get("deviceId", "?")] = rec
            except ValueError:
                pass


def now_ms():
    return int(time.time() * 1000)


def fmt_age(ms):
    if not ms:
        return "never"
    m = (now_ms() - ms) // 60000
    return f"{m} min" if m < 120 else f"{m // 60} h {m % 60} min"


class Handler(BaseHTTPRequestHandler):
    def _authorized(self):
        return not KEY or parse_qs(urlparse(self.path).query).get("key", [""])[0] == KEY

    def do_POST(self):
        if urlparse(self.path).path != "/v1/heartbeat" or not self._authorized():
            return self.send_error(404)
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 16384:
            return self.send_error(400)
        try:
            body = json.loads(self.rfile.read(length))
        except ValueError:
            return self.send_error(400)
        rec = {"receivedAt": now_ms(), **body}
        with open(LOG, "a") as f:
            f.write(json.dumps(rec) + "\n")
        latest[rec.get("deviceId", "?")] = rec
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/v1/devices":
            data = json.dumps(list(latest.values()), indent=2).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            return self.wfile.write(data)
        if path != "/":
            return self.send_error(404)
        rows = []
        for rec in sorted(latest.values(), key=lambda r: r.get("model", "")):
            age = (now_ms() - rec["receivedAt"]) / 60000
            state = "paused" if rec.get("paused") else (
                "fresh" if age <= FRESH_MIN else "late" if age <= STALE_MIN else "MONITORING UNAVAILABLE")
            cells = [rec.get("model", "?"), rec.get("deviceId", "?")[:8], state, fmt_age(rec["receivedAt"]),
                     fmt_age(rec.get("lastInteraction")), f'{rec.get("battery", "?")}% {rec.get("plugged", "")}',
                     rec.get("source", ""), "yes" if rec.get("usageAccess") else "NO", rec.get("standbyBucket", "")]
            rows.append("<tr>" + "".join(f"<td>{html.escape(str(c))}</td>" for c in cells) + "</tr>")
        page = f"""<!doctype html><meta charset=utf-8><meta http-equiv=refresh content=60>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>MaPa spike</title><style>body{{font:14px system-ui;margin:16px}}td,th{{padding:4px 8px;border-bottom:1px solid #ddd;text-align:left}}</style>
<h1>MaPa telemetry spike</h1><p>Fresh &le; {FRESH_MIN} min, late &le; {STALE_MIN} min, otherwise monitoring unavailable.</p>
<table><tr><th>Phone<th>Id<th>State<th>Last sync<th>Last interaction<th>Battery<th>Woken by<th>Usage access<th>Bucket</tr>
{''.join(rows) or '<tr><td colspan=9>No heartbeats yet</td></tr>'}</table>"""
        data = page.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"MaPa spike collector on :{PORT}, writing {LOG}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

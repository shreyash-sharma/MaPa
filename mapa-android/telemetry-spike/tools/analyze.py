#!/usr/bin/env python3
"""Turns MaPa spike logs into a reliability report (Markdown on stdout). Python 3 stdlib only.

  python3 analyze.py phone-export.jsonl [more exports...]   # logs shared from the app
  python3 analyze.py ../server/heartbeats.jsonl             # server log, grouped per phone

The question it answers: could a family dashboard have honestly said "this phone's data is current"?
"""
import json
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime

THRESHOLDS = (30, 60, 180)  # minutes


def load(path):
    out = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass
    return out


def ts(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%a %d %b %H:%M")


def gap_stats(times, start, end):
    """Gaps (minutes) between consecutive reports, including the open gap at the end of the window."""
    pts = sorted(times)
    if not pts:
        return [], {}
    gaps = [(b - a) / 60000 for a, b in zip(pts, pts[1:])]
    window = (end - start) / 60000 or 1
    edges = [(pts[0] - start) / 60000] + gaps + [(end - pts[-1]) / 60000]
    # Share of the window during which the newest report was older than each threshold.
    stale = {t: sum(max(0, g - t) for g in edges) / window * 100 for t in THRESHOLDS}
    return gaps, stale


def describe(gaps):
    if not gaps:
        return "n/a"
    q = sorted(gaps)
    p90 = q[min(len(q) - 1, int(len(q) * 0.9))]
    return f"median {statistics.median(q):.0f}m, p90 {p90:.0f}m, max {q[-1]:.0f}m"


def report_device(name, events):
    events.sort(key=lambda e: e.get("t", 0))
    beats = [e for e in events if e.get("type") == "heartbeat"]
    start, end = events[0]["t"], events[-1]["t"]
    hours = (end - start) / 3_600_000
    models = {e.get("model") for e in events if e.get("model")}
    print(f"## {name}\n")
    print(f"- Phone: {', '.join(sorted(models)) or 'unknown'}")
    print(f"- Window: {ts(start)} to {ts(end)} ({hours:.1f} h), {len(events)} events\n")

    print("### Heartbeats by wake-up path\n")
    print("| Path | Count | Expected (every 15m) | Gaps | >30m | >60m |")
    print("|---|---|---|---|---|---|")
    for src in ("work", "alarm", "boot", "manual"):
        times = [b["t"] for b in beats if b.get("source") == src]
        if not times:
            continue
        gaps, _ = gap_stats(times, start, end)
        expected = int(hours * 4) if src in ("work", "alarm") else "-"
        print(f"| {src} | {len(times)} | {expected} | {describe(gaps)} | "
              f"{sum(g > 30 for g in gaps)} | {sum(g > 60 for g in gaps)} |")
    gaps, stale = gap_stats([b["t"] for b in beats], start, end)
    print(f"| **any** | {len(beats)} | | {describe(gaps)} | {sum(g > 30 for g in gaps)} | {sum(g > 60 for g in gaps)} |\n")
    print("Share of the window where the newest heartbeat was older than: " +
          ", ".join(f"{t}m: **{v:.1f}%**" for t, v in stale.items()) + "\n")

    print("### Worst gaps\n")
    pts = sorted(beats, key=lambda b: b["t"])
    worst = sorted(zip(pts, pts[1:]), key=lambda p: p[1]["t"] - p[0]["t"], reverse=True)[:5]
    for a, b in worst:
        mins = (b["t"] - a["t"]) / 60000
        if mins <= 20:
            break
        why = []
        if a.get("boot") != b.get("boot"):
            why.append("reboot in between")
        exits = [e for e in events if e.get("type") == "process_exit" and a["t"] <= e.get("at", 0) <= b["t"]]
        if exits:
            why.append("process killed: " + ", ".join(sorted({e.get("reason", "?") for e in exits})))
        if a.get("deviceIdle") or b.get("deviceIdle"):
            why.append("Doze active")
        if b.get("powerSave"):
            why.append("battery saver")
        why.append(f"battery {a.get('battery')}%→{b.get('battery')}%, plugged {a.get('plugged')}→{b.get('plugged')}")
        print(f"- {ts(a['t'])} → {ts(b['t'])}: **{mins:.0f} min**, resumed by `{b.get('source')}`; " + "; ".join(why))
    print()

    print("### Lifecycle\n")
    print(f"- Distinct boots: {len({e.get('boot') for e in events})}, boot broadcasts received: "
          f"{sum(e.get('type') == 'boot' for e in events)}, reboots detected: {sum(e.get('type') == 'reboot_detected' for e in events)}")
    print(f"- Process starts: {sum(e.get('type') == 'process_start' for e in events)}")
    exits = Counter(e.get("reason") for e in events if e.get("type") == "process_exit")
    print(f"- Process exits (Android 11+): {dict(exits) or 'none recorded'}")
    buckets = Counter(b.get("standbyBucket") for b in beats if b.get("standbyBucket"))
    print(f"- Standby bucket at heartbeat: {dict(buckets) or 'n/a'}")
    print(f"- Heartbeats taken while Doze was on: {sum(bool(b.get('deviceIdle')) for b in beats)}")
    no_access = sum(b.get("usageAccess") is False for b in beats)
    print(f"- Heartbeats without Usage Access: {no_access}" + (" ⚠️ activity unknown" if no_access else ""))
    print()

    print("### Activity signal\n")
    counts = Counter()
    minutes = set()
    for b in beats:
        u = b.get("usage") or {}
        counts.update(u.get("counts") or {})
        minutes.update(u.get("activeMinutes") or [])
    live = Counter(e.get("action") for e in events if e.get("type") == "screen")
    print(f"- From Usage Access (survives our process dying): unlocks {counts.get('keyguardHidden', 0)}, "
          f"screen-on {counts.get('screenOn', 0)}, app opens {counts.get('foreground', 0)}, active minutes {len(minutes)}")
    print(f"- Seen live by receiver (only while process alive): unlocks {live.get('USER_PRESENT', 0)}, "
          f"screen-on {live.get('SCREEN_ON', 0)}")
    if counts.get("screenOn"):
        print(f"- Live receiver coverage of screen-on: {live.get('SCREEN_ON', 0) / counts['screenOn'] * 100:.0f}%")
    sync = Counter((b.get("sync") or {}).get("status", "?") for b in beats)
    print(f"- Sync results: {dict(sync)}\n")


def report_server(records):
    by_dev = defaultdict(list)
    for r in records:
        by_dev[r.get("deviceId", "?")].append(r)
    print("## Server view (what a family dashboard would have seen)\n")
    print("| Phone | Syncs | Window | Gaps | Stale >30m | Stale >60m | Stale >3h |")
    print("|---|---|---|---|---|---|---|")
    for dev, recs in by_dev.items():
        times = [r["receivedAt"] for r in recs]
        start, end = min(times), max(times)
        gaps, stale = gap_stats(times, start, end)
        print(f"| {recs[-1].get('model', '?')} ({dev[:8]}) | {len(recs)} | {(end - start) / 3_600_000:.1f} h | "
              f"{describe(gaps)} | {stale[30]:.1f}% | {stale[60]:.1f}% | {stale[180]:.1f}% |")
    print()


def main(paths):
    if not paths:
        sys.exit(__doc__)
    print("# MaPa telemetry spike report\n")
    for p in paths:
        recs = load(p)
        if not recs:
            print(f"_{p}: empty_\n")
        elif "receivedAt" in recs[0]:
            report_server(recs)
        else:
            report_device(p.rsplit("/", 1)[-1], recs)


if __name__ == "__main__":
    main(sys.argv[1:])

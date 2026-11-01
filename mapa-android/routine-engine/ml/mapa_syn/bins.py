"""Canonical bins preserve unknown telemetry instead of inventing silence."""
from collections import defaultdict

FIELDS = ["pid", "t", "interactions", "phone_on", "usage_ok", "network_ok", "charging", "battery", "paused", "moved", "present", "coverage_min", "observation_basis"]
CHANNELS = FIELDS[2:10]
OBSERVATION_BASES = {"source_recorded", "heartbeat_inferred", "synthetic", "synthetic_learned", "unknown"}


def validate_rows(rows):
    previous = {}
    for r in rows:
        if set(r) != set(FIELDS):
            raise ValueError("Unexpected canonical fields")
        pid, t = r["pid"], int(r["t"])
        if t != previous.get(pid, -1) + 1:
            raise ValueError("Clock must be complete, unique, and ordered within person")
        previous[pid] = t
        for field in ["phone_on", "usage_ok", "network_ok", "charging", "paused", "moved"]:
            if int(r[field]) not in {-1, 0, 1}:
                raise ValueError(f"Invalid {field}")
        if not -1 <= int(r["battery"]) <= 100 or int(r["interactions"]) < -1:
            raise ValueError("Invalid counts or battery")
        if not 0 <= int(r["coverage_min"]) <= 15:
            raise ValueError("Invalid coverage")
        if int(r["usage_ok"]) == 1 and int(r["phone_on"]) != 0 and int(r["present"]) != 0 and int(r["coverage_min"]) != 15:
            raise ValueError("Partial observations cannot be marked fully observed")
        if r["observation_basis"] not in OBSERVATION_BASES:
            raise ValueError("Invalid observation_basis")
        if r["observation_basis"] == "heartbeat_inferred" and (
            int(r["coverage_min"]) != 15 or int(r["usage_ok"]) != 1 or int(r["interactions"]) < 0
        ):
            raise ValueError("Heartbeat inference requires all 15 minutes and known interactions")
    return len(previous)


def longest_observed_run(rows):
    best = defaultdict(int)
    current = defaultdict(int)
    for r in rows:
        p = r["pid"]
        current[p] = current[p] + 1 if int(r["usage_ok"]) == 1 and int(r["interactions"]) >= 0 else 0
        best[p] = max(best[p], current[p])
    return dict(best)

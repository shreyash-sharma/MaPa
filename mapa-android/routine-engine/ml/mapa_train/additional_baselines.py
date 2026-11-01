"""Additive train-OOF baselines, scored by the shared evaluator.

This module does not fit a model or open validation/test. It adds the fixed
8-hour rule after the frozen v3 fits, without changing their source hashes.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq

from mapa_release.common import ROOT, trial
from .decision import DAY, score_config, stats_limits
from .protocol import DATA, verify_data


def _score_eight_hour(views):
    # Reuse score_config's alert counting, severity strata, stale guard and
    # person-bootstrap intervals. A binary score makes its global threshold
    # implement exactly the fixed 32-bin (8-hour) silence rule.
    for row in views:
        row["score_behavior"] = (row["silence"] >= 32).astype(float)
    stats_limits(views)
    result = score_config(views, {}, {
        "kind": "global", "floor": 0.5, "score_kind": "behaviour",
        "source": "fixed 8-hour rule",
    })
    result["eight_hour_rule"] = result.pop("model")
    result["metric_scope"] = "train OOF only"
    return result


def _train_day_views():
    """Stream only train columns needed by score_config; avoid a full pandas frame."""
    columns = ["pid", "episode_id", "day_index", "t", "present", "phone_on",
               "usage_ok", "network_ok", "observed", "life_tag", "window",
               "interactions", "expected", "severity"]
    days = {}
    parquet = pq.ParquetFile(DATA / "train.parquet")
    for batch in parquet.iter_batches(batch_size=100_000, columns=columns):
        data = batch.to_pydict()
        for i in range(batch.num_rows):
            pid = str(data["pid"][i])
            raw_eid = data["episode_id"][i]
            eid = "" if raw_eid is None else str(raw_eid)
            day = int(data["day_index"][i])
            key = (pid, eid, day)
            row = days.get(key)
            if row is None:
                row = {
                    "pid": pid, "eid": eid, "day": day,
                    "expect": str(data["expected"][i]),
                    "severity": str(data["severity"][i]),
                    "avail": np.zeros(96, dtype=bool),
                    "tags": np.zeros(96, dtype=bool),
                    "win": np.zeros(96, dtype=bool),
                    "counts": np.full(96, -1, dtype=np.int32),
                }
                days[key] = row
            slot = int(data["t"][i]) % 96
            available = all(bool(data[name][i]) for name in
                            ("present", "phone_on", "usage_ok", "network_ok", "observed"))
            row["avail"][slot] = available
            row["tags"][slot] = bool(data["life_tag"][i])
            row["win"][slot] = bool(data["window"][i])
            value = data["interactions"][i]
            row["counts"][slot] = -1 if value is None or not available else int(value)

    views = []
    for row in days.values():
        silence = np.zeros(96, dtype=np.int16)
        streak = 0
        for slot in range(96):
            streak = streak + 1 if row["avail"][slot] and DAY[slot] and row["counts"][slot] == 0 else 0
            silence[slot] = streak
        row["silence"] = silence
        row["score_behavior"] = (silence >= 32).astype(np.float32)
        row["score_surprise"] = np.zeros(96, dtype=np.float32)
        row["alert_eligible"] = row["avail"] & DAY & (row["counts"] == 0)
        views.append(row)
    return views


def run() -> Path:
    trial("additional-baseline", "eight-hour-rule-train-oof", "STARTED",
          "train-only Parquet stream; validation/test unopened; shared score_config evaluator")
    try:
        verify_data()
        protocol_path = ROOT / "training-protocol-v3.json"
        protocol_sha256 = hashlib.sha256(protocol_path.read_bytes()).hexdigest()
        views = _train_day_views()
        fold_map = json.loads(protocol_path.read_text())["fold_assignment"]
        report = {
            "protocol_sha256": protocol_sha256,
            "dataset_manifest_sha256": hashlib.sha256((DATA / "manifest.json").read_bytes()).hexdigest(),
            "metric_scope": "train OOF only; no validation/test rows opened",
            "baseline": "8-hour rule (32 consecutive observed daytime quiet bins)",
            "aggregate": _score_eight_hour(views),
            "folds": {},
        }
        for fold in range(5):
            held = [v for v in views if int(fold_map[v["pid"]]) == fold]
            report["folds"][str(fold)] = _score_eight_hour(held)
        dest = ROOT / "results" / "additional-baselines-train-oof.json"
        dest.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        trial("additional-baseline", "eight-hour-rule-train-oof", "COMPLETE",
              json.dumps({"output": str(dest), "people": report["aggregate"]["people"],
                          "rows": len(views)}))
        return dest
    except Exception as exc:
        trial("additional-baseline", "eight-hour-rule-train-oof", "FAIL", repr(exc))
        raise


if __name__ == "__main__":
    print(run())

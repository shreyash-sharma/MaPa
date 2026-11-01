"""Train-only, age-stratified 8-hour baseline for the cold-start report.

Uses the frozen final-v2 train partition only. It does not fit a detector or
read validation/test rows. Run after the currently active OOF evaluator to
avoid competing for CPU.
"""
from __future__ import annotations

import json
from pathlib import Path

from mapa_release.common import ROOT, trial
from .additional_baselines import _score_eight_hour, _train_day_views
from .protocol import DATA, sha256, verify_data

BANDS = (("0-1", 0, 1), ("2-3", 2, 3), ("4-7", 4, 7),
         ("8-14", 8, 14), ("15+", 15, float("inf")))


def run() -> dict:
    trial("cold-start-baseline", "8h-by-install-age-train-oof", "STARTED",
          "final-v2 train only; no fitting; validation/test unopened")
    try:
        verify_data("train")
        views = _train_day_views()
        origins: dict[str, int] = {}
        for view in views:
            if not view["eid"]:
                pid = str(view["pid"])
                origins[pid] = min(origins.get(pid, int(view["day"])), int(view["day"]))
        for view in views:
            pid = str(view["pid"])
            if pid not in origins:
                raise ValueError(f"No untouched install-origin day for pid {pid}")
            view["days_since_install"] = int(view["day"]) - origins[pid]

        strata = {}
        for label, lo, hi in BANDS:
            selected = [v for v in views if lo <= v["days_since_install"] <= hi]
            behavior = [v for v in selected if v["eid"] and v["expect"] == "unusual_inactivity"]
            result = _score_eight_hour(selected)
            strata[label] = {
                "people": len({str(v["pid"]) for v in selected}),
                "behavior_episodes": {
                    severity: sum(v["severity"] == severity for v in behavior)
                    for severity in ("mild", "clear", "severe")
                },
                "eight_hour_rule": result["eight_hour_rule"],
                "observation_episodes_separate": result["observation_episodes_separate"],
                "hard_negative_alert_rate": result["hard_negative_alert_rate"],
                "stale_as_fresh": result["stale_as_fresh"],
            }
        report = {
            "metric_scope": "final-v2 train only; pooled person-held-out OOF baseline; no detector fitting",
            "install_origin": "first available untouched day, simulated install",
            "dataset_manifest_sha256": sha256(DATA / "manifest.json"),
            "strata": strata,
            "validation_opened": False,
            "test_opened": False,
        }
        out = ROOT / "results" / "cold-start-8h-train-oof.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        trial("cold-start-baseline", "8h-by-install-age-train-oof", "COMPLETE",
              f"five age bands; people={len({str(v['pid']) for v in views})}; output={out.name}")
        return report
    except Exception as exc:
        trial("cold-start-baseline", "8h-by-install-age-train-oof", "FAIL",
              f"{type(exc).__name__}: {exc}")
        raise


if __name__ == "__main__":
    print(json.dumps(run(), indent=2))
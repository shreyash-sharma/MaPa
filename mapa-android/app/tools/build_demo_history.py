"""Extract the identity-free Ma/Papa app demo history from frozen final-v2 train data.

Only model-derived baseline rows are read. The 12-hour alerts are calculated from
observed quiet intervals for the interactive demo; they are not device notifications.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = Path(r"D:\GitHub\MaPa\data\MaPaData\mapa-rhythm-release\final-v2\train.parquet")
OUTPUT = ROOT / "shared" / "src" / "main" / "assets" / "demo" / "demo-history.json"
PEOPLE = (("Ma", "derived-0000", 0, 7), ("Papa", "derived-0001", 90, 120))
REQUIRED = ["pid", "subset", "provenance", "scenario", "day_index", "t", "dow", "interactions", "observed", "charging", "battery"]
WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


def alert_for_day(day: dict) -> dict | None:
    """Return a demo check-in at the 12-hour continuous-observed quiet backstop."""
    observed = day["observed"]
    active = [seen and count > 0 for seen, count in zip(observed, day["interactions"])]
    best_start, best_length = -1, 0
    bin_index = 24  # 06:00
    while bin_index < 84:  # through 20:45
        if not observed[bin_index] or active[bin_index]:
            bin_index += 1
            continue
        start = bin_index
        while bin_index < 84 and observed[bin_index] and not active[bin_index]:
            bin_index += 1
        if bin_index - start > best_length:
            best_start, best_length = start, bin_index - start
    if best_length < 48:
        return None
    first_use_after = next((i for i in range(best_start + 48, 84) if observed[i] and active[i]), None)
    return {
        "day_number": day["day_number"],
        "day_offset": day["day_offset"],
        "weekday": day["weekday"],
        "alert_bin": best_start + 48,
        "quiet_minutes": best_length * 15,
        "activity_resumed_bin": first_use_after,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    source_hash = hashlib.sha256(args.source.read_bytes()).hexdigest()
    frame = pd.read_parquet(args.source, columns=REQUIRED)
    baseline = frame[
        (frame["subset"] == "derived")
        & (frame["provenance"] == "model_derived")
        & (frame["scenario"] == 0)
    ]

    result = {
        "version": 1,
        "source": "MaPa-Rhythm final-v2 train.parquet",
        "source_sha256": source_hash,
        "split": "train",
        "subset": "derived",
        "provenance": "model_derived",
        "bin_minutes": 15,
        "alert_rule": "12 hours of continuous observed quiet between 06:00 and 21:00; demo record only",
        "people": [],
    }
    for label, source_pid, start_day, end_day in PEOPLE:
        rows = baseline[(baseline["pid"] == source_pid) & baseline["day_index"].between(start_day, end_day - 1)]
        days = []
        for number, (day_index, group) in enumerate(rows.groupby("day_index", sort=True), start=1):
            group = group.sort_values("t")
            expected_t = [int(day_index) * 96 + i for i in range(96)]
            if len(group) != 96 or group["t"].tolist() != expected_t:
                raise ValueError(f"Expected 96 quarter-hour rows for {label} day {day_index}.")
            if not (group["provenance"] == "model_derived").all() or not (group["subset"] == "derived").all():
                raise ValueError("Demo fixture may only contain model-derived baseline rows.")
            observations = group["observed"].astype(bool).tolist()
            interactions = [int(value) if observed else -1 for value, observed in zip(group["interactions"], observations)]
            active_bins = [seen and value > 0 for seen, value in zip(observations, interactions)]
            active_indices = [i for i, active in enumerate(active_bins) if active]
            observed_values = [int(value) for value, seen in zip(group["battery"], observations) if seen]
            day = {
                "day_number": number,
                "day_offset": end_day - 1 - int(day_index),
                "weekday": WEEKDAYS[int(group["dow"].iloc[0])],
                "interactions": interactions,
                "observed": observations,
                "charging": group["charging"].astype(bool).tolist(),
                "battery": [int(value) for value in group["battery"]],
                "interaction_total": sum(max(value, 0) for value, seen in zip(interactions, observations) if seen),
                "observed_bins": sum(observations),
                "charging_bins": sum(bool(value) and seen for value, seen in zip(group["charging"], observations)),
                "first_use_bin": active_indices[0] if active_indices else -1,
                "last_use_bin": active_indices[-1] if active_indices else -1,
                "battery_last": observed_values[-1] if observed_values else -1,
            }
            days.append(day)
        if len(days) != end_day - start_day:
            raise ValueError(f"Expected {end_day - start_day} days for {label}; got {len(days)}.")
        active_by_bin = [
            sum(day["observed"][i] and day["interactions"][i] > 0 for day in days) / max(1, sum(day["observed"][i] for day in days))
            for i in range(96)
        ]
        alerts = [event for day in days if (event := alert_for_day(day)) is not None]
        result["people"].append({
            "name": label,
            "history_days": len(days),
            "usual_use_probability": active_by_bin,
            "days": days,
            "alerts": alerts,
        })

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Wrote {args.output}: Ma 7 days / {len(result['people'][0]['alerts'])} alerts; "
          f"Papa 30 days / {len(result['people'][1]['alerts'])} alerts")


if __name__ == "__main__":
    main()

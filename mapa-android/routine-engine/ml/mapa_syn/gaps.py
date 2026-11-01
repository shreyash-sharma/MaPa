"""Descriptive, strictly causal label statistics; never fits real-data models."""
from bisect import insort
import math

from .io import read_csv, write_csv, write_json
from .manifest import check_inputs, update
from .paths import version_root
from .settings import GROUPS
from .store import IX, load_group

GAP_FIELDS = ["pid", "day_index", "completed_gaps", "q90_min", "q97_min", "q995_min", "wake_days", "wake_p50_min", "wake_p90_min"]


def quantile(sorted_values, q):
    if not sorted_values:
        return None
    index = (len(sorted_values) - 1) * q
    lo, hi = math.floor(index), math.ceil(index)
    return sorted_values[lo] if lo == hi else sorted_values[lo] * (hi - index) + sorted_values[hi] * (index - lo)


def causal_days(timeline):
    observed = timeline.observed()
    values = timeline.values[:, IX["interactions"]]
    gaps, wakes = [], []
    last = None
    for day in range(len(values) // 96):
        yield dict(pid=timeline.pid, day_index=day, completed_gaps=len(gaps), q90_min=quantile(gaps, .9),
                   q97_min=quantile(gaps, .97), q995_min=quantile(gaps, .995), wake_days=len(wakes),
                   wake_p50_min=quantile(wakes, .5), wake_p90_min=quantile(wakes, .9))
        first = None
        for t in range(day * 96, (day + 1) * 96):
            if not observed[t]:
                last = None
                continue
            if values[t] > 0:
                if last is not None and t - last > 1:
                    insort(gaps, (t - last - 1) * 15)
                last = t
                if first is None and 28 <= t % 96 < 72 and observed[day * 96 + 28:t + 1].all():
                    first = (t % 96) * 15
        if first is not None:
            insort(wakes, first)


def gap_index(group):
    result = {}
    for row in read_csv(version_root() / "gaps" / f"{group}.csv"):
        result[(row["pid"], int(row["day_index"]))] = {k: (float(v) if v else None) for k, v in row.items() if k != "pid"}
    return result


def gaps(cfg):
    check_inputs()
    paths, counts = [], {}
    for group in GROUPS:
        timelines = load_group(group)
        path = version_root() / "gaps" / f"{group}.csv"
        count = 0
        def rows():
            nonlocal count
            for pid, timeline in sorted(timelines.items()):
                for row in causal_days(timeline):
                    count += 1
                    yield row
        write_csv(path, rows(), GAP_FIELDS)
        counts[group] = count
        paths.append(path)
    update(cfg, paths, gap_rows=counts, gaps_policy="Only completed observed positive-to-positive silent gaps ending before the current day; no real-data fitting.")
    write_json(version_root() / "stage-S4.json", {"gate": "PASS", "rows": counts})
    return {"stage": "S4", "gate": "PASS", "rows": counts}

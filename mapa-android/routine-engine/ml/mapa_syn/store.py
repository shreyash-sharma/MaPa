"""Compact canonical timelines; no private source payloads are loaded."""
from dataclasses import dataclass
import numpy as np

from .bins import FIELDS
from .io import read_csv
from .paths import version_root
from .settings import source_dir

COLS = [f for f in FIELDS if f not in {"pid", "t", "observation_basis"}]
IX = {name: i for i, name in enumerate(COLS)}


@dataclass
class Timeline:
    pid: str
    source: str
    start_dow: int
    values: np.ndarray

    def observed(self):
        a = self.values
        return (a[:, IX["interactions"]] >= 0) & (a[:, IX["usage_ok"]] == 1) & (a[:, IX["phone_on"]] != 0) & (a[:, IX["present"]] == 1) & (a[:, IX["coverage_min"]] == 15)

    def rows(self, values=None, replay_id=None):
        values = self.values if values is None else values
        for t, row in enumerate(values):
            basis = "synthetic" if self.source == "synthetic" else "heartbeat_inferred" if int(row[IX["coverage_min"]]) == 15 else "unknown"
            yield dict(pid=replay_id or self.pid, t=t, **{c: int(row[i]) for i, c in enumerate(COLS)}, observation_basis=basis)


def load_source(source, selected=None):
    directory = source_dir(source)
    parents = [p for p in read_csv(directory / "parents.csv") if selected is None or p["pid"] in selected]
    timelines = {p["pid"]: Timeline(p["pid"], source, int(p["start_dow"]), np.full((int(p["days"]) * 96, len(COLS)), -1, dtype=np.int32)) for p in parents}
    for row in read_csv(directory / "bins.csv.gz"):
        if row["pid"] in timelines:
            timelines[row["pid"]].values[int(row["t"])] = [int(row[c]) for c in COLS]
    return timelines


def group_rows(group):
    return [r for r in read_csv(version_root() / "splits.csv") if r["split"] == group]


def load_group(group):
    rows = group_rows(group)
    timelines = {}
    for source in sorted({r["source"] for r in rows}):
        timelines.update(load_source(source, {r["pid"] for r in rows if r["source"] == source}))
    return timelines

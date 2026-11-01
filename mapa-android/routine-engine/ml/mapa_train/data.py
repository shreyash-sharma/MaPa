"""Read MaPa-Syn canonical bins into per-person arrays. Unknown stays unknown (-1); nothing is invented."""
from __future__ import annotations

import gzip
import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

from mapa_rhythm.features import BINS_PER_DAY

VERSION = "mapa-syn/v0.1"
# Where each named data set lives under the data root, and which bins file to read.
SOURCES = {
    "synth_hand": ("converted/synthetic", "bins.csv.gz", "synthetic"),
    "synth_learned": ("converted/synth_learned", "bins.csv.gz", "synth_learned"),
    "s3": ("converted/s3/heartbeat_inferred", "bins.csv.gz", "s3"),
    "ubiqlog": ("converted/ubiqlog/heartbeat_inferred", "bins.csv.gz", "ubiqlog"),
}
REAL_SOURCES = ("s3", "ubiqlog")


class LeakError(RuntimeError):
    """A held-out person would be read for training."""


@dataclass
class Person:
    pid: str
    source: str
    start_dow: int
    interactions: np.ndarray          # int16, -1 unknown
    moved: np.ndarray                 # int8, 1 / 0 / -1 no signal
    charging: np.ndarray              # int8, 1 / 0 / -1 unknown
    battery: np.ndarray               # int16 percent, -1 unknown
    exclude: np.ndarray = field(default=None)   # bool: injected positive windows (never learned as normal)
    tagged_days: set = field(default_factory=set)

    def __post_init__(self):
        if self.exclude is None:
            self.exclude = np.zeros(len(self.interactions), dtype=bool)

    @property
    def days(self) -> int:
        return len(self.interactions) // BINS_PER_DAY

    def dow(self, day: int) -> int:
        return (self.start_dow + day) % 7


def read_splits(root: Path) -> pd.DataFrame:
    path = Path(root) / VERSION / "splits.csv"
    if not path.exists():
        raise FileNotFoundError(f"{path} is missing: run the MaPa-Syn split stage first")
    return pd.read_csv(path, dtype=str)


def real_check_ids(splits: pd.DataFrame) -> set[str]:
    return set(splits.loc[splits["split"] == "real_check", "pid"])


def test_ids(splits: pd.DataFrame) -> set[str]:
    return set(splits.loc[splits["split"].isin(["test", "real_check"]), "pid"])


def guard(pids, splits: pd.DataFrame, purpose: str) -> None:
    """Stop if a training or validation read would touch a held-out person."""
    bad = set(pids) & test_ids(splits)
    if bad:
        raise LeakError(f"{purpose} would read {len(bad)} held-out people (test or real_check), e.g. {sorted(bad)[:3]}")


def select(splits: pd.DataFrame, dataset: str, split: str) -> list[str]:
    """pids of one named data set and split. 'gen_fit' = real people outside real_check (spec v0.1.2)."""
    src = SOURCES[dataset][2] if dataset in SOURCES else dataset
    rows = splits[splits["source"] == src]
    if split == "gen_fit":
        if dataset not in REAL_SOURCES:
            raise ValueError("gen_fit applies to real sources only")
        held = real_check_ids(splits)
        return None, held      # resolved against the bins file: every real person except real_check
    if rows.empty:
        raise LookupError(f"splits.csv has no rows for source '{src}'. Freeze its split first; this code never draws one.")
    return list(rows.loc[rows["split"] == split, "pid"]), set()


def load(root: Path, dataset: str, split: str, splits: pd.DataFrame | None = None, purpose: str = "test",
         limit: int | None = None) -> list[Person]:
    """Load the people of `dataset`/`split`. For purpose 'train' or 'validate' the leak guard runs first."""
    root = Path(root)
    splits = read_splits(root) if splits is None else splits
    folder, binsname, _ = SOURCES[dataset]
    d = root / folder
    if not (d / "parents.csv").exists():
        raise LookupError(f"{dataset} is not built yet ({d} has no parents.csv)")
    parents = pd.read_csv(d / "parents.csv", dtype={"pid": str})
    pids, held = select(splits, dataset, split)
    if pids is None:     # gen_fit
        pids = [p for p in parents["pid"] if p not in held]
    pids = sorted(pids)[:limit] if limit else sorted(pids)
    if purpose in ("train", "validate"):
        guard(pids, splits, f"{purpose} on {dataset}/{split}")
    want = set(pids)
    cols = ["pid", "t", "interactions", "charging", "battery", "moved"]
    chunks = []
    for chunk in pd.read_csv(d / binsname, usecols=cols, dtype={"pid": str}, chunksize=2_000_000):
        chunk = chunk[chunk["pid"].isin(want)]
        if len(chunk):
            chunks.append(chunk)
    bins = pd.concat(chunks) if chunks else pd.DataFrame(columns=cols)
    dow = dict(zip(parents["pid"], parents["start_dow"].astype(int)))
    out = []
    for pid, g in bins.groupby("pid", sort=True):
        g = g.sort_values("t")
        n = int(g["t"].iloc[-1]) + 1
        n = -(-n // BINS_PER_DAY) * BINS_PER_DAY          # whole days; padding is unknown
        def col(name, dtype):
            a = np.full(n, -1, dtype=dtype)
            a[g["t"].to_numpy()] = g[name].to_numpy()
            return a
        out.append(Person(pid, dataset, dow.get(pid, 0), col("interactions", np.int16), col("moved", np.int8),
                          col("charging", np.int8), col("battery", np.int16)))
    return out


def apply_episodes(people: list[Person], episodes_path: Path) -> int:
    """Mark injected windows so training never learns them as normal, and record tagged days."""
    if not Path(episodes_path).exists():
        return 0
    by = {p.pid: p for p in people}
    n = 0
    opener = gzip.open if str(episodes_path).endswith(".gz") else open
    with opener(episodes_path, "rt", encoding="utf-8") as f:
        for line in f:
            e = json.loads(line)
            p = by.get(e.get("pid"))
            if p is None:
                continue
            inj = e.get("injected", {})
            s, t = int(inj.get("start_bin", -1)), int(inj.get("end_bin", -1))
            if 0 <= s < t:
                p.exclude[s:min(t, len(p.exclude))] = True
                n += 1
            if e.get("scenario") == "life_tag_day" and "day_index" in e:
                p.tagged_days.add(int(e["day_index"]))
    return n

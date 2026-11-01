"""Build the fixed 20-channel training interface from frozen final-v2 rows.

Channels 0..17 are the phone features. Indices 0, 1, 2 and 8 encode in-use,
short break, long quiet and night; index 14 is causal state entropy. Indices
16 and 17 remain movement and movement-known. Channels 18 and 19 are the
historical quiet p90 and p99.5 already computed by the release feature builder.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import numpy as np
import pyarrow.parquet as pq

from mapa_release.common import ROOT
from mapa_rhythm.features import N_FEATURES, TRAIN_FEATURES, training_inputs
from .protocol import DATA, verify_data, sha256
from .quiet_cache import precompute_quiet_quantiles


def build(split: str, version: str = "causal-v3", dataset_version: str = "final-v2",
          allow_locked_eval: bool = False) -> tuple[np.ndarray, dict]:
    if split not in {"train", "val", "test"}:
        raise ValueError(f"unknown split: {split}")
    if split in {"val", "test"}:
        if not allow_locked_eval:
            raise RuntimeError("Held-out feature building is reserved for the one-time final evaluator")
        folder = ROOT / "models" / "final-fit-hybrid-row9-v16"
        selection_path = folder / "selection.json"
        access_path = folder / f"{split}-access.json"
        if not selection_path.exists() or not access_path.exists():
            raise RuntimeError(f"{split} feature building requires a locked selection and its access marker")
        if split == "test" and not (folder / "validation-results.json").exists():
            raise RuntimeError("Test feature building requires the single validation confirmation")
        access = json.loads(access_path.read_text())
        expected = sha256(selection_path)
        if (access.get("selection_sha256") != expected or access.get("split") != split
                or access.get("model") != folder.name):
            raise RuntimeError(f"{split} access marker does not match the locked selection")
    manifest = verify_data(split)
    cache = ROOT / f"learning-{version}-{dataset_version}" / f"{split}.npz"
    groups_path = cache.with_suffix(".groups.json")
    if not cache.exists() or not groups_path.exists():
        raise FileNotFoundError(f"Build release features from frozen {dataset_version} first: {cache}")
    arrays = dict(np.load(cache, allow_pickle=False))
    old_x = arrays["x"]
    if old_x.shape[-1] != N_FEATURES:
        raise ValueError(f"Expected frozen 18 phone features, got {old_x.shape}")
    groups = json.loads(groups_path.read_text())
    if len(groups) != len(old_x):
        raise ValueError("feature rows and frozen person-group index differ")

    table = pq.read_table(DATA / f"{split}.parquet", columns=["pid", "episode_id", "day_index", "t", "moved", "interactions", "observed"])
    raw = {}
    for batch in table.to_batches(max_chunksize=500_000):
        cols = batch.to_pydict()
        for i, (pid, eid, day, slot, moved, interactions, observed) in enumerate(zip(
                cols["pid"], cols["episode_id"], cols["day_index"], cols["t"], cols["moved"],
                cols["interactions"], cols["observed"])):
            key = (pid, eid or "", day)
            row = raw.setdefault(key, {"moved": np.full((96,), -1, dtype=np.int8),
                                       "interactions": np.full((96,), -1, dtype=np.int32),
                                       "observed": np.zeros((96,), dtype=bool)})
            s = int(slot) % 96
            row["moved"][s] = -1 if moved is None else int(moved)
            row["interactions"][s] = int(interactions) if observed else -1
            row["observed"][s] = bool(observed)
    movement = np.zeros((len(groups), 96, 2), dtype=np.float32)
    for i, g in enumerate(groups):
        key = (g["pid"], g.get("episode_id") or "", int(g["day"]))
        day_raw = raw.get(key)
        if day_raw is None:
            raise ValueError(f"Missing frozen movement row for {key}")
        mv = day_raw["moved"]
        movement[i, :, 0] = mv == 1
        movement[i, :, 1] = mv >= 0
    qhistory = old_x[:, :, 16:18].copy()
    phone = old_x.copy()
    phone[:, :, 16:18] = movement
    arrays["x"] = training_inputs(phone, qhistory)
    # The first bin of each day uses the previous bin from the same parent's
    # untouched timeline; episode copies inherit the unaffected prior day.
    for i, g in enumerate(groups):
        key = (g["pid"], g.get("episode_id") or "", int(g["day"]))
        day_raw = raw[key]
        prev = raw.get((g["pid"], g.get("episode_id") or "", int(g["day"]) - 1))
        if prev is None and g.get("episode_id"):
            prev = raw.get((g["pid"], "", int(g["day"]) - 1))
        starts = np.zeros(96, dtype=np.float32)
        counts = day_raw["interactions"]
        known = day_raw["observed"]
        starts[1:] = ((counts[1:] > 0) & known[1:] & known[:-1] & (counts[:-1] == 0))
        if prev is not None and prev["observed"][95] and prev["interactions"][95] == 0 and known[0] and counts[0] > 0:
            starts[0] = 1.
        arrays["x"][i, :, 20] = starts
    assert arrays["x"].shape[-1] == TRAIN_FEATURES
    slots = np.arange(96)
    quality_bins = ((slots >= 24) & (slots < 96))
    arrays["quality_day"] = (arrays["x"][:, quality_bins, 3] > .5).sum(axis=1) >= 56
    summaries = arrays["daily_summary"].astype(np.float32)
    slow = np.zeros((len(groups), 30, summaries.shape[-1] + 96), dtype=np.float32)
    slow_count = np.zeros(len(groups), dtype=np.int64)
    base_by_pid = {}
    for i, g in enumerate(groups):
        if not g.get("episode_id") and arrays["quality_day"][i]:
            base_by_pid.setdefault(str(g["pid"]), []).append(i)
    for pids, history_rows in base_by_pid.items():
        history_rows.sort(key=lambda i: int(groups[i]["day"]))
    for i, g in enumerate(groups):
        prior = [j for j in base_by_pid.get(str(g["pid"]), [])
                 if int(groups[j]["day"]) < int(g["day"])]
        prior = prior[-30:]
        n_prior = len(prior)
        slow_count[i] = n_prior
        if n_prior:
            slow[i, :n_prior, :9] = summaries[prior]
            for h, j in enumerate(prior):
                pg = groups[j]
                record = raw[(pg["pid"], "", int(pg["day"]))]
                slow[i, h, 9:] = np.where(record["observed"], record["interactions"] > 0, -1)
    arrays["slow_history"] = slow
    arrays["slow_count"] = slow_count
    if split == "train":
        # This table is derived only from the frozen train rows and is shared
        # by every fold/seed process. It covers every sampled prefix length.
        quiet_path = cache.with_name("train.slow-quiet-prefix-v1.npy")
        quiet_index_path = cache.with_name("train.slow-quiet-row-index-v1.npy")
        quiet_keys_path = cache.with_name("train.slow-quiet-keys-v1.json")
        quiet_meta_path = cache.with_name("train.slow-quiet-prefix-v1.json")
        quiet_source = Path(__file__).with_name("quiet_cache.py")
        person_day_index = {}
        representative_rows = []
        person_day_keys = []
        row_index = np.empty(len(groups), dtype=np.int32)
        for i, g in enumerate(groups):
            key = (str(g["pid"]), int(g["day"]))
            if key not in person_day_index:
                person_day_index[key] = len(representative_rows)
                representative_rows.append(i)
                person_day_keys.append([key[0], key[1]])
            else:
                representative = representative_rows[person_day_index[key]]
                if slow_count[i] != slow_count[representative] or not np.array_equal(slow[i], slow[representative]):
                    raise ValueError(f"Episode and parent histories differ for person-day {key}")
            row_index[i] = person_day_index[key]
        keys_blob = json.dumps(person_day_keys, separators=(",", ":")) + "\n"
        quiet_keys_path.write_text(keys_blob)
        expected_meta = {
            "schema": 1,
            "dataset_manifest_sha256": sha256(DATA / "manifest.json"),
            "feature_cache_sha256": sha256(cache),
            "groups_sha256": sha256(groups_path),
            "algorithm_sha256": sha256(quiet_source),
            "person_day_keys_sha256": sha256(quiet_keys_path),
            "unique_person_days": len(person_day_keys),
            "row_count": len(groups),
            "shape": [len(person_day_keys), 31, 2],
        }
        valid = False
        if quiet_path.exists() and quiet_meta_path.exists():
            try:
                valid = json.loads(quiet_meta_path.read_text()) == expected_meta
                valid = valid and np.load(quiet_path, mmap_mode="r").shape == tuple(expected_meta["shape"])
                valid = valid and np.array_equal(np.load(quiet_index_path, mmap_mode="r"), row_index)
            except (OSError, ValueError, json.JSONDecodeError):
                valid = False
        if not valid:
            table = precompute_quiet_quantiles(slow[representative_rows], slow_count[representative_rows])
            temporary = quiet_path.with_name(quiet_path.name + f".{os.getpid()}.tmp")
            with temporary.open("wb") as stream:
                np.save(stream, table, allow_pickle=False)
            os.replace(temporary, quiet_path)
            index_temporary = quiet_index_path.with_name(quiet_index_path.name + f".{os.getpid()}.tmp")
            with index_temporary.open("wb") as stream:
                np.save(stream, row_index, allow_pickle=False)
            os.replace(index_temporary, quiet_index_path)
            quiet_meta_path.write_text(json.dumps(expected_meta, indent=2) + "\n")
        arrays["slow_quiet_quantile_table"] = np.load(quiet_path, mmap_mode="r")
        arrays["slow_quiet_quantile_index"] = np.load(quiet_index_path, mmap_mode="r")
    return arrays, {"dataset_manifest_sha256": sha256(DATA / "manifest.json"), "feature_contract": TRAIN_FEATURES}

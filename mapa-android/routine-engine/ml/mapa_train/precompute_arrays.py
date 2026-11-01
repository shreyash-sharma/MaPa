"""Materialize the complete train-only GPU array bundle for one-night CV."""
from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np

from mapa_release.common import ROOT, trial
from .cold_start import fit_priors
from .augmentation import day_swap_indices, similarity_weights
from .zoo import real_only_weights
from .features20 import build as build_features
from .protocol import SEEDS, assignments, freeze, sha256


def precompute_train_arrays(version: str = "causal-v3", output: Path | None = None) -> Path:
    started = time.perf_counter()
    trial("gpu-array-precompute", "final-v2-train-v2", "STARTED",
          "Train partition only; 21 inputs, labels/masks, 30-day summaries, history tables and fold/seed priors")
    try:
        protocol = freeze()
        protocol_path = ROOT / "training-protocol-v9.json"
        a, info = build_features("train", version, "final-v2")
        groups_path = ROOT / f"learning-{version}-final-v2" / "train.groups.json"
        groups = json.loads(groups_path.read_text())
        x = np.asarray(a["x"], dtype=np.float32)
        y = np.asarray(a["y"], dtype=np.float32)
        mask = np.asarray(a["mask"], dtype=np.uint8)
        tag = np.asarray(a["tag"], dtype=np.uint8)
        quality = np.asarray(a["quality_day"], dtype=np.uint8)
        episode = np.asarray(a["episode"], dtype=np.uint8)
        slow = np.asarray(a["slow_history"], dtype=np.float32)
        slow_count = np.asarray(a["slow_count"], dtype=np.uint8)
        row_person_day = np.asarray(a["slow_quiet_quantile_index"], dtype=np.int32)
        quiet_table = np.asarray(a["slow_quiet_quantile_table"], dtype=np.float64)
        unique_days = len(quiet_table)

        representative = np.full(unique_days, -1, dtype=np.int64)
        for i, unique_id in enumerate(row_person_day):
            if representative[unique_id] < 0:
                representative[unique_id] = i
        if np.any(representative < 0):
            raise ValueError("A cached person-day has no training row")

        # Prefix statistics are independent of detector labels. Hourly means
        # stay float64 until the GPU transfer; q values follow the old
        # apply_priors float32 assignment exactly.
        hourly = np.full((unique_days, 31, 96), np.nan, dtype=np.float64)
        history_summary = np.zeros((unique_days, 31, 9), dtype=np.float32)
        quiet_context = np.empty((unique_days, 31, 2), dtype=np.float32)
        history_weight = np.zeros((unique_days, 31), dtype=np.float64)
        log_cap = np.log1p(192.)
        for u, row in enumerate(representative):
            n_available = int(slow_count[row])
            sums = np.zeros(96, dtype=np.float32)
            counts = np.zeros(96, dtype=np.int32)
            for n in range(31):
                q = quiet_table[u, n]
                quiet_context[u, n] = (np.log1p(q) / log_cap).astype(np.float32)
                if n == 0 or n > n_available:
                    continue
                day = slow[row, n - 1, 9:]
                known = day >= 0
                sums += np.where(known, day, 0.)
                counts += known
                hourly[u, n] = np.divide(sums, counts,
                    out=np.full(96, np.nan, dtype=np.float64), where=counts > 0)
                history_summary[u, n] = slow[row, :n, :9].mean(axis=0)
                history_weight[u, n] = n / (n + 7.)

        fold_map = assignments()
        fold_by_row = np.asarray([fold_map[g["pid"]] for g in groups], dtype=np.int8)
        n_folds, n_seeds = 5, len(SEEDS)
        prior_vectors = np.zeros((n_folds, n_seeds, 6, 5), dtype=np.float32)
        prior_centers = np.zeros((n_folds, n_seeds, 9), dtype=np.float32)
        prior_scales = np.ones((n_folds, n_seeds, 9), dtype=np.float32)
        prior_centroids = np.zeros((n_folds, n_seeds, 5, 9), dtype=np.float32)
        cluster_ids = np.zeros((n_folds, n_seeds, unique_days, 31), dtype=np.uint8)
        fold_stage_rows = []
        fold_derived_original_count = np.zeros(n_folds, dtype=np.int32)
        fold_stage_weights = np.zeros((n_folds, 3, len(groups)), dtype=np.float32)
        fold_stage_similarity = np.zeros((n_folds, 3, len(groups)), dtype=np.float32)
        fold_weight_reports = []
        fold_day_swap_reports = []
        for fold in range(n_folds):
            train_ix = np.flatnonzero(fold_by_row != fold)
            local_groups = [groups[i] for i in train_ix]
            local_x = x[train_ix]
            local_slow = slow[train_ix]
            local_count = slow_count[train_ix].astype(np.int64)
            local_tag, local_quality, local_episode = tag[train_ix], quality[train_ix], episode[train_ix]
            is_real = np.asarray([g.get("source") != "derived" for g in local_groups], dtype=bool)
            normal_rows = np.flatnonzero((local_episode == 0) & (local_tag == 0))
            real_rows = normal_rows[is_real[normal_rows]]
            derived_rows = normal_rows[~is_real[normal_rows]]
            behaviour_rows = np.flatnonzero((local_tag == 0) & ((~is_real) | (local_quality > 0)))
            augmentation_rows, day_swap_report = day_swap_indices(local_x, local_groups, derived_rows)
            fold_derived_original_count[fold] = len(derived_rows)
            derived_train_rows = np.concatenate([derived_rows, augmentation_rows])
            weight_source_rows = np.concatenate([real_rows, derived_train_rows])
            real_w, real_s, real_report = real_only_weights(len(train_ix), local_groups, real_rows)
            normal_w, normal_s, normal_report = similarity_weights(local_x, local_groups, weight_source_rows)
            behaviour_w, behaviour_s, behaviour_report = similarity_weights(local_x, local_groups, behaviour_rows)
            stage_rows = [train_ix[real_rows], train_ix[derived_train_rows], train_ix[behaviour_rows]]
            fold_stage_rows.append(stage_rows)
            for stage_idx, (weights, sims) in enumerate(((real_w, real_s), (normal_w, normal_s), (behaviour_w, behaviour_s))):
                fold_stage_weights[fold, stage_idx, train_ix] = weights
                fold_stage_similarity[fold, stage_idx, train_ix] = sims
            fold_weight_reports.append({"real_observed_pretrain": real_report,
                                        "derived_normal_rhythm": normal_report,
                                        "behaviour_episode_finetune": behaviour_report})
            fold_day_swap_reports.append(day_swap_report)
            summaries = local_slow[np.arange(len(train_ix)), np.maximum(local_count - 1, 0), :9]
            eligible = normal_rows[local_count[normal_rows] > 0]
            for si, seed in enumerate(SEEDS[:n_seeds]):
                fitted = fit_priors(local_x, summaries, local_groups, eligible, seed)
                prior_vectors[fold, si, 0] = fitted["population_prior"]
                prior_vectors[fold, si, 1:] = fitted["archetype_priors"]
                prior_centers[fold, si] = fitted["center"]
                prior_scales[fold, si] = fitted["scale"]
                prior_centroids[fold, si] = fitted["centroids"]
                z = (history_summary - fitted["center"]) / fitted["scale"]
                distance = ((z[:, :, None, :] - fitted["centroids"][None, None, :, :]) ** 2).sum(axis=-1)
                cluster_ids[fold, si] = np.argmin(distance, axis=-1).astype(np.uint8) + 1
                cluster_ids[fold, si, :, :3] = 0

        max_rows = max(len(rows) for stage in fold_stage_rows for rows in stage)
        fold_stage_indices = np.full((n_folds, 3, max_rows), -1, dtype=np.int32)
        fold_stage_counts = np.zeros((n_folds, 3), dtype=np.int32)
        for fold, stages in enumerate(fold_stage_rows):
            for stage_idx, rows in enumerate(stages):
                fold_stage_counts[fold, stage_idx] = len(rows)
                fold_stage_indices[fold, stage_idx, :len(rows)] = rows

        if output is None:
            output = ROOT / "precomputed" / "final-v2-train-arrays-v2.npz"
        output = Path(output)
        output.parent.mkdir(parents=True, exist_ok=True)
        metadata = {
            "dataset": "final-v2", "split": "train", "rows": len(groups),
            "people": len(set(g["pid"] for g in groups)), "unique_person_days": unique_days,
            "inputs": 21, "history_prefixes": 31, "daily_summary_rows": 30,
            "folds": 5, "seeds": SEEDS, "fold_assignment": fold_map,
            "dataset_manifest_sha256": info["dataset_manifest_sha256"],
            "source_groups_sha256": sha256(groups_path),
            "training_protocol_sha256": sha256(protocol_path),
            "input_contract": "base x plus exact per-person/day prefix statistics; fold/seed archetype priors and cluster indices",
            "partition_scope": ["train"],
            "batch_workers": 0, "fold_preprocessing": "stage rows, data weights, day swaps and archetype choices precomputed once",
        }
        temporary = output.with_suffix(output.suffix + ".tmp")
        with temporary.open("wb") as stream:
            np.savez_compressed(
                stream, x=x, y=y, mask=mask, tag=tag, quality=quality,
                episode=episode, slow_history=slow, slow_count=slow_count,
                row_person_day=row_person_day, hourly_profile=hourly,
                history_summary=history_summary, quiet_context=quiet_context,
                history_weight=history_weight, archetype_priors=prior_vectors,
                archetype_centers=prior_centers, archetype_scales=prior_scales,
                archetype_centroids=prior_centroids, cluster_ids=cluster_ids,
                fold_stage_indices=fold_stage_indices, fold_stage_counts=fold_stage_counts,
                fold_derived_original_count=fold_derived_original_count,
                fold_stage_weights=fold_stage_weights, fold_stage_similarity=fold_stage_similarity,
                fold_weight_reports_json=np.asarray(json.dumps(fold_weight_reports)),
                fold_day_swap_reports_json=np.asarray(json.dumps(fold_day_swap_reports)),
                fold_by_row=fold_by_row, groups_json=np.asarray(json.dumps(groups)),
                metadata_json=np.asarray(json.dumps(metadata)))
        temporary.replace(output)
        elapsed = time.perf_counter() - started
        trial("gpu-array-precompute", "final-v2-train-v2", "COMPLETE",
              json.dumps({"output": str(output), "sha256": sha256(output),
                          "elapsed_seconds": elapsed, **{k: metadata[k] for k in
                          ("rows", "people", "unique_person_days", "inputs", "folds", "seeds")}}))
        return output
    except Exception as exc:
        trial("gpu-array-precompute", "final-v2-train-v2", "FAIL", repr(exc))
        raise

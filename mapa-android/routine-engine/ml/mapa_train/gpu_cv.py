"""Overnight person-fold schedule using one train-only GPU array bundle."""
from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor, as_completed
import multiprocessing
from pathlib import Path

from mapa_release.common import ROOT, progress, trial
from .gpu_training import ARRAY_FILE, run_fold_gpu
from .precompute_arrays import precompute_train_arrays
from .protocol import SEEDS, freeze, sha256
from .zoo import train_cv, STAGE_EPOCHS


def _worker(task):
    family, version, run_tag, fold, seeds, protocol_sha, ablation_row, batch_size = task
    return run_fold_gpu(family, version, run_tag, fold, seeds, protocol_sha,
                        ablation_row=ablation_row, batch_size=batch_size)


def _run_folds(family, version, run_tag, seeds, workers=3, ablation_row=None,
               batch_size=96, folds=range(5), aggregate=True):
    protocol = freeze()
    protocol_sha = sha256(ROOT / "training-protocol-v9.json")
    tasks = [(family, version, run_tag, int(fold), list(seeds), protocol_sha,
              ablation_row, batch_size) for fold in folds]
    context = multiprocessing.get_context("spawn")
    failures = []
    with ProcessPoolExecutor(max_workers=workers, mp_context=context) as pool:
        futures = {pool.submit(_worker, task): task for task in tasks}
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as exc:
                task = futures[future]
                detail = f"{type(exc).__name__}: {exc}"
                trial("gpu-fold-process", str(task), "FAIL", detail)
                failures.append(detail)
    if failures:
        raise RuntimeError(f"{len(failures)} GPU fold process(es) failed: {failures}")
    if not aggregate:
        return {"family": family, "folds_completed": list(folds), "seeds": list(seeds)}
    return train_cv([family], version, sum(STAGE_EPOCHS.values()), run_tag,
                    ablation_row=ablation_row, seed_list=list(seeds),
                    fold_list=list(range(5)), finalize=True)


def ensure_array_bundle():
    protocol = freeze()
    rebuild = not ARRAY_FILE.exists()
    if not rebuild:
        import numpy as np
        try:
            with np.load(ARRAY_FILE, allow_pickle=False) as z:
                metadata = __import__("json").loads(str(z["metadata_json"]))
                rebuild = metadata.get("training_protocol_sha256") != sha256(ROOT / "training-protocol-v9.json")
        except Exception:
            rebuild = True
    if rebuild:
        precompute_train_arrays()
    trial("gpu-array-precompute", "final-v2-train-v2", "REUSED",
          f"Single train-only array file ready; protocol v{protocol['protocol_version']}; SHA-256={sha256(ARRAY_FILE)}")
    return ARRAY_FILE


def pilot_and_schedule(workers=3, batch_size=96, run_all=True, skip_hybrid=False):
    """Build, time one fit, report its projection, then optionally run the plan."""
    ensure_array_bundle()
    if not skip_hybrid:
        # One representative fit becomes fold 0 / seed 20261101 of the full run.
        _run_folds("hybrid", "causal-v3", "night-v7-hybrid", [SEEDS[0]],
                   workers=1, batch_size=batch_size, folds=[0], aggregate=False)
        if not run_all:
            return
        _run_folds("hybrid", "causal-v3", "night-v7-hybrid", SEEDS[:3],
                   workers=workers, batch_size=batch_size)
    elif not run_all:
        return

    # Baseline and decision metrics are emitted by the main hybrid OOF gate.
    # The 8-hour reference uses the previously completed train-only evaluator.
    # The 8-hour train-only baseline artifact is already complete; the hybrid
    # OOF evaluator emits the 4-hour, timers and statistics-only comparisons.

    for row in range(1, 11):
        family = "plain-cnn" if row == 1 else "hybrid"
        run_tag = "night-v7-ablations"
        _run_folds(family, "causal-v3", run_tag, [SEEDS[0]], workers=workers,
                   ablation_row=row, batch_size=batch_size)

    # Train neural alternatives on the GPU while the single LightGBM comparison
    # uses the CPU. Both see only final-v2 train folds.
    with ThreadPoolExecutor(max_workers=1) as cpu:
        lightgbm = cpu.submit(train_cv, ["lightgbm"], "causal-v3", 15,
                              "night-v7-comparisons", None, [SEEDS[0]], list(range(5)), True)
        for family in ("hybrid-no-attention", "plain-gru", "plain-cnn", "rebuild-lstm"):
            _run_folds(family, "causal-v3", "night-v7-comparisons", [SEEDS[0]],
                       workers=workers, batch_size=batch_size)
        lightgbm.result()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=96)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--no-run", action="store_true")
    parser.add_argument("--skip-hybrid", action="store_true",
                        help="continue from ablations using the hash-verified v8 hybrid OOF candidate")
    args = parser.parse_args()
    ensure_array_bundle()
    if not args.prepare_only:
        pilot_and_schedule(args.workers, args.batch_size, not args.no_run, args.skip_hybrid)

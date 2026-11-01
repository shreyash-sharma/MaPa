"""Run person folds as separate local processes, then aggregate OOF results."""
from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor, as_completed
import multiprocessing

from mapa_release.common import ROOT, progress, trial
from .features20 import build as build_features
from .protocol import SEEDS
from .zoo import train_cv, STAGE_EPOCHS


def _fit_fold(task):
    family, version, epochs, run_tag, seed_list, fold, ablation_row = task
    return train_cv([family], version, epochs, run_tag,
                    ablation_row=ablation_row, seed_list=seed_list,
                    fold_list=[fold], finalize=False)


def _run_tasks(tasks, workers):
    if not tasks:
        return
    ctx = multiprocessing.get_context("spawn")
    failures = []
    with ProcessPoolExecutor(max_workers=workers, mp_context=ctx) as pool:
        futures = {pool.submit(_fit_fold, task): task for task in tasks}
        for future in as_completed(futures):
            task = futures[future]
            try:
                future.result()
            except Exception as exc:
                detail = f"{type(exc).__name__}: {exc}"
                failures.append((task, detail))
                trial("parallel-fold", str(task), "FAIL", detail)
    if failures:
        raise RuntimeError(f"{len(failures)} fold processes failed; inspect logs/trials.csv")


def run_family(family, version, run_tag, seed_list, workers=2, ablation_row=None):
    # Materialize the shared history-stat cache before workers start, preventing
    # races and ensuring the expensive preprocessing happens once.
    build_features("train", version, "final-v2")
    epochs = sum(STAGE_EPOCHS.values())
    tasks = [(family, version, epochs, run_tag, seed_list, fold, ablation_row)
             for fold in range(5)]
    progress(f"Parallel CV starting: family={family}, row={ablation_row}, folds=5, seeds={seed_list}, workers={workers}; train-only.")
    _run_tasks(tasks, workers)
    return train_cv([family], version, epochs, run_tag, ablation_row=ablation_row,
                    seed_list=seed_list, fold_list=list(range(5)), finalize=True)


def run_ablation_grid(version="causal-v3", run_tag="v4-ablations", workers=2):
    """Ablations: one frozen seed, five person folds per row."""
    from .ablations import ROWS
    seed_list = SEEDS[:1]
    build_features("train", version, "final-v2")
    epochs = sum(STAGE_EPOCHS.values())
    for row in range(1, 11):
        row_family = "plain-cnn" if row == 1 else "hybrid"
        tasks = [(row_family, version, epochs, run_tag, seed_list, fold, row)
                 for fold in range(5)]
        progress(f"Ablation row {row}/10: starting five person folds, seed={seed_list[0]}.")
        _run_tasks(tasks, workers)
        train_cv([row_family], version, epochs, run_tag, ablation_row=row,
                 seed_list=seed_list, fold_list=list(range(5)), finalize=True)


def run_comparisons(version="causal-v3", run_tag="v4-comparisons", workers=2):
    """Approved alternatives, one frozen seed and five person folds."""
    seed_list = SEEDS[:1]
    for family in ("hybrid-no-attention", "plain-gru", "plain-cnn", "rebuild-lstm", "lightgbm"):
        run_family(family, version, run_tag, seed_list, workers)


def run_priority_schedule(workers=2):
    """Execute the requested order; test and validation remain unopened."""
    # (a) Main design: five folds x three seeds.
    run_family("hybrid", "causal-v3", "v4-hybrid", SEEDS[:3], workers)
    # (b) Train-only rule/statistics baselines are recorded by the shared
    # baseline evaluator; existing 4h/8h/stats-only results are not refit here.
    # (c) Full declared ablation grid, five folds x one seed.
    run_ablation_grid("causal-v3", "v4-ablations", workers)
    # (d) Approved simple alternatives, one seed x five folds.
    run_comparisons("causal-v3", "v4-comparisons", workers)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--stage", choices=["hybrid", "ablations", "comparisons", "all"], default="hybrid")
    a = ap.parse_args()
    if a.stage in {"hybrid", "all"}:
        run_family("hybrid", "causal-v3", "v4-hybrid", SEEDS[:3], a.workers)
    if a.stage in {"ablations", "all"}:
        run_ablation_grid(workers=a.workers)
    if a.stage in {"comparisons", "all"}:
        run_comparisons(workers=a.workers)

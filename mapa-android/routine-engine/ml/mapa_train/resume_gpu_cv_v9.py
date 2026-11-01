"""Resume the unfinished v9 ablation/comparison queue from saved train fits.

Run only after ``python -m mapa_train.gpu_cv`` has stopped. Existing fold
artifacts are reused by ``run_fold_gpu``/``train_cv`` only when their frozen
protocol, held-person indices, and artifact hashes match.
"""
from __future__ import annotations

import json
import subprocess
from concurrent.futures import ThreadPoolExecutor

from mapa_release.common import ROOT, trial
from .gpu_cv import _run_folds, ensure_array_bundle
from .protocol import SEEDS
from .zoo import STAGE_EPOCHS, train_cv


RUN_TAG = "night-v7-ablations"
COMPARISON_TAG = "night-v7-comparisons"


def _assert_scheduler_stopped() -> None:
    """Prevent two schedulers from contending for the same checkpoints/GPU."""
    command = (
        "$p=Get-CimInstance Win32_Process | Where-Object {"
        "$_.Name -match '^python(w)?\.exe$' -and $_.CommandLine -match '-m mapa_train\.gpu_cv'"
        "}; if ($p) { $p | Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress }"
    )
    result = subprocess.run(
        ["powershell", "-NoProfile", "-Command", command],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Could not check for a live scheduler: {result.stderr.strip()}")
    if result.stdout.strip():
        raise RuntimeError(
            "The v9 scheduler is still running; wait for it to stop before resuming. "
            + result.stdout.strip()
        )


def _oof_finished(directory: str, *, need_cold_start: bool = False) -> bool:
    folder = ROOT / "models" / directory
    report_path = folder / "cv-decision-results.json"
    if not report_path.is_file():
        return False
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    if report.get("validation_opened") is not False or report.get("test_opened") is not False:
        return False
    if not report.get("selected_on_train_oof_only") or not report.get("all_settings"):
        return False
    return not need_cold_start or (folder / "cold-start-curve-train.json").is_file()


def _run_ablation(row: int, workers: int, batch_size: int) -> None:
    directory = f"hybrid-ablation-{row}-{RUN_TAG}"
    if _oof_finished(directory, need_cold_start=True):
        trial("gpu-cv-resume", directory, "REUSED", "Train OOF decision and cold-start artifacts already complete")
        return
    family = "plain-cnn" if row == 1 else "hybrid"
    _run_folds(
        family,
        "causal-v3",
        RUN_TAG,
        [SEEDS[0]],
        workers=workers,
        ablation_row=row,
        batch_size=batch_size,
    )


def _run_comparison(family: str, workers: int, batch_size: int) -> None:
    directory = f"zoo-{family}-{COMPARISON_TAG}"
    if _oof_finished(directory):
        trial("gpu-cv-resume", directory, "REUSED", "Train OOF decision artifact already complete")
        return
    _run_folds(
        family,
        "causal-v3",
        COMPARISON_TAG,
        [SEEDS[0]],
        workers=workers,
        batch_size=batch_size,
    )


def resume(workers: int = 3, batch_size: int = 96) -> None:
    _assert_scheduler_stopped()
    ensure_array_bundle()
    for row in (9, 10):
        _run_ablation(row, workers, batch_size)

    # Keep the CPU LightGBM comparison concurrent with the remaining GPU fits,
    # matching the original schedule.
    lgbm_dir = f"zoo-lightgbm-{COMPARISON_TAG}"
    lgbm_future = None
    executor = ThreadPoolExecutor(max_workers=1)
    try:
        if _oof_finished(lgbm_dir):
            trial("gpu-cv-resume", lgbm_dir, "REUSED", "Train OOF decision artifact already complete")
        else:
            lgbm_future = executor.submit(
                train_cv,
                ["lightgbm"],
                "causal-v3",
                sum(STAGE_EPOCHS.values()),
                COMPARISON_TAG,
                None,
                [SEEDS[0]],
                list(range(5)),
                True,
            )

        for family in ("hybrid-no-attention", "plain-gru", "plain-cnn", "rebuild-lstm"):
            _run_comparison(family, workers, batch_size)
        if lgbm_future is not None:
            try:
                lgbm_future.result()
            except Exception as exc:
                # A concurrent CPU comparison must not silently erase its
                # failure or abort the already-finished GPU comparison queue.
                trial("cv-family", lgbm_dir, "FAIL", f"LightGBM future raised: {exc!r}")
                raise
    finally:
        executor.shutdown(wait=True)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=96)
    args = parser.parse_args()
    resume(args.workers, args.batch_size)

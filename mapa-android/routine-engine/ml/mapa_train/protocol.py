"""Frozen train-only model selection protocol for the MaPa-Rhythm bake-off."""
from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

import pyarrow.parquet as pq

from mapa_release.common import ROOT
from mapa_release.common import trial, progress

DATA = ROOT / "final-v2"
FAMILIES = ["logistic", "lgbm", "gru", "lstm", "transformer", "cnn"]
SEEDS = [20261101, 20261102, 20261103, 20261104, 20261105]
FOLDS = 5


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def verify_data(split: str = "train") -> dict:
    """Verify only the requested partition; training defaults to train only.

    The manifest is safe to inspect before selection. Validation and test
    payloads are not hashed or opened until their explicitly gated evaluation.
    """
    if split not in {"train", "val", "test", "all"}:
        raise ValueError(f"unknown data partition: {split}")
    manifest = json.loads((DATA / "manifest.json").read_text())
    for name, expected in manifest["sha256"].items():
        partition = Path(name).stem
        if split != "all" and partition != split:
            continue
        actual = sha256(DATA / name)
        if actual != expected:
            raise RuntimeError(f"Frozen final-v2 hash mismatch: {name}")
    return manifest


def person_ids() -> list[str]:
    verify_data()
    return sorted(set(pq.read_table(DATA / "train.parquet", columns=["pid"])["pid"].to_pylist()))


def assignments(seed: int = 20261101) -> dict[str, int]:
    """Assign whole people to balanced folds, deterministically by salted hash."""
    pids = person_ids()
    ordered = sorted(pids, key=lambda p: hashlib.sha256(f"{seed}:{p}".encode()).digest())
    return {pid: i % FOLDS for i, pid in enumerate(ordered)}


def legacy_freeze() -> dict:
    manifest = verify_data()
    pids = person_ids()
    folds = assignments()
    result = {
        "dataset": "final-v2",
        "dataset_manifest_sha256": sha256(DATA / "manifest.json"),
        "dataset_payload_sha256": manifest["sha256"],
        "families": FAMILIES,
        "seeds": SEEDS,
        "folds": FOLDS,
        "train_people": len(pids),
        "fold_people": {str(f): sum(v == f for v in folds.values()) for f in range(FOLDS)},
        "fold_assignment": folds,
        "selection_data": "train only; person-grouped out-of-fold results",
        "validation": "one confirmation after family and settings are locked",
        "test": "not opened by this protocol",
        "real_check": "not opened by this protocol",
        "created_before_training": True,
    }
    path = ROOT / "training-protocol.json"
    result.update({
        "feature_contract": {"phone_inputs": 18, "movement_indices": [16, 17],
                             "quiet_history_indices": [18, 19], "total": 20},
        "day_quality_gate": {"start_bin": 24, "end_bin": 96, "minimum_observed_bins": 56,
                             "minimum_observed_hours": 14},
        "epochs_per_fit": 15,
        "family_selection": "mean of fold-level decision metrics; all family/settings outcomes retained",
        "decision_grid": "mapa_train.decision.grid; frozen source hash recorded in run metadata",
        "test_opening": "prohibited until family, config and trained weights are locked",
        "final_evaluation": "one validation confirmation, then one test opening through mapa_train.final_eval; no tuning or refit",
    })
    if path.exists():
        old = json.loads(path.read_text())
        if old != result:
            if old.get("feature_contract") is None:
                legacy = ROOT / "training-protocol-v1-incomplete.json"
                if not legacy.exists(): shutil.copy2(path, legacy)
                path.write_text(json.dumps(result, indent=2) + "\n")
            else:
                raise RuntimeError("Training protocol already exists and differs; do not redraw folds")
    else:
        path.write_text(json.dumps(result, indent=2) + "\n")
    trial("training-protocol", "final-v2-5fold", "PASS",
          f"Frozen before training; {len(pids)} train people across folds {result['fold_people']}; five seeds; validation one confirmation; test unopened.")
    progress("Training protocol frozen before model runs. final-v2 hashes match; person-grouped five-fold assignments and five deterministic seeds are recorded. Test and real_check remain unopened.")
    return result


def freeze(version: int = 9) -> dict:
    """Freeze a continuation protocol while retaining the fixed person folds."""
    if version not in {9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24}:
        raise ValueError(f"unsupported training protocol version: {version}")
    from .zoo import STAGE_EPOCHS, NEURAL
    from .decision import grid
    from .ablations import ROWS, decision_grid
    verify_data()
    original = json.loads((ROOT / "training-protocol.json").read_text())
    if original["fold_assignment"] != assignments():
        raise RuntimeError("Original person folds differ; refusing to redraw them")
    result = dict(original)
    result.update({
        "protocol_version": version,
        "decision_quantile_cache": {
            "implementation": "CausalHistory memoizes exact NumPy quantiles keyed by person/day/quantile/history type",
            "scope": "train OOF decision grid only; preserves np.quantile values and decisions",
        },
        "carried_forward_hybrid": {
            "protocol": "v8",
            "protocol_sha256": sha256(ROOT / "training-protocol-v8.json"),
            "results": "models/zoo-hybrid-night-v6-hybrid/cv-decision-results.json",
            "reason": "v9 fixes stage-list enumeration for the two-stage plain-CNN ablation; hybrid's configured three-stage sequence is unchanged",
            "fit_reuse": "v8 hybrid OOF fits and selection are retained, hash-verified and not retrained",
        },
        "model_name": "MaPa Rhythm Hybrid",
        "families": list(NEURAL) + ["lightgbm"],
        "feature_contract": {"phone_inputs": 18, "movement_indices": [16, 17],
                             "quiet_history_indices": [18, 19], "session_start_index": 20,
                             "total": 21},
        "stage_epochs": STAGE_EPOCHS,
        "decision_settings": list(grid()),
        "ablation_rows": ROWS,
        "ablation_decision_settings": {str(row): list(decision_grid(row)) for row in range(1, 11)},
        "cold_start_bins": [[0, 1], [2, 3], [4, 7], [8, 14], [15, None]],
        "parameter_limit": 50000,
        "cold_start_pseudo_days": 7.0,
        "cold_start_cache": {
            "artifact": "final-v2-train-arrays-v2.npz",
            "contents": "all 21-step inputs, labels/masks, daily rows, per-person/day history tables and fold/seed archetype priors",
            "cache_scope": "one train-only array file; loaded once per GPU process, shared by folds and seeds",
            "equivalence": "history statistics exactly match old apply_priors; sample equality test is required",
        },
        "seed_schedule": {
            "hybrid": SEEDS[:3],
            "ablations": SEEDS[:1],
            "comparisons": SEEDS[:1],
            "extra_seeds": SEEDS[3:],
        },
        "fold_execution": "five person folds in separate local PC processes; no real_check access",
        "training_backend": {"device": "NVIDIA GeForce RTX 3060 Laptop GPU", "memory_mib": 6144,
                             "mixed_precision": "CUDA autocast float16 with GradScaler",
                             "whole_dataset_gpu_resident": True, "dataloader_workers": 0,
                             "parallel_fold_processes": 3},
        "coverage_policy": {"normal_pretraining": "all observed real bins with target masks, every fold",
                            "slow_daily_rows": "at least 56 observed bins between06:00and24:00",
                            "real_behavior_and_limit_history": "partial real days excluded"},
        "export": {"runtime": "ExecuTorch", "backend": "XNNPACK", "parity_tolerance": .001},
        "wording": "Trained on real smartphone logs, plus parent timelines generated by a statistical model learned from those logs. Every disruption is injected.",
        "source_sha256": {name: sha256(Path(__file__).parent / name)
                          for name in ["protocol.py", "zoo.py", "decision.py", "features20.py", "quiet_cache.py", "precompute_arrays.py", "gpu_training.py", "gpu_cv.py", "parallel_cv.py", "final_fit.py", "final_eval.py", "locked_model.py", "../mapa_release/learning.py", "../mapa_release/forest.py", "../mapa_syn/io.py", "augmentation.py", "cold_start.py", "ablations.py", "evaluate_saved_oof.py", "masking.py", "models.py", "hybrid_streaming.py", "hybrid_weights.py", "export_hybrid.py", "checkpoints.py", "../tests/mapa_train/test_final_eval_gates.py", "../../engine/src/main/kotlin/app/mapa/routine/HybridReference.kt", "../../engine/build.gradle.kts"]},
    })
    if version == 10:
        result["protocol_change_note"] = (
            "LightGBM fold tuple assembly fix only. Retains v9 final-v2 data, person folds, seed, "
            "feature/target schemas, training stages, hyperparameters, and evaluator. Existing v9 "
            "OOF results remain separately versioned and are not relabeled."
        )
    elif version == 11:
        result["protocol_change_note"] = (
            "LightGBM and final-fit mask corrections: remove stale cache tuple fields and use boolean "
            "normal-row, behavior-row, and target masks to avoid uint8 bitwise inversion/indexing. Retains "
            "the v9 final-v2 data, person folds, seed, feature/target schemas, training stages, "
            "hyperparameters, and evaluator. The failed v10 attempt produced no fit artifacts; prior v9 "
            "OOF results remain separately versioned and unchanged."
        )
    elif version == 12:
        result["protocol_change_note"] = (
            "Correct cumulative ablation lineage after the train-OOF evidence dropped attention: rows 9 and 10 "
            "now inherit row 7 (no attention), with row 9 adding day-one start and row 10 adding the full "
            "decision layer. final-v2 data, person folds, seeds, stage allocations, and evaluator are unchanged. "
            "Previously completed attention-based rows 9 and 10 remain diagnostic and are not relabeled."
        )
    elif version == 13:
        result["protocol_change_note"] = (
            "Adds a hash-checked train-OOF-only evaluator for corrected no-attention row 9. It reuses the "
            "existing five person-held-out no-attention fits with matching architecture and calls the single "
            "shared decision.score_config evaluator. No model refit, validation, or test read. final-v2 data, "
            "person folds, seeds, row definitions, and training settings remain unchanged."
        )
    elif version == 14:
        result["protocol_change_note"] = (
            "Final-fit selection now locks the model architecture report and the separately evaluated row-9 "
            "train-OOF decision report by SHA-256. This prevents inheriting row-10 morning-check settings from "
            "the architecture-fit folder when row 9 is the selected policy. Training data, person folds, model "
            "architecture, and train-only selection evidence are unchanged; validation and test remain closed."
        )
    elif version == 15:
        result["protocol_change_note"] = (
            "Final-fit selection locks both model and row-9 decision reports, and now verifies every saved OOF "
            "source artifact hash listed by the separate decision report before fitting. This is a pre-fit "
            "integrity correction to v14; no fitting occurred under v14. Training data, person folds, model "
            "architecture, and train-only selection evidence are unchanged; validation and test remain closed."
        )
    elif version == 16:
        result["protocol_change_note"] = (
            "Final-fit preflight now resolves the shared train.groups.json source artifact from the frozen "
            "learning bundle, rather than the model output folder. A pre-fit attempt under v15 stopped on this "
            "path mismatch before creating a checkpoint or reading validation/test. v16 includes the corrected "
            "path and the same report/source hash checks. No training occurred under v14 or v15."
        )
    elif version == 17:
        result["protocol_change_note"] = (
            "Adds an explicit trained-checkpoint export path, hash-locked to the final-fit selection, with "
            "XNNPACK export, ExecuTorch runtime parity, Kotlin text weights and deterministic Kotlin golden "
            "vectors. This changes deployment artifact generation only; no training or selection results change."
        )
    elif version == 18:
        result["protocol_change_note"] = (
            "Adds a train-OOF-selected 0.15 model / 0.85 profile blend for the next-bin forecast only. The weight "
            "was chosen by untouched-real next-bin log-loss, not detector metrics; the fixed row-9 alert policy "
            "was audited on train OOF and its reported behavior metrics were unchanged. The trained exporter, "
            "Kotlin reference and hash list now include this calibration and parity path. No validation/test reads."
        )
    elif version == 19:
        result["protocol_change_note"] = (
            "Adds a gated, one-shot final evaluator that loads the train-locked hybrid, generates validation/test "
            "scores, reports behavior and observation episodes separately, includes the 8-hour timer, and records "
            "next-use loss and cold-start strata. Test requires a validation confirmation bound to the same locked "
            "selection; the test access marker is written before any split payload read. This is evaluation tooling "
            "only; no train data, weights, selection settings, or held-out payloads changed during implementation."
        )
    elif version == 20:
        result["protocol_change_note"] = (
            "Hardens the final-evaluation path: held-out feature building now requires a matching split access "
            "marker, decision.day_views checks the split gate before hashing or reading a held-out payload, and "
            "the frozen shipped CNN is preflighted and evaluated only after its reconstructed fit-person set is "
            "verified disjoint from the evaluation people. Adds source hashes for the feature-array builder and "
            "CNN loader. No held-out payload was read while making this change."
        )
    elif version == 21:
        result["protocol_change_note"] = (
            "Adds a separately reported first-week zero-history stress replay: existing held-out injected "
            "behaviour episodes are re-evaluated as simulated install-day episodes with zero prior personal days. "
            "These episodes are not added to the frozen dataset or pooled into ordinary C4 metrics. No held-out "
            "payload was read while implementing or testing this replay."
        )
    elif version == 22:
        result["protocol_change_note"] = (
            "Extends the shared decision evaluator with person counts and 95% person-bootstrap false-alert "
            "intervals for each fixed baseline as well as the selected model. Point estimates and selection "
            "rules are unchanged. No model was refit and no validation/test payload was read."
        )
    elif version == 23:
        result["protocol_change_note"] = (
            "Adds complete Markdown report generation from the saved held-out JSON result, including all baselines, "
            "separate observation metrics, cold-start strata, first-week stress results, and next-use metrics. A "
            "saved-report render path can regenerate documentation without reopening the split. No model was refit "
            "and no validation/test payload was read."
        )
    elif version == 24:
        result["protocol_change_note"] = (
            "Adds locked export and parity details to the final held-out reports: parameter count, ExecuTorch "
            "program sizes and max errors, Kotlin parity errors, and the explicit unmeasured Pixel 6a latency. "
            "Values are read from the frozen trained export manifest. No model or held-out data changed."
        )
    path = ROOT / f"training-protocol-v{version}.json"
    if path.exists() and json.loads(path.read_text()) != result:
        raise RuntimeError(f"GPU protocol v{version} changed after freezing; record a new protocol version")
    if not path.exists():
        path.write_text(json.dumps(result, indent=2) + "\n")
        trial("training-protocol", f"hybrid-v{version}", "FROZEN",
              "21 inputs; fixed person folds; v8 hybrid candidate hash-verified and carried forward; "
              "plain-CNN two-stage ablation schedule fixed; exact decision cache retained")
    return result


if __name__ == "__main__":
    print(json.dumps(freeze(), indent=2))

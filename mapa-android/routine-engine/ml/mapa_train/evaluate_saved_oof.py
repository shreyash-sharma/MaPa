"""Evaluate a decision ablation on already-fitted, person-held-out train OOF scores.

This reuses the shared decision.score_config evaluator; it never opens validation/test
and never refits a model. Used only when an identical no-attention fit can be reused
for a row whose difference is solely the post-hoc decision grid.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from mapa_release.common import ROOT, progress, trial
from .ablations import configuration, decision_grid
from .decision import cold_start_curve, day_views, personal_history, score_config, stats_limits
from .protocol import DATA, SEEDS, freeze, sha256, verify_data


def _sha(path: Path) -> str:
    import hashlib
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def _select(configs: list[dict], fold_results: list[list[dict]]) -> tuple[dict, list[dict]]:
    means = []
    for ci, cfg in enumerate(configs):
        fold_rows = [rows[ci] for rows in fold_results]
        baselines = {b: {
            "fa_per_week": float(np.mean([r[b]["fa_per_week"] for r in fold_rows])),
            "catch": {s: float(np.mean([r[b]["catch"][s] for r in fold_rows])
                                ) for s in ("mild", "clear", "severe")},
        } for b in ("4h_rule", "statistics_only")}
        model = {
            "fa_per_week": float(np.mean([r["model"]["fa_per_week"] for r in fold_rows])),
            "catch": {s: float(np.mean([r["model"]["catch"][s] for r in fold_rows])
                                ) for s in ("mild", "clear", "severe")},
        }
        c4 = all(model["fa_per_week"] < baselines[b]["fa_per_week"] and
                 all(model["catch"][s] >= baselines[b]["catch"][s]
                     for s in ("mild", "clear", "severe")) for b in baselines)
        minimum = (model["fa_per_week"] < baselines["statistics_only"]["fa_per_week"] and
                   model["catch"]["severe"] == 1.0 and
                   all(model["catch"][s] >= baselines["statistics_only"]["catch"][s]
                       for s in ("mild", "clear", "severe")) and
                   all(r["stale_as_fresh"] == 0 for r in fold_rows))
        means.append({"config": cfg, "model": model, "baselines": baselines,
                      "minimum_mean": bool(minimum), "C4_mean": bool(c4), "folds": fold_rows})
    passing = [r for r in means if r["C4_mean"] and r["minimum_mean"]]
    if not passing:
        passing = [r for r in means if r["minimum_mean"]]
    chosen = min(passing, key=lambda r: r["model"]["fa_per_week"]) if passing else max(
        means, key=lambda r: min(r["model"]["catch"][s] -
                                 max(r["baselines"][b]["catch"][s] for b in r["baselines"])
                                 for s in ("mild", "clear", "severe")))
    return chosen, means


def run(row: int = 9, model_name: str = "zoo-hybrid-no-attention-night-v7-comparisons",
        protocol_version: int = 13) -> dict:
    trial("cv-ablation-reuse", f"row{row}-no-attention-saved-oof", "STARTED",
          "final-v2 train OOF only; reuse hash-checked no-attention fits; shared decision evaluator; no validation/test")
    try:
        if row not in (9, 10):
            raise ValueError("Only corrected no-attention rows 9 and 10 may reuse this saved fit")
        protocol = freeze(protocol_version)
        verify_data("train")
        model_dir = ROOT / "models" / model_name
        complete = json.loads((model_dir / "complete.json").read_text(encoding="utf-8"))
        if (complete.get("dataset_version") != "final-v2" or complete.get("feature_contract") != 21 or
                complete.get("train_people") != 162 or complete.get("cv_folds") != 5 or
                complete.get("seeds") != [SEEDS[0]] or complete.get("test_read") is not False or
                complete.get("validation_read") is not False):
            raise RuntimeError("Saved fit is outside the frozen one-seed/five-fold train-only reuse contract")
        cfg = configuration(row)
        expected_arch = {k: bool(cfg.get(k, False))
                         for k in ("data_tricks", "cold_start", "personal", "slow", "attention")}
        runs = complete.get("runs", [])
        if len(runs) != 5 or {int(r["fold"]) for r in runs} != set(range(5)):
            raise RuntimeError("Saved fit does not contain exactly one fit for each held person fold")
        if any(r.get("ablation_config") != expected_arch for r in runs):
            raise RuntimeError("Saved fit architecture differs from the corrected no-attention ablation row")
        for run_row in runs:
            fit_dir = model_dir / f"fold{int(run_row['fold'])}-seed{int(run_row['seed'])}"
            for rel, digest in run_row["artifacts_sha256"].items():
                artifact = fit_dir / rel
                if not artifact.is_file() or _sha(artifact) != digest:
                    raise RuntimeError(f"Saved fit artifact failed SHA-256 verification: {artifact.name}")
        configs = list(decision_grid(row))
        if configs != protocol["ablation_decision_settings"][str(row)]:
            raise RuntimeError("Corrected row decision grid differs from the frozen protocol")
        fold_map = protocol["fold_assignment"]
        views = day_views("train", model_name)
        fold_results = []
        for fold in range(5):
            held = [v for v in views if int(fold_map[v["pid"]]) == fold]
            histories = {kind: personal_history(held, kind)
                         for kind in ("behaviour", "surprise", "combined")}
            stats_limits(held)
            fold_results.append([score_config(held, histories[c["score_kind"]], c)
                                 for c in configs])
        chosen, all_settings = _select(configs, fold_results)
        curve = cold_start_curve(views, personal_history(views, chosen["config"]["score_kind"]),
                                 chosen["config"])
        groups_path = ROOT / "learning-causal-v3-final-v2" / "train.groups.json"
        report = {
            "ablation_row": row, "ablation_name": cfg["name"], "model": model_name,
            "model_fit_protocol_sha256": complete["training_protocol_v9_sha256"],
            "evaluation_protocol_sha256": sha256(ROOT / f"training-protocol-v{protocol_version}.json"),
            "dataset_manifest_sha256": sha256(DATA / "manifest.json"),
            "metric_scope": "final-v2 train person-held-out OOF only; fixed one-seed saved fits; no refitting",
            "oof_people": protocol["fold_people"], "seeds": [SEEDS[0]],
            "settings_evaluated": len(configs), "selected_on_train_oof_only": chosen,
            "all_settings": all_settings, "cold_start_curve": curve,
            "source_artifacts_sha256": {
                name: _sha(model_dir / name) for name in
                ("complete.json", "train-scores.npy", "train-next-use.npy", "train-confidence.npy")
            } | {"train.groups.json": _sha(groups_path)},
            "validation_opened": False, "test_opened": False,
        }
        out = ROOT / "results" / f"ablation-row{row}-no-attention-train-oof.json"
        out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        trial("cv-ablation-reuse", f"row{row}-no-attention-saved-oof", "COMPLETE",
              f"5 person folds; {len(configs)} settings; minimum={chosen['minimum_mean']}; C4={chosen['C4_mean']}; "
              f"report={out.name}; sha256={_sha(out)}; validation/test unopened")
        progress(f"Corrected no-attention ablation row {row} evaluated on hash-verified saved OOF fits. "
                 f"Selected FA/week={chosen['model']['fa_per_week']:.6f}; minimum={chosen['minimum_mean']}; "
                 f"C4={chosen['C4_mean']}; validation/test remain closed.")
        return report
    except Exception as exc:
        trial("cv-ablation-reuse", f"row{row}-no-attention-saved-oof", "FAIL",
              f"{type(exc).__name__}: {exc}")
        raise


if __name__ == "__main__":
    print(json.dumps(run(), indent=2))

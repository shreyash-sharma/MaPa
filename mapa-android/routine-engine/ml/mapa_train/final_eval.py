"""One-shot held-out evaluation for the frozen MaPa Rhythm Hybrid.

Usage at the scheduled final stage:
    python -m mapa_train.final_eval --split val
    python -m mapa_train.final_eval --split test

The test command is locked until the validation confirmation exists. Selection,
checkpoint, calibration, and model settings are hash-checked before a split is
opened. A durable access marker is created before reading that split.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from collections import defaultdict

import numpy as np
import torch

from mapa_release.common import ROOT, progress, trial
from mapa_release.learning import prepare_arrays
from .decision import DAY, cold_start_curve, day_views, detect, next_use_report, personal_history, score_config, stats_limits
from .features20 import build as build_features
from .locked_model import load_locked_hybrid
from .protocol import DATA, sha256, verify_data
from .cold_start import apply_priors
from .zoo import predict

MODEL_NAME = "final-fit-hybrid-row9-v16"


def fixed_timer_metrics(views: list[dict], quiet_bins: int = 32) -> dict:
    """Report an eight-hour fixed timer with the same denominators as score_config."""
    by_person = defaultdict(lambda: [0, 0.0])
    behavior = {name: [0, 0] for name in ("mild", "clear", "severe")}
    observation = {name: [0, 0] for name in ("mild", "clear", "severe")}
    hard_negative = [0, 0]
    stale = 0
    for row in views:
        alerts = (row["silence"] >= quiet_bins) & row["alert_eligible"] & row["avail"]
        stale += int(np.sum(alerts & ~row["avail"]))
        if not row["eid"]:
            alerts = alerts & ~row["tags"]
            events = int(np.sum(alerts & ~np.r_[False, alerts[:-1]]))
            exposure = float(np.sum(row["avail"] & DAY & ~row["tags"]) / (60 * 7))
            by_person[row["pid"]][0] += events
            by_person[row["pid"]][1] += exposure
        elif row["expect"] == "unusual_inactivity":
            slot = row["severity"]
            behavior[slot][0] += int(bool((alerts & row["win"]).any()))
            behavior[slot][1] += 1
        elif row["expect"] == "monitoring_unavailable":
            slot = row["severity"]
            observation[slot][0] += int(bool((~row["avail"] & row["win"]).any()))
            observation[slot][1] += 1
        elif row["expect"] == "none":
            hard_negative[0] += int(bool((alerts & row["win"]).any()))
            hard_negative[1] += 1
    false_alerts = sum(v[0] for v in by_person.values())
    exposure = sum(v[1] for v in by_person.values())
    rng = np.random.default_rng(20261101)
    people = np.asarray(list(by_person.values()), dtype=np.float64)
    ci = None
    if len(people):
        draws = people[rng.integers(len(people), size=(1000, len(people)))].sum(axis=1)
        rates = draws[:, 0] / np.maximum(draws[:, 1], 1e-12)
        ci = np.quantile(rates, [.025, .975]).tolist()
    return {
        "rule": f"{quiet_bins * 15}-minute continuous observed quiet timer",
        "false_alerts": false_alerts,
        "person_weeks_observed": exposure,
        "false_alerts_per_person_week": false_alerts / max(exposure, 1e-9),
        "false_alerts_95ci_person_bootstrap": ci,
        "people": len(by_person),
        "behavior_episodes": {s: {"caught": n, "episodes": total,
                                   "rate": n / max(total, 1)}
                              for s, (n, total) in behavior.items()},
        "observation_episodes_separate": {s: {"caught": n, "episodes": total,
                                              "rate": n / max(total, 1)}
                                          for s, (n, total) in observation.items()},
        "hard_negative_episodes": {"alerted": hard_negative[0], "episodes": hard_negative[1],
                                    "rate": hard_negative[0] / max(hard_negative[1], 1)},
        "stale_as_fresh": stale,
    }


def shipped_cnn_training_pids() -> set[str]:
    """Reconstruct the shipped cnn-v1 fit population from its recorded sources."""
    from mapa_syn.io import sha256 as mapa_sha256
    cnn_folder = ROOT / "models" / "cnn-v1"
    complete = json.loads((cnn_folder / "complete.json").read_text())
    selection = json.loads((cnn_folder / "selection.json").read_text())
    checkpoint = cnn_folder / "mapa-rhythm-base.pt"
    if mapa_sha256(checkpoint) != complete.get("checkpoint_sha256") or selection.get("checkpoint_sha256") != complete.get("checkpoint_sha256"):
        raise RuntimeError("Shipped CNN checkpoint hash does not match its training and selection records")
    if complete.get("training_source") != "candidate train only":
        raise RuntimeError("Shipped CNN candidate-training provenance is not recognized")
    import pyarrow.parquet as pq
    fit_pids = set()
    for filename in ("train-base.parquet", "train-episodes.parquet"):
        path = ROOT / "candidate" / filename
        if not path.is_file():
            raise RuntimeError(f"Shipped CNN training source is missing: {filename}")
        fit_pids.update(str(pid) for pid in pq.read_table(path, columns=["pid"])["pid"].to_pylist())
    access_path = ROOT.parent / "generator" / "fit-access.json"
    access = json.loads(access_path.read_text())
    if access.get("heldout_opened") or access.get("real_check_opened"):
        raise RuntimeError("Shipped CNN real-pretraining provenance includes a held-out/real_check access record")
    fit_pids.update(str(pid) for pid in access.get("opened_person_pids", []))
    return fit_pids


def load_shipped_cnn(device: str) -> dict:
    """Load and verify the shipped CNN before opening any held-out split."""
    cnn_folder = ROOT / "models" / "cnn-v1"
    try:
        training_pids = shipped_cnn_training_pids()
        from mapa_release.forest import load_network
        checkpoint = torch.load(cnn_folder / "mapa-rhythm-base.pt", map_location="cpu", weights_only=False)
        model = load_network(checkpoint).to(device).eval()
        threshold = float(json.loads((cnn_folder / "selection.json").read_text())["threshold"])
    except Exception as error:
        return {"status": "ineligible", "reason": f"Could not verify/load shipped CNN: {error}"}
    return {"status": "ready", "model": model, "training_pids": training_pids,
            "threshold": threshold,
            "provenance": {
        "training_people": len(training_pids),
        "training_pids_sha256": hashlib.sha256("\n".join(sorted(training_pids)).encode()).hexdigest(),
        "checkpoint_sha256": sha256(cnn_folder / "mapa-rhythm-base.pt"),
    }}


def shipped_cnn_metrics(views: list[dict], arrays: dict, groups: list[dict], device: str,
                        preflight: dict) -> dict:
    """Evaluate the frozen shipped CNN only when its full fit-pid set is disjoint."""
    if preflight["status"] != "ready":
        return preflight
    evaluation_pids = {str(group["pid"]) for group in groups}
    training_pids = preflight["training_pids"]
    overlap = sorted(training_pids & evaluation_pids)
    provenance = {**preflight["provenance"], "evaluation_people": len(evaluation_pids),
                  "overlap_people": len(overlap)}
    if overlap:
        return {"status": "ineligible", "reason": "Person overlap with shipped CNN fitting data", **provenance}
    model = preflight["model"]
    scores = []
    with torch.no_grad():
        x = arrays["x"][:, :, :18]
        for start in range(0, len(x), 256):
            logits, _ = model(torch.from_numpy(x[start:start + 256]).to(device))
            scores.append(torch.sigmoid(logits[:, :, 2]).cpu().numpy())
    score = np.concatenate(scores)
    cnn_views = [{**row, "score_behavior": score[i], "score_surprise": np.zeros_like(score[i]),
                  "confidence": np.zeros_like(score[i])} for i, row in enumerate(views)]
    config = {"kind": "global", "floor": preflight["threshold"],
              "score_kind": "behaviour", "cold_start": False, "morning": False,
              "silence_floor": None, "model_weight": 1.0, "uncertainty_margin": 0.0}
    metrics = score_config(cnn_views, {}, config)
    return {"status": "evaluated", "threshold": config["floor"],
        "threshold_source": "frozen cnn-v1 selection; its historical validation comparison is descriptive",
            "metrics": metrics, **provenance}


def zero_history_episode_replay(model, arrays: dict, groups: list[dict], views: list[dict],
                                config: dict, device: str) -> dict:
    """Stress replay held-out injected behaviour episodes as day-zero installs.

    Each episode is evaluated with no prior personal days. This is reported
    separately from ordinary split metrics because it re-times existing
    injected episodes; it does not add a new observed event or alter final-v2.
    """
    chosen = [i for i, view in enumerate(views)
              if view["eid"] and view["expect"] == "unusual_inactivity"]
    counts = {severity: [0, 0] for severity in ("mild", "clear", "severe")}
    per_person = defaultdict(lambda: {severity: [0, 0] for severity in counts})
    by_scenario = defaultdict(lambda: [0, 0])
    delays = defaultdict(list)
    if not chosen:
        return {"status": "no_injected_behaviour_episodes", "episode_counts": counts}
    zero_history = np.zeros((len(chosen), 30, arrays["slow_history"].shape[-1]), dtype=np.float32)
    zero_count = np.zeros(len(chosen), dtype=np.int64)
    x = arrays["x"][chosen].copy()
    x = apply_priors(x, zero_history, zero_count, model.cold_start_priors)
    output = predict("hybrid-no-attention", model, x, device, zero_history, zero_count)
    hazards = output["hazard"].copy()
    profile = x[:, :, 11]
    calibration = json.loads((ROOT / "models" / MODEL_NAME / "forecast-calibration.json").read_text())
    hazards[..., 0] = float(calibration["model_weight"]) * hazards[..., 0] + float(calibration["profile_weight"]) * profile
    empty_history = {}
    alerts_by_row = {}
    for j, view_index in enumerate(chosen):
        original = views[view_index]
        survival = 1.0
        surprise = np.zeros(96, dtype=np.float32)
        for slot in range(96):
            if slot == 28:
                survival = 1.0
            if original["avail"][slot] and DAY[slot] and original["counts"][slot] == 0:
                hazard = float(hazards[j, max(0, slot - 1), 0])
                survival *= 1.0 - hazard
                surprise[slot] = 1.0 - survival
            else:
                survival = 1.0
        replay = {**original, "score_behavior": output["behaviour"][j],
                  "score_surprise": surprise, "confidence": output["confidence"][j],
                  "days_since_install": 0}
        key = (replay["pid"], replay["day"])
        empty_history[key] = (np.zeros(0), [])
        alerts_by_row[view_index] = detect(replay, empty_history, config)
        severity = replay["severity"]
        caught = int(bool((alerts_by_row[view_index] & replay["win"]).any()))
        counts[severity][0] += caught
        counts[severity][1] += 1
        per_person[str(replay["pid"])][severity][0] += caught
        per_person[str(replay["pid"])][severity][1] += 1
        scenario = str(replay["scenario"])
        by_scenario[scenario][0] += caught
        by_scenario[scenario][1] += 1
        if caught:
            onset = int(np.flatnonzero(replay["win"])[0])
            first = int(np.flatnonzero(alerts_by_row[view_index] & replay["win"])[0])
            delays[severity].append(max(0, first - onset) * 15)
    ci = {}
    people = list(per_person.values())
    rng = np.random.default_rng(20261101)
    for severity, (_, total) in counts.items():
        if not total or not people:
            ci[severity] = None
            continue
        draws = [people[int(i)][severity] for i in rng.integers(len(people), size=(1000, len(people))).ravel()]
        # Reshape to bootstrap samples, then aggregate person-level episode counts.
        draws = np.asarray(draws, dtype=np.float64).reshape(1000, len(people), 2).sum(axis=1)
        valid = draws[:, 1] > 0
        ci[severity] = np.quantile(draws[valid, 0] / draws[valid, 1], [.025, .975]).tolist()
    return {
        "status": "evaluated_separately",
        "description": "Existing held-out injected behaviour episodes replayed as simulated install-day episodes with zero prior personal days; not pooled into ordinary test metrics.",
        "people": len(per_person),
        "episodes_by_severity": {s: {"caught": n, "episodes": total,
                                      "rate": n / max(total, 1), "ci95_person_bootstrap": ci[s]}
                                 for s, (n, total) in counts.items()},
        "by_scenario": {s: {"caught": n, "episodes": total, "rate": n / max(total, 1)}
                        for s, (n, total) in by_scenario.items()},
        "time_to_alert_minutes": {s: {"caught_episodes": len(values),
                                       "median": float(np.median(values)) if values else None,
                                       "p90": float(np.quantile(values, .9)) if values else None}
                                  for s, values in delays.items()},
    }


def render_report(result: dict) -> str:
    """Render the locked machine result as a complete, reviewable Markdown report."""
    split = result["split"]
    alerts = result["alerts"]
    cnn = result["shipped_cnn_baseline"]
    cnn_metrics = cnn.get("metrics", {}).get("model") if cnn.get("status") == "evaluated" else None
    rows = [("MaPa Rhythm Hybrid", alerts["model"]),
            ("4-hour rule", alerts["4h_rule"]),
            ("8-hour rule", result["eight_hour_rule"]),
            ("12-hour timer", alerts["12h_rule"]),
            ("24-hour timer", alerts["24h_rule"]),
            ("Statistics only", alerts["statistics_only"])]
    if cnn_metrics is not None:
        rows.append(("Shipped CNN (cnn-v1)", cnn_metrics))
    lines = [f"# MaPa Rhythm {split.title()} Confirmation", "",
             f"- Model: `{result['model']}` (`{result['family']}`)",
             f"- Evaluation people / day rows: {result['people']} / {result['rows']}",
             f"- Selection SHA-256: `{result['selection_sha256']}`",
             f"- Checkpoint SHA-256: `{result['checkpoint_sha256']}`",
             f"- Frozen split SHA-256: `{result['split_sha256']}`",
             f"- Held-out split opened: {str(result['test_opened'] if split == 'test' else True).lower()}",
             "- Validation is a single confirmation; test is a single final evaluation. No threshold selection or refit occurs here.", "",
             "## False alerts and behaviour catches", "",
             "False alerts are per observed person-week. Behaviour catches use injected behaviour episodes only; observation episodes are listed separately.", "",
             "| System | False alerts / person-week (95% person-bootstrap CI) | Mild catch | Clear catch | Severe catch |", "|---|---:|---:|---:|---:|"]
    for name, metrics in rows:
        catches = metrics.get("catch", {})
        timer_catches = metrics.get("behavior_episodes", {})
        counts = metrics.get("caught_counts", {})
        episodes = metrics.get("episode_counts", {})
        ci = metrics.get("catch_95ci", {})
        def catch_cell(severity):
            rate = catches.get(severity)
            if rate is None and severity in timer_catches:
                timer = timer_catches[severity]
                rate = timer.get("rate")
                return f"{timer.get('caught', 0)}/{timer.get('episodes', 0)} ({rate:.3f})"
            if rate is None:
                return "n/a"
            interval = ci.get(severity) if isinstance(ci, dict) else None
            suffix = f" [{interval[0]:.3f}, {interval[1]:.3f}]" if interval else ""
            return f"{counts.get(severity, 0)}/{episodes.get(severity, 0)} ({rate:.3f}){suffix}"
        fa = metrics.get("fa_per_week", metrics.get("false_alerts_per_person_week"))
        fa_ci = metrics.get("fa_95ci", metrics.get("false_alerts_95ci_person_bootstrap"))
        fa_text = "n/a" if fa is None else f"{fa:.4f}"
        if fa_ci:
            fa_text += f" [{fa_ci[0]:.4f}, {fa_ci[1]:.4f}]"
        lines.append(f"| {name} | {fa_text} | {catch_cell('mild')} | {catch_cell('clear')} | {catch_cell('severe')} |")
    if cnn.get("status") != "evaluated":
        lines += ["", f"Shipped CNN status: **{cnn.get('status')}** — {cnn.get('reason', 'not evaluated')}."]
    lines += ["", "## Complete metric records", "",
              "The JSON below preserves every count, interval, negative result, and guard field from the evaluator.",
              "", "### Decision metrics", "", "```json", json.dumps(alerts, indent=2), "```",
              "", "### 8-hour baseline", "", "```json", json.dumps(result["eight_hour_rule"], indent=2), "```",
              "", "### Shipped CNN baseline", "", "```json", json.dumps(cnn, indent=2), "```",
              "", "### Cold-start curve", "", "```json", json.dumps(result["cold_start_curve"], indent=2), "```",
              "", "### Zero-history first-week injected stress replay", "", "```json",
              json.dumps(result["cold_start_unwell_first_week_stress"], indent=2), "```",
              "", "### Next-use log-loss", "", "Raw model forecast:", "", "```json",
              json.dumps(result["next_use_raw"], indent=2), "```", "", "Train-OOF calibrated model/profile blend:", "",
              "```json", json.dumps(result["next_use_blended"], indent=2), "```", "",
              "## On-device artifacts and parity", "", "```json",
              json.dumps(result.get("on_device", {}), indent=2), "```", "",
              "## Export and scope", "",
              f"Feature contract: {result['feature_contract']} inputs. Test is opened only once after the validation result is saved for the same selection. This report makes no medical or safety claim.", ""]
    return "\n".join(lines)


def write_saved_report(split: str, model_name: str = MODEL_NAME) -> Path:
    """Rebuild Markdown from a completed JSON result without reading dataset rows."""
    if model_name != MODEL_NAME:
        raise ValueError(f"Reports are locked to {MODEL_NAME}")
    folder = ROOT / "models" / model_name
    result_path = folder / f"{split}-results.json"
    if split not in {"val", "test"} or not result_path.is_file():
        raise FileNotFoundError(f"No saved {split} result to render")
    result = json.loads(result_path.read_text())
    if result.get("selection_sha256") != sha256(folder / "selection.json"):
        raise RuntimeError("Saved result no longer matches the locked selection")
    report = ROOT / "results" / ("validation.md" if split == "val" else "test.md")
    report.parent.mkdir(parents=True, exist_ok=True)
    temporary = report.with_suffix(".md.tmp")
    temporary.write_text(render_report(result), encoding="utf-8")
    os.replace(temporary, report)
    return report


def begin_access(folder: Path, split: str) -> dict:
    """Check gates and create an exclusive access marker without reading data."""
    folder = Path(folder)
    if split not in {"val", "test"}:
        raise ValueError("Only validation confirmation and final test are supported")
    selection_path = folder / "selection.json"
    if not selection_path.is_file():
        raise RuntimeError("Held-out evaluation requires a saved train-only selection")
    selection = json.loads(selection_path.read_text())
    if selection.get("selection_data") != "train person-OOF only":
        raise RuntimeError("Selection is not locked to train person-OOF evidence")
    if selection.get("test_opened") is not False:
        raise RuntimeError("Selection does not certify that test remains unopened")
    selection_hash = sha256(selection_path)
    result_path = folder / f"{split}-results.json"
    access_path = folder / f"{split}-access.json"
    if result_path.exists() or access_path.exists():
        raise RuntimeError(f"{split} confirmation is one-shot; a result or access marker already exists")
    if split == "test":
        val_path = folder / "validation-results.json"
        if not val_path.is_file():
            raise RuntimeError("Test is locked until the single validation confirmation is recorded")
        validation = json.loads(val_path.read_text())
        if validation.get("selection_sha256") != selection_hash or validation.get("confirmation_only") is not True:
            raise RuntimeError("Validation result does not confirm this exact locked selection")
    # O_EXCL makes concurrent attempts fail closed. This marker is written
    # before verify_data(), feature building, or any split payload access.
    marker = {"split": split, "model": folder.name,
              "selection_sha256": selection_hash, "access_started": True}
    fd = os.open(access_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(marker, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    return marker


def evaluate(split: str, model_name: str = MODEL_NAME) -> dict:
    if split not in {"val", "test"}:
        raise ValueError("split must be val or test")
    if model_name != MODEL_NAME:
        raise ValueError(f"Final held-out evaluation is locked to {MODEL_NAME}")
    folder = ROOT / "models" / model_name
    # Verify all model-side inputs before creating the split access marker.
    model, selection, metadata = load_locked_hybrid(folder, "cuda" if torch.cuda.is_available() else "cpu")
    if selection.get("decision_config") is None:
        raise RuntimeError("Locked selection has no decision configuration")
    calibration_path = folder / "forecast-calibration.json"
    if sha256(calibration_path) != selection.get("forecast_calibration_sha256"):
        raise RuntimeError("Locked forecast calibration hash mismatch")
    calibration = json.loads(calibration_path.read_text())
    model_weight = float(calibration["model_weight"])
    profile_weight = float(calibration["profile_weight"])
    if not np.isclose(model_weight + profile_weight, 1.0) or not 0 <= model_weight <= 1:
        raise RuntimeError("Locked next-use calibration weights are invalid")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    cnn_preflight = load_shipped_cnn(device)
    marker = begin_access(folder, split)
    trial("heldout-evaluation", f"{model_name}-{split}", "STARTED",
          f"one-shot {split}; selection_sha256={marker['selection_sha256']}; test remains gated by validation")
    try:
        # The split is opened only after the selection is verified and the
        # durable access marker is present. Caches are created from this split.
        manifest = verify_data(split)
        version = metadata.get("data_version", "causal-v3")
        prepare_arrays(split, version, "final-v2")
        arrays, feature_info = build_features(split, version, "final-v2",
                                               allow_locked_eval=True)
        groups_path = ROOT / f"learning-{version}-final-v2" / f"{split}.groups.json"
        groups = json.loads(groups_path.read_text())
        outputs = predict(selection["family"], model, arrays["x"], device,
                          arrays["slow_history"], arrays["slow_count"])
        blended_hazard = outputs["hazard"].copy()
        profile_probability = arrays["x"][:, :, 11]
        blended_hazard[..., 0] = (model_weight * blended_hazard[..., 0]
                                   + profile_weight * profile_probability)
        np.save(folder / f"{split}-scores.npy", outputs["behaviour"])
        np.save(folder / f"{split}-confidence.npy", outputs["confidence"])
        np.save(folder / f"{split}-next-use.npy", blended_hazard)

        views = day_views(split, model_name, access_authorized=True)
        cfg = selection["decision_config"]
        history = personal_history(views, cfg.get("score_kind", "behaviour"))
        stats_limits(views)
        alert_metrics = score_config(views, history, cfg)
        cold_start = cold_start_curve(views, history, cfg)
        raw_forecast = next_use_report(groups, arrays["y"], arrays["mask"], outputs["hazard"],
                                       profile_probability, arrays["real"], arrays["episode"])
        blended_forecast = next_use_report(groups, arrays["y"], arrays["mask"], blended_hazard,
                                           profile_probability, arrays["real"], arrays["episode"])
        cnn_baseline = shipped_cnn_metrics(views, arrays, groups, device, cnn_preflight)
        first_week_stress = zero_history_episode_replay(model, arrays, groups, views,
                                                        cfg, device)
        export_manifest = json.loads((folder / "export" / "export-manifest.json").read_text())
        params = sum(parameter.numel() for parameter in model.parameters())
        on_device = {
            "parameters": params,
            "executorch_runtime": export_manifest.get("runtime"),
            "executorch_step_bytes": export_manifest["files"]["rhythm-step.pte"]["bytes"],
            "executorch_daily_bytes": export_manifest["files"]["rhythm-daily.pte"]["bytes"],
            "executorch_stream_max_error": export_manifest["execu_torch_parity"]["stream_max_error"],
            "executorch_daily_max_error": export_manifest["execu_torch_parity"]["daily_max_error"],
            "executorch_parity_limit": export_manifest["execu_torch_parity"]["limit"],
            "kotlin_stream_max_error": export_manifest["kotlin_parity"]["stream_max_error"],
            "kotlin_daily_max_error": export_manifest["kotlin_parity"]["daily_max_error"],
            "kotlin_parity_limit": export_manifest["kotlin_parity"]["tolerance"],
            "pixel_6a_ms_per_step": None,
            "pixel_6a_latency_status": "not measured",
        }
        result = {
            "split": split,
            "model": model_name,
            "family": selection["family"],
            "selection_sha256": marker["selection_sha256"],
            "checkpoint_sha256": selection["checkpoint_sha256"],
            "dataset_manifest_sha256": sha256(DATA / "manifest.json"),
            "split_sha256": manifest["sha256"][f"{split}.parquet"],
            "evaluator_sha256": sha256(Path(__file__)),
            "feature_contract": feature_info["feature_contract"],
            "people": len({str(g["pid"]) for g in groups}),
            "rows": len(groups),
            "alerts": alert_metrics,
            "eight_hour_rule": fixed_timer_metrics(views),
            "shipped_cnn_baseline": cnn_baseline,
            "cold_start_unwell_first_week_stress": first_week_stress,
            "cold_start_curve": cold_start,
            "next_use_raw": raw_forecast,
            "next_use_blended": blended_forecast,
            "forecast_calibration": {"model_weight": model_weight,
                                     "profile_weight": profile_weight,
                                     "selection_metric": "train OOF next-bin log-loss only"},
            "on_device": on_device,
            "confirmation_only": split == "val",
            "test_opened": split == "test",
        }
        destination = folder / f"{split}-results.json"
        temporary = destination.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, destination)
        if split == "val":
            # The legacy decision gate checks this canonical name before test.
            validation_path = folder / "validation-results.json"
            tmp_validation = validation_path.with_suffix(".json.tmp")
            tmp_validation.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            os.replace(tmp_validation, validation_path)
        write_saved_report(split, model_name)
        trial("heldout-evaluation", f"{model_name}-{split}", "COMPLETE",
              json.dumps({"result": str(destination), "people": result["people"],
                          "rows": result["rows"], "C4": alert_metrics["C4"],
                          "stale_as_fresh": alert_metrics["stale_as_fresh"],
                          "selection_sha256": marker["selection_sha256"]}))
        progress(f"One-shot {split} confirmation saved for locked {model_name}; no tuning/refit; C4={alert_metrics['C4']}.")
        return result
    except Exception as error:
        trial("heldout-evaluation", f"{model_name}-{split}", "FAIL",
              f"Access marker retained; do not reopen this split. {error!r}")
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", required=True, choices=["val", "test"])
    parser.add_argument("--model", default=MODEL_NAME)
    parser.add_argument("--render-saved", action="store_true",
                         help="Render an already saved result as Markdown without opening a dataset split")
    args = parser.parse_args()
    if args.render_saved:
        print(write_saved_report(args.split, args.model))
        return
    result = evaluate(args.split, args.model)
    print(json.dumps({"split": result["split"], "people": result["people"],
                      "alerts": result["alerts"]["model"],
                      "C4": result["alerts"]["C4"],
                      "next_use_blended": result["next_use_blended"]["model"]}, indent=2))


if __name__ == "__main__":
    main()

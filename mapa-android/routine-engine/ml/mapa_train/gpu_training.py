"""GPU-resident causal training from the precomputed final-v2 train bundle."""
from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

from mapa_release.common import ROOT, trial
from .models import Hybrid, HybridNoAttention, Rnn, PlainCnn, RebuildLSTM
from .protocol import SEEDS
from .zoo import BEHAVIOUR_TARGET, POS_WEIGHT, STAGE_EPOCHS


ARRAY_FILE = ROOT / "precomputed" / "final-v2-train-arrays-v2.npz"
NEURAL = {"hybrid": Hybrid, "hybrid-no-attention": HybridNoAttention,
          "plain-gru": Rnn, "plain-cnn": PlainCnn, "rebuild-lstm": RebuildLSTM}


def load_gpu_arrays(path: Path = ARRAY_FILE, device: str = "cuda"):
    if not torch.cuda.is_available():
        raise RuntimeError("GPU-resident training requires CUDA")
    names = {
        "x": torch.float32, "y": torch.float16, "mask": torch.uint8,
        "tag": torch.uint8, "quality": torch.uint8, "episode": torch.uint8,
        "slow_history": torch.float32, "slow_count": torch.uint8,
        "row_person_day": torch.int32, "hourly_profile": torch.float64,
        "history_summary": torch.float32, "quiet_context": torch.float32,
        "history_weight": torch.float64, "archetype_priors": torch.float32,
        "cluster_ids": torch.uint8, "fold_by_row": torch.int8,
        "fold_stage_indices": torch.int32, "fold_stage_counts": torch.int32,
        "fold_derived_original_count": torch.int32,
        "fold_stage_weights": torch.float32, "fold_stage_similarity": torch.float32,
    }
    tensors = {}
    with np.load(path, allow_pickle=False) as archive:
        metadata = json.loads(str(archive["metadata_json"]))
        groups = json.loads(str(archive["groups_json"]))
        for name, dtype in names.items():
            cpu = archive[name]
            tensors[name] = torch.as_tensor(cpu, dtype=dtype, device=device)
            del cpu
        reports = {
            "weights": json.loads(str(archive["fold_weight_reports_json"])),
            "day_swap": json.loads(str(archive["fold_day_swap_reports_json"])),
        }
    return tensors, metadata, groups, reports


def _mask_use_inputs(x: torch.Tensor, hidden: torch.Tensor) -> torch.Tensor:
    out = x.clone()
    out[..., :4] = torch.where(hidden[..., None], 0., out[..., :4])
    out[..., 14] = torch.where(hidden.any(dim=1)[:, None], 0., out[..., 14])
    affected = hidden.clone()
    affected[:, 1:] |= hidden[:, :-1]
    out[..., 20] = torch.where(affected, 0., out[..., 20])
    return out


def _cold_start_inputs(data, global_rows, prefix_counts, fold, seed_index, x):
    person_day = data["row_person_day"][global_rows].long()
    clusters = data["cluster_ids"][fold, seed_index, person_day, prefix_counts]
    prior = data["archetype_priors"][fold, seed_index, clusters.long()]
    hourly = data["hourly_profile"][person_day, prefix_counts]
    hourly = torch.where(torch.isfinite(hourly), hourly, prior[:, 0:1])
    padded = torch.cat((hourly, hourly[:, :8]), dim=1)
    windows = padded.unfold(1, 4, 1)
    one = torch.roll(hourly, shifts=-1, dims=1)
    four = windows[:, 1:97].mean(dim=-1)
    eight = windows[:, 5:101].mean(dim=-1)
    quiet = data["quiet_context"][person_day, prefix_counts]
    personal = torch.stack((one, four, eight, quiet[:, 0:1].expand(-1, 96),
                            quiet[:, 1:2].expand(-1, 96)), dim=-1).float()
    # NumPy's old path stored personal channels as float32 before blending;
    # cast the exact float64 shrinkage weight to float32 at that same point.
    weight = data["history_weight"][person_day, prefix_counts].float()
    context = personal * weight[:, None, None] + prior[:, None, :] * (1. - weight[:, None, None])
    out = x.clone()
    out[..., 11] = context[..., 0]
    out[..., 12] = context[..., 1]
    out[..., 13] = context[..., 2]
    out[..., 18] = context[..., 3]
    out[..., 19] = context[..., 4]
    out[..., 15] = weight[:, None]
    return out


def enabled_training_stages(stage_names):
    """Use the stages configured for the model; CNN ablations have two stages."""
    return list(stage_names)


def _predict_gpu(family, model, data, fold, seed_index, held_rows, batch_size=128):
    model.eval()
    behaviour, hazard, confidence = [], [], []
    with torch.no_grad():
        for start in range(0, len(held_rows), batch_size):
            rows = held_rows[start:start + batch_size]
            x = data["x"][rows].float()
            count = data["slow_count"][rows].long()
            if getattr(model, "cold_start_enabled", False):
                x = _cold_start_inputs(data, rows, count, fold, seed_index, x)
            if family.startswith("hybrid"):
                h = data["slow_history"][rows].float()
                with torch.autocast("cuda", dtype=torch.float16):
                    logits = model(x, h, count)
            else:
                with torch.autocast("cuda", dtype=torch.float16):
                    logits = model(x)
            if getattr(model, "behaviour_enabled", family == "rebuild-lstm"):
                behaviour.append(torch.sigmoid(logits[..., 17]).float().cpu().numpy())
            else:
                behaviour.append(torch.sigmoid(logits[..., 0]).float().cpu().numpy())
            confidence.append(torch.sigmoid(logits[..., 18]).float().cpu().numpy()
                              if family.startswith("hybrid") else np.zeros(logits.shape[:2], np.float32))
            hazard.append(torch.sigmoid(logits[..., :16]).float().cpu().numpy())
    return {"behaviour": np.concatenate(behaviour), "hazard": np.concatenate(hazard),
            "confidence": np.concatenate(confidence)}


def fit_one_gpu(data, metadata, groups, reports, family, fold, seed, ablation_row=None,
                batch_size=96, epochs=15):
    """Train one fit; all row selection and tensor indexing stay on the GPU."""
    if family not in NEURAL:
        raise ValueError(f"GPU neural trainer does not handle {family}")
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    torch.set_num_threads(2)
    try:
        torch.set_num_interop_threads(1)
    except RuntimeError:
        pass
    from .ablations import configuration
    cfg = configuration(ablation_row) if ablation_row is not None else {
        "data_tricks": True, "cold_start": True, "personal": True, "slow": True,
        "attention": family != "hybrid-no-attention"}
    actual_family = "plain-cnn" if ablation_row == 1 else family
    if actual_family.startswith("hybrid"):
        net = Hybrid(attention=cfg.get("attention", False), personal=cfg.get("personal", False),
                     slow=cfg.get("slow", False)).cuda()
    else:
        net = NEURAL[actual_family]().cuda()
    net.ablation_config = cfg
    combined = ((family.startswith("hybrid") or family == "rebuild-lstm") and
                (ablation_row is None or ablation_row >= 3))
    net.behaviour_enabled = combined
    net.cold_start_enabled = bool(cfg.get("cold_start", False) and isinstance(net, Hybrid))
    net_seed_index = list(SEEDS).index(seed)
    if net.cold_start_enabled:
        priors = data["archetype_priors"][fold, net_seed_index].float()
        net.cold_start_priors = {"population_prior": priors[0].detach().cpu().numpy(),
                                 "archetype_priors": priors[1:].detach().cpu().numpy(),
                                 "fit_pids": sorted({g["pid"] for g in groups
                                     if metadata["fold_assignment"][g["pid"]] != fold and
                                     g.get("source") == "derived"}),
                                 "pseudo_days": 7.0}
    opt = torch.optim.AdamW(net.parameters(), lr=1e-3, weight_decay=1e-4)
    scaler = torch.amp.GradScaler("cuda")
    stage_names = (("real_observed_pretrain", "derived_normal_rhythm", "behaviour_episode_finetune")
                   if combined else ("real_observed_pretrain", "derived_normal_rhythm"))
    stage_logs = []
    stage_rows = data["fold_stage_indices"][fold]
    stage_counts = data["fold_stage_counts"][fold]
    enabled_stages = enabled_training_stages(stage_names)
    stage_by_name = {name: i for i, name in enumerate(stage_names)}
    row_num = {"real_observed_pretrain": 0, "derived_normal_rhythm": 1,
               "behaviour_episode_finetune": 2}
    stage_lengths = [STAGE_EPOCHS[name] for name in stage_names]
    start_all = time.perf_counter()
    for stage_name, n_epochs in zip(enabled_stages, stage_lengths):
        stage = stage_by_name[stage_name]
        n_rows = int(stage_counts[stage].item())
        if stage == 1 and not cfg.get("data_tricks", False):
            n_rows = int(data["fold_derived_original_count"][fold].item())
        if n_rows == 0:
            continue
        rows = stage_rows[stage, :n_rows]
        base_weights = data["fold_stage_weights"][fold, stage]
        similarities = data["fold_stage_similarity"][fold, stage]
        for epoch in range(n_epochs):
            net.train()
            order = torch.randperm(len(rows), device="cuda")
            total = 0.0
            batches = 0
            for begin in range(0, len(order), batch_size):
                batch_rows = rows[order[begin:begin + batch_size]]
                x = data["x"][batch_rows].float()
                y = data["y"][batch_rows].float()
                m = data["mask"][batch_rows].float()
                if cfg.get("data_tricks", False):
                    sim = similarities[batch_rows].double() / .20
                    local = torch.softmax(sim, dim=0).float() * len(batch_rows)
                    sample_weight = base_weights[batch_rows] * local
                else:
                    sample_weight = torch.ones(len(batch_rows), device="cuda")
                hidden = torch.zeros(x.shape[:2], dtype=torch.bool, device="cuda")
                if stage != 2:
                    known = x[..., 3] > .5
                    hidden = (torch.rand(known.shape, device="cuda") < .15) & known
                    x = _mask_use_inputs(x, hidden)
                selected = data["slow_count"][batch_rows].long()
                history = None
                if isinstance(net, Hybrid) and cfg.get("slow", False):
                    if net.cold_start_enabled:
                        draw = torch.randint(0, 31, (len(batch_rows),), device="cuda")
                        selected = torch.minimum(selected, draw)
                        x = _cold_start_inputs(data, batch_rows, selected, fold, net_seed_index, x)
                    history = data["slow_history"][batch_rows].float()
                    slots = torch.arange(30, device="cuda")[None, :]
                    history = torch.where(slots[..., None] < selected[:, None, None], history,
                                          torch.zeros_like(history))
                opt.zero_grad(set_to_none=True)
                with torch.autocast("cuda", dtype=torch.float16):
                    logits = net(x, history, selected) if isinstance(net, Hybrid) else net(x)
                    if stage == 2:
                        values = nn.functional.binary_cross_entropy_with_logits(
                            logits[..., 17], y[..., BEHAVIOUR_TARGET], reduction="none",
                            pos_weight=torch.tensor(POS_WEIGHT, device="cuda"))
                        selected_mask = m[..., BEHAVIOUR_TARGET]
                        loss = (values * selected_mask * sample_weight[:, None]).sum() / (
                            selected_mask * sample_weight[:, None]).sum().clamp(min=1)
                    else:
                        values = nn.functional.binary_cross_entropy_with_logits(
                            logits[..., :16], y[..., :16], reduction="none")
                        hazard_mask = m[..., :16]
                        loss = (values * hazard_mask * sample_weight[:, None, None]).sum() / (
                            hazard_mask * sample_weight[:, None, None]).sum().clamp(min=1)
                        recon = nn.functional.binary_cross_entropy_with_logits(
                            logits[..., 16], data["x"][batch_rows, :, 0].float(), reduction="none")
                        recon_mask = hidden.float()
                        loss = loss + .25 * (recon * recon_mask * sample_weight[:, None]).sum() / (
                            recon_mask * sample_weight[:, None]).sum().clamp(min=1)
                        if isinstance(net, Hybrid) and net.cold_start_enabled:
                            confidence_target = selected.float() / 30.
                            confidence_logits = logits[..., 18].mean(dim=1)
                            loss = loss + .1 * nn.functional.mse_loss(
                                torch.sigmoid(confidence_logits), confidence_target)
                scaler.scale(loss).backward()
                scaler.unscale_(opt)
                nn.utils.clip_grad_norm_(net.parameters(), 1.0)
                scaler.step(opt)
                scaler.update()
                total += float(loss.detach()); batches += 1
            stage_logs.append({"stage": stage_name, "epoch": epoch,
                               "train_loss": total / max(1, batches)})
    net.eval()
    params = sum(p.numel() for p in net.parameters())
    fit_info = {
        "params": params, "stages": enabled_stages, "log": stage_logs,
        "stage_epochs": STAGE_EPOCHS, "ablation_config": cfg,
        "day_swap": reports["day_swap"][fold],
        "similarity_weights": reports["weights"][fold],
        "fit_elapsed_seconds": time.perf_counter() - start_all,
        "training_device": torch.cuda.get_device_name(0),
        "amp": "float16 autocast + GradScaler", "gpu_batch_size": batch_size,
        "dataloader_workers": 0,
    }
    real_rows = stage_rows[0, :int(stage_counts[0].item())]
    fit_info["stage1_real_observed_bins"] = int((data["x"][real_rows, :, 3] > .5).sum().item())
    fit_info["stage1_real_next_use_targets"] = int(data["mask"][real_rows, :, :16].sum().item())
    fit_info["real_days_passing_14h"] = int(data["quality"][real_rows].sum().item())
    fit_info["real_days_total"] = int(len(real_rows))
    if hasattr(net, "cold_start_priors"):
        fit_info["cold_start_priors"] = net.cold_start_priors
    return fit_info, net, actual_family


def predict_fold(family, model, data, fold, seed, held_rows):
    si = list(SEEDS).index(seed)
    actual = "plain-cnn" if family == "hybrid" and getattr(model, "ablation_config", {}).get("row") == 1 else family
    return _predict_gpu(actual, model, data, fold, si, held_rows)


def run_fold_gpu(family, version, run_tag, fold, seed_list, protocol_sha,
                 ablation_row=None, batch_size=96):
    """Process one frozen person fold; checkpoints are committed per seed."""
    from .checkpoints import resume_fit, digest
    from .protocol import DATA, sha256

    path = ARRAY_FILE
    data, metadata, groups, reports = load_gpu_arrays(path)
    if metadata["dataset_manifest_sha256"] != sha256(DATA / "manifest.json"):
        raise RuntimeError("GPU array bundle is bound to a different final-v2 manifest")
    if metadata.get("split") != "train" or metadata.get("partition_scope") != ["train"]:
        raise RuntimeError("GPU array bundle is not train-only")
    if metadata.get("training_protocol_sha256") != protocol_sha:
        raise RuntimeError("GPU array bundle and fit use different frozen protocols")
    fold_by_row = data["fold_by_row"].cpu().numpy()
    held_ix = np.flatnonzero(fold_by_row == fold)
    folder_name = (f"hybrid-ablation-{ablation_row}-{run_tag}" if ablation_row is not None
                   else f"zoo-{family}-{run_tag}")
    folder = ROOT / "models" / folder_name
    folder.mkdir(parents=True, exist_ok=True)
    for seed in seed_list:
        fit_folder = folder / f"fold{fold}-seed{seed}"
        resumed = resume_fit(fit_folder, protocol_sha, held_ix)
        if resumed is not None:
            trial("cv-fit", f"{folder_name}-fold{fold}-seed{seed}", "REUSED",
                  "Protocol, held indices and artifact hashes match")
            continue
        trial("cv-fit", f"{folder_name}-fold{fold}-seed{seed}", "STARTED",
              f"protocol={protocol_sha}; device=RTX3060; fold={fold}; train-only array bundle; validation/test unopened")
        try:
            fit_info, model, actual_family = fit_one_gpu(
                data, metadata, groups, reports, family, fold, seed, ablation_row,
                batch_size=batch_size, epochs=sum(STAGE_EPOCHS.values()))
            scores = _predict_gpu(actual_family, model, data, fold, list(SEEDS).index(seed), held_ix)
            fit_folder.mkdir(exist_ok=True)
            state = {k: v.detach().cpu() for k, v in model.state_dict().items()}
            torch.save({"state_dict": state, "family": actual_family,
                        "ablation_row": ablation_row,
                        "protocol_sha256": protocol_sha}, fit_folder / "model.pt")
            priors = fit_info.pop("cold_start_priors", None)
            if priors is not None:
                priors = {k: v.tolist() if hasattr(v, "tolist") else v for k, v in priors.items()}
                (fit_folder / "cold-start-priors.json").write_text(json.dumps(priors, indent=2))
            np.savez_compressed(fit_folder / "held-predictions.npz", indices=held_ix, **scores)
            fit_json = {"protocol_sha256": protocol_sha, "fold": fold, "seed": seed,
                        "family": family, "ablation_row": ablation_row,
                        "artifacts_sha256": {p.name: digest(p) for p in fit_folder.iterdir()
                            if p.name != "fit.json" and p.is_file()}, **fit_info}
            (fit_folder / "fit.json").write_text(json.dumps(fit_json, indent=2))
            trial("cv-fit", f"{folder_name}-fold{fold}-seed{seed}", "COMPLETE",
                  json.dumps(fit_json))
        except Exception as exc:
            trial("cv-fit", f"{folder_name}-fold{fold}-seed{seed}", "FAIL", repr(exc))
            raise
        finally:
            if "model" in locals():
                del model
            torch.cuda.empty_cache()
    return {"folder": folder_name, "fold": fold, "seeds": seed_list,
            "held_people": len({groups[i]["pid"] for i in held_ix})}

"""Train and compare causal MaPa-Rhythm models on frozen final-v2.

    python -m mapa_train.zoo --families hybrid,hybrid-no-attention,lightgbm,plain-gru,plain-cnn,rebuild-lstm

The approved hybrid and compact alternatives share one evaluator. The hybrid first learns normal patterns on
untouched days with masked-bin reconstruction; stage two fine-tunes an unusual-quiet head on behaviour
episodes and untouched negatives. Inputs at step t contain no later bins. Data are read from the frozen
final-v2 feature cache, with person-disjoint OOF folds on train only.

The normal-first sequence objective follows Gibietz et al. (2026) and Cejudo et al. (2025); sequence modelling
and routine-aware day-swap augmentation follow Shen et al. (2025), arXiv:2509.23158. Per-person thresholds
are evaluated in `mapa_train.decision` (Gibietz et al., 2026; Cejudo et al., 2025). Validation is reserved for
one confirmation after train-only selection; test is reserved for the final locked evaluation.
"""
from __future__ import annotations

import argparse
import json
import time

import numpy as np
import torch
from torch import nn

from mapa_release.common import ROOT, SEED, trial
from mapa_rhythm.cnn import CausalCnn
from mapa_rhythm.features import TRAIN_FEATURES
from .features20 import build as build_features
from .protocol import verify_data
from .cold_start import fit_priors, apply_priors, sample_history_prefix
from .masking import mask_use_inputs
from .augmentation import batch_softmax_weights, day_swap_indices, similarity_weights

BEHAVIOUR_TARGET = 16   # final target column: injected unusual-quiet window
POS_WEIGHT = 12.0       # same as the release CNNs
STAGE_EPOCHS = {"real_observed_pretrain": 3, "derived_normal_rhythm": 5,
                "behaviour_episode_finetune": 7}


def load(split: str, version: str):
    a, data_info = build_features(split, version, "final-v2")
    return (a["x"], a["y"], a["mask"], a["tag"], a["quality_day"], a["episode"],
            a["slow_history"], a["slow_count"], a.get("slow_quiet_quantile_table"),
            a.get("slow_quiet_quantile_index")), data_info


# ---- the one combined model: a 21-input causal trunk, 16-step hazard head and behaviour head -------

from .models import Hybrid, HybridNoAttention, Rnn, PlainCnn, RebuildLSTM


def real_only_weights(n_rows: int, groups: list[dict], rows: np.ndarray):
    """Equal person mass for the real-only first phase; reads no derived profiles."""
    counts = {}
    for i in rows:
        pid = str(groups[int(i)]["pid"])
        counts[pid] = counts.get(pid, 0) + 1
    weights = np.zeros(n_rows, dtype=np.float32)
    for i in rows:
        weights[int(i)] = 1.0 / max(1, len(counts) * counts[str(groups[int(i)]["pid"])])
    similarities = np.ones(n_rows, dtype=np.float32)
    return weights, similarities, {"method": "inverse-day-count within real observed train people",
                                  "fit_source": "real_observed only", "people": len(counts),
                                  "derived_rows_used_for_weights": False}


NEURAL = {"hybrid": Hybrid, "hybrid-no-attention": HybridNoAttention,
          "plain-gru": Rnn, "plain-cnn": PlainCnn,
          "rebuild-lstm": RebuildLSTM}


def train_neural(family, tr, epochs, device, seed=SEED, batch=128, lr=1e-3, ablation_row=None):
    torch.manual_seed(seed)
    from .ablations import configuration
    cfg = configuration(ablation_row) if ablation_row is not None else {
        "data_tricks": True, "cold_start": True, "personal": True, "slow": True,
        "attention": family != "hybrid-no-attention"}
    if ablation_row == 1:
        family = "plain-cnn"
    if family.startswith("hybrid"):
        net = Hybrid(attention=cfg.get("attention", False), personal=cfg.get("personal", False),
                     slow=cfg.get("slow", False)).to(device)
    else:
        net = NEURAL[family]().to(device)
    net.ablation_config = cfg
    net.cold_start_enabled = bool(cfg.get("cold_start", False) and isinstance(net, Hybrid))
    opt = torch.optim.AdamW(net.parameters(), lr=lr, weight_decay=1e-4)
    xtr, ytr, mtr, tag, quality, episode, slowtr, slow_count, groups = tr[:9]
    quiet_table = tr[9] if len(tr) > 9 else None
    quiet_row_index = tr[10] if len(tr) > 10 else None
    is_real = np.asarray([g.get("source") != "derived" for g in groups], dtype=bool)
    normal_mask = ~np.asarray(episode, dtype=bool) & ~np.asarray(tag, dtype=bool)
    normal_rows = np.flatnonzero(normal_mask)
    real_rows = normal_rows[is_real[normal_rows]]
    derived_rows = normal_rows[~is_real[normal_rows]]
    behaviour_rows = np.flatnonzero(~np.asarray(tag, dtype=bool) & (~is_real | np.asarray(quality, dtype=bool)))
    augmentation_rows, day_swap_report = day_swap_indices(xtr, groups, derived_rows)
    if not cfg.get("data_tricks", False):
        augmentation_rows = np.zeros(0, dtype=np.int64)
        day_swap_report = {"enabled": False, "copies": 0}
    derived_train_rows = np.concatenate([derived_rows, augmentation_rows])
    rng = np.random.default_rng(seed)
    log = []
    if sum(STAGE_EPOCHS.values()) != epochs:
        raise ValueError(f"epochs={epochs} must match frozen stage allocation {STAGE_EPOCHS}")
    if not len(real_rows) or not len(derived_train_rows):
        raise RuntimeError(f"Need real and derived normal days in each fit; got real={len(real_rows)} derived={len(derived_rows)}")
    combined = (family.startswith("hybrid") or family == "rebuild-lstm") and (ablation_row is None or ablation_row >= 3)
    uses_slow = family.startswith("hybrid")
    net.behaviour_enabled = combined
    behavior_index = 17
    confidence_index = 18
    stages = [("real_observed_pretrain", real_rows, STAGE_EPOCHS["real_observed_pretrain"]),
              ("derived_normal_rhythm", derived_train_rows, STAGE_EPOCHS["derived_normal_rhythm"])]
    if combined:
        stages.append(("behaviour_episode_finetune", behaviour_rows,
                       STAGE_EPOCHS["behaviour_episode_finetune"]))
    weight_source_rows = np.concatenate([real_rows, derived_train_rows])
    if net.cold_start_enabled:
        summaries = slowtr[np.arange(len(slowtr)), np.maximum(slow_count - 1, 0), :9]
        net.cold_start_priors = fit_priors(xtr, summaries, groups,
                                          normal_rows[slow_count[normal_rows] > 0], seed)
    weight_reports = {}
    for stage, rows, n_epochs in stages:
        if not cfg.get("data_tricks", False):
            person_weights = np.ones(len(xtr), dtype=np.float32)
            daily_similarity = np.ones(len(xtr), dtype=np.float32)
            weight_report = {"enabled": False, "method": "uniform"}
        elif stage == "real_observed_pretrain":
            person_weights, daily_similarity, weight_report = real_only_weights(len(xtr), groups, rows)
        else:
            weight_rows = behaviour_rows if stage == "behaviour_episode_finetune" else weight_source_rows
            person_weights, daily_similarity, weight_report = similarity_weights(xtr, groups, weight_rows)
        weight_reports[stage] = weight_report
        for ep in range(n_epochs):
            net.train()
            order = rng.permutation(rows)
            epoch_loss = 0.0
            epoch_batches = 0
            for s in range(0, len(order), batch):
                idx = order[s:s + batch]
                x_np = xtr[idx].copy()
                y_np = ytr[idx].astype(np.float32)
                m_np = mtr[idx].copy()
                hidden = np.zeros((len(idx), x_np.shape[1]), dtype=bool)
                if stage != "behaviour_episode_finetune":
                    known = x_np[:, :, 3] > .5
                    hidden = (rng.random(known.shape) < .15) & known
                    x_np = mask_use_inputs(x_np, hidden)
                x = torch.from_numpy(x_np).to(device)
                y = torch.from_numpy(y_np).to(device)
                m = torch.from_numpy(m_np.astype(np.float32)).to(device)
                sample_weight_np = batch_softmax_weights(person_weights[idx], daily_similarity[idx])
                sample_weight = torch.from_numpy(sample_weight_np).to(device)
                if uses_slow:
                    histories, selected_counts = (sample_history_prefix(slowtr[idx], slow_count[idx], rng)
                                                  if net.cold_start_enabled else (slowtr[idx], slow_count[idx]))
                    h = torch.from_numpy(histories).to(device)
                    hc = torch.from_numpy(selected_counts).to(device)
                    if net.cold_start_enabled:
                        x_np = apply_priors(x_np, histories, selected_counts, net.cold_start_priors,
                                            quiet_quantiles=(quiet_table[quiet_row_index[idx]]
                                                if quiet_table is not None else None))
                    x = torch.from_numpy(x_np).to(device)
                else:
                    h = hc = None
                logits = net(x, h, hc) if uses_slow else net(x)
                if stage == "behaviour_episode_finetune":
                    loss_values = nn.functional.binary_cross_entropy_with_logits(
                        logits[..., behavior_index], y[..., BEHAVIOUR_TARGET], reduction="none",
                        pos_weight=torch.tensor(POS_WEIGHT, device=device))
                    selected = m[..., BEHAVIOUR_TARGET]
                    weights = selected * sample_weight[:, None]
                    loss = (loss_values * weights).sum() / weights.sum().clamp(min=1)
                else:
                    hazard_loss = nn.functional.binary_cross_entropy_with_logits(
                        logits[..., :16], y[..., :16], reduction="none")
                    hazard_mask = m[..., :16]
                    hazard_weights = hazard_mask * sample_weight[:, None, None]
                    loss = (hazard_loss * hazard_weights).sum() / hazard_weights.sum().clamp(min=1)
                    recon = nn.functional.binary_cross_entropy_with_logits(
                        logits[..., 16], torch.from_numpy(xtr[idx, :, 0].copy()).to(device), reduction="none")
                    recon_mask = torch.from_numpy(hidden.astype(np.float32)).to(device)
                    recon_weights = recon_mask * sample_weight[:, None]
                    loss = loss + .25 * (recon * recon_weights).sum() / recon_weights.sum().clamp(min=1)
                    if uses_slow:
                        confidence_target = torch.from_numpy(selected_counts.astype(np.float32) / 30.).to(device)
                        confidence_logits = logits[..., confidence_index].mean(dim=1)
                        loss = loss + .1 * nn.functional.mse_loss(torch.sigmoid(confidence_logits), confidence_target)
                opt.zero_grad(); loss.backward()
                nn.utils.clip_grad_norm_(net.parameters(), 1.0)
                opt.step()
                epoch_loss += float(loss.detach()); epoch_batches += 1
            log.append({"stage": stage, "epoch": ep,
                        "train_loss": epoch_loss / max(1, epoch_batches)})
    net.eval()
    cpu = net.to("cpu")
    x1 = torch.from_numpy(xtr[:1])
    h1 = torch.from_numpy(slowtr[:1]) if uses_slow else None
    hc1 = torch.from_numpy(slow_count[:1]) if uses_slow else None
    with torch.no_grad():
        for _ in range(3):
            cpu(x1, h1, hc1) if uses_slow else cpu(x1)
        t0 = time.perf_counter()
        for _ in range(20):
            cpu(x1, h1, hc1) if uses_slow else cpu(x1)
    day_ms = (time.perf_counter() - t0) / 20 * 1000
    net.to(device)
    params = sum(p.numel() for p in net.parameters())
    if params > 50_000:
        raise RuntimeError(f"Phone parameter budget exceeded: {params} > 50000")
    return {"params": params, "cpu_ms_per_day_forward": day_ms, "log": log,
            "stages": [stage for stage, _, _ in stages], "model": family,
            "attention": bool(getattr(net, "attention_enabled", False)),
            "stage1_real_observed_bins": int((xtr[real_rows, :, 3] > .5).sum()),
            "stage1_real_next_use_targets": int(mtr[real_rows, :, 0].sum()),
            "real_days_passing_14h": int(np.sum(quality[real_rows])),
            "real_days_total": int(len(real_rows)),
            "ablation_config": cfg, "stage_epochs": STAGE_EPOCHS, "day_swap": day_swap_report,
            "similarity_weights": weight_reports}, net


# ---- gradient-boosted trees: causal lag features per step ----------------------------------------------

def lag_features(x):
    """Per step: current 18 inputs, the first 4 inputs 1, 4 and 8 steps back, and the step of the day."""
    n, t, f = x.shape
    feats = [x]
    for lag in (1, 4, 8):
        sh = np.zeros_like(x[:, :, :4]); sh[:, lag:] = x[:, :-lag, :4]
        feats.append(sh)
    feats.append(np.broadcast_to(np.arange(t, dtype=np.float32)[None, :, None] / t, (n, t, 1)))
    return np.concatenate(feats, -1)


def train_lgbm(tr, seed=SEED):
    import lightgbm as lgb
    xtr, ytr, mtr, tag, quality, episode, slowtr, slow_count, groups = tr[:9]
    normal_rows = ~np.asarray(episode, dtype=bool) & ~np.asarray(tag, dtype=bool)
    is_real = np.asarray([g.get("source") != "derived" for g in groups], dtype=bool)
    real_rows = np.flatnonzero(normal_rows & is_real)
    derived_rows = np.flatnonzero(normal_rows & ~is_real)
    augmentation_rows, day_swap_report = day_swap_indices(xtr, groups, derived_rows)
    derived_train_rows = np.concatenate([derived_rows, augmentation_rows])
    known = np.asarray(mtr[:, :, 0], dtype=bool) & normal_rows[:, None]
    hidden = (np.random.default_rng(seed).random(known.shape) < .15) & known
    masked_x = mask_use_inputs(xtr, hidden)
    normal_feats = lag_features(masked_x)
    model = None
    weight_reports = {}
    weight_source_rows = np.concatenate([real_rows, derived_train_rows])
    for source_stage, rows in (("real_observed_pretrain", real_rows),
                               ("derived_normal_rhythm", derived_train_rows)):
        if not len(rows):
            raise RuntimeError(f"No rows for LightGBM {source_stage}")
        if source_stage == "real_observed_pretrain":
            person_weights, daily_similarity, report = real_only_weights(len(xtr), groups, rows)
        else:
            person_weights, daily_similarity, report = similarity_weights(xtr, groups, weight_source_rows)
        weight_reports[source_stage] = report
        keep = known[rows] & hidden[rows]
        ftr = normal_feats[rows][keep]
        batch_weights = batch_softmax_weights(person_weights[rows], daily_similarity[rows])
        bin_weights = np.broadcast_to(batch_weights[:, None], keep.shape)[keep]
        next_model = lgb.LGBMClassifier(n_estimators=STAGE_EPOCHS[source_stage] * 80,
                                        learning_rate=0.05, num_leaves=31, min_child_samples=50,
                                        subsample=0.8, subsample_freq=1, colsample_bytree=0.8,
                                        random_state=seed, verbose=-1)
        next_model.fit(ftr, ytr[rows, :, 0][keep], sample_weight=bin_weights,
                       init_model=model.booster_ if model is not None else None)
        model = next_model
    fday = lag_features(xtr[:1])
    t0 = time.perf_counter()
    for _ in range(20):
        model.predict_proba(fday[0])
    day_ms = (time.perf_counter() - t0) / 20 * 1000
    info = {"trees": int(model.booster_.num_trees()),
            "cpu_ms_per_day_forward": day_ms, "params": None,
            "stages": ["real_observed_next_bin_alternative", "derived_next_bin_alternative"],
            "stage_epochs": STAGE_EPOCHS,
            "day_swap": day_swap_report, "similarity_weights": weight_reports}
    return info, model


def predict(family, model, x, device, slow_history=None, slow_count=None):
    if family == "lightgbm":
        f = lag_features(x)
        flat=f.reshape(-1, f.shape[-1])
        h = model.predict_proba(flat)[:, 1].reshape(x.shape[:2])
        return {"behaviour": np.zeros_like(h), "hazard": h[..., None], "confidence": np.zeros_like(h)}
    model.eval()
    behaviour, hazard, confidence = [], [], []
    with torch.no_grad():
        for start in range(0, len(x), 256):
            batch_x = x[start:start + 256]
            if getattr(model, "cold_start_enabled", False):
                batch_x = apply_priors(batch_x, slow_history[start:start + 256],
                                       slow_count[start:start + 256], model.cold_start_priors)
            z = torch.from_numpy(batch_x).to(device)
            if family.startswith("hybrid"):
                h = torch.from_numpy(slow_history[start:start + 256]).to(device)
                hc = torch.from_numpy(slow_count[start:start + 256]).to(device)
                logits=model(z, h, hc)
            else:
                logits=model(z)
            if getattr(model, "behaviour_enabled", family == "rebuild-lstm"):
                behaviour.append(torch.sigmoid(logits[..., 17]).cpu().numpy())
            else:
                behaviour.append(torch.sigmoid(logits[..., 0]).cpu().numpy())
            if family.startswith("hybrid"):
                confidence.append(torch.sigmoid(logits[..., 18]).cpu().numpy())
            else:
                confidence.append(np.zeros(logits.shape[:2], dtype=np.float32))
            hazard.append(torch.sigmoid(logits[..., :16]).cpu().numpy())
    return {"behaviour": np.concatenate(behaviour), "hazard": np.concatenate(hazard), "confidence": np.concatenate(confidence)}


def train_cv(families, version, epochs, run_tag="pilot", ablation_row=None,
             seed_list=None, fold_list=None, finalize=True, protocol_version=9):
    """Person-disjoint OOF scores. No validation or test data are opened."""
    unsupported = set(families) - (set(NEURAL) | {"lightgbm"})
    if unsupported:
        raise ValueError(f"Models outside the approved hybrid scope: {sorted(unsupported)}")
    from .protocol import freeze, SEEDS
    protocol = freeze(protocol_version)
    active_protocol_path = ROOT / f"training-protocol-v{protocol_version}.json"
    if not active_protocol_path.exists():
        raise RuntimeError(f"Freeze training-protocol-v{protocol_version}.json before any compliant CV run")
    active_protocol = json.loads(active_protocol_path.read_text())
    if active_protocol.get("dataset_manifest_sha256") != protocol["dataset_manifest_sha256"]:
        raise RuntimeError("Active training protocol is not bound to this final-v2 manifest")
    if active_protocol.get("fold_assignment") != protocol["fold_assignment"] or active_protocol.get("seeds") != SEEDS:
        raise RuntimeError("Active protocol folds/seeds differ from the pre-frozen assignment")
    if active_protocol.get("stage_epochs") != STAGE_EPOCHS:
        raise RuntimeError("Active protocol does not freeze this three-stage epoch allocation")
    active_protocol_sha = __import__("hashlib").sha256(active_protocol_path.read_bytes()).hexdigest()
    run_seeds = list(SEEDS if seed_list is None else seed_list)
    run_folds = list(range(5) if fold_list is None else fold_list)
    if not run_seeds or not set(run_seeds).issubset(SEEDS):
        raise ValueError("Run seeds must be a non-empty subset of the frozen seed list")
    if not run_folds or not set(run_folds).issubset(range(5)):
        raise ValueError("Run folds must be a non-empty subset of the five frozen person folds")
    if finalize and set(run_folds) != set(range(5)):
        raise ValueError("OOF finalization requires all five person folds")
    if epochs != sum(STAGE_EPOCHS.values()):
        raise RuntimeError("Requested epoch count differs from active three-stage protocol")
    from .gpu_training import ARRAY_FILE
    with np.load(ARRAY_FILE, allow_pickle=False) as arrays:
        packed_meta = json.loads(str(arrays["metadata_json"]))
        x, y, mask = arrays["x"], arrays["y"], arrays["mask"]
        tag, quality, episode = arrays["tag"], arrays["quality"], arrays["episode"]
        slow_history, slow_count = arrays["slow_history"], arrays["slow_count"]
        groups = json.loads(str(arrays["groups_json"]))
    info = {"dataset_manifest_sha256": packed_meta["dataset_manifest_sha256"],
            "feature_contract": TRAIN_FEATURES}
    fold_map = protocol["fold_assignment"]
    folds = np.array([fold_map[g["pid"]] for g in groups])
    device = "cuda" if torch.cuda.is_available() else "cpu"
    summary = {}
    for family in families:
        name = f"hybrid-ablation-{ablation_row}-{run_tag}" if ablation_row is not None else f"zoo-{family}-{run_tag}"
        folder = ROOT / "models" / name
        folder.mkdir(parents=True, exist_ok=True)
        trial_file = folder / "cv-scores.npy"
        all_scores = []
        run_logs = []
        for fold in run_folds:
            train_ix = np.flatnonzero(folds != fold)
            held_ix = np.flatnonzero(folds == fold)
            for seed in run_seeds:
                from .checkpoints import resume_fit, digest
                fit_folder = folder / f"fold{fold}-seed{seed}"
                resumed = resume_fit(fit_folder, active_protocol_sha, held_ix)
                if resumed is not None:
                    saved_info, saved_scores = resumed
                    all_scores.append((held_ix, saved_scores))
                    run_logs.append(saved_info)
                    trial("cv-fit", f"{name}-fold{fold}-seed{seed}", "REUSED", "Protocol, held indices and artifact hashes match")
                    continue
                if family != "lightgbm":
                    raise RuntimeError(f"GPU fit artifact missing for {name}-fold{fold}-seed{seed}; refusing CPU fallback")
                # A seed and fold are a distinct, logged fit.
                trial("cv-fit", f"{name}-fold{fold}-seed{seed}", "STARTED",
                      f"protocol={active_protocol_sha}; train people={len(set(groups[i]['pid'] for i in train_ix))}; held people={len(set(groups[i]['pid'] for i in held_ix))}; stages={STAGE_EPOCHS}; test and validation unopened")
                tr = (x[train_ix], y[train_ix], mask[train_ix], tag[train_ix], quality[train_ix],
                      episode[train_ix], slow_history[train_ix], slow_count[train_ix],
                      [groups[i] for i in train_ix])
                try:
                    verify_data()
                    if family == "lightgbm":
                        fit_info, model = train_lgbm(tr, seed)
                    else:
                        fit_info, model = train_neural(family, tr, epochs, device, seed=seed, ablation_row=ablation_row)
                    scores = predict(family, model, x[held_ix], device,
                                     slow_history[held_ix], slow_count[held_ix])
                except Exception as e:
                    trial("cv-fit", f"{name}-fold{fold}-seed{seed}", "FAIL", repr(e))
                    raise
                all_scores.append((held_ix, scores))
                fit_folder = folder / f"fold{fold}-seed{seed}"
                fit_folder.mkdir(exist_ok=True)
                if family == "lightgbm":
                    model.booster_.save_model(str(fit_folder / "model.txt"))
                else:
                    torch.save({"state_dict": model.state_dict(), "family": family,
                                "ablation_row": ablation_row,
                                "protocol_sha256": active_protocol_sha}, fit_folder / "model.pt")
                    if hasattr(model, "cold_start_priors"):
                        priors = {key: value.tolist() if isinstance(value, np.ndarray) else value
                                  for key, value in model.cold_start_priors.items()}
                        (fit_folder / "cold-start-priors.json").write_text(json.dumps(priors, indent=2))
                np.savez_compressed(fit_folder / "held-predictions.npz", indices=held_ix, **scores)
                (fit_folder / "fit.json").write_text(json.dumps({
                    "protocol_sha256": active_protocol_sha, "fold": fold, "seed": seed,
                    "family": family, "ablation_row": ablation_row,
                    "artifacts_sha256": {p.name: digest(p) for p in fit_folder.iterdir()
                                         if p.name != "fit.json" and p.is_file()}, **fit_info}, indent=2))
                run_logs.append({"fold": fold, "seed": seed, **fit_info})
                trial("cv-fit", f"{name}-fold{fold}-seed{seed}", "COMPLETE", json.dumps(fit_info))
                del model
                if torch.cuda.is_available(): torch.cuda.empty_cache()
        if not finalize:
            return {family: {"folds_completed": run_folds, "seeds": run_seeds,
                             "fit_runs": len(run_folds) * len(run_seeds),
                             "finalized": False}}
        # Average probabilities across the five fixed seeds within each held fold.
        oof = {"behaviour": np.full((len(x), 96), np.nan, dtype=np.float32),
               "hazard": np.full((len(x), 96, 16), np.nan, dtype=np.float32),
               "confidence": np.full((len(x), 96), np.nan, dtype=np.float32)}
        for fold in range(5):
            predictions = [s for ix, s in all_scores if folds[ix[0]] == fold]
            ix = np.flatnonzero(folds == fold)
            for kind in oof:
                values = [s[kind] for s in predictions]
                if kind == "hazard":
                    values = [np.repeat(v, 16, axis=-1) if v.shape[-1] == 1 else v for v in values]
                oof[kind][ix] = np.mean(values, axis=0)
        if not all(np.isfinite(v).all() for v in oof.values()):
            raise RuntimeError("Missing out-of-fold predictions")
        np.save(folder / "train-scores.npy", oof["behaviour"])
        np.save(folder / "train-next-use.npy", oof["hazard"])
        np.save(folder / "train-confidence.npy", oof["confidence"])
        (folder / "complete.json").write_text(json.dumps({
            "name": name, "family": family, "data_version": version,
            "dataset_version": "final-v2", "feature_contract": TRAIN_FEATURES,
            "train_people": 162, "cv_folds": 5, "seeds": run_seeds,
            "cross_validation": "person-grouped OOF; model scores averaged over fixed seeds within each held fold",
            "training_days": len(x), "test_read": False, "validation_read": False,
            "ablation_row": ablation_row,
            f"training_protocol_v{protocol_version}_sha256": active_protocol_sha,
            "training_stages": STAGE_EPOCHS,
            "manifest_sha256": info["dataset_manifest_sha256"], "runs": run_logs}, indent=2))
        from .decision import day_views, personal_history, stats_limits, score_config, grid, cold_start_curve, next_use_report
        normal_report = next_use_report(groups, y, mask, oof["hazard"], x[:, :, 11],
                                        np.array([g.get("source") != "derived" for g in groups]), episode)
        (folder / "next-use-train-real.json").write_text(json.dumps(normal_report, indent=2))
        views = day_views("train", name)
        from .ablations import decision_grid
        if ablation_row is not None:
            configs = list(decision_grid(ablation_row))
        elif family.startswith("hybrid"):
            configs = list(decision_grid(10))
        else:
            configs = list(grid())
        fold_results = []
        for fold in range(5):
            held = [v for v in views if fold_map[v["pid"]] == fold]
            histories = {kind: personal_history(held, kind) for kind in ("behaviour", "surprise", "combined")}
            stats_limits(held)
            fold_results.append([score_config(held, histories[cfg["score_kind"]], cfg) for cfg in configs])
        means = []
        for ci, cfg in enumerate(configs):
            fold_rows = [rows[ci] for rows in fold_results]
            baselines = {b: {"fa_per_week": float(np.mean([r[b]["fa_per_week"] for r in fold_rows])),
                             "catch": {s: float(np.mean([r[b]["catch"][s] for r in fold_rows])) for s in ("mild", "clear", "severe")}}
                         for b in ("4h_rule", "statistics_only")}
            model_mean = {"fa_per_week": float(np.mean([r["model"]["fa_per_week"] for r in fold_rows])),
                          "catch": {s: float(np.mean([r["model"]["catch"][s] for r in fold_rows])) for s in ("mild", "clear", "severe")}}
            c4_mean = all(model_mean["fa_per_week"] < baselines[b]["fa_per_week"] and
                          all(model_mean["catch"][s] >= baselines[b]["catch"][s] for s in ("mild", "clear", "severe"))
                          for b in baselines)
            minimum_mean = (model_mean["fa_per_week"] < baselines["statistics_only"]["fa_per_week"] and
                            model_mean["catch"]["severe"] == 1.0 and
                            all(model_mean["catch"][s] >= baselines["statistics_only"]["catch"][s]
                                for s in ("mild", "clear", "severe")) and
                            all(r["stale_as_fresh"] == 0 for r in fold_rows))
            means.append({"config": cfg, "model": model_mean, "baselines": baselines,
                          "minimum_mean": minimum_mean, "C4_mean": c4_mean, "folds": fold_rows})
        passing = [r for r in means if r["C4_mean"] and r["minimum_mean"]]
        if not passing:
            passing = [r for r in means if r["minimum_mean"]]
        chosen = min(passing, key=lambda r: r["model"]["fa_per_week"]) if passing else max(
            means, key=lambda r: min(r["model"]["catch"][s] - max(r["baselines"][b]["catch"][s] for b in r["baselines"])
                                     for s in ("mild", "clear", "severe")))
        all_hist = personal_history(views, chosen["config"]["score_kind"])
        curve = cold_start_curve(views, all_hist, chosen["config"])
        (folder / "cold-start-curve-train.json").write_text(json.dumps(curve, indent=2))
        report = {"family": family, "oof_people": protocol["fold_people"], "seeds": run_seeds,
                  "settings_evaluated": len(configs), "selected_on_train_oof_only": chosen,
                  "all_settings": means, "validation_opened": False, "test_opened": False}
        (folder / "cv-decision-results.json").write_text(json.dumps(report, indent=2))
        summary[family] = {"fold_people": protocol["fold_people"], "fit_runs": len(run_folds) * len(run_seeds),
                           "oof_scores": str(folder / "train-scores.npy"), "selected_config": chosen["config"],
                           "mean_C4": chosen["C4_mean"], "mean_metrics": chosen["model"], "failure": None}
        summary[family]["minimum_pass"] = chosen["minimum_mean"]
        trial("cv-family", name, "OOF_COMPLETE", f"{len(run_folds) * len(run_seeds)} person-held-out fits; aggregate decision evaluation next")
    (ROOT / "models" / "cv-family-summary.json").write_text(json.dumps(summary, indent=2))
    return summary


def run(families, version, epochs, run_tag="pilot", protocol_version=9):
    verify_data()
    # Isolate family failures so one unavailable package or model bug does not
    # suppress the remaining planned runs.  Each fold fit and each family
    # outcome gets an explicit trials.csv record.
    results = {}
    for family in families:
        try:
            results.update(train_cv([family], version, epochs, run_tag,
                                    protocol_version=protocol_version))
        except Exception as exc:
            detail = f"{type(exc).__name__}: {exc}"
            trial("cv-family", f"zoo-{family}-{run_tag}", "FAIL", detail)
            results[family] = {"failure": detail}
    (ROOT / "models" / "cv-family-summary.json").write_text(json.dumps(results, indent=2))
    return results


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--families", default="hybrid,lightgbm,plain-gru,plain-cnn,rebuild-lstm")
    ap.add_argument("--version", default="causal-v3")
    ap.add_argument("--epochs", type=int, default=15)
    ap.add_argument("--run-tag", default="pilot")
    ap.add_argument("--protocol-version", type=int, default=9)
    a = ap.parse_args()
    run(a.families.split(","), a.version, a.epochs, a.run_tag, a.protocol_version)

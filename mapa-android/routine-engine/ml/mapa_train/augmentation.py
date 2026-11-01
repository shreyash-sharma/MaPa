"""Train-only routine-aware day swaps and similarity weights.

P2 follows Shen et al. (2025), arXiv:2509.23158. P3 uses only source labels and
daily-use profiles. The older-adult reference is a pooled calibration of
derived parents, not an age label assigned to any person; see
`routine-engine/CALIBRATION.md` (Shen et al., 2026; Gordon et al., 2019).
No detector score, validation row, test row, or real_check person is read here.
"""
from __future__ import annotations

from collections import defaultdict

import numpy as np


def daily_profiles(x: np.ndarray) -> np.ndarray:
    """24 hourly active-use rates, with unknown bins excluded."""
    active = np.asarray(x[:, :, 0], dtype=np.float64)
    known = np.asarray(x[:, :, 3] > .5)
    rates = np.zeros((len(x), 24), dtype=np.float64)
    for h in range(24):
        sl = slice(h * 4, (h + 1) * 4)
        den = known[:, sl].sum(axis=1)
        rates[:, h] = np.divide((active[:, sl] * known[:, sl]).sum(axis=1), den,
                                out=np.zeros(len(x), dtype=np.float64), where=den > 0)
    return rates


def day_swap_indices(x: np.ndarray, groups: list[dict], rows: np.ndarray,
                     nearest: int = 5, quantile: float = .10) -> tuple[np.ndarray, dict]:
    """Return five nearest same-person training-day copies below the P10 distance.

    Distances use standardized 24-hour active-use profiles, with a per-person
    scale. People with too few qualifying days to supply five strict P10
    neighbours get no copies; the count is reported rather than relaxing the
    threshold.
    """
    rows = np.asarray(rows, dtype=np.int64)
    profiles = daily_profiles(x)
    by_person: dict[str, list[int]] = defaultdict(list)
    for i in rows:
        if not groups[i].get("episode_id"):
            by_person[str(groups[i]["pid"])].append(int(i))
    copies: list[int] = []
    eligible_people = 0
    for pid, ix in by_person.items():
        if len(ix) <= nearest + 1:
            continue
        z = profiles[ix]
        center = np.nanmean(z, axis=0)
        scale = np.nanstd(z, axis=0)
        scale[scale < 1e-6] = 1.0
        z = (z - center) / scale
        delta = z[:, None, :] - z[None, :, :]
        dist = np.sqrt(np.mean(delta * delta, axis=2))
        np.fill_diagonal(dist, np.inf)
        all_dist = dist[np.isfinite(dist)]
        if not len(all_dist):
            continue
        limit = float(np.quantile(all_dist, quantile))
        added = 0
        for j, source in enumerate(ix):
            order = np.argsort(dist[j], kind="stable")
            close = [int(ix[k]) for k in order if dist[j, k] < limit][:nearest]
            if len(close) == nearest:
                copies.extend(close)
                added += 1
        eligible_people += int(added > 0)
    report = {"method": "same-person standardized 24-hour active-rate Euclidean distance",
              "nearest": nearest, "strict_distance_quantile": quantile,
              "source_days": int(len(rows)), "synthetic_copies": len(copies),
              "people_with_copies": eligible_people,
              "people_without_five_strict_neighbours": len(by_person) - eligible_people,
              "fit_split_only": "train"}
    return np.asarray(copies, dtype=np.int64), report


def similarity_weights(x: np.ndarray, groups: list[dict], rows: np.ndarray,
                       source_mass: dict[str, float] | None = None,
                       temperature: float = .20) -> tuple[np.ndarray, np.ndarray, dict]:
    """Person and batch softmax weights from source and use-profile similarity.

    Source mass is split equally by default. The derived source is the only
    source with the published over-60 aggregate calibration. No per-person age
    is inferred. Within each source, closer person profiles receive a softmax
    weight; day examples share their person's mass.
    """
    rows = np.asarray(rows, dtype=np.int64)
    if source_mass is None:
        source_mass = {"real_observed": .5, "model_derived_over60_calibrated": .5}
    profiles = daily_profiles(x)
    people: dict[str, list[int]] = defaultdict(list)
    source_by_pid: dict[str, str] = {}
    for i in rows:
        pid = str(groups[i]["pid"])
        people[pid].append(int(i))
        source = str(groups[i].get("source", "derived"))
        source_by_pid[pid] = "model_derived_over60_calibrated" if source == "derived" or pid.startswith("derived-") else "real_observed"
    if not people:
        raise ValueError("similarity weighting received no training people")
    person_profile = {pid: np.nanmean(profiles[ix], axis=0) for pid, ix in people.items()}
    derived_people = [pid for pid in person_profile if source_by_pid[pid] == "model_derived_over60_calibrated"]
    if not derived_people:
        raise ValueError("the pooled over-60 calibration target requires derived train people")
    # CALIBRATION.md documents the aggregate older-adult unlock/movement
    # calibration used for this source. This is a group target, never a
    # fabricated age for an individual derived or observed person.
    target = np.nanmean(np.stack([person_profile[pid] for pid in derived_people]), axis=0)
    target_norm = np.linalg.norm(target) + 1e-8
    similarity = {pid: float(np.dot(v, target) / ((np.linalg.norm(v) + 1e-8) * target_norm))
                  for pid, v in person_profile.items()}
    by_source: dict[str, list[str]] = defaultdict(list)
    for pid, src in source_by_pid.items():
        by_source[src].append(pid)
    person_mass: dict[str, float] = {}
    used_mass = {s: float(source_mass.get(s, 0.0)) for s in by_source}
    total_mass = sum(used_mass.values())
    if total_mass <= 0:
        raise ValueError("source masses must have positive total")
    used_mass = {s: m / total_mass for s, m in used_mass.items()}
    for src, pids in by_source.items():
        logits = np.asarray([similarity[pid] / temperature for pid in pids], dtype=np.float64)
        logits -= logits.max()
        probs = np.exp(logits); probs /= probs.sum()
        for pid, prob in zip(pids, probs):
            person_mass[pid] = used_mass[src] * float(prob)
    weights = np.zeros(len(x), dtype=np.float32)
    daily_similarity = np.zeros(len(x), dtype=np.float32)
    for pid, ix in people.items():
        daily_mass = person_mass[pid] / len(ix)
        weights[ix] = daily_mass
        for i in ix:
            daily_similarity[i] = float(np.dot(profiles[i], target) /
                                        ((np.linalg.norm(profiles[i]) + 1e-8) * target_norm))
    weights /= weights.mean()
    report = {"method": "person-profile cosine softmax within source, then batch softmax",
              "temperature": temperature, "source_mass": used_mass,
              "people_by_source": {s: len(v) for s, v in by_source.items()},
              "no_individual_ages": True,
              "age_reference": "pooled over-60 calibration attached to model_derived parents; CALIBRATION.md",
              "profile": "24 hourly active-use rates",
              "weights_min_max": [float(weights[rows].min()), float(weights[rows].max())]}
    return weights, daily_similarity, report


def batch_softmax_weights(base: np.ndarray, similarity: np.ndarray, temperature: float = .20) -> np.ndarray:
    """Combine person/source weights with a batch-local profile softmax."""
    logits = np.asarray(similarity, dtype=np.float64) / temperature
    logits -= logits.max()
    local = np.exp(logits)
    local /= local.mean()
    return np.asarray(base, dtype=np.float32) * local.astype(np.float32)

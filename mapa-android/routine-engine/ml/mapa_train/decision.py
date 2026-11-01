"""Decision layer on existing model scores (MODEL-PLAN step a): validation only, no training, no test.

    python -m mapa_train.decision --model cnn-v3

The scoring rules (eligible bins, false alerts per person-week, catch by severity, the 4-hour rule and the
statistics-only baseline) are copied from mapa_release.evaluate.evaluate, so the numbers are comparable. What
changes is only how a model score becomes an alert:

- global:   score >= one threshold (reproduces the release).
- personal: score >= max(floor, the parent's own q-quantile of scores over their last 14 untouched days)
            (Gibietz et al. 2026); q in {0.90, 0.97, 0.995} is the family's sensitivity (Cejudo et al. 2025).
- + safety floor: a daytime silence of at least F bins always alerts.
- + morning check: no use yet today, and the slot is past the parent's late first-use time
            (90th percentile of their last 14 untouched days) (eNeighbor).
Everything personal is causal: a day only sees earlier untouched days of the same person.
"""
from __future__ import annotations

import argparse
import itertools
import json
from collections import defaultdict

import numpy as np
import pyarrow.parquet as pq

from mapa_release.common import ROOT
from .protocol import DATA, verify_data

DAY = (np.arange(96) >= 28) & (np.arange(96) < 88)
SEVERITIES = ("mild", "clear", "severe")


class CausalHistory(dict):
    """Per-fold personal histories with memoized exact quantiles.

    A decision grid scores the same (person, day) against many thresholds. Its
    historical quiet samples and first-use slots do not change between those
    configurations, so calculate each requested quantile once per history.
    """

    def __init__(self):
        super().__init__()
        self._quantiles = {}

    def quantile(self, key, q, first_use=False):
        cache_key = (key, float(q), bool(first_use))
        if cache_key not in self._quantiles:
            values = self[key][1 if first_use else 0]
            self._quantiles[cache_key] = float(np.quantile(values, q))
        return self._quantiles[cache_key]


def day_views(split, model, access_authorized=False):
    """One record per (person, episode, day): availability, counts, tags, window, score, labels."""
    if split not in {"train", "val", "test"}:
        raise ValueError(f"unknown split: {split}")
    folder = ROOT / "models" / model
    if split in {"val", "test"}:
        selection = folder / "selection.json"
        if not selection.exists():
            raise RuntimeError(f"{split} is locked until train-only model selection is saved")
        access = folder / f"{split}-access.json"
        if access_authorized:
            if not access.exists():
                raise RuntimeError(f"{split} evaluator access marker is missing")
            marker = json.loads(access.read_text())
            selection_hash = __import__("hashlib").sha256(selection.read_bytes()).hexdigest()
            if marker.get("split") != split or marker.get("selection_sha256") != selection_hash:
                raise RuntimeError(f"{split} evaluator access marker does not match the locked selection")
        else:
            if access.exists():
                raise RuntimeError(f"{split} may be opened only once; access marker already exists")
            if split == "test" and not (folder / "validation-results.json").exists():
                raise RuntimeError("test is locked until the single validation confirmation is recorded")
            access.write_text(json.dumps({"split": split, "model": model,
                                          "selection_sha256": __import__("hashlib").sha256(selection.read_bytes()).hexdigest(),
                                          "access_started": True}, indent=2) + "\n")
    # Held-out integrity checks must happen only after the corresponding gate.
    verify_data(split)
    config = json.loads((folder / "complete.json").read_text())
    version = config.get("data_version", "causal-v3")
    cache = ROOT / f"learning-{version}-final-v2" / f"{split}.npz"
    groups = json.loads(cache.with_suffix(".groups.json").read_text())
    score_path = folder / f"{split}-scores.npy"
    if split == "train" and not score_path.exists():
        score_path = folder / "train-scores.npy"
    scores = np.load(score_path)
    confidence_path = folder / f"{split}-confidence.npy"
    confidence = np.load(confidence_path) if confidence_path.exists() else np.zeros_like(scores)
    if confidence.shape != scores.shape:
        raise ValueError("Confidence and behavior score arrays differ")
    hazard_path = folder / f"{split}-next-use.npy"
    hazards = np.load(hazard_path) if hazard_path.exists() else np.zeros_like(scores)
    if hazards.ndim == 2:
        hazards = hazards[..., None]
    frame = pq.read_table(DATA / f"{split}.parquet").to_pandas()
    metadata = {}
    for key, df in frame.groupby(["pid", "episode_id", "day_index"], sort=False, dropna=False):
        pid, eid, day = key
        eid = "" if eid is None or str(eid) == "nan" else str(eid)
        metadata[(str(pid), eid, int(day))] = df
    if len(groups) != len(scores) or len(groups) != len(hazards):
        raise ValueError(f"feature groups ({len(groups)}), behaviour scores ({len(scores)}), and hazards ({len(hazards)}) differ for {split}")
    out = []
    for i, g in enumerate(groups):
        eid = g.get("episode_id")
        eid = "" if eid is None or str(eid) == "nan" else str(eid)
        df = metadata[(str(g["pid"]), eid, int(g["day"]))]
        slots = df.t.to_numpy() % 96
        avail = np.zeros(96, bool); tags = np.zeros(96, bool); win = np.zeros(96, bool)
        ok = np.logical_and.reduce([df[c].to_numpy(dtype=bool) for c in ["present", "phone_on", "usage_ok", "network_ok", "observed"]])
        avail[slots] = ok; tags[slots] = df.life_tag.to_numpy(dtype=bool); win[slots] = df.window.to_numpy(dtype=bool)
        counts = np.full(96, -1); counts[slots] = df.interactions
        silence = np.zeros(96); streak = 0
        for k in range(96):
            streak = streak + 1 if avail[k] and DAY[k] and counts[k] == 0 else 0
            silence[k] = streak
        surprise = np.zeros(96); survival = 1.0
        for k in range(96):
            if k == 28:
                survival = 1.0
            if avail[k] and DAY[k] and counts[k] == 0:
                hazard_row = hazards[i, max(0, k - 1)]
                h1 = float(hazard_row[0] if np.ndim(hazard_row) else hazard_row)
                survival *= 1.0 - h1
                surprise[k] = 1.0 - survival
            else:
                survival = 1.0
        expected=str(df.expected.iloc[0])
        injected=str(df.injected.iloc[0])
        if expected == "unusual_inactivity" and injected not in {
                "daytime_inactivity", "delayed_morning", "phone_left_home"}:
            raise ValueError(f"unusual target is not labelled as an injected disruption: {g['pid']}/{eid}/{g['day']}")
        out.append(dict(pid=g["pid"], eid=g["episode_id"], day=int(g["day"]), avail=avail, tags=tags, win=win,
                        counts=counts, silence=silence, score_behavior=scores[i], score_surprise=surprise,
                        confidence=confidence[i],
                        hazard=hazards[i], expect=expected, injected=injected,
                        severity=str(df.severity.iloc[0]), scenario=int(df.scenario.iloc[0]),
                        eligible=avail & DAY & ~tags & (counts == 0),
                        alert_eligible=avail & DAY & (counts == 0)))
    return out


def score_for(v, kind):
    if kind == "combined":
        return .5 * (v["score_behavior"] + v["score_surprise"])
    return v["score_surprise"] if kind == "surprise" else v["score_behavior"]


def personal_history(views, score_kind="behaviour"):
    """Causal per-day history from untouched days: score samples and first-use slots of the last 14 days."""
    base = defaultdict(list)
    for v in views:
        if not v["eid"]:
            base[v["pid"]].append(v)
    hist = CausalHistory()
    for pid, days in base.items():
        days.sort(key=lambda v: v["day"])
        scores, firsts = [], []
        for v in days:
            hist[(pid, v["day"])] = (np.concatenate(scores[-14:]) if scores else np.zeros(0), list(firsts[-14:]))
            sel = v["avail"] & DAY & (v["counts"] == 0)
            full_day = int(v["avail"][24:96].sum()) >= 56
            if not full_day:
                continue
            scores.append(score_for(v, score_kind)[sel])
            used = np.flatnonzero(v["avail"] & (v["counts"] > 0) & (np.arange(96) >= 20))
            if len(used):
                firsts.append(int(used[0]))
    return hist


def stats_limits(views):
    """The statistics-only baseline's causal personal limit, exactly as in mapa_release.evaluate."""
    limits, day_limits = {}, {}
    # Parquet/group iteration order is not a temporal guarantee.  Enforce the
    # baseline's causal contract explicitly: untouched days update the limit
    # only after that day's alert line has been recorded; episode copies never
    # update it.  Place each untouched day before any same-day episode rows.
    ordered = sorted(views, key=lambda v: (v["pid"], v["day"], bool(v["eid"])))
    for v in ordered:
        prior = day_limits.get((v["pid"], v["day"]), limits.get(v["pid"], 16.))
        if not v["eid"]:
            day_limits[(v["pid"], v["day"])] = prior
            if int(v["avail"][24:96].sum()) < 56:
                v["stats_limit"] = prior
                continue
            quiet = v["silence"][v["avail"] & DAY]
            if len(quiet):
                limits[v["pid"]] = max(4., .9 * prior + .1 * float(np.quantile(quiet, .97)))
        v["stats_limit"] = prior


def cold_start_levels(v, cfg, line):
    """Two alert levels with bounded blending, uncertainty and immutable guards."""
    weight = float(cfg["model_weight"])
    if not 0 <= weight <= 1:
        raise ValueError("Model blend weight must lie in [0,1]")
    confidence = np.clip(np.asarray(v["confidence"]), 0., 1.)
    model = .5 * (v["score_behavior"] + v["score_surprise"])
    rule = np.clip(v["silence"] / 16., 0., 1.)
    score = (1. - weight) * rule + weight * model
    threshold = np.minimum(1., line + (1. - confidence) * cfg.get("uncertainty_margin", .2))
    eligible = v["alert_eligible"] & v["avail"]
    call = ((score >= threshold) | (v["silence"] >= 32)) & eligible
    look = (score >= .7 * threshold) & eligible & ~call
    return {"please_call": call, "worth_a_look": look,
            "still_learning": confidence < .5, "score": score}


def detect(v, hist, cfg):
    if cfg.get("hard_switch") and v["days_since_install"] < 14:
        return (v["silence"] >= 16) & v["alert_eligible"] & v["avail"]
    if cfg.get("cold_start"):
        past, firsts = hist.get((v["pid"], v["day"]), (np.zeros(0), []))
        line = cfg["floor"]
        if len(past) >= 96:
            qv = hist.quantile((v["pid"], v["day"]), cfg["q"]) if isinstance(hist, CausalHistory) else float(np.quantile(past, cfg["q"]))
            line = max(line, qv)
        levels = cold_start_levels(v, cfg, line)
        alert = levels["please_call"]
        if cfg.get("morning") and len(firsts) >= 5:
            qv = hist.quantile((v["pid"], v["day"]), .9, first_use=True) if isinstance(hist, CausalHistory) else float(np.quantile(firsts, .9))
            late = int(qv) + 1
            seen = np.maximum.accumulate((v["counts"] > 0) & (np.arange(96) >= 20))
            alert |= (np.arange(96) >= late) & ~seen & v["alert_eligible"] & v["avail"]
        return alert
    s = score_for(v, cfg.get("score_kind", "behaviour"))
    if cfg["kind"] == "global":
        alert = s >= cfg["floor"]
    else:
        past, firsts = hist.get((v["pid"], v["day"]), (np.zeros(0), []))
        line = cfg["floor"]
        if len(past) >= 96:                              # need about a day of quiet samples first
            qv = hist.quantile((v["pid"], v["day"]), cfg["q"]) if isinstance(hist, CausalHistory) else float(np.quantile(past, cfg["q"]))
            line = max(cfg["floor"], qv)
        alert = s >= line
        if cfg.get("silence_floor"):
            alert = alert | (v["silence"] >= cfg["silence_floor"])
        if cfg.get("morning") and len(firsts) >= 5:
            qv = hist.quantile((v["pid"], v["day"]), .9, first_use=True) if isinstance(hist, CausalHistory) else float(np.quantile(firsts, .9))
            late = int(qv) + 1
            seen_use = np.maximum.accumulate((v["counts"] > 0) & (np.arange(96) >= 20))
            alert = alert | ((np.arange(96) >= late) & ~seen_use & v["avail"])
    # Life-tag labels are an evaluation stratum, not an input to inference.
    # Keep predictions on those days so hard-negative alert rate is measurable;
    # untouched-day false-alert counting masks tags separately below.
    return alert & v["alert_eligible"] & v["avail"]


def score_config(views, hist, cfg):
    stale_as_fresh = 0
    person_catches = {name: defaultdict(lambda: np.zeros((3, 2), dtype=np.int64))
                      for name in ["model", "4h_rule", "12h_rule", "24h_rule", "statistics_only"]}
    tot = {"fa": 0, "exp": 0.0, "pos": {s: [0, 0] for s in SEVERITIES}, "hn": [0, 0], "people": defaultdict(lambda: [0, 0.0])}
    observation = {s: [0, 0] for s in SEVERITIES}
    alert_delays = {s: [] for s in SEVERITIES}
    base = {m: {"fa": 0, "exp": 0.0, "people": defaultdict(lambda: [0, 0.0]),
                "pos": {s: [0, 0] for s in SEVERITIES}}
            for m in ("4h_rule", "12h_rule", "24h_rule", "statistics_only")}
    for v in views:
        dets = {"model": detect(v, hist, cfg),
                "4h_rule": (v["silence"] >= 16) & v["alert_eligible"],
                "12h_rule": (v["silence"] >= 48) & v["alert_eligible"],
                "24h_rule": (v["silence"] >= 96) & v["alert_eligible"],
                "statistics_only": (v["silence"] >= v["stats_limit"]) & v["alert_eligible"]}
        stale_as_fresh += int(np.sum(dets["model"] & ~v["avail"]))
        for m, alert in dets.items():
            t = tot if m == "model" else base[m]
            if not v["eid"]:
                alert = alert & ~v["tags"]
                ev = int(np.sum(alert & ~np.r_[False, alert[:-1]]))
                ex = float(np.sum(v["avail"] & DAY & ~v["tags"]) / (60 * 7))
                t["fa"] += ev; t["exp"] += ex
                if m == "model":
                    tot["people"][v["pid"]][0] += ev; tot["people"][v["pid"]][1] += ex
                else:
                    t["people"][v["pid"]][0] += ev; t["people"][v["pid"]][1] += ex
            elif v["expect"] == "unusual_inactivity":
                caught = np.flatnonzero(alert & v["win"])
                t["pos"][v["severity"]][0] += int(len(caught) > 0); t["pos"][v["severity"]][1] += 1
                person_catches[m][v["pid"]][SEVERITIES.index(v["severity"])] += [int(len(caught) > 0), 1]
                if m == "model" and len(caught):
                    window_start = int(np.flatnonzero(v["win"])[0])
                    alert_delays[v["severity"]].append(max(0, int(caught[0]) - window_start) * 15)
            elif v["expect"] == "monitoring_unavailable":
                if m == "model":
                    observation[v["severity"]][0] += int(bool((~v["avail"] & v["win"]).any()))
                    observation[v["severity"]][1] += 1
            elif v["expect"] == "none" and m == "model":
                tot["hn"][0] += int((alert & v["win"]).any()); tot["hn"][1] += 1

    def summary(t):
        return {"fa_per_week": t["fa"] / max(t["exp"], 1e-9),
                "false_alerts": t["fa"], "person_weeks_observed": t["exp"],
                "people": len(t["people"]),
                "episode_counts": {s: n for s, (c, n) in t["pos"].items()},
                "caught_counts": {s: c for s, (c, n) in t["pos"].items()},
                "catch": {s: (c / n if n else None) for s, (c, n) in t["pos"].items()}}
    res = {"config": cfg, "model": summary(tot), **{m: summary(b) for m, b in base.items()},
           "behavior_only": True,
           "stale_as_fresh": stale_as_fresh,
           "observation_episodes_separate": {s: {"caught": v[0], "episodes": v[1],
                                                 "rate": v[0] / max(v[1], 1)} for s, v in observation.items()},
           "time_to_alert_minutes": {s: {"caught_episodes": len(v),
                                         "median": float(np.median(v)) if v else None,
                                         "p90": float(np.quantile(v, .9)) if v else None}
                                     for s, v in alert_delays.items()},
           "hard_negative_alert_rate": tot["hn"][0] / max(tot["hn"][1], 1)}
    c4 = stale_as_fresh == 0 and tot["exp"] > 0 and all(n > 0 for c, n in tot["pos"].values())
    for b in ("4h_rule", "statistics_only"):
        c4 &= res["model"]["fa_per_week"] < res[b]["fa_per_week"]
        for s in SEVERITIES:
            x, y = res["model"]["catch"][s], res[b]["catch"][s]
            if x is not None and y is not None:
                c4 &= x >= y
    res["C4"] = bool(c4)
    res["people"] = len({v["pid"] for v in views})
    for name, records in person_catches.items():
        rng_catch = np.random.default_rng(20261101)
        matrix = np.asarray(list(records.values()))
        intervals = {s: None for s in SEVERITIES}
        if len(matrix):
            draws = matrix[rng_catch.integers(len(matrix), size=(1000, len(matrix)))].sum(axis=1)
            for j, severity in enumerate(SEVERITIES):
                valid = draws[:, j, 1] > 0
                if valid.any():
                    intervals[severity] = np.quantile(draws[valid, j, 0]/draws[valid, j, 1], [.025, .975]).tolist()
        res[name]["catch_95ci"] = intervals
    rng = np.random.default_rng(20261101)
    for name, totals in [("model", tot), *base.items()]:
        people_rows = np.asarray(list(totals["people"].values()), dtype=np.float64)
        if len(people_rows):
            sampled = people_rows[rng.integers(len(people_rows), size=(1000, len(people_rows)))]
            den = sampled[:, :, 1].sum(axis=1)
            rates = sampled[:, :, 0].sum(axis=1) / np.maximum(den, 1e-12)
            res[name]["fa_95ci"] = np.quantile(rates, [.025, .975]).tolist()
        else:
            res[name]["fa_95ci"] = None
    return res


def cold_start_curve(views, hist, cfg):
    """Score install-age strata using the same evaluator and full causal histories."""
    origins = {}
    for v in views:
        if not v["eid"]:
            origins[v["pid"]] = min(origins.get(v["pid"], v["day"]), v["day"])
    for v in views:
        if v["pid"] not in origins:
            raise ValueError("Install origin requires an untouched timeline for each person")
        v["days_since_install"] = v["day"] - origins[v["pid"]]
    result = {}
    for label, lo, hi in [("0-1", 0, 1), ("2-3", 2, 3), ("4-7", 4, 7),
                          ("8-14", 8, 14), ("15+", 15, float("inf"))]:
        selected = [v for v in views if lo <= v["days_since_install"] <= hi]
        behavior = [v for v in selected if v["eid"] and v["expect"] == "unusual_inactivity"]
        result[label] = {"people": len({v["pid"] for v in selected}),
                         "behavior_episodes": {s: sum(v["severity"] == s for v in behavior) for s in SEVERITIES},
                         "metrics": score_config(selected, hist, cfg) if selected else None,
                         "hard_14_day_switch": score_config(selected, hist, dict(cfg, hard_switch=True)) if selected else None}
    return {"install_origin": "first available untouched day, simulated install", "strata": result}


def next_use_report(groups, targets, masks, hazards, profile_probability, real, episode):
    """Next-bin log-loss on untouched real days, bootstrapped by whole people."""
    people = defaultdict(lambda: np.zeros(3, dtype=np.float64))
    for i, g in enumerate(groups):
        if not real[i] or episode[i]:
            continue
        valid = masks[i, :, 0].astype(bool)
        y = targets[i, :, 0][valid]
        model = np.clip(hazards[i, :, 0][valid], 1e-6, 1-1e-6)
        profile = np.clip(profile_probability[i][valid], 1e-6, 1-1e-6)
        losses = [float(-(y*np.log(p)+(1-y)*np.log1p(-p)).sum()) for p in [model, profile]]
        people[str(g['pid'])] += [*losses, int(valid.sum())]
    rows = np.asarray(list(people.values()))
    result = {'people': len(rows), 'bins': int(rows[:, 2].sum()) if len(rows) else 0}
    rng = np.random.default_rng(20261101)
    for j, name in enumerate(['model', 'profile_only']):
        if not result['bins']:
            result[name] = {'log_loss': None, 'ci95': None}
            continue
        draws = rows[rng.integers(len(rows), size=(1000, len(rows)))].sum(axis=1)
        ok = draws[:, 2] > 0
        result[name] = {'log_loss': float(rows[:, j].sum()/rows[:, 2].sum()),
                        'ci95': np.quantile(draws[ok, j]/draws[ok, 2], [.025, .975]).tolist()}
    return result


def grid():
    floors = [.05, .1, .2, .3, .4, .5, .6, .7]
    for score_kind in ("behaviour", "surprise"):
        yield from ({"kind": "global", "floor": f, "score_kind": score_kind} for f in floors)
        for f, q, sf, mo in itertools.product([.05, .1, .2, .3, .5], [.9, .97, .995], [None, 24, 32, 48], [False, True]):
            yield {"kind": "personal", "floor": f, "q": q, "silence_floor": sf, "morning": mo,
                   "score_kind": score_kind}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="cnn-v3")
    ap.add_argument("--split", default="val", choices=["val"])           # test is never opened here
    a = ap.parse_args()
    folder = ROOT / "models" / a.model
    selection = json.loads((folder / "selection.json").read_text())
    cfg = selection.get("decision_config")
    if not isinstance(cfg, dict):
        raise RuntimeError("selection.json must contain the locked decision_config before validation opens")
    out = folder / "validation-results.json"
    if out.exists():
        raise RuntimeError("Validation confirmation already exists; do not tune or rerun")
    views = day_views(a.split, a.model)
    hist = personal_history(views, cfg.get("score_kind", "behaviour"))
    stats_limits(views)
    result = score_config(views, hist, cfg)
    result["cold_start_curve"] = cold_start_curve(views, hist, cfg)
    result["selection_sha256"] = __import__("hashlib").sha256((folder / "selection.json").read_bytes()).hexdigest()
    result["confirmation_only"] = True
    out.write_text(json.dumps(result, indent=2))
    print(json.dumps(result["model"], indent=2))
    print("Validation C4:", result["C4"])


if __name__ == "__main__":
    main()

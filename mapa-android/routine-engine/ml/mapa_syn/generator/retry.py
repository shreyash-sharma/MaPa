"""v0.1.3 bounded retry: four candidates, frozen roles, pre-age fidelity.

Candidate settings depend on fit/tune only. Each candidate receives one
fidelity-test evaluation, cached with its sample hash and fully logged.
"""
import copy
import json
from pathlib import Path
import numpy as np

from ..io import read_json, write_json, write_csv, sha256, read_csv
from ..paths import data_root, PROJECT, version_root
from .data import directory, fit_people, load_person, policy_hashes
from .fit import fit_model, TS_URL
from .hsmm import runs, fit_duration, fit_quiet
from .calibrated import _cell, residual_pmf, scaled_pmf, simulate_calibrated
from .fidelity import matched, survival_ks, correlation, classifier_auc, privacy_distances

CANDIDATES = ["hierarchical_lognormal", "three_blocks_no_weekday", "censored_km", "hand_structure_learned_quiet"]


def km_pmf(records, cap=96):
    # Unknown starts are residual lives. Ordinary KM cannot estimate fresh
    # durations from them, so only known-start records enter this estimator.
    records = records[records[:, 4] == 0]
    if len(records) == 0:
        raise ValueError("No known-start quiet intervals")
    d, c = records[:, 1].astype(int), records[:, 5].astype(bool)
    survival = 1.
    pmf = np.zeros(cap)
    for t in np.unique(d):
        risk, events = int((d >= t).sum()), int(((d == t) & ~c).sum())
        mass = survival * events / risk
        pmf[min(t, cap) - 1] += mass
        survival -= mass
    pmf[-1] += survival  # explicitly unresolved tail at the existing model cap
    return pmf / pmf.sum()


def make_candidates(base, people):
    result = [copy.deepcopy(base) for _ in CANDIDATES]
    all_use, all_quiet = [], []
    for p in people:
        rr = runs(p["counts"], p["observed"], p["start_dow"])
        all_use.extend(rr[rr[:, 0] == 1].tolist())
        if p["source"] == "ubiqlog":
            all_quiet.extend(rr[rr[:, 0] == 0].tolist())
    use, quiet = np.array(all_use).reshape(-1, 6), np.array(all_quiet).reshape(-1, 6)
    # Three 8-hour blocks, no weekday split (three blocks, not 3-hour widths).
    coarse = result[1]
    for group in range(3):
        uu = use[(use[:, 3].astype(int) % 6) // 2 == group]
        qq = quiet[(quiet[:, 3].astype(int) % 6) // 2 == group]
        u = fit_duration(uu[:, 1], uu[:, 2], base["duration_fit_diagnostics"]["global_use"], uu[:, 4], uu[:, 5]) if len(uu) else base["use"][0]
        q = fit_quiet(qq[:, 1], qq[:, 2], base["duration_fit_diagnostics"]["global_quiet"], qq[:, 4], qq[:, 5]) if len(qq) else base["duration_fit_diagnostics"]["global_quiet"]
        cells = [c for c in range(12) if (c % 6) // 2 == group]
        for c in cells:
            for key, values in [("use", u), ("short", q["short"]), ("long", q["long"])]:
                coarse[key][c] = list(values)
            coarse["quiet_weight"][c] = q["weight"]
        # Shift each fitted-person mean as well as the population centre.
        for state, values in enumerate([u, q["short"], q["long"]]):
            index = np.array(cells) + 12 * state
            centre = np.array(base["population_mean"])[index].mean()
            for row in coarse["person_parameters"]:
                old = np.array(row["parameters"])[index].mean()
                for j in index:
                    row["parameters"][int(j)] = values[0] + old - centre
    # Collapse profile day types too, while retaining hourly occupancy data.
    for row in coarse["person_parameters"]:
        ph = np.exp(np.array(row["parameters"])[48:96].reshape(2, 24))
        ph = (5 * ph[0] + 2 * ph[1]) / 7
        row["parameters"][48:96] = np.log(np.tile(ph, 2)).tolist()
    vv = np.array([p["parameters"] for p in coarse["person_parameters"]])
    coarse["population_mean"] = vv.mean(0).tolist()
    cv = np.cov(vv, rowvar=False)
    coarse["population_covariance"] = (.5 * cv + .5 * np.diag(np.diag(cv)) + np.eye(97) * 1e-4).tolist()
    pooled = km_pmf(quiet)
    cells = []
    for c in range(12):
        q = quiet[quiet[:, 3] == c]
        known = int((q[:, 4] == 0).sum())
        local = km_pmf(q) if known else pooled
        weight = known / (known + 8)
        cells.append((weight * local + (1 - weight) * pooled).tolist())
    result[2]["quiet_pmf"] = cells
    result[3]["quiet_pmf"] = cells
    for i, model in enumerate(result):
        model["candidate"] = CANDIDATES[i]
        model["spec_revision"] = "0.1.3"
        model["age_layer_applied_for_fidelity"] = False
    return result


def raw_path(params, vector, shape, weekend, rng, cache):
    path = np.full(60, 2, np.int8)
    t, state = 28, None
    while t < 88:
        c = weekend * 6 + t // 16
        if c not in cache:
            cache[c] = _cell(params, vector, c)
        pmfs, weight, scales, means = cache[c]
        umean = np.dot(np.arange(1, 97), pmfs[0])
        occupancy = np.clip(shape[weekend, t // 4], .001, .99)
        target = np.clip(umean * (1 - occupancy) / occupancy, 1, 96)
        scale = np.interp(target, means, scales)
        short, long = scaled_pmf(pmfs[1], scale), scaled_pmf(pmfs[2], scale)
        laws = [pmfs[0], short, long]
        fresh = state is not None
        if state is None:
            sm, lm = [np.dot(np.arange(1, 97), p) for p in [short, long]]
            qmean = weight * sm + (1 - weight) * lm
            if rng.random() < umean / (umean + qmean):
                state = 0
            else:
                state = 1 if rng.random() < weight * sm / qmean else 2
        p = laws[state] if fresh else residual_pmf(laws[state])
        duration = int(np.searchsorted(np.cumsum(p), rng.random(), side="right") + 1)
        end = min(88, t + duration)
        path[t - 28:end - 28] = state
        t = end
        state = (1 if rng.random() < weight else 2) if state == 0 else 0
    return path


def simulate_raw(params, hand, parents=200, days=120, seed=20261101):
    rng = np.random.default_rng(seed)
    vectors = rng.multivariate_normal(params["population_mean"], params["population_covariance"], size=parents)
    counts = np.zeros((parents, days, 96), np.int32)
    observed = np.ones_like(counts, bool)
    states = np.full(counts.shape, 3, np.int8)
    dows = rng.integers(0, 7, parents)
    night_slots = np.r_[0:28, 88:96]
    hand_counts = hand["counts"].reshape(-1, 96)
    hand_obs = hand["observed"].reshape(-1, 96)
    hand_profile = []
    for h in range(24):
        c = hand_counts[:, h * 4:h * 4 + 4]; o = hand_obs[:, h * 4:h * 4 + 4]
        hand_profile.append(float(((c > 0) & o).sum() / max(o.sum(), 1)))
    for person, vector in enumerate(vectors):
        intensity = np.maximum(np.exp(np.clip(vector[36:48], -2, 7)), 1)
        shape = np.exp(np.clip(vector[48:96], -7, 0)).reshape(2, 24)
        if params["candidate"] == "hand_structure_learned_quiet":
            shape = np.tile(hand_profile, (2, 1))
        cache = {}
        for day in range(days):
            template = rng.integers(0, len(hand_counts))
            counts[person, day, night_slots] = hand_counts[template, night_slots]
            observed[person, day, night_slots] = hand_obs[template, night_slots]
            weekend = int((dows[person] + day) % 7 >= 5)
            if params["candidate"] == "hand_structure_learned_quiet":
                # Preserve hand in-use burst/count structure; replace each
                # quiet sojourn by an independently sampled learned duration.
                source = hand_counts[template]
                source_use = runs(source, hand_obs[template])
                positive = source_use[source_use[:, 0] == 1]
                t = 28
                while t < 88:
                    if len(positive):
                        duration = max(1, int(positive[rng.integers(len(positive)), 1]))
                        end = min(88, t + duration)
                        possible = source[(source > 0) & (np.arange(96) >= 28) & (np.arange(96) < 88)]
                        counts[person, day, t:end] = rng.choice(possible, end - t)
                        states[person, day, t:end] = 0
                        t = end
                    else:
                        break
                    c = weekend * 6 + min(t, 87) // 16
                    p = np.array(params["quiet_pmf"][c])
                    duration = int(np.searchsorted(np.cumsum(p), rng.random(), side="right") + 1)
                    end = min(88, t + duration)
                    states[person, day, t:end] = 1 if duration <= 4 else 2
                    t = end
            else:
                path = raw_path(params, vector, shape, weekend, rng, cache)
                states[person, day, 28:88] = path
                pos = np.flatnonzero(path == 0) + 28
                means = intensity[weekend * 6 + pos // 16]
                counts[person, day, pos] = 1 + rng.poisson(np.maximum(means - 1, 0))
    return dict(counts=counts, observed=observed, states=states, start_dow=dows,
                parameters=[dict(pid=f"sl-{i:04d}", log_parameters=v.tolist()) for i, v in enumerate(vectors)])


def score(sample, people, hand, privacy=False, all_real=None):
    a, b = matched(people, sample), matched(people, hand)
    assert np.array_equal(a["real"], b["real"])
    ks, baseline_ks = [survival_ks(m["real_quiet"], m["fake_quiet"]) for m in [a, b]]
    corr, baseline_corr = correlation(a), correlation(b)
    classifier, baseline_classifier = classifier_auc(a), classifier_auc(b)
    daily = np.maximum(sample["counts"], 0).sum(2)
    real_iqr = np.quantile(a["real"][:, 0] * 60, [.25, .75]).tolist()
    median = float(np.median(daily))
    gates = {
        "F1": dict(pass_=ks is not None and baseline_ks is not None and ks < baseline_ks, learned_ks=ks, synth_hand_ks=baseline_ks),
        "F2": dict(pass_=corr is not None and baseline_corr is not None and corr > baseline_corr, learned_correlation=corr, synth_hand_correlation=baseline_corr),
        "F3": dict(pass_=real_iqr[0] <= median <= real_iqr[1], median=median, real_proxy_iqr=real_iqr, mean=float(daily.mean()), sd=float(daily.std()), age_layer_applied=False),
        "F4": dict(pass_=classifier is not None and baseline_classifier is not None and classifier < baseline_classifier, learned_auc=classifier, synth_hand_auc=baseline_classifier)}
    if privacy:
        real_days = np.concatenate([np.where(p["observed"].reshape(-1, 96), p["counts"].reshape(-1, 96), -1)[p["observed"].reshape(-1, 96).any(1)] for p in all_real])
        fake = np.where(sample["observed"], sample["counts"], -1).reshape(-1, 96)
        d = privacy_distances(fake, real_days)
        gates["F5"] = dict(pass_=bool(np.all(d > 0)), minimum_distance=float(d.min()), exact_copies=int((d == 0).sum()), synthetic_days=len(fake), real_days=len(real_days))
    for gate in gates.values():
        gate["pass"] = bool(gate.pop("pass_"))
    return dict(gates=gates, matched_day_pairs=len(a["real"]), real_pids=[p["pid"] for p in people],
                profile_masked=True, age_layer_applied=False, interpretation="v0.1.3 repeated diagnostics; not a pristine confirmatory holdout")


def run_retry():
    root = directory()
    roles = read_json(root / "roles-v013.json")
    if roles["policy"] != policy_hashes():
        raise RuntimeError("v0.1.3 policy changed after the frozen split")
    if "Spec change v0.1.3" not in (PROJECT / "docs/mapa-syn/DESIGN.md").read_text(encoding="utf-8"):
        raise RuntimeError("v0.1.3 retry authorization is missing")
    work = root / "experiments/v013"
    work.mkdir(parents=True, exist_ok=True)
    audit = []
    fit = list(fit_people(roles, audit=audit))
    if set(audit) & {r["pid"] for r in roles["people"] if r["partition"] != "fit"}:
        raise RuntimeError("Partition leak")
    model_path = work / "candidate-models.json"
    if model_path.exists():
        models = read_json(model_path)
    else:
        base = fit_model(fit)
        models = make_candidates(base, fit)
        write_json(model_path, models)
    write_json(work / "fit-access.json", dict(opened_pids=audit, role_counts={g: sum(r["partition"] == g for r in roles["people"]) for g in ["fit", "tune", "fidelity_test", "real_check"]}))
    with np.load(root / "hand_reference.npz", allow_pickle=False) as z:
        hand = {k: z[k] for k in ["counts", "observed", "start_dow"]}
    tune = [load_person(p) for p in roles["people"] if p["partition"] == "tune"]
    # First freeze every candidate and tune score. No new test records read yet.
    for model in models:
        folder = work / model["candidate"]
        folder.mkdir(exist_ok=True)
        samplefile = folder / "unaged.npz"
        if not samplefile.exists():
            print("Sampling pre-age candidate: " + model["candidate"], flush=True)
            sample = simulate_raw(model, hand)
            np.savez_compressed(samplefile, **{k: v for k, v in sample.items() if k != "parameters"})
            write_json(folder / "sampled_parameters.json", sample["parameters"])
        with np.load(samplefile, allow_pickle=False) as z:
            sample = {k: z[k] for k in z.files}
        if not (folder / "tune.json").exists():
            scores = score(sample, tune, hand)
            scores.update(sample_sha256=sha256(samplefile), model_sha256=sha256(model_path))
            write_json(folder / "tune.json", scores)
        print("Tune: " + model["candidate"] + " " + json.dumps(read_json(folder / "tune.json")["gates"]), flush=True)
    write_json(work / "selection-lock.json", dict(settings_frozen=True, models_sha256=sha256(model_path), roles_sha256=sha256(root / "roles-v013.json"), candidate_limit=4, age_layer_for_fidelity=False))
    test = [load_person(p) for p in roles["people"] if p["partition"] == "fidelity_test"]
    all_real = [load_person(p) for p in roles["people"]]
    results = []
    for model in models:
        folder = work / model["candidate"]
        if (folder / "test.json").exists():
            scores = read_json(folder / "test.json")
            assert scores["sample_sha256"] == sha256(folder / "unaged.npz")
        else:
            with np.load(folder / "unaged.npz", allow_pickle=False) as z:
                sample = {k: z[k] for k in z.files}
            print("Single fidelity-test evaluation: " + model["candidate"], flush=True)
            scores = score(sample, test, hand, privacy=True, all_real=all_real)
            scores.update(sample_sha256=sha256(folder / "unaged.npz"), model_sha256=sha256(model_path))
            write_json(folder / "test.json", scores)
        results.append(dict(candidate=model["candidate"], passes=all(g["pass"] for g in scores["gates"].values()), scores=scores))
    write_json(work / "results.json", results)
    if not any(r["passes"] for r in results):
        from .retry_report import finalize_negative_retry
        return finalize_negative_retry(results)
    return dict(stage="v0.1.3_retry", gate="PASS" if any(r["passes"] for r in results) else "FAIL", results=results,
                candidate_limit=4, injection_allowed=False)

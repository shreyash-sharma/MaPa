"""Held-out, exposure-matched diagnostics. No feedback into generator fitting."""
import numpy as np
from scipy.optimize import minimize
from scipy.special import expit
from scipy.spatial import cKDTree
from scipy.stats import rankdata, lognorm

from ..io import read_json, write_json, write_csv
from ..paths import data_root
from .data import directory, load_person
from .fit import RELEASE_WORDING
from .hsmm import runs, km_curve


def features(counts, mask):
    c = counts[mask].astype(float)
    blocks = []
    for h in range(6):
        m = mask[h * 16:(h + 1) * 16]
        blocks.append(float((counts[h * 16:(h + 1) * 16][m] > 0).mean()) if m.any() else 0.)
    adjacent = mask[1:] & mask[:-1]
    persistence = float(((counts[1:] > 0) & (counts[:-1] > 0))[adjacent].mean()) if adjacent.any() else 0.
    return np.r_[c.mean(), (c > 0).mean(), c.std(), persistence, blocks]


def matched(real_people, sampled):
    real_features, fake_features, groups = [], [], []
    profiles = np.zeros((2, 24))
    exposures = np.zeros(24)
    real_q, fake_q = [], []
    nparents, ndays, _ = sampled["counts"].shape
    ng = len(real_people)
    for group, real in enumerate(real_people):
        counts = real["counts"].reshape(-1, 96)
        observed = real["observed"].reshape(-1, 96)
        pool = np.arange(group, nparents, ng)
        if not len(pool):
            pool = np.array([group % nparents])
        for day, (c, mask0) in enumerate(zip(counts, observed)):
            mask = mask0.copy()
            mask[:28] = False; mask[88:] = False
            if mask.sum() < 4:
                continue  # fixed, equal minimum one-hour exposure for day features
            parent = int(pool[day % len(pool)])
            fake_day = day % ndays
            real_weekend = (real["start_dow"] + day) % 7 >= 5
            # Pair the same day type without choosing on the result.
            for offset in range(7):
                d = (fake_day + offset) % ndays
                if ((int(sampled["start_dow"][parent]) + d) % 7 >= 5) == real_weekend:
                    fake_day = d
                    break
            f = np.maximum(sampled["counts"][parent, fake_day], 0)
            real_features.append(features(c, mask)); fake_features.append(features(f, mask)); groups.append(group)
            for hour in range(24):
                m = mask[hour * 4:(hour + 1) * 4]
                exposures[hour] += m.sum()
                profiles[0, hour] += ((c[hour * 4:(hour + 1) * 4] > 0) & m).sum()
                profiles[1, hour] += ((f[hour * 4:(hour + 1) * 4] > 0) & m).sum()
            if real["source"] == "ubiqlog":
                for values, output in [(c, real_q), (f, fake_q)]:
                    r = runs(values, mask, (real["start_dow"] + day) % 7)
                    output.extend(r[r[:, 0] == 0, 1:3].tolist())
    return dict(real=np.array(real_features), fake=np.array(fake_features), groups=np.array(groups),
                profiles=profiles, exposure=exposures, real_quiet=np.array(real_q).reshape(-1, 2), fake_quiet=np.array(fake_q).reshape(-1, 2))


def survival_ks(real, fake):
    if len(real) == 0 or len(fake) == 0 or (real[:, 1] == 0).sum() == 0:
        return None
    limit = min(real[:, 0].max(), fake[:, 0].max())
    grid = np.unique(np.r_[real[:, 0], fake[:, 0]])
    grid = grid[grid <= limit]
    return float(np.max(np.abs(km_curve(real[:, 0], real[:, 1], grid) - km_curve(fake[:, 0], fake[:, 1], grid)))) if len(grid) else None


def correlation(match):
    m = match["exposure"] > 0
    profiles = match["profiles"][:, m] / match["exposure"][m]
    if m.sum() < 3 or np.any(profiles.std(1) < 1e-10):
        return None
    return float(np.corrcoef(profiles)[0, 1])


def auc(labels, predictions):
    labels = np.asarray(labels, bool)
    n1, n0 = labels.sum(), (~labels).sum()
    if not n1 or not n0:
        return None
    return float((rankdata(predictions)[labels].sum() - n1 * (n1 + 1) / 2) / (n1 * n0))


def classifier_auc(match):
    real, fake, groups = match["real"], match["fake"], match["groups"]
    if len(set(groups)) < 3 or len(real) < 10:
        return None
    x, y, g = np.r_[real, fake], np.r_[np.ones(len(real)), np.zeros(len(fake))], np.r_[groups, groups]
    predictions = np.zeros(len(y))
    for holdout in np.unique(g):
        train, test = g != holdout, g == holdout
        mu, sd = x[train].mean(0), np.maximum(x[train].std(0), 1e-6)
        xt = np.c_[np.ones(train.sum()), (x[train] - mu) / sd]
        def objective(w):
            z = xt @ w
            penalty = .5 * np.dot(w[1:], w[1:])
            loss = (np.logaddexp(0, z) - y[train] * z).sum() + penalty
            grad = xt.T @ (expit(z) - y[train]) + np.r_[0., w[1:]]
            return loss, grad
        fitted = minimize(objective, np.zeros(xt.shape[1]), jac=True, method="L-BFGS-B", options={"maxiter": 300})
        if not np.isfinite(fitted.fun):
            return None
        predictions[test] = expit(np.c_[np.ones(test.sum()), (x[test] - mu) / sd] @ fitted.x)
    return auc(y, predictions)


def privacy_distances(fake_days, real_days):
    """Exact Euclidean day-vector NN, not a claim of general anonymity."""
    tree = cKDTree(np.unique(real_days.astype(float), axis=0))
    distances, _ = tree.query(fake_days.astype(float), k=1, workers=1)
    return distances


def age_count_check(daily, hand_daily, age, real_features):
    """Do not substitute a broad mean±SD range for the spec's IQR criterion.

    CALIBRATION supplies moments, not an empirical older-adult IQR. The aged
    lognormal model implies an IQR, explicitly labeled as a model assumption.
    Both that IQR and the cited moment range must contain the sample median.
    """
    lower, upper = age["daily_count_mean"] - age["daily_count_sd"], age["daily_count_mean"] + age["daily_count_sd"]
    sigma = np.sqrt(np.log1p((age["daily_count_sd"] / age["daily_count_mean"]) ** 2))
    iqr = lognorm.ppf([.25, .75], sigma, scale=age["daily_count_mean"] * np.exp(-sigma ** 2 / 2)).tolist()
    median = float(np.median(daily))
    return dict(pass_=bool(lower <= median <= upper and iqr[0] <= median <= iqr[1]), learned_median=median,
                learned_mean=float(daily.mean()), learned_sd=float(daily.std()), synth_hand_median=float(np.median(hand_daily)),
                cited_aggregate_interval=[round(lower, 10), round(upper, 10)], model_derived_age_iqr=iqr,
                raw_heldout_exposure_adjusted_daily_proxy_iqr=np.quantile(real_features[:, 0] * 60, [.25, .75]).tolist(),
                citation=age["citation"], qualification="Session/unlock count proxy; older-adult IQR is model-derived from cited moments, not published. Broad mean±SD containment alone does not pass.")


def evaluate(sampled, params):
    root = directory()
    roles = read_json(root / "roles.json")
    held_rows = [r for r in roles["people"] if r["partition"] == "heldout"]
    held = [load_person(r) for r in held_rows]
    if set(p["pid"] for p in held) & set(roles["reserved_real_check"]):
        raise RuntimeError("real_check entered F1–F4")
    with np.load(root / "hand_reference.npz", allow_pickle=False) as z:
        hand = {k: z[k] for k in ["counts", "observed", "start_dow"]}
    learned_match, hand_match = matched(held, sampled), matched(held, hand)
    if not np.array_equal(learned_match["real"], hand_match["real"]):
        raise RuntimeError("Fidelity baselines were not evaluated on identical real days")
    lks, hks = [survival_ks(m["real_quiet"], m["fake_quiet"]) for m in [learned_match, hand_match]]
    lcorr, hcorr = [correlation(m) for m in [learned_match, hand_match]]
    lauc, hauc = [classifier_auc(m) for m in [learned_match, hand_match]]
    daily = np.maximum(sampled["counts"], 0).sum(2).astype(float)
    hand_daily = np.maximum(hand["counts"], 0).sum(2)
    age = params["age_layer"]
    all_real, privacy_pids = [], []
    for row in roles["people"]:
        # Evaluation-only access. Nothing here mutates params or feeds genfit.
        p = load_person(row)
        c, o = p["counts"].reshape(-1, 96), p["observed"].reshape(-1, 96)
        valid = o.any(1)
        all_real.append(np.where(o[valid], c[valid], -1))
        privacy_pids.append(row["pid"])
    real_days = np.concatenate(all_real)
    fake_days = np.where(sampled["observed"], sampled["counts"], -1).reshape(-1, 96)
    print("F1-F4 computed; checking every synthetic day for exact copies", flush=True)
    distances = privacy_distances(fake_days, real_days)
    days = sampled["counts"].shape[1]
    write_csv(root / "privacy-nearest.csv.gz", [dict(pid=f"sl-{i // days:04d}", day_index=i % days, nearest_distance=float(d)) for i, d in enumerate(distances)], ["pid", "day_index", "nearest_distance"])
    q = learned_match["real_quiet"]
    result = dict(gates={
        "F1": dict(pass_=lks is not None and hks is not None and lks < hks, learned_ks=lks, synth_hand_ks=hks,
                   real_quiet_records=len(q), real_quiet_events=int((q[:, 1] == 0).sum()), real_quiet_censored=int((q[:, 1] != 0).sum()),
                   method="Kaplan–Meier survival supremum distance on shared estimable support; identical observation masks"),
        "F2": dict(pass_=lcorr is not None and hcorr is not None and lcorr > hcorr, learned_correlation=lcorr, synth_hand_correlation=hcorr),
        "F3": age_count_check(daily, hand_daily, age, learned_match["real"]),
        "F4": dict(pass_=lauc is not None and hauc is not None and lauc < hauc, learned_auc=lauc, synth_hand_auc=hauc,
                   method="scipy ridge logistic regression; leave-one-real-person-out CV; synthetic parents assigned disjointly to the same folds; identical masks/day types"),
        "F5": dict(pass_=bool(np.all(distances > 0)), minimum_nearest_distance=float(distances.min()),
                   synthetic_days=len(fake_days), real_days=len(real_days), exact_copies=int((distances == 0).sum()),
                   qualification="Full encoded 96-bin counts; unknown=-1. Exact-copy check does not certify anonymity or equality on unobserved real bins.")},
        heldout_pids=[r["pid"] for r in held_rows], matched_day_pairs=len(learned_match["real"]),
        selection="Fixed before evaluation: daytime strict bins, at least four observed bins per day; no generator tuning on held-out people.")
    for gate in result["gates"].values():
        gate["pass"] = bool(gate.pop("pass_"))
    write_json(root / "evaluation-access.json", {"F1_to_F4_pids": [r["pid"] for r in held_rows], "F5_evaluation_only_pids": privacy_pids, "generator_parameters_changed": False})
    return result


def build_status(outcome):
    root = directory()
    fit = read_json(root / "stage-S2a.json")
    recovery = read_json(data_root() / "logs/interruption-recovery.json")
    lines = ["# MaPa-Syn v0.1.2 — S2a/S2b review", "",
             f"Status: S2a {fit['gate']}; S2b {outcome['gate']}. STOPPED AFTER S2b. Injection remains blocked. No rhythm-model training or upload ran.", "",
             "## Interruption recovery", "", recovery["reason"], "",
             "Partial outputs deleted: none; the interrupted command completed with a verified PASS gate. Old S3/S4 are retained as historical v0.1.1 artifacts and are not reused to fit the v0.1.2 generator.", "",
             "## Generator fit", "",
             f"Fit people opened: {fit['fit_people']}; held-out gen_fit people: {fit['heldout_people']}; real_check people opened by fitting: 0. "
             "Inputs were physically segregated before fitting. fit-access.json and the full-stage access-isolation test document this boundary. "
             "F5 may read real_check only for evaluation/privacy comparison; these records never feed parameter fitting.", "",
             f"Censoring: {fit['censoring']}", "",
             "Age-scaled quantities, formulas, and citations are in generator/generator_card.md. The cited 28.7±21.4 unlocks/day is applied "
             "to a foreground-count/session proxy, explicitly an assumption. No unsupported older-adult screen-time target is invented. "
             "Night is the original synth_hand night block, explicitly assumed. [TS](https://academic.oup.com/innovateage/article/10/4/igag007/8443438).", "",
             "Required future-release wording, conditional on actual completion of real-log pretraining (not performed in this task):", "", "> " + RELEASE_WORDING, "",
             "## Fidelity — all results", "",
             "| Gate | Pass | Results |", "| --- | --- | --- |"]
    for name, result in outcome["fidelity"]["gates"].items():
        import json
        details = {k: v for k, v in result.items() if k != "pass"}
        lines.append(f"| {name} | {'PASS' if result['pass'] else 'FAIL'} | {json.dumps(details, sort_keys=True)} |")
    lines += ["", "Comparison protocol: held-out gen_fit people only for F1–F4. Both synthetic sets use identical real-day observation masks. "
              "KS uses censored survival, not a naive complete-gap ECDF. The classifier uses person-disjoint folds. "
              "No fitting parameters were adjusted after reading held-out results. All five gates must pass before promotion/injection.", "",
              f"Sample: {outcome['parents']} new parents x {outcome['days']} days, seed {outcome['seed']}, observation_basis=synthetic_learned on every bin. "
              f"Location: {outcome['sample_path']}", "",
              "Passed samples are promoted to converted/synth_learned. Failed samples are retained only under generator/failed-samples for review; they are not approved benchmark data. "
              "No episodes were generated. Model A/B pretraining experiments remain unrun. generator/stage-S2a.json, stage-S2b.json, fidelity.json and generator_card.md contain the evidence."]
    if outcome.get("diagnostic_correction"):
        lines += ["", "## Diagnostic correction", "", outcome["diagnostic_correction"]]
    model = read_json(root / "params.json")
    lines += ["", "## Input and verification limits", "",
              f"44 timestamp-usable gen_fit candidates (21 S3 + 23 UbiqLog) were split into 35 fit and 9 held-out people. "
              "One further UbiqLog participant has no parseable daily filenames and cannot be fitted; it is not treated as observed quiet. "
              f"{len(model['person_parameters'])} fit people had observed daytime evidence for parameter estimation; "
              f"{len(model['unidentifiable_people_no_observed_daytime'])} opened people lacked it and were excluded from population estimation, not assigned low-use values."]
    junit = data_root() / "logs/generator-tests.xml"
    if junit.exists():
        from xml.etree import ElementTree
        suites = list(ElementTree.parse(junit).getroot().iter("testsuite"))
        n = sum(int(s.get("tests", 0)) for s in suites)
        failures = sum(int(s.get("failures", 0)) + int(s.get("errors", 0)) for s in suites)
        lines += ["", f"Tests: {n}; failures/errors: {failures}. Evidence: logs/generator-tests.xml. Fidelity gate failures are scientific results, distinct from software test failures."]
    (data_root() / "logs/build-status.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

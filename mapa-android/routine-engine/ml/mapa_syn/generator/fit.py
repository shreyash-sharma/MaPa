"""Fit statistical duration/emission parameters on the fit people only."""
import numpy as np

from ..io import read_json, sha256, write_json
from .data import directory, fit_people, policy_hashes, spec_files
from .hsmm import fit_duration, fit_quiet, runs

TS_URL = "https://academic.oup.com/innovateage/article/10/4/igag007/8443438"
GO_URL = "https://arxiv.org/abs/1911.05683"
RELEASE_WORDING = "MaPa is pretrained on real smartphone logs. Its non-use data comes from a statistical model learned from real phone use."


def fit_model(people):
    if not people:
        raise RuntimeError("No fit people")
    info, use_all, quiet_all, unsupported = [], [], [], []
    act_hour, exp_hour = np.zeros(48), np.zeros(48)
    positive_cell = [[] for _ in range(12)]
    for p in people:
        counts, obs = p["counts"], p["observed"]
        t = np.arange(len(counts))
        daytime = obs & (t % 96 >= 28) & (t % 96 < 88)
        if not daytime.any():
            unsupported.append(p["pid"])
            continue
        weekend = (((t // 96 + p["start_dow"]) % 7) >= 5).astype(int)
        cell, hour = weekend * 6 + (t % 96) // 16, weekend * 24 + (t % 96) // 4
        records = runs(counts, obs, p["start_dow"])
        use = records[records[:, 0] == 1]
        quiet = records[records[:, 0] == 0] if p["source"] == "ubiqlog" else np.empty((0, 6))
        use_all.extend(use.tolist()); quiet_all.extend(quiet.tolist())
        exposure = np.bincount(hour[daytime], minlength=48)
        active = np.bincount(hour[daytime & (counts > 0)], minlength=48)
        act_hour += active; exp_hour += exposure
        for c in range(12):
            positive_cell[c].extend(counts[daytime & (cell == c) & (counts > 0)].tolist())
        daily_exp = daytime.reshape(-1, 96).sum(1)
        daily_count = np.where(daytime, counts, 0).reshape(-1, 96).sum(1)
        daily = 60 * daily_count[daily_exp > 0] / daily_exp[daily_exp > 0]
        rate = float(60 * counts[daytime].sum() / max(daytime.sum(), 1))
        info.append(dict(pid=p["pid"], source=p["source"], use=use, quiet=quiet, exposure=exposure,
                         active=active, rate=max(rate, .1), daily=daily,
                         intensity=float(counts[daytime & (counts > 0)].mean()) if (daytime & (counts > 0)).any() else None))
    use_all, quiet_all = np.array(use_all).reshape(-1, 6), np.array(quiet_all).reshape(-1, 6)
    if len(use_all) < 3 or len(quiet_all) < 8:
        raise RuntimeError("Insufficient source durations for learned HSMM")
    global_use = fit_duration(use_all[:, 1], use_all[:, 2], left_censored=use_all[:, 4], right_censored=use_all[:, 5])
    global_quiet = fit_quiet(quiet_all[:, 1], quiet_all[:, 2], left_censored=quiet_all[:, 4], right_censored=quiet_all[:, 5])
    if not global_quiet.get("identifiable", False):
        raise RuntimeError("Quiet tail is not identifiable from fit data; no learned generator is claimed")
    pooled_count = float(np.mean([v for cell in positive_cell for v in cell]))
    profile = (act_hour + 10 * (act_hour.sum() / max(exp_hour.sum(), 1))) / (exp_hour + 10)
    use_params, short_params, long_params, weights, intensities = [], [], [], [], []
    for c in range(12):
        u = use_all[use_all[:, 3] == c]
        q = quiet_all[quiet_all[:, 3] == c]
        local_u = fit_duration(u[:, 1], u[:, 2], global_use, u[:, 4], u[:, 5]) if len(u) else global_use
        local_q = fit_quiet(q[:, 1], q[:, 2], global_quiet, q[:, 4], q[:, 5]) if len(q) else global_quiet
        wu, wq = len(u) / (len(u) + 8), len(q) / (len(q) + 8)
        use_params.append((wu * np.array(local_u) + (1 - wu) * np.array(global_use)).tolist())
        short_params.append((wq * np.array(local_q["short"]) + (1 - wq) * np.array(global_quiet["short"])).tolist())
        long_params.append((wq * np.array(local_q["long"]) + (1 - wq) * np.array(global_quiet["long"])).tolist())
        weights.append(wq * local_q["weight"] + (1 - wq) * global_quiet["weight"])
        intensities.append((sum(positive_cell[c]) + 5 * pooled_count) / (len(positive_cell[c]) + 5))
    vectors, individual, within = [], [], []
    for p in info:
        # Every person gets their own shrunk estimate; missing cells pool to the fit population.
        u = fit_duration(p["use"][:, 1], p["use"][:, 2], global_use, p["use"][:, 4], p["use"][:, 5]) if len(p["use"]) else global_use
        q = fit_quiet(p["quiet"][:, 1], p["quiet"][:, 2], global_quiet, p["quiet"][:, 4], p["quiet"][:, 5]) if len(p["quiet"]) else global_quiet
        wu, wq = len(p["use"]) / (len(p["use"]) + 8), len(p["quiet"]) / (len(p["quiet"]) + 8)
        offset_u = wu * (u[0] - global_use[0])
        offset_s, offset_l = [wq * (q[s][0] - global_quiet[s][0]) for s in ["short", "long"]]
        ph = (p["active"] + 10 * profile) / (p["exposure"] + 10)
        intensity = p["intensity"] or pooled_count
        ic = np.log(np.maximum(intensities, 1)) + .5 * np.log(intensity / pooled_count)
        # Exposure-poor phones must not receive extreme population rate
        # estimates solely from a handful of observed bins.
        exposure = float(p["exposure"].sum())
        pooled_rate_per_bin = float(sum(sum(cell) for cell in positive_cell) / max(exp_hour.sum(), 1))
        shrunk_rate = (p["rate"] * exposure / 60 + 10 * pooled_rate_per_bin) / (exposure + 10) * 60
        vector = np.r_[np.array(use_params)[:, 0] + offset_u, np.array(short_params)[:, 0] + offset_s,
                       np.array(long_params)[:, 0] + offset_l, ic, np.log(np.maximum(ph, .001)), np.log(shrunk_rate)]
        vectors.append(vector)
        individual.append(dict(pid=p["pid"], source=p["source"], observed_daytime_bins=int(p["exposure"].sum()),
                               session_runs=len(p["use"]), quiet_runs=len(p["quiet"]), parameters=vector.tolist()))
        if len(p["daily"]) > 1:
            within.append(float(np.var(np.log(np.maximum(p["daily"], .1)))))
    vectors = np.array(vectors)
    cov = np.cov(vectors, rowvar=False) if len(vectors) > 1 else np.eye(97) * .01
    cov = .5 * cov + .5 * np.diag(np.diag(cov)) + np.eye(97) * 1e-4
    between = float(np.var(vectors[:, -1]))
    fraction = float(np.clip(between / max(between + np.mean(within or [0]), 1e-8), .05, .95))
    # Only CALIBRATION.md's cited TS mean/SD supply the age targets. Mapping
    # foreground counts to unlock/session proxies is an explicit assumption.
    mean, sd = 28.7, 21.4
    age_logvar = float(np.log1p(max(sd * sd - mean, 0) / (mean * mean)))
    return dict(model="hidden_semi_markov", revision="r2_boundary_count_calibration", states=["in_use", "short_break", "long_quiet", "night"],
                duration_conditioning="six 4-hour blocks x weekday/weekend; start-of-sojourn context",
                use=use_params, short=short_params, long=long_params, quiet_weight=weights,
                intensity=intensities, population_mean=vectors.mean(0).tolist(), population_covariance=cov.tolist(),
                person_parameters=individual, profile=profile.tolist(),
                duration_fit_diagnostics=dict(global_quiet=global_quiet, global_use=global_use),
                unidentifiable_people_no_observed_daytime=unsupported,
                censoring=dict(quiet_records=len(quiet_all), quiet_events=int((quiet_all[:, 2] == 0).sum()),
                               quiet_censored=int((quiet_all[:, 2] == 1).sum()), left_censored=int(quiet_all[:, 4].sum()),
                               right_censored=int(quiet_all[:, 5].sum()), quiet_sources=["ubiqlog"],
                               likelihood="finite discrete lognormal law; fresh interval event/survival and equilibrium residual event/integrated-survival likelihood; mixture length-bias normalized jointly"),
                age_layer=dict(daily_count_mean=mean, daily_count_sd=sd, log_rate_variance=age_logvar,
                               between_person_fraction=fraction, citation=TS_URL,
                               derivation="log(1 + (SD^2 - mean)/mean^2) for a Poisson-lognormal count proxy; empirical within/between variance allocation",
                               session_rate_multiplier="day budget divided by learned conditional count intensity; quiet sojourns scaled by renewal occupancy",
                               mapping_assumption="Foreground-session count is treated as an unlock/session proxy; these are not identical measurements.",
                               no_screen_time_target=True),
                assumptions=dict(night="Original synth_hand night bins 22:00–07:00, not learned from real night non-use",
                                 shrinkage_pseudocount=8, profile_pseudocount=10, covariance_diagonal_shrinkage=.5,
                                 covariance_floor=1e-4, lognormal_bounds=[.15, 3], duration_cap_bins=96,
                                 unsupported_censor_tail="Cells with no completed events pool to fit-only population; tail beyond observation is model extrapolation."))


def genfit(reader=None):
    root = directory()
    roles = read_json(root / "roles.json")
    if roles["policy"] != policy_hashes():
        raise RuntimeError("Generator policy changed after role preparation")
    audit = []
    model = fit_model(list(fit_people(roles, reader=reader, audit=audit)))
    from .calibrated import budget_parameters
    with np.load(root / "hand_nights.npz", allow_pickle=False) as night:
        model["count_budget"] = budget_parameters(model["age_layer"], night["counts"])
    if set(audit) & set(roles["reserved_real_check"]):
        raise RuntimeError("BLOCKER: real_check read by fitting")
    model.update(spec_revision="0.1.2", policy=policy_hashes(), roles_sha256=sha256(root / "roles.json"),
                 fit_pids=audit, heldout_pids=[p["pid"] for p in roles["people"] if p["partition"] == "heldout"],
                 reserved_real_check=roles["reserved_real_check"], rhythm_model_training_performed=False)
    write_json(root / "params.json", model)
    write_json(root / "fit-access.json", {"opened_person_pids": audit, "heldout_opened": [], "real_check_opened": [], "reader": "physically segregated fit-only npz shards"})
    age = model["age_layer"]
    card = f"""# MaPa-Syn learned generator v0.1.2

This is a statistical HSMM learned from real phone use. Its output timelines are synthetic. No rhythm model was pretrained or fine-tuned in this build.

Required future-release wording, conditional on completing the separately authorized real-log pretraining experiment (not a claim that it ran here):
> {RELEASE_WORDING}

## Fit, roles and censoring

{len(audit)} people in the seeded 80% gen_fit partition were opened. The 20% held-out partition and all 11 real_check people were not opened by genfit. See roles.json, fit-access.json and the access-isolation regression test. Role preparation reads mixed CSVs solely to create segregated shards; it is not fitting. F1–F4 read only held-out gen_fit during evaluation.

States are in_use, short_break, long_quiet and night. Lognormal sojourn distributions use six four-hour blocks and weekday/weekend context. The two zero-emission quiet states are a latent censored mixture. Quiet lengths use UbiqLog fit people only; session lengths and positive-bin counts use S3 plus UbiqLog fit people. Right-censored windows contribute survival probabilities, never event densities. Unknown starts contribute lower bounds too. Administrative night boundaries censor runs. Tails without observed events pool to fit-only estimates and remain extrapolations.

Censor counts: {model['censoring']}. Night counts come from the original hand generator's 22:00–07:00 block; this is an ASSUMPTION. The log-space population has a shrunk covariance with positive variance; every sampled parent receives a continuous random draw, not a copied parameter vector.

## Every age-scaled value and its provenance

| Parameter | Value / derivation | Citation / qualification |
| --- | --- | --- |
| Daily count target mean | 28.7 | [TS, Table 1]({TS_URL}), as rounded in CALIBRATION.md; phone unlocks/day |
| Daily count target SD | 21.4 | [TS, Table 1]({TS_URL}), as rounded in CALIBRATION.md; participant-day SD |
| Poisson-lognormal log-rate variance | {age['log_rate_variance']:.8f} = log(1 + (21.4² − 28.7)/28.7²) | Derived solely from the same [TS]({TS_URL}) mean and SD; distributional model is an ASSUMPTION |
| Between/within allocation | fit-derived fraction {age['between_person_fraction']:.8f} | Learned from fit-only observed-day count rates, not an additional older-adult aggregate |
| Per-parent and per-day arrival rate | target mean × lognormal person/day factors, variance allocated above | Derived from the same [TS]({TS_URL}) targets; foreground counts treated as unlock/session proxies is an ASSUMPTION |
| Session-rate / quiet-duration scale | renewal scale = mean(use duration) × (1−desired occupancy) / (desired occupancy × mean(quiet duration)); desired occupancy = hourly age-count budget / fitted positive-bin intensity | Derived from [TS]({TS_URL}) count targets plus fit-only HSMM parameters; runtime values saved in sampled-person parameters |
| Daily screen-time/active-minutes scale | NOT APPLIED | CALIBRATION.md cites no over-60 screen-time target. Daily use here means the count proxy, not claimed minutes of use |

No uncited over-60 aggregate, re-unlock factor, hand-tuned 1.24 multiplier, away-time or movement target is introduced. F3 reports the cited mean±SD interval and the lognormal-derived IQR separately; the latter is not a published IQR. Measured sample mean/SD are reported rather than asserted to equal targets.

[TS] Shen et al., Innovation in Aging 2026;10(4):igag007, DOI 10.1093/geroni/igag007. Small US cohort; not Indian Android ground truth. [GO] is only a cross-check: the linked arXiv paper is by **Jonas Rauber, Emily B. Fox and Leon A. Gatys**, rather than the author list in CALIBRATION.md. Its 113-person, 12-week unlock totals are not used to scale any parameter. [Verified linked paper]({GO_URL}). CALIBRATION.md itself has not been edited.

## Fidelity and limits

F1 uses Kaplan–Meier survival KS on equally masked observed windows, with event/censor counts. F2 uses hourly observed-use probabilities. F4 uses person-disjoint cross-validation for a scipy logistic classifier on equally masked day features. Held-out people do not adjust or select generator parameters. F5 tests nearest-neighbour distances on full encoded 96-bin day vectors, with unknown bins represented by −1; this is an exact-copy check, not a general anonymity guarantee. Sampling a censor-extrapolated tail does not turn it into observed real quiet.

No injected events, neural generator, rhythm-model training or upload is performed. The S2b gate is not passed unless every F1–F5 comparison passes. Failed candidates remain diagnostic, not approved benchmark data.
"""
    (root / "generator_card.md").write_text(card, encoding="utf-8")
    budget = model["count_budget"]
    with (root / "generator_card.md").open("a", encoding="utf-8") as f:
        f.write("\n## R2 mathematical corrections (supersede the initial sampler formulas above)\n\n"
                "Unknown starts now use the equilibrium residual-life distribution: P(R=r)=P(D>=r)/E[D]; "
                "double censoring integrates that survival. Quiet-mixture components are mixed before the residual normalization, "
                "so their length-biased weights are correct. Fresh right censoring retains P(D>=d). "
                "Fitting and simulation use the same rounded, capped discrete duration law including its cap atom. "
                "This uses a stationarity assumption at observation boundaries; context is that of the observed start. "
                "[Renewal background](https://ocw.mit.edu/courses/6-262-discrete-stochastic-processes-spring-2011/resources/lecture-11-renewals-strong-law-and-rewards/).\n\n"
                "The R1 Poisson-lognormal rate formula did not control the realized burst-count distribution. "
                "R2 preserves the hand night count blocks, subtracts their empirical moments from the cited whole-day target, "
                "and samples daytime counts using a lognormal-parent/Gamma-day/Poisson hierarchy. "
                "The count budget is then allocated through conditional Poisson (multinomial) emissions across an HSMM path, "
                "sampled conditional on containing an in-use bin when its budget is positive. No day is selected by a validation score.\n\n"
                f"Night mean={budget['night_mean']:.10f}, variance={budget['night_variance']:.10f}: empirical synth_hand assumptions, not age measurements. "
                f"Daytime target mean={budget['daytime_mean']:.10f}, variance={budget['daytime_variance']:.10f}; "
                f"parent rate variance={budget['parent_rate_variance']:.10f}, parent log variance={budget['parent_log_variance']:.10f}, "
                f"day Gamma shape={budget['day_gamma_shape']:.10f}. All age-scaled values derive from the same "
                f"[TS 28.7 and 21.4 moments]({TS_URL}), inherited hand night moments, and the fit-only variance allocation. "
                "No extra empirical older-adult numbers are introduced.\n\n"
                "Age mapping first reduces count-emission intensity toward the daily budget per fitted active bin, with a minimum mean of one. "
                "The remaining count-rate change rescales quiet durations using the finite discrete mean. This is explicitly a count-proxy "
                "mapping assumption; foreground events are not verified unlocks. The fitted hourly occupancy profile is a probability "
                "and is no longer divided by a separately varying hourly count intensity. Runtime emission/quiet scales are saved "
                "per sampled parent with the TS citation.\n\n"
                "The original R1 failure remains archived under experiments/r1. F1-F5 code and thresholds are frozen. "
                "The nine-person holdout is already exposed; all subsequent scores are repeated diagnostics, not untouched generalization evidence.\n")
    result = dict(stage="S2a", gate="PASS", fit_people=len(audit), heldout_people=len(model["heldout_pids"]),
                  real_check_opened=0, censoring=model["censoring"], params_sha256=sha256(root / "params.json"))
    write_json(root / "stage-S2a.json", result)
    return result

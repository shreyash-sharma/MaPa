"""Sample new parameter vectors and synthetic timelines; never injects events."""
import numpy as np

from ..bins import FIELDS, validate_rows
from ..io import read_csv, read_json, sha256, write_csv, write_json
from ..paths import data_root, version_root
from .data import directory, policy_hashes


def simulate(params, night_counts, night_observed, parents, days, seed):
    if params.get("revision") == "r2_boundary_count_calibration":
        from .calibrated import simulate_calibrated
        return simulate_calibrated(params, night_counts, night_observed, parents, days, seed)
    rng = np.random.default_rng(seed)
    mean, cov = np.array(params["population_mean"]), np.array(params["population_covariance"])
    vectors = rng.multivariate_normal(mean, cov, size=parents)
    counts = np.zeros((parents, days, 96), dtype=np.int32)
    observed = np.ones_like(counts, dtype=bool)
    states = np.full(counts.shape, 3, dtype=np.int8)
    dows = rng.integers(0, 7, size=parents)
    age = params["age_layer"]
    sigma_b = np.sqrt(age["log_rate_variance"] * age["between_person_fraction"])
    sigma_w = np.sqrt(age["log_rate_variance"] * (1 - age["between_person_fraction"]))
    night_slots = np.r_[0:28, 88:96]
    runtime = []
    for person, vector in enumerate(vectors):
        mus = vector[:36].reshape(3, 12)
        intensity = np.maximum(np.exp(np.clip(vector[36:48], -2, 7)), 1)
        shape = np.exp(np.clip(vector[48:96], -7, 0)).reshape(2, 24)
        z = (vector[-1] - mean[-1]) / np.sqrt(cov[-1, -1])
        rate = age["daily_count_mean"] * np.exp(sigma_b * z - sigma_b ** 2 / 2)
        scales = []
        for day in range(days):
            template = rng.integers(0, len(night_counts))
            counts[person, day, night_slots] = night_counts[template]
            observed[person, day, night_slots] = night_observed[template]
            weekend = int((int(dows[person]) + day) % 7 >= 5)
            target = rate * np.exp(sigma_w * rng.normal() - sigma_w ** 2 / 2)
            night_total = np.maximum(night_counts[template], 0).sum()
            budget = max(.2, target - night_total)
            profile = shape[weekend]
            normalizer = 4 * profile[7:22].sum()
            t, state = 28, None
            while t < 88:
                c = weekend * 6 + t // 16
                occupancy = np.clip(budget * profile[t // 4] / normalizer / intensity[c], .001, .95)
                w = params["quiet_weight"][c]
                local_mu = mus[:, c].copy()
                if local_mu[1] > local_mu[2]:
                    local_mu[1], local_mu[2], w = local_mu[2], local_mu[1], 1 - w
                sigmas = [params[key][c][1] for key in ["use", "short", "long"]]
                means = np.exp(np.clip(local_mu + np.square(sigmas) / 2, -3, 8))
                quiet_mean = w * means[1] + (1 - w) * means[2]
                scale = float(np.clip(means[0] * (1 - occupancy) / (occupancy * quiet_mean), .02, 100))
                scales.append(scale)
                if state is None:
                    state = 0 if rng.random() < occupancy else 1 if rng.random() < w else 2
                duration = int(np.clip(round(rng.lognormal(local_mu[state], sigmas[state]) * (scale if state else 1)), 1, 96))
                end = min(88, t + duration)
                if state == 0:
                    counts[person, day, t:end] = 1 + rng.poisson(max(0., intensity[c] - 1), end - t)
                states[person, day, t:end] = state
                t = end
                state = (1 if rng.random() < w else 2) if state == 0 else 0
        runtime.append(dict(pid=f"sl-{person:04d}", log_parameters=vector.tolist(), age_person_daily_rate=float(rate),
                            quiet_duration_scale_min=float(min(scales)), quiet_duration_scale_max=float(max(scales)),
                            scaled_value_citation=age["citation"]))
    return dict(counts=counts, observed=observed, states=states, start_dow=dows, parameters=runtime)


def write_sample(folder, result, seed):
    counts, observed = result["counts"], result["observed"]
    n, days, _ = counts.shape
    def rows():
        for p in range(n):
            for t in range(days * 96):
                day, slot = divmod(t, 96)
                known = bool(observed[p, day, slot])
                night = slot < 28 or slot >= 88
                yield dict(pid=f"sl-{p:04d}", t=t, interactions=int(counts[p, day, slot]) if known else -1,
                           phone_on=int(known), usage_ok=1, network_ok=int(known), charging=int(night and known),
                           battery=80, paused=0, moved=-1, present=int(known), coverage_min=15 if known else 0,
                           observation_basis="synthetic_learned")
    write_csv(folder / "bins.csv.gz", rows(), FIELDS)
    validate_rows(read_csv(folder / "bins.csv.gz"))
    write_csv(folder / "parents.csv", [dict(pid=f"sl-{p:04d}", start_dow=int(result["start_dow"][p]), days=days,
               observed_days=int(observed[p].any(1).sum())) for p in range(n)], ["pid", "start_dow", "days", "observed_days"])
    write_csv(folder / "people.csv", [dict(pid=f"sl-{p:04d}", source="synth_learned", age_group="over-60 calibrated aggregate, individual age unknown") for p in range(n)], ["pid", "source", "age_group"])
    write_json(folder / "sampled_parameters.json", result["parameters"])
    np.savez_compressed(folder / "sample_arrays.npz", counts=counts, observed=observed, states=result["states"], start_dow=result["start_dow"])
    write_json(folder / "summary.json", dict(source="synth_learned", parents=n, days=days, seed=seed,
               observation_basis="synthetic_learned", actual_episodes_generated=0, bins=n * days * 96,
               params_sha256=sha256(directory() / "params.json")))


def gensample(parents=200, days=120, seed=20261101):
    from .fidelity import evaluate, build_status
    root = directory()
    params = read_json(root / "params.json")
    if params["policy"] != policy_hashes():
        raise RuntimeError("Policy changed since genfit")
    if read_json(root / "stage-S2a.json")["gate"] != "PASS":
        raise RuntimeError("S2a did not pass")
    stage = data_root() / "converted" / "synth_learned.partial"
    if stage.exists():
        raise RuntimeError("Partial sample exists; recover it before rerunning")
    stage.mkdir(parents=True)
    with np.load(root / "hand_nights.npz", allow_pickle=False) as night:
        result = simulate(params, night["counts"], night["observed"], parents, days, seed)
    write_sample(stage, result, seed)
    print("Sampled timelines written; running F1-F5", flush=True)
    fidelity = evaluate(result, params)
    write_json(root / "fidelity.json", fidelity)
    passed = all(v["pass"] for v in fidelity["gates"].values())
    revision = params.get("revision", "r1")
    destination = data_root() / "converted/synth_learned" if passed else root / "failed-samples" / revision / f"seed-{seed}"
    if destination.exists():
        raise RuntimeError("Sample destination already exists; refusing overwrite")
    destination.parent.mkdir(parents=True, exist_ok=True)
    stage.rename(destination)
    outcome = dict(stage="S2b", gate="PASS" if passed else "FAIL", sample_path=str(destination), seed=seed,
                   parents=parents, days=days, fidelity=fidelity, injection_allowed=False, revision=revision,
                   validation_exposure="Previously inspected holdout; repeated diagnostic, not fresh generalization evidence")
    write_json(root / "stage-S2b.json", outcome)
    write_json(destination / "SHA256.json", {p.name: sha256(p) for p in sorted(destination.iterdir()) if p.is_file() and p.name != "SHA256.json"})
    write_json(version_root() / "review_gate.json", {"injection_allowed": False, "state": "v0.1.2_S2b_complete_review_required", "S2b_gate": outcome["gate"]})
    build_status(outcome)
    return outcome

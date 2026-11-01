"""Count-constrained HSMM sampling with finite-law-consistent renewal means.

All calibration targets are independent published moments. No validation rows
or fidelity scores are accepted by this module.
"""
import numpy as np
from .hsmm import duration_pmf


def budget_parameters(age, night_counts):
    night = np.maximum(night_counts, 0).sum(1).astype(float)
    mean = float(age["daily_count_mean"] - night.mean())
    variance = float(age["daily_count_sd"] ** 2 - night.var())
    if mean <= 0 or variance <= mean:
        raise ValueError("Inherited night moments cannot support the specified age count model")
    extra = variance - mean
    between = extra * age["between_person_fraction"]
    within = extra - between
    return dict(night_mean=float(night.mean()), night_variance=float(night.var()),
                daytime_mean=mean, daytime_variance=variance, parent_rate_variance=between,
                parent_log_variance=float(np.log1p(between / mean ** 2)),
                day_gamma_shape=float((mean ** 2 + between) / within),
                citation=age["citation"], derivation="Independent inherited night + Poisson(Gamma(lognormal-parent-rate)): subtract night moments, allocate remaining rate variance by fit-only variance fraction.")


def scaled_pmf(pmf, scale):
    durations = np.clip(np.rint(np.arange(1, 97) * scale), 1, 96).astype(int)
    return np.bincount(durations - 1, weights=pmf, minlength=96)


def residual_pmf(pmf):
    survival = np.cumsum(pmf[::-1])[::-1]
    return survival / survival.sum()


def _cell(params, vector, cell):
    mus = vector[:36].reshape(3, 12)[:, cell].copy()
    sigmas = [params[key][cell][1] for key in ["use", "short", "long"]]
    weight = params["quiet_weight"][cell]
    if mus[1] > mus[2]:
        mus[1], mus[2] = mus[2], mus[1]
        sigmas[1], sigmas[2] = sigmas[2], sigmas[1]
        weight = 1 - weight
    pmfs = [duration_pmf(mu, sigma) for mu, sigma in zip(mus, sigmas)]
    if "quiet_pmf" in params:
        mixture = np.array(params["quiet_pmf"][cell])
        short_mass = float(mixture[:4].sum())
        short = mixture.copy(); short[4:] = 0
        long = mixture.copy(); long[:4] = 0
        if short_mass > 0:
            short /= short_mass
        else:
            short[0] = 1
        if short_mass < 1:
            long /= 1 - short_mass
        else:
            long[4] = 1
        # New-parent log-space variability is retained around the pooled KM law.
        centre = np.array(params["population_mean"])[:36].reshape(3, 12)
        pmfs[1] = scaled_pmf(short, float(np.exp(np.clip(mus[1] - centre[1, cell], -1.4, 1.4))))
        pmfs[2] = scaled_pmf(long, float(np.exp(np.clip(mus[2] - centre[2, cell], -1.4, 1.4))))
        weight = short_mass
    scales = np.geomspace(.001, 512, 161)
    mixture = weight * pmfs[1] + (1 - weight) * pmfs[2]
    means = np.array([np.dot(np.arange(1, 97), scaled_pmf(mixture, scale)) for scale in scales])
    return pmfs, weight, scales, means


def _path(params, vector, shape, intensity, weekend, total, rng, cache):
    states = np.full(60, 2, np.int8)
    if total == 0:
        return states, []
    # Occupancy is a probability profile, not an event-count profile divided
    # by a different hourly intensity. This keeps the fitted profile's units.
    profile = shape[weekend]
    norm = 4 * profile[7:22].sum()
    average_intensity = sum(4 * profile[h] * intensity[weekend * 6 + h // 4] for h in range(7, 22)) / norm
    active_bins = total / average_intensity
    scale_values = []
    for attempt in range(64):
        t, state = 28, None
        states.fill(2)
        while t < 88:
            c = weekend * 6 + t // 16
            if c not in cache:
                cache[c] = _cell(params, vector, c)
            pmfs, w, scales, means = cache[c]
            umean = np.dot(np.arange(1, 97), pmfs[0])
            occupancy = np.clip(active_bins * profile[t // 4] / norm, .001, .95)
            target = np.clip(umean * (1 - occupancy) / occupancy, 1, 96)
            scale = float(np.interp(target, means, scales))
            short, long = scaled_pmf(pmfs[1], scale), scaled_pmf(pmfs[2], scale)
            actual_pmfs = [pmfs[0], short, long]
            fresh = state is not None
            if state is None:
                sm, lm = [np.dot(np.arange(1, 97), p) for p in [short, long]]
                qmean = w * sm + (1 - w) * lm
                if rng.random() < umean / (umean + qmean):
                    state = 0
                else:
                    state = 1 if rng.random() < w * sm / qmean else 2
            law = actual_pmfs[state] if fresh else residual_pmf(actual_pmfs[state])
            duration = int(np.searchsorted(np.cumsum(law), rng.random(), side="right") + 1)
            end = min(88, t + duration)
            states[t - 28:end - 28] = state
            scale_values.append(scale)
            t = end
            state = (1 if rng.random() < w else 2) if state == 0 else 0
        if np.any(states == 0):
            return states, scale_values
    raise RuntimeError("Could not sample an in-use path conditional on a positive count budget")


def simulate_calibrated(params, night_counts, night_observed, parents, days, seed):
    rng = np.random.default_rng(seed)
    mean, cov = np.array(params["population_mean"]), np.array(params["population_covariance"])
    vectors = rng.multivariate_normal(mean, cov, size=parents)
    budget = budget_parameters(params["age_layer"], night_counts)
    counts = np.zeros((parents, days, 96), np.int32)
    observed = np.ones_like(counts, bool)
    states = np.full(counts.shape, 3, np.int8)
    dows = rng.integers(0, 7, size=parents)
    night_slots = np.r_[0:28, 88:96]
    runtime = []
    for person, vector in enumerate(vectors):
        intensity = np.maximum(np.exp(np.clip(vector[36:48], -2, 7)), 1)
        shape = np.exp(np.clip(vector[48:96], -7, 0)).reshape(2, 24)
        z = (vector[-1] - mean[-1]) / np.sqrt(cov[-1, -1])
        parent_rate = budget["daytime_mean"] * np.exp(np.sqrt(budget["parent_log_variance"]) * z - .5 * budget["parent_log_variance"])
        cache, scale_values, emission_scales = {}, [], []
        for day in range(days):
            template = rng.integers(0, len(night_counts))
            counts[person, day, night_slots] = night_counts[template]
            observed[person, day, night_slots] = night_observed[template]
            k = budget["day_gamma_shape"]
            rate = rng.gamma(k, parent_rate / k)
            total = int(rng.poisson(rate))
            weekend = int((int(dows[person]) + day) % 7 >= 5)
            profile = shape[weekend]
            expected_active = 4 * profile[7:22].sum()
            raw_intensity = sum(4 * profile[h] * intensity[weekend * 6 + h // 4] for h in range(7, 22)) / expected_active
            # Counts are a proxy for unlock/session events. Calibrate the
            # count emission first, retaining fitted active-use structure
            # where the daily count budget can support it. This is a declared
            # measurement-mapping assumption, not a learned age effect.
            needed_intensity = np.clip(total / expected_active, 1, raw_intensity)
            emission_scale = float(np.clip((needed_intensity - 1) / max(raw_intensity - 1, 1e-9), 0, 1))
            aged_intensity = 1 + (intensity - 1) * emission_scale
            emission_scales.append(emission_scale)
            path, scales = _path(params, vector, shape, aged_intensity, weekend, total, rng, cache)
            states[person, day, 28:88] = path
            slots = np.flatnonzero(path == 0) + 28
            if total:
                weights = aged_intensity[weekend * 6 + slots // 16]
                counts[person, day, slots] = rng.multinomial(total, weights / weights.sum())
            scale_values.extend(scales)
            assert np.maximum(counts[person, day, 28:88], 0).sum() == total
        runtime.append(dict(pid=f"sl-{person:04d}", log_parameters=vector.tolist(),
                            age_person_daily_rate=float(parent_rate + budget["night_mean"]),
                            quiet_duration_scale_min=float(min(scale_values or [1])), quiet_duration_scale_max=float(max(scale_values or [1])),
                            age_emission_scale_min=float(min(emission_scales)), age_emission_scale_max=float(max(emission_scales)),
                            scaled_value_citation=params["age_layer"]["citation"]))
    return dict(counts=counts, observed=observed, states=states, start_dow=dows, parameters=runtime,
                calibration=budget)

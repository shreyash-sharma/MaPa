import numpy as np
import pytest

from mapa_syn.generator.hsmm import runs, fit_duration, log_likelihood, km_curve, duration_pmf, likelihood_from_pmf
from mapa_syn.generator.data import fit_people, directory, policy_hashes
from mapa_syn.generator.fit import genfit
from mapa_syn.generator.sample import simulate
from mapa_syn.generator.fidelity import privacy_distances, auc, age_count_check
from mapa_syn.io import write_json, read_json


def person(pid, source, seed):
    rng = np.random.default_rng(seed)
    counts = np.where(rng.random(28 * 96) < .35, 1 + rng.poisson(2, 28 * 96), 0).astype(np.int32)
    observed = np.ones(len(counts), bool)
    observed[100:110] = False
    return dict(pid=pid, source=source, counts=counts, observed=observed, start_dow=0)


def test_heartbeat_stop_right_censors_quiet_length():
    c, obs = np.zeros(96, int), np.zeros(96, bool)
    c[28] = 1; obs[28:31] = True
    q = runs(c, obs)
    q = q[q[:, 0] == 0]
    assert q.shape == (1, 6)
    assert q[0, 1] == 2 and q[0, 2] == 1 and q[0, 5] == 1


def test_unknown_quiet_start_is_not_completed_event():
    c, obs = np.zeros(96, int), np.zeros(96, bool)
    obs[28:33] = True; c[31:33] = 1
    q = runs(c, obs)
    q = q[q[:, 0] == 0]
    assert q[0, 4] == 1 and q[0, 2] == 1


def test_censored_likelihood_does_not_shorten_tail_as_if_complete():
    d = np.r_[np.ones(5), np.full(10, 8)]
    censored = np.r_[np.zeros(5), np.ones(10)]
    a = fit_duration(d, censored)
    b = fit_duration(d, np.zeros(len(d)))
    assert a[0] > b[0]
    assert not np.array_equal(log_likelihood(d, censored, 1, .8), log_likelihood(d, np.zeros(len(d)), 1, .8))


def test_km_censor_does_not_create_failure():
    curve = km_curve([1, 2], [False, True], np.array([0, 1, 2, 3]))
    assert np.allclose(curve, [1, .5, .5, .5])


def test_genfit_full_stage_never_reads_real_check_or_heldout(tmp_path, monkeypatch):
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    root = directory()
    rows = [dict(pid=pid, source=src, role=role, partition=part, path=f"inputs/{part}/{pid}.npz") for pid, src, role, part in [
        ("s3-fit", "s3", "gen_fit", "fit"), ("ubiq-fit", "ubiqlog", "gen_fit", "fit"),
        ("heldout-poison", "ubiqlog", "gen_fit", "heldout"), ("real-check-poison", "ubiqlog", "real_check", "real_check")]]
    write_json(root / "roles.json", dict(policy=policy_hashes(), reserved_real_check=["real-check-poison"], people=rows))
    np.savez(root / "hand_nights.npz", counts=np.zeros((1, 36)), observed=np.ones((1, 36), bool))
    opened = []
    def poison_reader(row):
        assert row["partition"] == "fit", "Evaluation data was read by genfit"
        assert row["pid"] not in {"real-check-poison", "heldout-poison"}
        opened.append(row["pid"])
        return person(row["pid"], row["source"], len(opened))
    result = genfit(reader=poison_reader)
    assert opened == ["s3-fit", "ubiq-fit"]
    assert result["gate"] == "PASS" and result["real_check_opened"] == 0
    model = read_json(root / "params.json")
    assert set(model["fit_pids"]) == set(opened)
    assert model["censoring"]["quiet_sources"] == ["ubiqlog"]


def test_reserved_pid_mislabeled_fit_is_rejected_before_any_read():
    roles = dict(reserved_real_check=["reserved"], people=[dict(pid="reserved", role="gen_fit", partition="fit")])
    def forbidden(row):
        pytest.fail("Reserved data reader was called")
    with pytest.raises(RuntimeError, match="BLOCKER"):
        list(fit_people(roles, reader=forbidden))


def test_f5_detects_identical_real_day():
    real = np.array([[0, 1, 0], [-1, 2, 3]])
    assert np.array_equal(privacy_distances(np.array([[0, 1, 0], [0, 2, 0]]), real), [0, 1])


def test_auc_ties_and_direction():
    assert auc([0, 1], [.5, .5]) == .5
    assert auc([0, 1], [.1, .9]) == 1


def test_f3_cannot_pass_by_only_using_broad_mean_sd_interval():
    result = age_count_check(np.array([8., 8., 8.]), np.array([59.]),
                             dict(daily_count_mean=28.7, daily_count_sd=21.4, citation="TS"), np.ones((3, 10)))
    assert result["cited_aggregate_interval"][0] < 8 < result["cited_aggregate_interval"][1]
    assert result["pass_"] is False


def test_sampled_people_are_new_random_parameter_draws():
    n = 97
    params = dict(population_mean=np.zeros(n).tolist(), population_covariance=(np.eye(n) * .01).tolist(),
                  use=[[0, .4]] * 12, short=[[0, .4]] * 12, long=[[1, .4]] * 12, quiet_weight=[.7] * 12,
                  age_layer=dict(daily_count_mean=28.7, log_rate_variance=.4, between_person_fraction=.5, citation="TS"))
    nights = np.zeros((1, 36), np.int32); observed = np.ones((1, 36), bool)
    a = simulate(params, nights, observed, 3, 2, 20261101)
    b = simulate(params, nights, observed, 3, 2, 20261101)
    assert np.array_equal(a["counts"], b["counts"])
    vectors = np.array([p["log_parameters"] for p in a["parameters"]])
    assert np.unique(vectors, axis=0).shape[0] == 3
    assert np.all(np.linalg.norm(vectors, axis=1) > 0)
    assert not np.array_equal(vectors[0], vectors[1])


def test_residual_likelihood_matches_exact_discrete_renewal_enumeration():
    p = np.array([.5, .5])
    ll = likelihood_from_pmf([1, 2, 2, 2], [False, False, True, True], p,
                             [True, True, False, True], [False, False, True, True])
    assert np.allclose(np.exp(ll), [2/3, 1/3, .5, 1/3])


def test_finite_duration_law_keeps_cap_mass_and_normalizes():
    p = duration_pmf(np.log(96), .5)
    assert np.isclose(p.sum(), 1)
    assert p[-1] > .49


def test_count_budget_restores_cited_whole_day_moments():
    from mapa_syn.generator.calibrated import budget_parameters
    night = np.zeros((10, 36)); night[:, 0] = np.arange(10)
    b = budget_parameters(dict(daily_count_mean=28.7, daily_count_sd=21.4, between_person_fraction=.5, citation="TS"), night)
    expected_mean = b["night_mean"] + b["daytime_mean"]
    eb2 = b["daytime_mean"] ** 2 + b["parent_rate_variance"]
    expected_variance = b["night_variance"] + b["daytime_mean"] + b["parent_rate_variance"] + eb2 / b["day_gamma_shape"]
    assert np.isclose(expected_mean, 28.7)
    assert np.isclose(expected_variance, 21.4 ** 2)

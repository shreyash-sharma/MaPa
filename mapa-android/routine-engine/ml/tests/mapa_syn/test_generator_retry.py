import numpy as np
import pytest
from mapa_syn.generator.retry import km_pmf, CANDIDATES
from mapa_syn.generator.data import fit_people


def test_retry_has_only_four_prespecified_candidate_families():
    assert len(CANDIDATES) == len(set(CANDIDATES)) == 4


def test_km_does_not_call_censoring_a_completed_length():
    records = np.array([[0, 1, 0, 1, 0, 0], [0, 2, 1, 1, 0, 1], [0, 1, 1, 1, 1, 1]], float)
    p = km_pmf(records)
    assert p[0] == .5
    assert p[1] == 0
    assert p[-1] == .5
    assert p.sum() == 1


def test_retry_fitter_never_opens_tune_test_or_real_check():
    rows = [dict(pid=group, partition=group, role="gen_fit" if group != "real_check" else group) for group in ["fit", "tune", "fidelity_test", "real_check"]]
    audit = []
    def reader(row):
        if row["partition"] != "fit":
            pytest.fail("Non-fit data opened by retry fitting")
        return row["pid"]
    assert list(fit_people(dict(people=rows, reserved_real_check=["real_check"]), reader, audit)) == ["fit"]
    assert audit == ["fit"]


def test_failed_f1_cannot_promote_candidate():
    from mapa_syn.generator.retry_report import decision
    results = [dict(candidate=c, scores=dict(gates={f'F{i}': {'pass': i != 1} for i in range(1, 6)})) for c in CANDIDATES]
    assert decision(results) == 'synth_hand'
    with pytest.raises(RuntimeError):
        decision(results[:3])

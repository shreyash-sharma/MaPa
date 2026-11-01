import numpy as np
from mapa_syn.gaps import causal_days
from mapa_syn.store import COLS, IX, Timeline


def timeline(days=30):
    a = np.zeros((days * 96, len(COLS)), dtype=np.int32)
    for c in ["phone_on", "usage_ok", "network_ok", "present"]:
        a[:, IX[c]] = 1
    a[:, IX["coverage_min"]] = 15
    a[::4, IX["interactions"]] = 1
    return Timeline("fake", "synthetic", 0, a)


def test_future_days_cannot_change_causal_statistics():
    base = timeline()
    before = list(causal_days(base))
    base.values[15 * 96:, IX["interactions"]] = 0
    after = list(causal_days(base))
    assert before[:16] == after[:16]
    assert before[16:] != after[16:]


def test_unknown_observation_breaks_gap_not_quiet():
    t = timeline(1)
    t.values[:, IX["interactions"]] = 0
    t.values[0, IX["interactions"]] = 1
    t.values[50, IX["interactions"]] = 1
    t.values[25, IX["present"]] = 0
    t.values = np.vstack([t.values, t.values.copy()])
    assert list(causal_days(t))[1]["completed_gaps"] == 0

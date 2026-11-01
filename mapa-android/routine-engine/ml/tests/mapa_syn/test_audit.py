from mapa_syn.audit import coverage_windows


def ticks(n, pid="fake-01", start=0, count=0):
    return [dict(pid=pid, t=t, interactions=count, usage_ok=1) for t in range(start, start+n)]


def test_unknown_gap_cannot_be_used_as_behaviour_window():
    rows = ticks(6) + [dict(pid="fake-01", t=6, interactions=-1, usage_ok=-1)] + ticks(6, start=7)
    assert coverage_windows(rows) == 0


def test_person_boundary_does_not_join_observation_windows():
    assert coverage_windows(ticks(6) + ticks(6, pid="fake-02")) == 0


def test_real_quiet_requires_known_zero_not_only_coverage():
    assert coverage_windows(ticks(12, count=1)) == 1
    assert coverage_windows(ticks(12, count=1), quiet_only=True) == 0
    assert coverage_windows(ticks(12), quiet_only=True) == 1


def test_missing_clock_tick_breaks_coverage():
    assert coverage_windows(ticks(6) + ticks(6, start=7)) == 0

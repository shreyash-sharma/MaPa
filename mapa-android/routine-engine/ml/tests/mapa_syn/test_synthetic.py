from mapa_syn.io import read_csv, sha256
from mapa_syn.synthetic import prepare


def test_synthetic_base_has_no_injections_and_rebuilds_identically(tmp_path, monkeypatch):
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    result = prepare(parents=3, days=28, seed=20261031)
    bins = tmp_path / "converted/synthetic/bins.csv.gz"
    before = sha256(bins)
    assert result["actual_episodes_generated"] == 0
    assert result["with_events"] is False
    assert all(r["observation_basis"] == "synthetic" for r in read_csv(bins))
    assert not list(tmp_path.rglob("episodes.jsonl"))
    prepare(parents=3, days=28, seed=20261031)
    assert sha256(bins) == before

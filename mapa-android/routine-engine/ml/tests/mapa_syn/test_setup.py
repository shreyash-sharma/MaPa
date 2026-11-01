from mapa_syn.io import read_csv, sha256, write_csv
from mapa_syn.paths import ENGINE, data_root


def test_data_outside_engine():
    assert ENGINE not in data_root().parents


def test_reject_data_inside_engine(monkeypatch):
    import pytest
    monkeypatch.setenv("MAPA_DATA", str(ENGINE / "data"))
    with pytest.raises(ValueError):
        data_root()


def test_gzip_reproducible(tmp_path):
    a, b = tmp_path / "a.csv.gz", tmp_path / "b.csv.gz"
    for path in (a, b):
        write_csv(path, [{"pid": "fake-01", "t": 0}], ["pid", "t"])
    assert sha256(a) == sha256(b)
    assert list(read_csv(a)) == [{"pid": "fake-01", "t": "0"}]

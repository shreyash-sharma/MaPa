import pytest
from mapa_syn.splits import assign_synthetic, freeze


def test_synthetic_person_split_is_seeded_and_exact():
    pids = [f"syn-{i}" for i in range(200)]
    result = assign_synthetic(pids, 20261027)
    assert result == assign_synthetic(list(reversed(pids)), 20261027)
    assert [list(result.values()).count(g) for g in ["train", "validate", "test"]] == [140, 30, 30]


def test_frozen_split_cannot_be_redrawn(tmp_path):
    path = tmp_path / "splits.csv"
    rows = [dict(pid="fake", source="synthetic", split="test")]
    freeze(rows, path)
    with pytest.raises(RuntimeError, match="refusing to redraw"):
        freeze([dict(pid="fake", source="synthetic", split="train")], path)


def test_duplicate_person_cannot_enter_two_groups(tmp_path):
    with pytest.raises(ValueError):
        freeze([dict(pid="fake", source="synthetic", split=g) for g in ["train", "test"]], tmp_path / "splits.csv")

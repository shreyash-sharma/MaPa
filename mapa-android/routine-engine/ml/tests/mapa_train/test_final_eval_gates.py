import hashlib
import json

import pytest

from mapa_train.final_eval import begin_access, fixed_timer_metrics
from mapa_train.features20 import build
from mapa_train import decision
from mapa_train import final_eval
import numpy as np


def _selection(folder):
    path = folder / "selection.json"
    path.write_text(json.dumps({
        "selection_data": "train person-OOF only",
        "decision_config": {"floor": 0.5},
        "test_opened": False,
    }))
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_test_split_cannot_be_marked_before_validation(tmp_path):
    _selection(tmp_path)
    with pytest.raises(RuntimeError, match="validation confirmation"):
        begin_access(tmp_path, "test")
    assert not (tmp_path / "test-access.json").exists()


def test_test_access_is_one_shot_and_bound_to_selection(tmp_path):
    selection_hash = _selection(tmp_path)
    (tmp_path / "validation-results.json").write_text(json.dumps({
        "selection_sha256": selection_hash,
        "confirmation_only": True,
    }))
    marker = begin_access(tmp_path, "test")
    assert marker["selection_sha256"] == selection_hash
    saved = json.loads((tmp_path / "test-access.json").read_text())
    assert saved == marker
    with pytest.raises(RuntimeError, match="one-shot"):
        begin_access(tmp_path, "test")


def test_test_access_rejects_validation_for_different_selection(tmp_path):
    _selection(tmp_path)
    (tmp_path / "validation-results.json").write_text(json.dumps({
        "selection_sha256": "not-the-selection-hash",
        "confirmation_only": True,
    }))
    with pytest.raises(RuntimeError, match="exact locked selection"):
        begin_access(tmp_path, "test")
    assert not (tmp_path / "test-access.json").exists()


def test_test_features_are_blocked_without_final_evaluator_capability():
    with pytest.raises(RuntimeError, match="one-time final evaluator"):
        build("test")


def test_heldout_decision_gate_runs_before_data_verification(tmp_path, monkeypatch):
    monkeypatch.setattr(decision, "ROOT", tmp_path)
    def unexpected_verify(_split):
        raise AssertionError("held-out hash verification ran before the model gate")
    monkeypatch.setattr(decision, "verify_data", unexpected_verify)
    with pytest.raises(RuntimeError, match="selection"):
        decision.day_views("test", "unlocked-model")


def test_eight_hour_baseline_uses_observed_daytime_exposure():
    avail = np.ones(96, dtype=bool)
    counts = np.zeros(96, dtype=np.int32)
    silence = np.zeros(96, dtype=np.float32)
    silence[28:60] = np.arange(1, 33)
    view = {
        "pid": "p1", "eid": "", "avail": avail, "tags": np.zeros(96, dtype=bool),
        "counts": counts, "silence": silence, "alert_eligible": avail.copy(),
        "expect": "none", "win": np.zeros(96, dtype=bool), "severity": "mild",
    }
    result = fixed_timer_metrics([view])
    assert result["false_alerts"] == 1
    assert result["person_weeks_observed"] == 60 / 420
    assert result["false_alerts_per_person_week"] == 7
    assert result["stale_as_fresh"] == 0


def test_zero_history_replay_keeps_first_week_stress_separate(tmp_path, monkeypatch):
    model_dir = tmp_path / "models" / final_eval.MODEL_NAME
    model_dir.mkdir(parents=True)
    (model_dir / "forecast-calibration.json").write_text(json.dumps({
        "model_weight": 0.15, "profile_weight": 0.85,
    }))
    monkeypatch.setattr(final_eval, "ROOT", tmp_path)
    model = type("M", (), {"cold_start_priors": {"population_prior": np.zeros(5), "pseudo_days": 7.0}})()

    def fake_predict(family, _model, _x, _device, _histories, counts):
        assert family == "hybrid-no-attention"
        assert np.all(counts == 0)
        return {"behaviour": np.zeros((1, 96), np.float32),
                "hazard": np.zeros((1, 96, 16), np.float32),
                "confidence": np.zeros((1, 96), np.float32)}

    monkeypatch.setattr(final_eval, "predict", fake_predict)
    avail = np.ones(96, dtype=bool)
    counts = np.zeros(96, dtype=np.int32)
    win = np.zeros(96, dtype=bool); win[28:60] = True
    silence = np.zeros(96, dtype=np.float32); silence[28:60] = np.arange(1, 33)
    view = {"pid": "p1", "eid": "episode1", "day": 10, "scenario": 1,
            "severity": "mild", "expect": "unusual_inactivity", "win": win,
            "avail": avail, "counts": counts, "silence": silence,
            "alert_eligible": avail.copy(), "score_behavior": np.zeros(96),
            "score_surprise": np.zeros(96), "confidence": np.zeros(96)}
    arrays = {"x": np.zeros((1, 96, 21), np.float32),
              "slow_history": np.zeros((1, 30, 105), np.float32)}
    groups = [{"pid": "p1", "episode_id": "episode1", "day": 10}]
    result = final_eval.zero_history_episode_replay(
        model, arrays, groups, [view],
        {"cold_start": True, "floor": 0.5, "model_weight": 1.0, "uncertainty_margin": 0.1},
        "cpu")
    assert result["status"] == "evaluated_separately"
    assert result["episodes_by_severity"]["mild"]["caught"] == 1
    assert result["episodes_by_severity"]["mild"]["episodes"] == 1


def test_shared_evaluator_reports_person_bootstrap_for_baselines():
    avail = np.ones(96, dtype=bool)
    silence = np.zeros(96, dtype=np.float32); silence[28:60] = np.arange(1, 33)
    view = {"pid": "p1", "eid": "", "day": 0, "avail": avail,
            "tags": np.zeros(96, dtype=bool), "win": np.zeros(96, dtype=bool),
            "counts": np.zeros(96, dtype=np.int32), "silence": silence,
            "score_behavior": np.zeros(96), "score_surprise": np.zeros(96),
            "confidence": np.zeros(96), "expect": "none", "injected": "none",
            "severity": "mild", "scenario": 0, "eligible": avail,
            "alert_eligible": avail, "stats_limit": 16}
    decision.stats_limits([view])
    result = decision.score_config([view], {}, {"kind": "global", "floor": 0.5,
                                                   "score_kind": "behaviour"})
    for baseline in ("model", "4h_rule", "12h_rule", "24h_rule", "statistics_only"):
        assert result[baseline]["people"] == 1
        assert result[baseline]["fa_95ci"] is not None


def test_markdown_report_retains_baselines_and_separate_observation_metrics():
    metric = {"fa_per_week": 1.25, "fa_95ci": [1.0, 1.5], "catch": {},
              "caught_counts": {}, "episode_counts": {}}
    report = final_eval.render_report({
        "split": "test", "model": "locked", "family": "hybrid-no-attention",
        "people": 39, "rows": 100, "selection_sha256": "s", "checkpoint_sha256": "c",
        "split_sha256": "d", "test_opened": True, "feature_contract": 21,
        "alerts": {"model": metric, "4h_rule": metric, "12h_rule": metric,
                   "24h_rule": metric, "statistics_only": metric,
                   "observation_episodes_separate": {"mild": {"caught": 2, "episodes": 3}}},
        "eight_hour_rule": {"false_alerts_per_person_week": 2.0,
                             "false_alerts_95ci_person_bootstrap": [1.0, 3.0],
                             "behavior_episodes": {}},
        "shipped_cnn_baseline": {"status": "ineligible", "reason": "pid overlap"},
        "cold_start_curve": {"strata": {}},
        "cold_start_unwell_first_week_stress": {"status": "evaluated_separately"},
        "next_use_raw": {}, "next_use_blended": {},
    })
    assert "Shipped CNN status: **ineligible**" in report
    assert "observation_episodes_separate" in report
    assert "Zero-history first-week injected stress replay" in report
    assert "1.2500 [1.0000, 1.5000]" in report

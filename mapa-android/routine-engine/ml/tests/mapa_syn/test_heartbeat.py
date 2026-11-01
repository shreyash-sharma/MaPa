import json
import zipfile
from argparse import Namespace
from datetime import datetime, timezone

import pytest

from mapa_syn.bins import validate_rows
from mapa_syn.episodes import validate_observation_basis
from mapa_syn.heartbeat import FULL, Person, candidate_rows, minute_masks, prepare_person, probe, read_ubiqlog, sensor_time, window_starts
from mapa_syn.io import read_csv, sha256, write_json
from mapa_syn.pipeline import run


def test_duplicate_sensor_records_do_not_create_minute_coverage():
    masks = minute_masks([0] * 1000)
    assert masks == {0: 1}
    assert masks[0] != FULL


def test_any_event_per_bin_is_not_a_full_heartbeat_window():
    masks = minute_masks([b * 900 for b in range(12)])
    assert list(window_starts(set(masks))) == [0]
    assert list(window_starts({b for b, mask in masks.items() if mask == FULL})) == []


def test_clock_gap_breaks_three_hour_heartbeat():
    assert list(window_starts(set(range(6)) | set(range(7, 13)))) == []


def test_zero_fill_requires_all_fifteen_minutes():
    person = Person()
    person.event("inertial", 86400)
    for minute in range(15, 30):
        person.event("inertial", 86400 + minute * 60)
    prepared = prepare_person(person, ["inertial"])
    rows = list(candidate_rows({"private": person}, {"private": prepared}, {"private": "s3-p001"}))
    assert rows[0]["interactions"] == -1 and rows[0]["observation_basis"] == "unknown"
    assert rows[1]["interactions"] == 0 and rows[1]["observation_basis"] == "heartbeat_inferred"
    validate_rows(rows)


def test_sensor_channels_can_supply_complementary_minutes():
    p = Person()
    for minute in range(15):
        p.event("WiFi" if minute % 2 else "Activity", 86400 + minute * 60)
    prepared = prepare_person(p, ["WiFi", "Activity"])
    assert prepared["masks"]["union"][96] == FULL
    assert prepared["masks"]["WiFi"][96] != FULL


@pytest.mark.parametrize("value,hour", [
    ("Monday, November 3, 2014 11:59:12 PM Pacific Standard Time", 23),
    ("Monday, January 13, 2014 3:23:00 PM GMT+00:00", 15),
    ("Thursday, December 5, 2013 12:00:00 AM Iran Standard Time", 0),
    ("11-4-2014 06:00:05", 6),
    ("Wednesday, 1 January 2014 00:00:51 Iran Standard Time", 0),
    ("Wednesday, 1 January 2014 23:59:51 Iran Standard Time", 23),
])
def test_sensor_local_wall_clock_formats(value, hour):
    assert sensor_time(value).hour == hour


def test_activity_interval_is_not_expanded_into_heartbeat(tmp_path, monkeypatch):
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    raw = tmp_path / "raw/ubiqlog"
    raw.mkdir(parents=True)
    record = {"Activity": {"start": "11-4-2014 06:00:00", "end": "11-4-2014 09:00:00", "type": "PRIVATE_LABEL"}}
    with zipfile.ZipFile(raw / "ubiqlog.zip", "w") as archive:
        archive.writestr("UbiqLog4UCI/private/log_11-4-2014.txt", json.dumps(record))
    people, diagnostics, sensors = read_ubiqlog()
    p = people["private"]
    assert len(p.events["Activity"]) == 1
    assert all(mask != FULL for mask in prepare_person(p, sensors)["masks"]["union"].values())


def test_ubiqlog_probe_deduplicates_access_points_and_keeps_only_timing(tmp_path, monkeypatch):
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    raw = tmp_path / "raw/ubiqlog"
    raw.mkdir(parents=True)
    records = []
    for minute in range(15):
        for access_point in range(5):
            records.append({"WiFi": {"SSID": f"PRIVATE_SSID_{access_point}", "time": f"11-4-2014 08:{minute:02d}:00"}})
    records.append({"Application": {"ProcessName": "PRIVATE_APP", "Start": "11-4-2014 08:03:00", "End": "11-4-2014 08:04:00"}})
    records.append({"WiFi": {"SSID": "PRIVATE_SSID", "time": "11-4-1970 08:00:00"}})
    with zipfile.ZipFile(raw / "ubiqlog.zip", "w") as archive:
        archive.writestr("UbiqLog4UCI/private/log_11-4-2014.txt", "\n".join(json.dumps(r) for r in records))
    write_json(raw / "SHA256.json", {"ubiqlog.zip": sha256(raw / "ubiqlog.zip")})
    result = probe("ubiqlog")
    assert result["strict_heartbeat_bins"] == 1
    assert result["diagnostics"]["invalid_time_WiFi"] == 1
    metrics = list(read_csv(tmp_path / "probes/heartbeat/ubiqlog/people.csv"))
    wifi = next(r for r in metrics if r["sensor"] == "WiFi")
    assert wifi["unique_event_timestamps"] == "15"
    assert wifi["duplicate_or_coincident_timestamps"] == "60"
    bins = list(read_csv(tmp_path / "converted/ubiqlog/heartbeat_inferred/bins.csv.gz"))
    observed = [r for r in bins if r["observation_basis"] == "heartbeat_inferred"]
    assert len(observed) == 1 and observed[0]["interactions"] == "1"
    assert "PRIVATE_SSID" not in str(bins) and "PRIVATE_APP" not in str(bins)


def test_s3_probe_does_not_retain_sensor_values_and_labels_inference(tmp_path, monkeypatch):
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    raw = tmp_path / "raw/s3"
    raw.mkdir(parents=True)
    start = datetime(2020, 11, 23, 8, tzinfo=timezone.utc)
    lines = []
    for delta in range(0, 3 * 3600, 20):
        fields = [str(int(start.timestamp() * 1000) + delta * 1000)] + ["PRIVATE_INERTIAL_VALUE"] * 40 + ["lun.", str(8 * 3600 + delta)]
        lines.append(",".join(fields))
    stat = [str(int(start.timestamp() * 1000) + 60000), "1", "2"] + ["PRIVATE_APP_ID"] * 11 + ["lun.", str(8 * 3600 + 60)]
    lines.append(",".join(stat))
    with zipfile.ZipFile(raw / "S3Dataset.zip", "w") as archive:
        archive.writestr("S3Dataset/info/S3dataset.txt", "authvoicedata_000001 private-original-id 0 0 0 st")
        archive.writestr("S3Dataset/data/authvoicedata_000001", "\n".join(lines))
    write_json(raw / "SHA256.json", {"S3Dataset.zip": sha256(raw / "S3Dataset.zip")})
    result = probe("s3")
    assert result["strict_heartbeat_bins"] == 12
    assert result["windows_3h_strict"] == 1
    rows = list(read_csv(tmp_path / "converted/s3/heartbeat_inferred/bins.csv.gz"))
    observed = [r for r in rows if r["observation_basis"] == "heartbeat_inferred"]
    assert len(observed) == 12
    assert observed[0]["interactions"] == "2"
    assert all(r["interactions"] == "0" for r in observed[1:])
    for directory in [tmp_path / "probes", tmp_path / "converted"]:
        for path in directory.rglob("*"):
            if path.is_file() and path.suffix != ".gz":
                content = path.read_text(encoding="utf-8")
                assert "PRIVATE_INERTIAL_VALUE" not in content and "PRIVATE_APP_ID" not in content and "private-original-id" not in content
    assert not list(tmp_path.rglob("episodes.jsonl"))


def test_episode_requires_observation_provenance():
    with pytest.raises(ValueError):
        validate_observation_basis({"source": "s3"})
    with pytest.raises(ValueError):
        validate_observation_basis({"source": "s3", "observation_basis": "source_recorded"})
    validate_observation_basis({"source": "s3", "observation_basis": "heartbeat_inferred"})
    validate_observation_basis({"source": "synthetic", "observation_basis": "synthetic"})


def test_injection_blocked_while_review_is_pending(tmp_path, monkeypatch):
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    with pytest.raises(RuntimeError, match="waiting for user review"):
        run(Namespace(stage="inject"))

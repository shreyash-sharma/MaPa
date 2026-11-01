from datetime import datetime, timezone
import json
import zipfile

import pytest

from mapa_syn.bins import FIELDS, validate_rows
from mapa_syn.io import read_csv
from mapa_syn.sources.common import finish
from mapa_syn.sources.s3 import parse_stat
from mapa_syn.sources.ubiqlog import CoveredMinutes, session_minutes


def test_s3_uses_foreground_total_not_identifier():
    stamp = int(datetime(2020, 11, 23, 8, 44, 0, tzinfo=timezone.utc).timestamp() * 1000)
    row = f"{stamp},1,2,9999,1,9999,9999,9999,88,7,9999,61,1923932,1139324,lun.,35040"
    minute, count, _ = parse_stat(row)
    assert count == 2
    assert minute % 1440 == 9 * 60 + 43


def test_s3_voice_and_inertial_not_parsed():
    assert parse_stat(",".join(["0"] * 514)) is None
    assert parse_stat(",".join(["0"] * 43)) is None


def test_s3_serialization_lag_uses_embedded_feature_minute():
    row = "1607097712607,1,2,9999,1,9999,9999,9999,88,7,9999,61,1923932,1139324,vie.,61300"
    minute, count, _ = parse_stat(row)
    assert minute % 1440 == 61300 // 60 - 1
    assert count == 2


def test_ubiqlog_partial_minutes_not_full_coverage():
    minutes = session_minutes("11-29-2013 07:49:07", "11-29-2013 07:58:34")
    assert len(minutes) == 8
    assert minutes.start % 1440 == 7 * 60 + 50


def test_ubiqlog_reversed_session_rejected():
    with pytest.raises(ValueError):
        session_minutes("11-29-2013 08:00:00", "11-29-2013 07:00:00")


def test_interval_union_preserves_gaps_and_deduplicates_sessions():
    minutes = CoveredMinutes([(5, 10), (7, 12), (20, 10**9)])
    assert minutes.intervals == [(5, 12), (20, 10**9)]
    assert minutes.get(6) == 1
    assert minutes.get(15) is None
    assert minutes.get(10**9) is None


def test_unknown_minutes_never_become_quiet(tmp_path, monkeypatch):
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    day = datetime(2020, 1, 1).toordinal()
    minutes = {day * 1440 + m: 0 for m in range(15)}
    minutes[day * 1440 + 30] = 2
    finish("fake", {"private-original-id": minutes}, {}, "test")
    rows = list(read_csv(tmp_path / "converted/fake/bins.csv.gz"))
    assert rows[0]["interactions"] == "0" and rows[0]["usage_ok"] == "1"
    assert rows[1]["interactions"] == "-1" and rows[1]["usage_ok"] == "-1"
    assert rows[2]["interactions"] == "2" and rows[2]["usage_ok"] == "-1"
    assert "private-original-id" not in (tmp_path / "converted/fake/bins.csv.gz").name
    assert all("private-original-id" not in str(row) for row in rows)


def test_partial_coverage_cannot_be_certified():
    row = dict.fromkeys(FIELDS, -1)
    row.update(pid="fake", t=0, usage_ok=1, coverage_min=14)
    with pytest.raises(ValueError, match="Partial"):
        validate_rows([row])


def test_ubiqlog_real_record_boundary_filters_private_payloads(tmp_path, monkeypatch):
    from mapa_syn.sources.ubiqlog import convert
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    raw = tmp_path / "raw" / "ubiqlog"
    raw.mkdir(parents=True)
    with zipfile.ZipFile(raw / "ubiqlog.zip", "w") as archive:
        records = [42, {"WiFi": {"private": "secret-network"}},
                   {"Application": {"ProcessName": "secret-app", "Start": "11-29-2013 08:00:00", "End": "11-29-2013 08:30:00"}},
                   {"Application": {"Start": "bad", "End": "bad"}}]
        archive.writestr("UbiqLog4UCI/original-private-id/log_11-29-2013.txt",
                         "\n".join(json.dumps(r) for r in records))
    result = convert()
    assert result["fully_observed_bins"] == 2
    assert result["diagnostics"]["malformed_rows"] == 1
    assert result["diagnostics"]["invalid_intervals"] == 1
    for path in (tmp_path / "converted" / "ubiqlog").iterdir():
        if path.suffix != ".gz":
            text = path.read_text(encoding="utf-8")
            assert "secret-app" not in text and "secret-network" not in text and "original-private-id" not in text


def test_ubiqlog_inconsistent_dates_cannot_create_observed_days(tmp_path, monkeypatch):
    from mapa_syn.sources.ubiqlog import convert
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    raw = tmp_path / "raw" / "ubiqlog"
    raw.mkdir(parents=True)
    record = {"Application": {"Start": "11-29-2013 08:00:00", "End": "12-3-2013 08:00:00"}}
    with zipfile.ZipFile(raw / "ubiqlog.zip", "w") as archive:
        archive.writestr("UbiqLog4UCI/fake/log_11-29-2013.txt", json.dumps(record))
    result = convert()
    assert result["fully_observed_bins"] == 0


def test_corrupt_raw_input_cannot_replace_converted_outputs(tmp_path, monkeypatch):
    from argparse import Namespace
    from mapa_syn.io import write_json
    from mapa_syn.pipeline import run
    monkeypatch.setenv("MAPA_DATA", str(tmp_path))
    raw = tmp_path / "raw" / "s3"
    raw.mkdir(parents=True)
    (raw / "S3Dataset.zip").write_bytes(b"tampered")
    write_json(raw / "SHA256.json", {"S3Dataset.zip": "0" * 64})
    converted = tmp_path / "converted" / "s3" / "bins.csv.gz"
    converted.parent.mkdir(parents=True)
    converted.write_bytes(b"previous-output")
    with pytest.raises(RuntimeError, match="integrity mismatch"):
        run(Namespace(stage="convert", source="s3"))
    assert converted.read_bytes() == b"previous-output"

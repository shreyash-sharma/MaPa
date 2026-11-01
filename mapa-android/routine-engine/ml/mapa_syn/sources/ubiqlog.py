"""Retain application-session aggregate activity, never content or identifiers."""
import json
import zipfile
from collections import defaultdict
from datetime import datetime
from bisect import bisect_right

from ..paths import data_root
from .common import finish


def parse_time(value):
    return datetime.strptime(value, "%m-%d-%Y %H:%M:%S")


def local_seconds(dt):
    return dt.toordinal() * 86400 + dt.hour * 3600 + dt.minute * 60 + dt.second


class DateConsistencyError(ValueError):
    pass


def session_minutes(start, end, file_day=None):
    start_dt, end_dt = parse_time(start), parse_time(end)
    if file_day is not None and any(abs((dt.date() - file_day).days) > 1 for dt in (start_dt, end_dt)):
        raise DateConsistencyError("Session dates inconsistent with daily source file")
    a, b = local_seconds(start_dt), local_seconds(end_dt)
    if b < a:
        raise ValueError("Application session ends before start")
    # Only complete minutes inside a known foreground session certify activity.
    # Do not turn absence of Application records into idle use based on WiFi logs.
    return range((a + 59) // 60, b // 60)


class CoveredMinutes:
    """Compact interval union: malformed long sessions cannot exhaust memory."""
    def __init__(self, intervals):
        self.intervals = []
        for start, end in sorted(intervals):
            if end <= start:
                continue
            if self.intervals and start <= self.intervals[-1][1]:
                self.intervals[-1] = (self.intervals[-1][0], max(end, self.intervals[-1][1]))
            else:
                self.intervals.append((start, end))
        self.starts = [a for a, b in self.intervals]

    def __bool__(self):
        return bool(self.intervals)

    @property
    def first_minute(self):
        return self.intervals[0][0]

    @property
    def last_minute(self):
        return self.intervals[-1][1] - 1

    def get(self, minute, default=None):
        i = bisect_right(self.starts, minute) - 1
        return 1 if i >= 0 and minute < self.intervals[i][1] else default


def convert():
    people = defaultdict(list)
    diagnostics = dict(application_rows=0, other_rows_dropped=0, malformed_rows=0, invalid_intervals=0,
                       zero_length_sessions=0, longest_session_minutes=0, date_inconsistent_sessions=0,
                       invalid_file_dates=0)
    with zipfile.ZipFile(data_root() / "raw" / "ubiqlog" / "ubiqlog.zip") as z:
        for name in sorted(z.namelist()):
            parts = name.split("/")
            if len(parts) != 3 or parts[0] != "UbiqLog4UCI" or not parts[2].startswith("log_") or not name.endswith(".txt"):
                continue
            person = parts[1]
            try:
                file_day = datetime.strptime(parts[2][4:-4], "%m-%d-%Y").date()
            except ValueError:
                diagnostics["invalid_file_dates"] += 1
                continue
            # Create a person even if every session is unusably short.
            people[person]
            with z.open(name) as f:
                for line in f:
                    # Most bytes are excluded WiFi/location/etc. Avoid parsing private
                    # payloads that cannot contribute to application aggregates.
                    if b'"Application"' not in line and line.lstrip().startswith(b"{"):
                        diagnostics["other_rows_dropped"] += 1
                        continue
                    try:
                        record = json.loads(line)
                    except (ValueError, UnicodeError):
                        diagnostics["malformed_rows"] += 1
                        continue
                    if not isinstance(record, dict):
                        diagnostics["malformed_rows"] += 1
                        continue
                    app = record.get("Application")
                    if not isinstance(app, dict):
                        diagnostics["other_rows_dropped"] += 1
                        continue
                    diagnostics["application_rows"] += 1
                    try:
                        minutes = session_minutes(app["Start"], app["End"], file_day)
                    except DateConsistencyError:
                        diagnostics["date_inconsistent_sessions"] += 1
                        continue
                    except (ValueError, KeyError, TypeError):
                        diagnostics["invalid_intervals"] += 1
                        continue
                    if not minutes:
                        diagnostics["zero_length_sessions"] += 1
                    else:
                        people[person].append((minutes.start, minutes.stop))
                        diagnostics["longest_session_minutes"] = max(diagnostics["longest_session_minutes"], len(minutes))
    compact = {person: CoveredMinutes(intervals) for person, intervals in people.items()}
    return finish("ubiqlog", compact, diagnostics,
        "Use Application Start/End intervals as foreground-activity evidence. Each completely enclosed "
        "minute contributes one active-minute count (overlapping sessions deduplicated). Only bins "
        "with all 15 minutes enclosed are fully observed. Session absence is unknown, never a zero. "
        "Discard app identifiers and all call, SMS, location, WiFi, Bluetooth and other payloads. "
        "Ancillary sensor events do not prove that application access was enabled. "
        "Reject sessions whose start or end calendar day differs by more than one day from "
        "the enclosing daily log filename. This conservative consistency guard quarantines "
        "bad clock/default dates rather than expanding them into years of apparent coverage; "
        "legitimate unusually long sessions can also be excluded. Rejections are counted. "
        "The proxy is active minutes rather than interaction events; this is documented source heterogeneity.")

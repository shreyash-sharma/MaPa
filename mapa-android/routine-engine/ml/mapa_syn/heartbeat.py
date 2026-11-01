"""Timestamp-only heartbeat probe. Sensor payloads never enter derived files."""
import json
import re
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from functools import lru_cache

from .bins import FIELDS, validate_rows
from .io import read_csv, read_json, sha256, write_csv, write_json
from .paths import data_root, version_root
from .sources.s3 import parse_stat
from .sources.ubiqlog import DateConsistencyError, parse_time, session_minutes

FULL = (1 << 15) - 1
UBIQ_SENSORS = ("WiFi", "Bluetooth", "Location", "Activity")
HEADER = re.compile(rb'^\s*\{\s*"(WiFi|Bluetooth|Location|Activity|Application)"\s*:')
TIME_VALUE = re.compile(rb'"time"\s*:\s*("(?:[^"\\]|\\.)*")', re.I)
END_VALUE = re.compile(rb'"end"\s*:\s*("(?:[^"\\]|\\.)*")', re.I)
VERBOSE_TIME = re.compile(r'^\w+,\s+(\w+)\s+(\d{1,2}),\s+(\d{4})\s+(\d{1,2}):(\d{2}):(\d{2})\s+(AM|PM)\b', re.I)
DAY_FIRST_TIME = re.compile(r'^\w+,\s+(\d{1,2})\s+(\w+)\s+(\d{4})\s+(\d{1,2}):(\d{2}):(\d{2})(?:\s+(AM|PM)\b)?', re.I)
MONTHS = {name.lower(): i for i, name in enumerate(
    ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"], 1)}


def seconds(dt):
    return dt.toordinal() * 86400 + dt.hour * 3600 + dt.minute * 60 + dt.second


@lru_cache(maxsize=8192)
def sensor_time(value):
    """Keep source local wall time; timezone names are not guessed as offsets."""
    if not isinstance(value, str):
        raise ValueError("Sensor timestamp must be a string")
    match = VERBOSE_TIME.match(value)
    if match:
        month, day, year, hour, minute, second, period = match.groups()
        hour = int(hour)
        if not 1 <= hour <= 12:
            raise ValueError("Invalid 12-hour clock")
        return datetime(int(year), MONTHS[month.lower()], int(day), hour % 12 + (12 if period.upper() == "PM" else 0), int(minute), int(second))
    match = DAY_FIRST_TIME.match(value)
    if match:
        day, month, year, hour, minute, second, period = match.groups()
        hour = int(hour)
        if period:
            if not 1 <= hour <= 12:
                raise ValueError("Invalid 12-hour clock")
            hour = hour % 12 + (12 if period.upper() == "PM" else 0)
        return datetime(int(year), MONTHS[month.lower()], int(day), hour, int(minute), int(second))
    return parse_time(value)


def s3_heartbeat_time(timestamp, local_seconds):
    epoch = int(timestamp) // 1000
    local_seconds = int(local_seconds)
    if not 0 <= local_seconds < 86400:
        raise ValueError("Invalid local seconds")
    delta = (local_seconds - epoch % 86400 + 43200) % 86400 - 43200
    offset = round(delta / 900) * 900
    if abs(offset - delta) > 120:
        raise ValueError("S3 clock cannot be reconciled")
    local = datetime.fromtimestamp(epoch + offset, timezone.utc)
    day = local.toordinal()
    difference = local_seconds - (local.hour * 3600 + local.minute * 60 + local.second)
    if difference > 43200:
        day -= 1
    elif difference < -43200:
        day += 1
    return day * 86400 + local_seconds


def minute_masks(event_seconds):
    masks = {}
    for second in event_seconds:
        b = second // 900
        masks[b] = masks.get(b, 0) | 1 << (second // 60 % 15)
    return masks


def window_starts(bin_set, length=12):
    run = 0
    previous = None
    for b in sorted(bin_set):
        run = run + 1 if previous is not None and b == previous + 1 else 1
        if run >= length:
            yield b - length + 1
        previous = b


def longest_run(bin_set):
    best = run = 0
    previous = None
    for b in sorted(bin_set):
        run = run + 1 if previous is not None and b == previous + 1 else 1
        best = max(best, run)
        previous = b
    return best


def percentile(sorted_values, fraction):
    if not sorted_values:
        return None
    index = (len(sorted_values) - 1) * fraction
    lo = int(index)
    hi = min(lo + 1, len(sorted_values) - 1)
    return sorted_values[lo] * (hi - index) + sorted_values[hi] * (index - lo) if hi != lo else sorted_values[lo]


@dataclass
class Person:
    events: dict = field(default_factory=lambda: defaultdict(set))
    foreground: dict = field(default_factory=dict)
    foreground_latest: dict = field(default_factory=dict)
    recorded_days: set = field(default_factory=set)
    raw_events: Counter = field(default_factory=Counter)

    def event(self, sensor, second):
        self.events[sensor].add(second)
        self.raw_events[sensor] += 1
        self.recorded_days.add(second // 86400)


def read_s3():
    people = {}
    diagnostics = Counter()
    with zipfile.ZipFile(data_root() / "raw/s3/S3Dataset.zip") as z:
        ownership = {row[0]: row[1] for row in (line.split() for line in z.read("S3Dataset/info/S3dataset.txt").decode("ascii").splitlines())}
        # Use the same participant ordering as the conservative statistics conversion.
        for name in sorted(z.namelist()):
            if not name.startswith("S3Dataset/data/authvoicedata_"):
                continue
            original = ownership[name.rsplit("/", 1)[1]]
            person = people.setdefault(original, Person())
            for line in z.read(name).splitlines():
                width = line.count(b",") + 1
                if width == 43:
                    # Only first and last metadata tokens are extracted. No numeric
                    # inertial feature is parsed, copied, hashed separately or saved.
                    try:
                        event = s3_heartbeat_time(line.split(b",", 1)[0], line.rsplit(b",", 1)[1])
                    except (ValueError, OverflowError, OSError):
                        diagnostics["invalid_inertial_time"] += 1
                        continue
                    person.event("inertial", event)
                    diagnostics["inertial_vectors"] += 1
                elif width == 16:
                    try:
                        minute, count, timestamp = parse_stat(line.decode("latin-1"))
                    except (ValueError, OverflowError, OSError):
                        diagnostics["invalid_foreground_time"] += 1
                        continue
                    if timestamp > person.foreground_latest.get(minute, -1):
                        person.foreground_latest[minute] = timestamp
                        person.foreground[minute] = count
                        person.recorded_days.add(minute // 1440)
                    diagnostics["foreground_records"] += 1
                elif width == 514:
                    diagnostics["voice_vectors_ignored"] += 1
                else:
                    diagnostics["unknown_vector_width"] += 1
    return people, diagnostics, ["inertial"]


def read_ubiqlog():
    people = {}
    diagnostics = Counter()
    with zipfile.ZipFile(data_root() / "raw/ubiqlog/ubiqlog.zip") as z:
        files = [name for name in sorted(z.namelist()) if name.startswith("UbiqLog4UCI/") and "/log_" in name and name.endswith(".txt")]
        for number, name in enumerate(files, 1):
            parts = name.split("/")
            if len(parts) != 3:
                continue
            try:
                file_day = datetime.strptime(parts[2][4:-4], "%m-%d-%Y").date()
            except ValueError:
                diagnostics["invalid_file_dates"] += 1
                continue
            person = people.setdefault(parts[1], Person())
            person.recorded_days.add(file_day.toordinal())
            with z.open(name) as f:
                for line in f:
                    header = HEADER.match(line)
                    if not header:
                        diagnostics["non_probe_records_ignored"] += 1
                        continue
                    sensor = header.group(1).decode("ascii")
                    if sensor == "Application":
                        try:
                            app = json.loads(line)["Application"]
                            # Validate dates, but do not expand the session interval.
                            session_minutes(app["Start"], app["End"], file_day)
                            start = parse_time(app["Start"])
                        except (ValueError, KeyError, TypeError, UnicodeError):
                            diagnostics["invalid_application_records"] += 1
                            continue
                        minute = seconds(start) // 60
                        person.foreground[minute] = person.foreground.get(minute, 0) + 1
                        diagnostics["foreground_records"] += 1
                        continue
                    # Extract only the timestamp string, never SSID, coordinates,
                    # device addresses, activity labels or confidence values.
                    match = (END_VALUE if sensor == "Activity" else TIME_VALUE).search(line)
                    if not match:
                        diagnostics[f"missing_time_{sensor}"] += 1
                        continue
                    try:
                        time = sensor_time(json.loads(match.group(1)))
                        if abs((time.date() - file_day).days) > 1:
                            raise DateConsistencyError("Sensor date inconsistent with log")
                    except (ValueError, KeyError, TypeError, UnicodeError):
                        diagnostics[f"invalid_time_{sensor}"] += 1
                        continue
                    person.event(sensor, seconds(time))
                    diagnostics[f"accepted_records_{sensor}"] += 1
            if number % 250 == 0:
                print(f"Heartbeat probe: {number}/{len(files)} UbiqLog daily files read", flush=True)
    return people, diagnostics, list(UBIQ_SENSORS)


def prepare_person(person, sensors):
    masks = {sensor: minute_masks(person.events.get(sensor, set())) for sensor in sensors}
    union = {}
    for values in masks.values():
        for b, mask in values.items():
            union[b] = union.get(b, 0) | mask
    masks["union"] = union
    days = set(person.recorded_days)
    days.update(m // 1440 for m in person.foreground)
    if not days:
        return None
    return {"first": min(days), "last": max(days), "masks": masks}


def person_metrics(pid, sensor, person, prepared):
    first, last = prepared["first"], prepared["last"]
    masks = prepared["masks"][sensor]
    full = {b for b, mask in masks.items() if mask == FULL}
    full_days = {b // 96 for b in full}
    starts = list(window_starts(full))
    daytime = [b for b in starts if b // 96 - first >= 14 and 32 <= b % 96 <= 72]
    events = set().union(*person.events.values()) if sensor == "union" else person.events.get(sensor, set())
    times = sorted(events)
    raw_events = sum(person.raw_events.values()) if sensor == "union" else person.raw_events[sensor]
    gaps = sorted(b - a for a, b in zip(times, times[1:]))
    recorded_days = person.recorded_days
    recorded_full = sum(b // 96 in recorded_days for b in full)
    recorded_any = sum(b // 96 in recorded_days for b in masks)
    quiet_full = {b for b in full if sum(person.foreground.get(m, 0) for m in range(b * 15, b * 15 + 15)) == 0}
    return dict(pid=pid, sensor=sensor, calendar_days=last - first + 1, recorded_days=len(recorded_days),
                days_with_strict_heartbeat=len(full_days), bins_with_any_heartbeat=len(masks),
                strict_heartbeat_bins=len(full), share_calendar_day_bins_any=len(masks) / ((last - first + 1) * 96),
                share_calendar_day_bins_strict=len(full) / ((last - first + 1) * 96),
                share_recorded_day_bins_any=recorded_any / (len(recorded_days) * 96) if recorded_days else None,
                share_recorded_day_bins_strict=recorded_full / (len(recorded_days) * 96) if recorded_days else None,
                windows_3h_any_event_per_bin=sum(1 for _ in window_starts(set(masks))),
                windows_3h_strict=len(starts), windows_3h_strict_after_warmup_daytime=len(daytime),
                longest_strict_run_minutes=longest_run(full) * 15,
                eligible_28_observed_days=len(full_days) >= 28,
                windows_3h_eligible_after_warmup_daytime=len(daytime) if len(full_days) >= 28 else 0,
                inferred_zero_foreground_windows_3h=sum(1 for _ in window_starts(quiet_full)),
                unique_event_timestamps=len(times), raw_sensor_records=raw_events,
                duplicate_or_coincident_timestamps=raw_events - len(times), median_gap_seconds=percentile(gaps, .5),
                p90_gap_seconds=percentile(gaps, .9),
                fraction_gaps_le_20s=sum(g <= 20 for g in gaps) / len(gaps) if gaps else None,
                fraction_gaps_le_60s=sum(g <= 60 for g in gaps) / len(gaps) if gaps else None)


def candidate_rows(people, prepared, pids):
    for original in sorted(people):
        if original not in prepared:
            continue
        person, p = people[original], prepared[original]
        union = p["masks"]["union"]
        for t in range((p["last"] - p["first"] + 1) * 96):
            b = p["first"] * 96 + t
            mask = union.get(b, 0)
            full = mask == FULL
            row = dict.fromkeys(FIELDS, -1)
            row.update(pid=pids[original], t=t, interactions=sum(person.foreground.get(m, 0) for m in range(b * 15, b * 15 + 15)) if full else -1,
                       phone_on=1 if full else -1, usage_ok=1 if full else -1,
                       present=int(bool(mask)), coverage_min=mask.bit_count(),
                       observation_basis="heartbeat_inferred" if full else "unknown")
            yield row


def probe(source):
    if source not in {"s3", "ubiqlog"}:
        raise ValueError("Heartbeat probe supports S3 and UbiqLog only")
    raw = data_root() / "raw" / source
    hashes = read_json(raw / "SHA256.json")
    for name, digest in hashes.items():
        if sha256(raw / name) != digest:
            raise RuntimeError(f"Raw integrity mismatch: {name}")
    people, diagnostics, sensors = read_s3() if source == "s3" else read_ubiqlog()
    pids = {original: f"{source}-p{i:03d}" for i, original in enumerate(sorted(people), 1)}
    prepared = {original: p for original, person in people.items() if (p := prepare_person(person, sensors)) is not None}
    out = data_root() / "probes" / "heartbeat" / source
    metrics = [person_metrics(pids[original], sensor, people[original], p)
               for original, p in sorted(prepared.items()) for sensor in sensors + ["union"]]
    if not metrics:
        raise RuntimeError("No timestamp evidence for heartbeat probe")
    write_csv(out / "people.csv", metrics, list(metrics[0]))

    def daily_rows():
        for original, p in sorted(prepared.items()):
            for sensor in sensors + ["union"]:
                masks = p["masks"][sensor]
                strict = {b for b, mask in masks.items() if mask == FULL}
                strict_starts = Counter(b // 96 for b in window_starts(strict))
                loose_starts = Counter(b // 96 for b in window_starts(set(masks)))
                for day in range(p["first"], p["last"] + 1):
                    bins = [masks.get(day * 96 + i, 0) for i in range(96)]
                    any_bins = sum(bool(mask) for mask in bins)
                    full_bins = sum(mask == FULL for mask in bins)
                    yield dict(pid=pids[original], day_index=day - p["first"], weekday=(day - 1) % 7,
                               sensor=sensor, source_day_present=int(day in people[original].recorded_days),
                               bins_with_any_heartbeat=any_bins, share_day_any_heartbeat=any_bins / 96,
                               strict_heartbeat_bins=full_bins, share_day_strict_heartbeat=full_bins / 96,
                               windows_3h_any_starting_day=loose_starts[day], windows_3h_strict_starting_day=strict_starts[day])

    write_csv(out / "days.csv.gz", daily_rows(), ["pid", "day_index", "weekday", "sensor", "source_day_present", "bins_with_any_heartbeat", "share_day_any_heartbeat", "strict_heartbeat_bins", "share_day_strict_heartbeat", "windows_3h_any_starting_day", "windows_3h_strict_starting_day"])
    union_metrics = [r for r in metrics if r["sensor"] == "union"]
    calendar_bins = sum(r["calendar_days"] * 96 for r in union_metrics)
    recorded_bins = sum(r["recorded_days"] * 96 for r in union_metrics)
    strict_bins = sum(r["strict_heartbeat_bins"] for r in union_metrics)
    strict_recorded = sum(round(r["share_recorded_day_bins_strict"] * r["recorded_days"] * 96) for r in union_metrics)
    candidates = data_root() / "converted" / source / "heartbeat_inferred"
    write_csv(candidates / "bins.csv.gz", candidate_rows(people, prepared, pids), FIELDS)
    validate_rows(read_csv(candidates / "bins.csv.gz"))
    parents = [dict(pid=r["pid"], start_dow=(prepared[original]["first"] - 1) % 7,
                    days=r["calendar_days"], observed_days=r["days_with_strict_heartbeat"])
               for original in sorted(prepared) for r in union_metrics if r["pid"] == pids[original]]
    write_csv(candidates / "parents.csv", parents, ["pid", "start_dow", "days", "observed_days"])
    write_csv(candidates / "people.csv", [dict(pid=r["pid"], source=source, age_group="unknown") for r in union_metrics], ["pid", "source", "age_group"])
    zero_bins = missing_fg_minutes = 0
    for original, p in prepared.items():
        fg = people[original].foreground
        for b, mask in p["masks"]["union"].items():
            if mask == FULL:
                values = [fg.get(m) for m in range(b * 15, b * 15 + 15)]
                missing_fg_minutes += sum(v is None for v in values)
                zero_bins += int(sum(v or 0 for v in values) == 0)
    summary = dict(source=source, status="WAITING_FOR_REVIEW_BEFORE_INJECTION", people_in_inventory=len(people),
                   people_with_clock_bounds=len(prepared), people_with_heartbeat=sum(r["unique_event_timestamps"] > 0 for r in union_metrics),
                   calendar_person_days=calendar_bins // 96, recorded_person_days=recorded_bins // 96,
                   strict_heartbeat_bins=strict_bins, share_calendar_bins_strict=strict_bins / calendar_bins,
                   bins_with_any_heartbeat=sum(r["bins_with_any_heartbeat"] for r in union_metrics),
                   share_calendar_bins_any=sum(r["bins_with_any_heartbeat"] for r in union_metrics) / calendar_bins,
                   share_recorded_day_bins_strict=strict_recorded / recorded_bins if recorded_bins else None,
                   longest_strict_run_minutes=max(r["longest_strict_run_minutes"] for r in union_metrics),
                   windows_3h_strict=sum(r["windows_3h_strict"] for r in union_metrics),
                   windows_3h_any_event_per_bin=sum(r["windows_3h_any_event_per_bin"] for r in union_metrics),
                   eligible_people_28_observed_days=sum(r["eligible_28_observed_days"] for r in union_metrics),
                   eligible_daytime_3h_windows_after_warmup=sum(r["windows_3h_eligible_after_warmup_daytime"] for r in union_metrics),
                   strict_bins_zero_foreground=zero_bins, strict_minutes_missing_foreground=missing_fg_minutes,
                   inferred_zero_foreground_windows_3h=sum(r["inferred_zero_foreground_windows_3h"] for r in union_metrics),
                   candidate_bins_sha256=sha256(candidates / "bins.csv.gz"), diagnostics=dict(diagnostics),
                   actual_episodes_generated=0, source_hashes=hashes,
                   interpretation="Rolling overlapping windows are placement candidates, not episodes or independent samples. Heartbeat does not verify foreground access.")
    write_json(out / "summary.json", summary)
    write_json(candidates / "summary.json", summary)
    notes = ("Heartbeat requires an event in every one of 15 distinct wall-clock minutes. Duplicate events at one timestamp never improve coverage. "
             "Only complete heartbeat bins infer interactions from foreground counts (missing counts become zero); incomplete bins remain unknown. "
             "observation_basis is heartbeat_inferred on every inferred bin and unknown otherwise. "
             "No sensor values, identifiers, absolute timestamps or dates are retained in outputs. "
             "S3 uses inertial timestamps and last-minute total foreground counters. UbiqLog uses the union of WiFi/Bluetooth/Location event timestamps and Activity END timestamps; no activity interval is filled. "
             "UbiqLog foreground counts are Application session-start records, a proxy rather than proven human interactions. "
             "Logger heartbeat does not prove usage access; foreground absence may reflect a failed usage channel. "
             "Verbose UbiqLog timestamps retain local wall time, ignoring trailing timezone names; inconsistent dates beyond one day of the daily log are excluded. "
             "Clock jumps can stretch calendar spans. Both calendar-span and recorded-day denominators are reported, with missing intervening days explicitly represented. "
             "A recorded S3 day has a valid vector or foreground record; a recorded UbiqLog day has a valid daily source file (plus valid event days). "
             "The review must decide whether coverage is good enough; no source-wide quality threshold is silently imposed. "
             "Three-hour counts include overlapping and cross-midnight windows; daytime candidates start 08:00–18:00 after clock day 14 and require 28 days with at least one strict bin. "
             "No scenarios have been injected.\n")
    (out / "README.md").write_text("# Heartbeat probe\n\n" + notes, encoding="utf-8")
    (candidates / "README.md").write_text("# Heartbeat-inferred candidate bins — review required\n\n" + notes, encoding="utf-8")
    for directory in [out, candidates]:
        write_json(directory / "SHA256.json", {p.name: sha256(p) for p in sorted(directory.iterdir()) if p.is_file() and p.name != "SHA256.json"})
    write_json(version_root() / "review_gate.json", {"state": "waiting_for_pre_injection_review", "injection_allowed": False})
    return summary

"""S3 statistics only. Voice embeddings and app identifiers are never exported."""
import zipfile
from collections import defaultdict
from datetime import datetime, timezone

from ..paths import data_root
from .common import finish


def parse_stat(line):
    fields = line.strip().split(",")
    if len(fields) != 16:
        return None  # 43 = sensor feature vector, 514 = voice embedding
    timestamp = int(fields[0])
    count = int(fields[2])  # second statistic: total foreground apps, last minute
    local_seconds = int(fields[-1])
    if count < 0 or not 0 <= local_seconds < 86400:
        raise ValueError("Invalid S3 statistics row")
    epoch_seconds = timestamp // 1000
    delta = (local_seconds - epoch_seconds % 86400 + 43200) % 86400 - 43200
    offset = round(delta / 900) * 900
    if abs(offset - delta) > 120:
        raise ValueError("Cannot reconcile S3 embedded local time")
    local = datetime.fromtimestamp(epoch_seconds + offset, timezone.utc)
    day = local.date().toordinal()
    if local_seconds - (local.hour * 3600 + local.minute * 60 + local.second) > 43200:
        day -= 1
    elif local_seconds - (local.hour * 3600 + local.minute * 60 + local.second) < -43200:
        day += 1
    minute = day * 1440 + local_seconds // 60
    # Each vector summarizes the preceding minute, not an unobserved next minute.
    return minute - 1, count, timestamp


def convert():
    root = data_root() / "raw" / "s3"
    minutes = defaultdict(dict)
    latest = defaultdict(dict)
    diagnostics = dict(statistics_rows=0, dropped_sensor_rows=0, dropped_voice_rows=0, invalid_time_rows=0)
    with zipfile.ZipFile(root / "S3Dataset.zip") as z:
        info = [line.split() for line in z.read("S3Dataset/info/S3dataset.txt").decode("ascii").splitlines()]
        ownership = {r[0]: r[1] for r in info}
        for name in sorted(z.namelist()):
            if not name.startswith("S3Dataset/data/authvoicedata_"):
                continue
            person = ownership[name.rsplit("/", 1)[1]]
            for line in z.read(name).decode("latin-1").splitlines():
                n = len(line.split(","))
                if n == 514:
                    diagnostics["dropped_voice_rows"] += 1
                    continue
                if n == 43:
                    diagnostics["dropped_sensor_rows"] += 1
                    continue
                if n != 16:
                    raise ValueError(f"Unrecognized S3 vector width: {n}")
                try:
                    minute, count, timestamp = parse_stat(line)
                except ValueError:
                    diagnostics["invalid_time_rows"] += 1
                    continue
                diagnostics["statistics_rows"] += 1
                if timestamp > latest[person].get(minute, -1):
                    latest[person][minute] = timestamp
                    minutes[person][minute] = count
    return finish("s3", minutes, diagnostics,
        "Use the 16-column statistics vectors, retaining only total foreground count (vector position 2 including timestamp). "
        "A vector covers its preceding local minute. Embedded seconds determine feature time; "
        "epoch time determines the local date after a nearest-quarter-hour offset check (up to 120 seconds "
        "serialization lag allowed); inconsistent rows are counted and excluded. Reconcile epoch time with embedded local seconds, "
        "then retain only relative day and weekday. For duplicate minute samples, retain the latest. "
        "Sum counts over fully supported 15-minute bins. This is a foreground-count proxy, not screen unlocks. "
        "Discard 43-column inertial features and 514-column voice embeddings. "
        "The source is session-oriented: gaps between sessions are unknown observation, not quiet time.",
        age="18-70 (source group only)")

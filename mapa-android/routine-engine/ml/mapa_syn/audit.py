"""Read-only feasibility checks; never bridges unknown observation gaps."""
from .io import read_csv, write_json
from .paths import data_root


def coverage_windows(rows, minimum_bins=12, quiet_only=False):
    """Count eligible start ticks; windows must be complete within one person."""
    count = run = 0
    previous_pid = previous_t = None
    for row in rows:
        pid, t = row["pid"], int(row["t"])
        if pid != previous_pid or previous_t is None or t != previous_t + 1:
            run = 0
        observed = int(row["usage_ok"]) == 1 and int(row["interactions"]) >= 0
        if quiet_only:
            observed = observed and int(row["interactions"]) == 0
        run = run + 1 if observed else 0
        if run >= minimum_bins:
            count += 1
        previous_pid, previous_t = pid, t
    return count


def audit():
    result = {}
    for source in ["s3", "ubiqlog"]:
        path = data_root() / "converted" / source / "bins.csv.gz"
        if not path.exists():
            result[source] = {"available": False}
            continue
        result[source] = {
            "available": True,
            "fully_observed_3h_window_starts": coverage_windows(read_csv(path)),
            "fully_observed_quiet_3h_window_starts": coverage_windows(read_csv(path), quiet_only=True),
        }
    write_json(data_root() / "logs" / "source-feasibility.json", result)
    return result

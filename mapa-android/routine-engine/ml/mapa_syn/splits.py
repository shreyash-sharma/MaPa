import random

from .io import read_csv, read_json, sha256, write_csv, write_json
from .manifest import update
from .paths import data_root, version_root
from .settings import ANOMALIES, source_dir


def assign_synthetic(pids, seed):
    ids = sorted(pids)
    random.Random(seed).shuffle(ids)
    n = len(ids)
    train, validate = round(n * .70), round(n * .15)
    return {pid: "train" if i < train else "validate" if i < train + validate else "test" for i, pid in enumerate(ids)}


def freeze(rows, path):
    fields = ["pid", "source", "split"]
    rows = sorted(rows, key=lambda r: (r["source"], r["pid"]))
    if len({r["pid"] for r in rows}) != len(rows):
        raise ValueError("Person occurs in more than one split")
    if path.exists():
        if list(read_csv(path)) != rows:
            raise RuntimeError("Frozen splits differ; refusing to redraw")
    else:
        write_csv(path, rows, fields)
    return rows


def split(cfg):
    root = version_root()
    probe = list(read_csv(data_root() / "probes/heartbeat/ubiqlog/people.csv"))
    union = {r["pid"]: r for r in probe if r["sensor"] == "union"}
    found_anomalies = {pid for pid, r in union.items() if int(r["calendar_days"]) > 2 * int(r["recorded_days"])}
    if found_anomalies != ANOMALIES:
        raise RuntimeError("Clock subgroup differs from reviewed v0.1.1 inventory")
    real_ids = {pid for pid, r in union.items() if int(r["windows_3h_eligible_after_warmup_daytime"]) > 0}
    if len(real_ids) != 11:
        raise RuntimeError("Reviewed real_check cohort must contain 11 people")
    rows, dropped, people, inputs = [], [], [], {}
    for source in ["synthetic", "s3", "ubiqlog"]:
        directory = source_dir(source)
        for name in ["bins.csv.gz", "parents.csv", "people.csv", "summary.json"]:
            file = directory / name
            inputs[file.relative_to(data_root()).as_posix()] = sha256(file)
        parents = list(read_csv(directory / "parents.csv"))
        assignments = assign_synthetic([p["pid"] for p in parents if int(p["observed_days"]) >= cfg["minimum_observed_days"]], cfg["seed"]) if source == "synthetic" else {}
        s3_metrics = {r["pid"]: r for r in read_csv(data_root() / "probes/heartbeat/s3/people.csv") if r["sensor"] == "union"} if source == "s3" else {}
        for parent in parents:
            pid = parent["pid"]
            if source == "synthetic":
                group = assignments.get(pid)
                reason = "fewer than 28 observed days"
            elif source == "s3":
                group = "s3_check" if int(s3_metrics[pid]["strict_heartbeat_bins"]) > 0 else None
                reason = "no strict bins for next-interaction log-loss"
            else:
                group = "real_check" if pid in real_ids else None
                reason = "not in reviewed 11-person eligible daytime cohort"
            if group is None:
                dropped.append(dict(pid=pid, source=source, reason=reason))
                continue
            rows.append(dict(pid=pid, source=source, split=group))
            metrics = union.get(pid, {})
            people.append(dict(pid=pid, source=source, split=group, age_group="unknown",
                               clock_anomaly=int(pid in ANOMALIES), days=int(parent["days"]),
                               observed_days=int(parent["observed_days"]), recorded_days=metrics.get("recorded_days", parent["days"])))
    path = root / "splits.csv"
    rows = freeze(rows, path)
    write_csv(root / "dropped_people.csv", dropped, ["pid", "source", "reason"])
    write_csv(root / "people.csv", people, ["pid", "source", "split", "age_group", "clock_anomaly", "days", "observed_days", "recorded_days"])
    counts = {group: sum(r["split"] == group for r in rows) for group in ["train", "validate", "test", "real_check", "s3_check"]}
    assert [counts[g] for g in ["train", "validate", "test"]] == [140, 30, 30]
    update(cfg, [path, root / "dropped_people.csv", root / "people.csv"], inputs=inputs, splits_sha256=sha256(path), split_counts=counts,
           real_check_policy={"no_training": True, "clock_anomaly_ids": sorted(ANOMALIES), "headline_excludes_clock_anomalies": True})
    write_json(root / "stage-S3.json", {"gate": "PASS", "counts": counts, "splits_sha256": sha256(path)})
    return {"stage": "S3", "gate": "PASS", "counts": counts, "dropped": len(dropped), "splits_sha256": sha256(path)}

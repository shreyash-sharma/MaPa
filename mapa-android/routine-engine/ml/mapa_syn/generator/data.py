"""Role preparation is separate from fitting: fitting never opens mixed-source CSVs."""
from itertools import groupby
from pathlib import Path
import numpy as np

from ..io import read_csv, read_json, sha256, write_csv, write_json
from ..paths import PROJECT, data_root

SEED = 20261102


def directory():
    return data_root() / "generator"


def spec_files():
    return [PROJECT / "docs/mapa-syn/DESIGN.md", PROJECT / "docs/mapa-syn/SPEC-v0.1.2.md",
            PROJECT / "mapa-android/routine-engine/CALIBRATION.md"]


def policy_hashes():
    files = spec_files()
    if "Spec change v0.1.2" not in files[0].read_text(encoding="utf-8"):
        raise RuntimeError("Required v0.1.2 section is absent")
    return {str(p.relative_to(PROJECT)).replace("\\", "/"): sha256(p) for p in files}


def prepare():
    """Materialize safe shards before genfit. This staging step reads all roles.

    No fitting occurs here. Fitting and fidelity have explicit, different readers.
    """
    root = directory()
    probe = list(read_csv(data_root() / "probes/heartbeat/ubiqlog/people.csv"))
    reserved = sorted(r["pid"] for r in probe if r["sensor"] == "union" and int(r["windows_3h_eligible_after_warmup_daytime"]) > 0)
    if len(reserved) != 11:
        raise RuntimeError("Reviewed 11-person real_check changed")
    roles, inputs = [], {}
    rng = np.random.default_rng(SEED)
    for source in ["s3", "ubiqlog"]:
        folder = data_root() / "converted" / source / "heartbeat_inferred"
        parents = {r["pid"]: r for r in read_csv(folder / "parents.csv")}
        eligible = sorted(pid for pid in parents if pid not in reserved)
        shuffled = rng.permutation(eligible).tolist()
        fit = set(shuffled[:round(.8 * len(shuffled))])
        by_pid = {}
        for pid in sorted(parents):
            partition = "real_check" if pid in reserved else "fit" if pid in fit else "heldout"
            rel = f"inputs/{partition}/{pid}.npz"
            row = dict(pid=pid, source=source, role="real_check" if pid in reserved else "gen_fit", partition=partition,
                       path=rel, days=int(parents[pid]["days"]), start_dow=int(parents[pid]["start_dow"]))
            roles.append(row)
            by_pid[pid] = row
        file = folder / "bins.csv.gz"
        inputs[file.relative_to(data_root()).as_posix()] = sha256(file)
        for pid, rows in groupby(read_csv(file), key=lambda r: r["pid"]):
            meta = by_pid[pid]
            n = meta["days"] * 96
            counts = np.full(n, -1, dtype=np.int32)
            observed = np.zeros(n, dtype=bool)
            for row in rows:
                t = int(row["t"])
                full = row["observation_basis"] == "heartbeat_inferred" and int(row["coverage_min"]) == 15 and int(row["usage_ok"]) == 1
                if full:
                    observed[t] = True
                    counts[t] = int(row["interactions"])
            path = root / meta["path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            np.savez_compressed(path, counts=counts, observed=observed, start_dow=np.array(meta["start_dow"]))
            meta["sha256"] = sha256(path)
    old = root / "roles.json"
    frozen = dict(seed=SEED, policy=policy_hashes(), reserved_real_check=reserved, people=roles, source_hashes=inputs)
    if old.exists() and read_json(old) != frozen:
        raise RuntimeError("Frozen generator roles or inputs changed; refusing to redraw")
    write_json(old, frozen)
    write_csv(root / "roles.csv", [{k: p[k] for k in ["pid", "source", "role", "partition"]} for p in roles], ["pid", "source", "role", "partition"])
    # Preserve the original 200-parent hand baseline. Only its night block is used by fitting.
    hand = data_root() / "converted/synthetic"
    people = list(read_csv(hand / "parents.csv"))
    if len(people) != 200 or any(int(r["days"]) != 120 for r in people):
        raise RuntimeError("Expected original 200 x 120-day synth_hand baseline")
    hs = read_json(hand / "summary.json")
    if hs["seed"] != 20261031 or hs["actual_episodes_generated"] != 0:
        raise RuntimeError("synth_hand provenance changed")
    counts, observed, dows = [], [], []
    for pid, rows in groupby(read_csv(hand / "bins.csv.gz"), key=lambda r: r["pid"]):
        a = np.full(120 * 96, -1, dtype=np.int32)
        o = np.zeros(120 * 96, dtype=bool)
        for row in rows:
            t = int(row["t"])
            a[t] = int(row["interactions"])
            o[t] = a[t] >= 0
        counts.append(a.reshape(120, 96)); observed.append(o.reshape(120, 96))
        dows.append(int(next(r for r in people if r["pid"] == pid)["start_dow"]))
    counts, observed = np.array(counts), np.array(observed)
    np.savez_compressed(root / "hand_reference.npz", counts=counts, observed=observed, start_dow=np.array(dows))
    night = np.r_[0:28, 88:96]
    np.savez_compressed(root / "hand_nights.npz", counts=counts[:, :, night].reshape(-1, 36), observed=observed[:, :, night].reshape(-1, 36))
    write_json(root / "preparation.json", {"gate": "PASS", "role_counts": {g: sum(p["partition"] == g for p in roles) for g in ["fit", "heldout", "real_check"]},
               "roles_sha256": sha256(old), "hand_bins_sha256": sha256(hand / "bins.csv.gz"), "night_sha256": sha256(root / "hand_nights.npz")})
    return read_json(root / "preparation.json")


def load_person(row):
    path = directory() / row["path"]
    if sha256(path) != row["sha256"]:
        raise RuntimeError("Per-person input changed")
    with np.load(path, allow_pickle=False) as z:
        return {"pid": row["pid"], "source": row["source"], "counts": z["counts"], "observed": z["observed"], "start_dow": int(z["start_dow"])}


def fit_people(roles, reader=None, audit=None):
    reader = reader or load_person
    reserved = set(roles["reserved_real_check"])
    for row in roles["people"]:
        if row["partition"] != "fit":
            continue
        if row["role"] != "gen_fit" or row["pid"] in reserved:
            raise RuntimeError("BLOCKER: attempted fitting access outside gen_fit/fit")
        if audit is not None:
            audit.append(row["pid"])
        yield reader(row)

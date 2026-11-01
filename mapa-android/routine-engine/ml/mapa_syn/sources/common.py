from datetime import datetime

from ..bins import FIELDS, validate_rows
from ..io import write_csv, write_json
from ..paths import data_root


def finish(source, people_minutes, diagnostics, rule, age="unknown"):
    """Minute keys are local ordinal minutes; values are counts or None.

    A missing minute is unknown. A bin is fully observed only with all 15
    minutes supported. Sparse known activity is retained but cannot certify
    coverage. Absolute time exists only in memory during conversion.
    """
    out = data_root() / "converted" / source
    parents, people = [], []
    totals = dict(bins=0, fully_observed_bins=0)
    runs = {}
    def generate_rows():
        for number, original in enumerate(sorted(people_minutes), 1):
            minutes = people_minutes[original]
            if not minutes:
                continue
            pid = f"{source}-p{number:03d}"
            first_minute = minutes.first_minute if hasattr(minutes, "first_minute") else min(minutes)
            last_minute = minutes.last_minute if hasattr(minutes, "last_minute") else max(minutes)
            first_day, last_day = first_minute // 1440, last_minute // 1440
            days = last_day - first_day + 1
            start_dow = datetime.fromordinal(first_day).weekday()
            observed_days = set()
            current_run = best_run = 0
            for t in range(days * 96):
                begin = first_day * 1440 + t * 15
                values = [minutes.get(m) for m in range(begin, begin + 15)]
                coverage = sum(v is not None for v in values)
                full = coverage == 15
                known_count = sum(v for v in values if v is not None)
                interactions = known_count if full or known_count > 0 else -1
                row = dict.fromkeys(FIELDS, -1)
                row.update(pid=pid, t=t, interactions=interactions, usage_ok=1 if full else -1,
                           present=int(coverage > 0), coverage_min=coverage,
                           observation_basis="source_recorded" if full else "unknown")
                totals["bins"] += 1
                totals["fully_observed_bins"] += int(full)
                current_run = current_run + 1 if full else 0
                best_run = max(best_run, current_run)
                if full:
                    observed_days.add(t // 96)
                yield row
            runs[pid] = best_run
            parents.append(dict(pid=pid, start_dow=start_dow, days=days, observed_days=len(observed_days)))
            people.append(dict(pid=pid, source=source, age_group=age))

    write_csv(out / "bins.csv.gz", generate_rows(), FIELDS)
    from ..io import read_csv
    validate_rows(read_csv(out / "bins.csv.gz"))
    write_csv(out / "parents.csv", parents, ["pid", "start_dow", "days", "observed_days"])
    write_csv(out / "people.csv", people, ["pid", "source", "age_group"])
    summary = dict(source=source, people=len(parents), calendar_person_days=sum(p["days"] for p in parents),
                   observed_person_days=sum(p["observed_days"] for p in parents),
                   eligible_people=sum(p["observed_days"] >= 28 for p in parents),
                   bins=totals["bins"], fully_observed_bins=totals["fully_observed_bins"],
                   missing_share=1 - totals["fully_observed_bins"] / totals["bins"] if totals["bins"] else None,
                   longest_fully_observed_run_minutes=max(runs.values(), default=0) * 15,
                   person_longest_runs_minutes={p: n * 15 for p, n in runs.items()}, diagnostics=diagnostics,
                   age_groups={age: len(people)})
    write_json(out / "summary.json", summary)
    (out / "README.md").write_text(f"# {source} conversion\n\n{rule}\n\n"
        "Only pseudonymous IDs, day indices, weekday offsets and aggregate counts are retained. "
        "Original participant keys and absolute times are not exported. All unrecorded health channels "
        "remain -1. No network, battery, charging, pause or power telemetry is inferred. "
        "Movement remains unknown because sensor features do not provide validated movement labels. "
        "Coverage requires all 15 individual minutes; unknown minutes never become zero. "
        "Observed days are days with at least one fully observed bin; this does not imply a fully observed day. "
        "Age group is source-level metadata only, not individual age.\n", encoding="utf-8")
    import json
    (out / "SUMMARY.md").write_text("# Conversion summary\n\n```json\n" + json.dumps(summary, indent=2) + "\n```\n", encoding="utf-8")
    return summary

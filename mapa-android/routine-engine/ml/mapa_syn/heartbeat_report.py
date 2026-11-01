"""Write the requested pre-injection review artifact; never starts injection."""
import zipfile
from datetime import datetime

from .io import read_csv, read_json, sha256, write_json
from .paths import ENGINE, data_root, version_root


def percentage(value):
    return "n/a" if value is None else f"{100 * float(value):.2f}%"


def report():
    root = data_root()
    sources = {s: read_json(root / "probes/heartbeat" / s / "summary.json") for s in ["s3", "ubiqlog"]}
    people = {s: list(read_csv(root / "probes/heartbeat" / s / "people.csv")) for s in sources}
    contributors = {s: sum(int(r["windows_3h_eligible_after_warmup_daytime"]) > 0 for r in rows if r["sensor"] == "union") for s, rows in people.items()}
    synthetic_path = root / "converted/synthetic/summary.json"
    synthetic = read_json(synthetic_path) if synthetic_path.exists() else None
    raw_people, valid_people = set(), set()
    with zipfile.ZipFile(root / "raw/ubiqlog/ubiqlog.zip") as archive:
        for name in archive.namelist():
            parts = name.split("/")
            if len(parts) != 3 or parts[0] != "UbiqLog4UCI" or not parts[2].startswith("log_") or not name.endswith(".txt"):
                continue
            raw_people.add(parts[1])
            try:
                datetime.strptime(parts[2][4:-4], "%m-%d-%Y")
            except ValueError:
                continue
            valid_people.add(parts[1])
    inventory = {"ubiqlog_raw_participants": len(raw_people), "ubiqlog_participants_with_valid_daily_filenames": len(valid_people),
                 "ubiqlog_participants_without_parseable_daily_filenames": len(raw_people - valid_people)}
    write_json(root / "logs/heartbeat-inventory.json", inventory)
    lines = ["# MaPa-Syn v0.1 — heartbeat probe and pre-injection review", "",
             "Status: WAITING FOR USER REVIEW. No scenario injection, scoring, training or upload has run.", "",
             "## Approved specification changes", "",
             "Behaviour scenarios 1–6: primary synthetic; secondary only on supported heartbeat-inferred real windows. "
             "Observation scenarios 7–10: synthetic and supported real. V8: at least 20 test episodes per scenario "
             "across synthetic plus real, with real counts reported separately. Actual episode counts are currently zero; V8 has not been evaluated.", "",
             "## Coverage", "",
             "Strict = at least one heartbeat in each of 15 distinct minutes. Loose = any event in a 15-minute bin. "
             "A loose three-hour window does not qualify for inferred observation. Counts are overlapping rolling starts, "
             "not independent episodes. The full calendar-span denominator includes every missing intervening day; "
             "the recorded-day denominator is also provided because clock anomalies can stretch the span.", "",
             "| Source | People with heartbeat | Strict bins | Calendar-span coverage | Recorded-day coverage | Loose 3 h starts | Strict 3 h starts | 28-day eligible people | Eligible daytime 3 h starts after warmup |",
             "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for source, s in sources.items():
        lines.append(f"| {source} | {s['people_with_heartbeat']} | {s['strict_heartbeat_bins']} | {percentage(s['share_calendar_bins_strict'])} | {percentage(s['share_recorded_day_bins_strict'])} | {s['windows_3h_any_event_per_bin']} | {s['windows_3h_strict']} | {s['eligible_people_28_observed_days']} | {s['eligible_daytime_3h_windows_after_warmup']} |")
    lines += ["", "Eligibility here means 28 days with at least one strict bin, not 28 complete days. "
              "Daytime starts are 08:00–18:00 after the untouched first 14 clock days. These counts do not "
              "establish severity, normality, sufficient clean personal training, or all scenario-specific placement conditions. "
              "These are pre-split coverage counts; held-out real episode counts are not yet known.", "",
              "People actually contributing eligible daytime three-hour starts: " + ", ".join(f"{s}: {count}" for s, count in contributors.items()) + ". "
              "People with 28 observed days can still have zero qualifying windows; overlapping starts are not independent samples.", "",
              "## Per-person coverage", "",
              f"UbiqLog has {len(raw_people)} participants in raw file paths; {len(valid_people)} have parseable daily filenames. "
              f"{len(raw_people - valid_people)} participant(s) cannot be day-indexed because all daily filenames are invalid. "
              "They are excluded with unknown coverage, not counted as observed or assigned invented days. Inventory evidence: logs/heartbeat-inventory.json.", "",
              "Each person's every calendar day (including missing days) is in `probes/heartbeat/<source>/days.csv.gz`. "
              "Columns include day_index, weekday, any-heartbeat share, strict-heartbeat share and three-hour starts per day. "
              "Each sensor and the union are reported separately. `people.csv` includes cadence and deduplication statistics.", "",
              "| Person | Calendar days | Recorded days | Strict day-bin share (calendar) | Strict day-bin share (recorded) | Days with strict bins | Strict 3 h starts | Eligible daytime starts |",
              "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for source in sources:
        for r in people[source]:
            if r["sensor"] == "union":
                lines.append(f"| {r['pid']} | {r['calendar_days']} | {r['recorded_days']} | {percentage(r['share_calendar_day_bins_strict'])} | {percentage(r['share_recorded_day_bins_strict'])} | {r['days_with_strict_heartbeat']} | {r['windows_3h_strict']} | {r['windows_3h_eligible_after_warmup_daytime']} |")
    lines += ["", "## Cadence and inference", "",
              "S3's nominal inertial cadence is 20 seconds. Actual unique-event median/p90 gaps and shares of gaps ≤20/60 seconds are in people.csv; sensor vector values were never extracted. "
              "UbiqLog probes WiFi, Bluetooth, Location and Activity independently and as a union. AP/device records sharing "
              "a timestamp are deduplicated; Activity contributes only its END event, never a filled interval.", "",
              "Candidate bins are staged under `converted/<source>/heartbeat_inferred/`. All strict inferred bins carry "
              "`observation_basis=heartbeat_inferred`; incomplete bins carry unknown and interactions=-1. On strict bins, "
              "interactions are available foreground counts, otherwise zero. S3 uses its last-minute foreground-total "
              "counter; UbiqLog uses Application session-start counts as a proxy. Logger heartbeat does not prove "
              "that foreground access worked. Zero-filled foreground absence is an explicit inference, not verified non-use.", ""]
    for source, s in sources.items():
        lines.append(f"- {source}: {s['strict_bins_zero_foreground']} strict bins have zero foreground count; "
                     f"{s['strict_minutes_missing_foreground']} strict minutes have no foreground record; "
                     f"{s['inferred_zero_foreground_windows_3h']} rolling 3 h windows have zero inferred foreground count.")
    lines += ["", "No arbitrary source-wide percentage threshold defines 'good'. Review the coverage and eligible windows "
              "before admitting secondary real behaviour tests. For scenarios requiring three hours, sources/persons without eligible "
              "windows must later report zero real episodes with a reason. Shorter scenarios need their own placement checks; "
              "a three-hour coverage failure alone does not exclude them. Clock anomalies and rejected timestamp counts are in source summary.json.", "",
              "## Synthetic primary base", ""]
    if synthetic:
        lines.append(f"Prepared {synthetic['parents']} parents × {synthetic['days']} days = {synthetic['bins']} bins, "
                     f"seed {synthetic['seed']}, using the unchanged existing generator API with with_events=False. "
                     "No original harness dataset was replaced. No scenario events or episode files were generated. "
                     "All bins declare observation_basis=synthetic. Seed, generator hash and output hashes are recorded in converted/synthetic.")
    else:
        lines.append("Not prepared; no synthetic generation is claimed.")
    lines += ["", "## Review boundary", "",
              "Episode provenance is required by config/episodes.schema.json and episodes.py. No episodes.jsonl exists "
              "because injection is explicitly deferred. review_gate.json blocks injection until the user reviews these results. "
              "Splitting, causal gap statistics, injection, trace/evaluation, critic rounds and final validation remain outstanding.", ""]
    baseline = root / "logs/protected-files-baseline.json"
    if baseline.exists():
        protected = read_json(baseline)
        changed = [name for name, digest in protected.items() if sha256(ENGINE / name) != digest]
        if changed:
            raise RuntimeError(f"Protected files changed: {changed}")
        lines.append(f"Protected-file integrity: {len(protected)} files match their baseline. Raw acquisitions were verified before each probe. No data was committed.")
    tests = root / "logs/heartbeat-tests.xml"
    if tests.exists():
        from xml.etree import ElementTree
        suites = ElementTree.parse(tests).getroot().iter("testsuite")
        totals = {key: 0 for key in ["tests", "failures", "errors", "skipped"]}
        for suite in suites:
            for key in totals:
                totals[key] += int(suite.get(key, 0))
        lines += ["", f"Python verification: {totals['tests']} tests, {totals['failures']} failures, {totals['errors']} errors, {totals['skipped']} skipped. JUnit evidence: logs/heartbeat-tests.xml."]
        if totals["failures"] or totals["errors"]:
            raise RuntimeError("Heartbeat tests did not pass; refusing a review-ready report")
    path = root / "logs/build-status.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    write_json(version_root() / "review_gate.json", {"state": "waiting_for_pre_injection_review", "injection_allowed": False,
               "build_status_sha256": sha256(path), "probe_hashes": {s: sha256(root / "probes/heartbeat" / s / "summary.json") for s in sources}})
    return {"status": "WAITING_FOR_REVIEW_BEFORE_INJECTION", "build_status": str(path), "sources": sources,
            "synthetic": synthetic, "actual_episodes_generated": 0}

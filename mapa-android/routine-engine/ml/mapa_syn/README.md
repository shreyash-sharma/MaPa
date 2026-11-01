# MaPa-Syn v0.1 tooling

Work in progress. The user-approved current phase ends at heartbeat/synthetic-base preparation and pauses before injection for review. See `data/MaPaData/logs/build-status.md` and `data/IMPLEMENTATION_DECISIONS.md`.

Run from `routine-engine/ml` with Python 3.11+. Current production acquisition/conversion use only the standard library; tests require pytest.

```powershell
python -m pytest tests/mapa_syn -q
python -m mapa_syn acquire --source s3
python -m mapa_syn acquire --source ubiqlog
python -m mapa_syn convert --source s3
python -m mapa_syn convert --source ubiqlog
python -m mapa_syn audit
python -m mapa_syn heartbeat --source s3
python -m mapa_syn heartbeat --source ubiqlog
python -m mapa_syn prepare-synthetic --parents 200 --days 120 --seed 20261031
python -m mapa_syn heartbeat-status
```

`MAPA_DATA` overrides the default `D:\GitHub\MaPa\data\MaPaData`. A path inside the engine tree is rejected. Gzip headers are deterministic. Every CLI run writes a log under the data root. Downloads preserve original archives and record published licence evidence and hashes. Converters never edit raw files.

Coverage requires all 15 minutes of a bin to have support. Missing minutes stay unknown. Partial-bin activity can be retained without claiming full coverage. Unrecorded health channels remain -1. No app identifiers, location, communications details, voice, absolute dates or original participant IDs are exported.

S3 uses foreground-count vectors; UbiqLog uses complete minutes within date-consistent Application intervals as a provisional activity proxy. Neither provides a verified continuous usage-access heartbeat. Do not treat raw logging gaps as quiet periods. Date filtering and source-specific limitations are documented in generated conversion READMEs.

Later-stage CLI names are reserved but fail explicitly: their presence does not imply implementation or a successful gate. Configuration overrides are not yet supported.

Heartbeat probes retain only timestamp-derived counts, cadence and coverage. See `probes/heartbeat/<source>/people.csv` and `days.csv.gz` for every person/day. Full heartbeat means an event in all 15 distinct minutes, not merely one sample per bin. Complete inferred bins receive `observation_basis=heartbeat_inferred`; missing foreground records become zero only on these bins. Incomplete bins remain unknown. Candidate outputs are separate from the original conservative conversions. The episode schema now requires the same provenance field.

Synthetic preparation uses NumPy and the existing unmodified `mapa_rhythm.synth.generate` with `with_events=False`. It writes new-seed base parents outside the code tree and does not generate episode files or replace existing harness data. `review_gate.json` blocks injection pending user review.

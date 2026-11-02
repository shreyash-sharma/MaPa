## Spec change v0.1.7: final-v3 with unlock and movement signals

Decided 2026-10-28 (shreyash: "add unlocks and movement only data as well"). Do not touch `final-v2`. Build `final-v3` beside it.

**New columns per 15-minute bin**

| Column | Meaning |
| --- | --- |
| `unlock_count` | Unlocks in the bin |
| `unlock_len_s` | Total unlocked seconds in the bin |
| `session_count` | Use sessions that start in the bin |
| `move_level` | Movement level 0 to 3 (none, light, moderate, strong) |
| `move_frac` | Fraction of the bin with movement |

Every new column has a companion `<col>_src` with `real_observed`, `model_derived` or `unobserved` (-1 value). The row-level `provenance` column stays.

**Rules**

1. Real logs give what they have. Never fill a real gap with a made-up value labelled real. Use -1 and `unobserved`.
2. Derived parents get the new signals from the same fitted statistical model (same fit, same seed rules), labelled `model_derived`. Conditioning: unlocks and sessions follow the in_use state; movement follows the person's hourly profile and state.
3. Splits and test people stay exactly as in `final-v2`. Check with a test that compares person lists.
4. Episodes: programmatically constructed disruptions change the new signals consistently. Example: phone left at home gives unlocks 0 and movement 0 together with the other signals. Observation failures (app killed, permission revoked) set new columns to -1.
5. Manifest lists SHA-256 for every file, row counts, the real versus derived share per column, and the `final-v2` hashes it did not change.
6. Fidelity: repeat the F1 to F5 table and add the hourly profile of unlock count and movement, derived versus observed.
7. Make `final-v3` read-only after its test passes. Codex retrains the winning design on `final-v3` by 2026-10-31.

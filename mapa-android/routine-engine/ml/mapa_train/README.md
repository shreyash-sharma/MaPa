# mapa_train: train and test the on-device rhythm CNN on MaPa-Syn

Spec: `docs/mapa-syn/SPEC-v0.1.2.md` (experiments A and B). Run everything from `routine-engine/ml/`.

The data sets, in plain words:
- `synth_hand`: parents from the old hand-written generator (rules we wrote). Baseline only (model A).
- `synth_learned`: parents sampled from a generator (HSMM) learned from real phone logs (S3 and UbiqLog
  `gen_fit` people), with an over-60 age layer. Not hand-written; not copies of real people.
- `gen_fit`: real phone logs (S3 and UbiqLog heartbeat-inferred bins) of everyone outside `real_check`.
- `real_check`: 11 real UbiqLog people. Test only; never trained on.

## Before a real run

1. `python -m mapa_train.parity` must pass: the Python features and CNN give the phone's numbers
   (golden files in `../models/cnn`). Checked 2026-10-27: features exact, logits within 2e-6, all 4 sizes.
2. The MaPa-Syn data must have: `converted/synthetic`, `converted/synth_learned` (S2b), the real
   `heartbeat_inferred` bins, and a frozen `mapa-syn/v0.1/splits.csv` with `synth_learned` rows.
   Episodes are optional: if `mapa-syn/v0.1/episodes/<split>/episodes.jsonl` exists, programmatically constructed windows are
   never learned as normal.

## Morning run

```
python -m mapa_train.train --exp A --size s --out runs
python -m mapa_train.train --exp B --size s --out runs
python -m mapa_train.test  --runs runs --out runs/report
python -m mapa_train.parity          # still passes; also check runs/*/golden_cnn.txt
```

`runs/report/REPORT.md` has every model on synth_hand/test, synth_learned/test and real_check
(with and without clock-anomaly people), each with people, bin counts and a 95% per-person bootstrap.
It also says plainly whether B beats A on real_check.

To ship a winner: copy `runs/<exp>-<size>/rhythm_cnn.weights.txt` and `golden_cnn.txt` to
`../models/cnn/` under the size name, export the `.pte` with
`python -m mapa_rhythm.export_executorch --model runs/<exp>-<size>/rhythm_cnn.pt --out ../models/cnn`,
run the Kotlin `CnnParityTest`, then rebuild the parent app.

## Rules the code enforces

- `real_check` and `test` people are never read for training or validation (`data.LeakError`).
- `gen_fit` = every real person outside `real_check`.
- A data set that is not built is skipped and reported, never invented.
- Wording for the card and pitch: "MaPa is pretrained on real smartphone logs. Its non-use data comes
  from a statistical model learned from real phone use."

## Not here

Alert metrics (catch rate, false alerts per person-week, time to alert, stale-as-fresh) come from the
harness traces in MaPa-Syn S6/S7 (`mapa_syn evaluate`).

## Relation to mapa_release (2026-10-28)

`ml/mapa_release` (committed 28 Oct) builds the frozen MaPa-Rhythm release (`MaPaData/mapa-rhythm-release/final/`,
train 162 / validation 39 / test 39 people) and trains and scores CNN versions on alert metrics. Use it for the
release. `mapa_train` keeps two jobs that `mapa_release` does not do: the phone parity check (`parity.py`) and the
next-interaction log-loss report with per-person intervals. It still reads `converted/`; pointing it at `final/` is
the next change. Do not run both trainers on the same data for the same claim.

## Current training goal (28 Oct, afternoon)

The training plan is `docs/TRAINING-GOAL.md` plus `docs/CODEX-ADDENDUM.md` in the repo root: one two-lane hybrid model
on `MaPaData/mapa-rhythm-release/final-v2/` (final-v3 with movement when ready). `zoo.py` and `decision.py` here are
starting code; the hybrid replaces the model contest.

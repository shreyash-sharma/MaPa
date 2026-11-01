# Learned non-use generator, spec v0.1.2

Run from `routine-engine/ml`:

```powershell
python -m mapa_syn genprepare
python -m mapa_syn genfit
python -m mapa_syn gensample --parents 200 --days 120 --seed 20261101
```

`genprepare` performs no fitting: it freezes an 80/20 person split inside timestamp-usable gen_fit, reserves the reviewed 11 real_check people, and creates physically separate per-person NPZ inputs. It also freezes the original hand dataset as the fidelity baseline and extracts its assumed night block. The old hand dataset remains unchanged.

`genfit` opens only fit shards. It uses censored likelihood for UbiqLog quiet durations, S3/UbiqLog session evidence, and shrunk log-space Gaussian person parameters. The full-stage access-isolation test rejects any attempt to read held-out/real_check data. Empty observation is not interpreted as low use.

`gensample` draws new parameters and timelines, applies the CALIBRATION.md cited count-proxy age layer, and evaluates F1–F5 on frozen held-out people. F5 can read real_check for evaluation-only privacy comparison; it never feeds fitting. The diagnostic logistic classifier uses scipy, not scikit-learn. There is no neural generator or rhythm-model training.

All gates must pass for promotion to `converted/synth_learned`. A failed gate returns exit code 1, keeps the candidate only in `generator/failed-samples`, records every result, and blocks injection. Do not tune against the held-out fidelity results or silently redraw people/seeds. All learned bins carry observation_basis=synthetic_learned. The current run has F1–F4 FAIL and F5 PASS; it is not an approved benchmark dataset.

Evidence is under the data root: `generator/params.json`, `generator/generator_card.md`, `generator/fidelity.json`, `generator/fit-access.json`, `generator/verification.json`, `generator/stage-S2a.json`, `generator/stage-S2b.json`, and `logs/build-status.md`. The card distinguishes published age aggregates from count-proxy, night and numerical assumptions. Its pretraining release wording is conditional; pretraining has not run in this task.

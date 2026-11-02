## Spec change v0.1.2: learned non-use generator

This section overrides v0.1.1 and all older sections where they differ (decided 2026-10-27). We replace hand-written non-use with a generator learned from real phone logs. The hand-written generator stays as a baseline.

**Honest wording (use exactly this in the card, report and pitch)**

- Allowed: "MaPa is pretrained on real smartphone logs. Its non-use data comes from a statistical model learned from real phone use."
- Not allowed: "pretrained on real non-use data", or any wording that calls generated timelines real.

**Data roles**

| Group | People | Role |
| --- | --- | --- |
| `gen_fit` | All S3 people + UbiqLog people NOT in `real_check` | Fit the generator. Pretrain the model on their observed bins. |
| `real_check` | The 11 UbiqLog people with eligible daytime windows | Never used to fit or train. Evaluation only. |
| `synth_learned` | 200 sampled parents x 120 days | New primary benchmark. Split 70/15/15 by parent. |
| `synth_hand` | Existing 200 hand-written parents (seed 20261031) | Baseline benchmark. Split 70/15/15 by parent. |

**Generator: hidden semi-Markov model (HSMM)**

1. States: `in_use`, `short_break`, `long_quiet`, `night`.
2. Each state has its own duration distribution, conditioned on hour block (six 4-hour blocks) and weekday or weekend.
3. Quiet lengths come from UbiqLog strict-heartbeat windows in `gen_fit`. A window that ends because the heartbeat stops is right-censored. Use a survival estimate (Kaplan-Meier or a censored log-normal fit). Never treat a censored length as a complete one.
4. Session structure (session length, count per bin) comes from S3 and UbiqLog observed bins in `gen_fit`.
5. Night: the sources barely cover night. Use the night block of the hand-written generator and mark it as an assumption.
6. Person variety: fit parameters per person, then sample new parents from the population of fitted parameters (log-space Gaussian with shrinkage). Never copy one real person's parameters exactly.
7. Age layer: the sources are mostly younger adults. Rescale session rate and daily use to the over-60 aggregates in `routine-engine/CALIBRATION.md`. Write every scaled parameter and its citation to `generator_card.md`.
8. Implement with numpy and scipy only. No neural generator in v0.1.

**Fidelity gates (a sampled set must pass all of these before scenario construction)**

| # | Check | Pass rule |
| --- | --- | --- |
| F1 | Quiet-length distribution vs held-out `gen_fit` people | KS distance reported; must beat `synth_hand` on the same test |
| F2 | Hour-of-day use profile | Correlation with real profile reported; must beat `synth_hand` |
| F3 | Sessions per day | Median within the real interquartile range (after the age layer, within the cited aggregate) |
| F4 | Real vs synthetic classifier (logistic regression on day features) | AUC reported; lower is better; must beat `synth_hand` |
| F5 | Privacy | No synthetic day equals a real day (nearest-neighbour distance > 0 on every day) |

Fit held-out for F1 to F4: split `gen_fit` people 80/20; fit on 80%, check on 20%.

**Experiments (all reported, none hidden)**

| Model | Pretraining | Fine-tune | Evaluated on |
| --- | --- | --- | --- |
| A | `synth_hand` | none | `synth_hand` test, `synth_learned` test, `real_check` |
| B | `gen_fit` real bins (next-interaction) + `synth_learned` | none | same three |
| Baselines | 4 h rule, statistics only | none | same three |

Headline: model B on `synth_learned` test and on `real_check`. Show A vs B side by side. If B is not better on `real_check`, we say so.

**New stages (between S2 and S3)**

| Stage | Command | Writes | Gate |
| --- | --- | --- | --- |
| S2a Fit | `python -m mapa_syn genfit` | `generator\params.json`, `generator_card.md` | Uses only `gen_fit`. Test proves `real_check` pids are never read. Censoring is handled. |
| S2b Sample | `python -m mapa_syn gensample --parents 200 --days 120 --seed 20261101` | `converted\synth_learned\` | F1 to F5 pass. All bins have `observation_basis=synthetic_learned`. |

Code goes in `ml/mapa_syn/generator/` (`hsmm.py`, `fit.py`, `sample.py`, `fidelity.py`) with tests in `tests/mapa_syn/test_generator.py`. The critic checklist gains two rows: "`real_check` never read by genfit or training" (blocker) and "honest wording" (blocker).

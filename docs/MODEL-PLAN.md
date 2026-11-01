# Model plan (28 October 2026)

> **Superseded for training by `TRAINING-GOAL.md` and `CODEX-ADDENDUM.md` (28 Oct, afternoon):** one two-lane
> hybrid model, day-one start, movement and session starts, 21 inputs. This file keeps the reasoning and the
> step (a) results.

How we train MaPa's rhythm model next, what we take from others, and what changes in our model.
Data: the frozen MaPa-Rhythm release (`MaPaData/mapa-rhythm-release/final/`, 162 / 39 / 39 people).
Rule: every choice is made on the 39 validation people; the 39 test people are used one time, at the end.

## 1. What we take from others

| Idea | From | Where it goes |
| --- | --- | --- |
| Own alert line per parent, from the last 14 normal days | Gibietz et al. 2026 | Decision layer |
| Day-swap augmentation (5 nearest days of the same person, closer than the 10th percentile) | Shen et al. 2025 | Training data |
| More weight for similar people (person-level and batch-level softmax) | Shen et al. 2025 | Training loss |
| Learn "normal" first, then score surprise | Gibietz 2026, Cejudo 2025 | Stage 1 pretraining |
| Sensitivity per parent ("tell me sooner" / "fewer alerts") | Cejudo et al. 2025 | Decision layer |
| Say what was different | Cejudo et al. 2025 | Alert text |
| Alerts in levels ("worth a look", "please call") | ElderWatch AI | Decision layer |
| Ask the parent first (one optional tap) | StillFine | Decision layer (product) |
| Strict morning check | eNeighbor | Decision layer |
| Unlock duration and sessions per 15 minutes | TechSANS | Later (version 2): the generator must make them too |

## 2. How we train (three stages)

**Stage 1: learn normal rhythm (no labels).** Every model learns to predict the next phone use
(the next-bin and current-bin heads) on real observed days and generated parents' untouched days.
Part of the inputs is hidden, as the release does now. Augmentation and similarity weights apply here.

**Stage 2: learn "unusual" (labels).** The models then learn the unusual-quiet head on the injected
episodes, with life-tag days left out. Positive weight as now (12). The real windows get day-swap copies.

**Stage 3: the decision layer (same for every model).**
1. Score from the model, each 15 minutes.
2. Each parent's own line: the score's 97th percentile over their last 14 normal days (the family's
   sensitivity moves this between the 90th and 99.5th), never below a global floor chosen on validation.
3. Two levels: above the line = "worth a look" (in the app); far above, or long enough = "please call" (push).
4. Ask the parent first before "please call" (one optional tap; no tap = the family is told).
5. Strict morning check: no first use by the parent's late first-use time (90th percentile) = "worth a look".
6. Safety floor: a very long daytime silence always alerts, whatever the model says.
7. Stale data is always "Monitoring unavailable", never fine (stale-as-fresh must stay 0).

**Model families (same stages, same data, same evaluator):** logistic hazard, LightGBM, GRU, LSTM, tiny
Transformer, CNN (`mapa_train/zoo.py`). The winner must run on the phone: causal, under about 50K parameters,
and it must export to ExecuTorch.

**Fairness checks:** 5 seeds per family (report the spread); hold one scenario out of training and
check it on validation, so the model does not only learn how our injections look.

**Pass rule (the release gate C4):** fewer false alerts per person-week than the 4-hour rule and the
statistics baseline, and at least their catch rate at every severity. Report failures too.

## 3. What changes in our model

1. **One feature contract.** Today the phone uses 18 inputs with movement in positions 16 and 17. The
   release models use those positions for the parent's quiet history (90th and 99.5th percentile).
   Change: keep movement and add quiet history as inputs 18 and 19 (20 inputs). Update the Kotlin
   `FeatureBuilder`, the Python features and the golden files together, and keep the parity check at 0.
2. **Three outputs, clear names:** next-use chance, current-use chance, and an unusual-quiet score.
3. **Personal line instead of one global threshold** (stage 3).
4. **Training data:** augmentation and similarity weights (stage 1).
5. **Export:** the winner goes to ExecuTorch and the Pixel 6a; Kotlin stays as the fallback.

## 4. Order of work

1. Decision layer on the existing CNN scores (no training): personal line, levels, morning check, floor.
   This tells us how much the gate problem is the decision rule, not the model.
2. Feature contract (20 inputs) in Python, Kotlin and the golden files.
3. Stage 1 and 2 training for all families, 5 seeds, on this PC's GPU (Modal only if it is too slow).
4. Choose on validation; test one time; export; check on the Pixel.

## 5. Risks
- The labels are injected, not real events: a model can learn the injection shape. The scenario-held-out
  check and the real_check people limit this; we say so in the paper.
- Real days are few and mostly from younger people; the age layer is a US aggregate.
- Stage 3 rules can hide a weak model; report the model alone and the full system separately.

## 6. Results of step (a), 28 October (validation only; `mapa_train/decision.py`)

**Finding 1: the release catch rates mix two things.** Its "catch rate" counts behaviour episodes (unusually long
quiet) together with observation episodes (the phone stops reporting). Every detector "catches" observation
episodes through the shared missing-data rule, so all catch rates look near 100%. Counting behaviour only:

| Detector (validation, 39 people, 3,941 untouched days) | False alerts / person-week | Catch mild / clear / severe |
| --- | --- | --- |
| 4-hour rule | 6.35 | 0.47 / 1.00 / 1.00 |
| Statistics only | 3.73 | 0.29 / 0.64 / 1.00 |
| CNN v3, one global line 0.2 | 2.27 | 0.32 / 0.57 / 0.65 |
| CNN v3 + own line (q 0.97) + 8 h floor | 2.98 | 0.26 / 0.46 / 1.00 |
| CNN v7 + own line (q 0.97) + 8 h floor | 3.43 | 0.29 / 0.61 / 1.00 |

**Finding 2: too few behaviour episodes.** Validation has 38 mild, 28 clear and 17 severe behaviour episodes
(83 in all), against 432 observation and 630 routine episodes. One episode moves a rate by 3 to 6 points.

**Finding 3: the decision layer helps, but the CNN score is the limit.** With an own line per parent and an 8-hour
floor, CNN v7 matches the statistics baseline (fewer false alerts, about the same catch) but does not beat it. No
setting of 128 passes the gate on behaviour catches. The model alone misses severe cases without the floor.

**Next:** (1) ask the dataset thread for more behaviour episodes in train and validation (same rules, more
episodes per person); (2) a "surprise" score from the next-use heads (how unlikely is this much quiet for this
parent?), which is the "learn normal first" idea; (3) the model comparison on the same behaviour-only metric.

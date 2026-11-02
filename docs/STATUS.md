# MaPa status and plan (28 October 2026)

A refresher in simple English. Sources: the code and data on the PC (`D:\GitHub\MaPa`), git commits `e5ded42` and
`20ad697`, `MaPa Meta hackathon plan.md`, and `data/MaPaData/mapa-rhythm-release`. Numbers here come from those
files; nothing is estimated.

## 1. What MaPa is

A calm daily picture of an aging parent's day for the family, from the parent's phone use. The parent's phone
learns that parent's own rhythm. When a day looks very different from usual (for example a morning that stays
quiet for much longer than usual), the family sees it within hours, not after a fixed 24 hours. No health or
safety claims. Old data is always shown as old. Only conclusions leave the parent's phone.

Hackathon: Meta AI Developer Hackathon. Deadline **4 Nov, 9:00 PM ET = 5 Nov, 7:30 AM IST**.

## 2. What works today (Pixel 6a, local Firebase emulator)

- Parent app: link, one welcome screen, one Yes, one Android switch. Hindi and English.
- Family app (Stitch design): tabs **Today**, **History**, **Rhythm**. Parent detail with the sky arc,
  Call, "Mark today as different", the new-routine question and Ask MaPa.
- On-device model runs on **ExecuTorch (XNNPACK)**: 5,360 parameters, 50 KB, 123 µs per step on the Pixel 6a.
  (The Kotlin version of the same model is the fallback: 11 to 15 µs per step.)
- The Rhythm tab shows the model to the family with real numbers from the parent's phone.
- Tests: 46 Kotlin core tests, 9 Firestore rules tests, 6 `mapa_train` tests, 50+ `mapa_syn` tests pass.
- Screens: `_stage/board-v2/index.html` (before/after board).

## 3. The model

| | |
| --- | --- |
| What | A causal 1D convolutional network (6 layers, kernel 3, dilations 1 to 32; looks back about 32 hours) |
| Size | "s": 16 channels, 5,360 parameters, 50 KB file |
| Input | 18 numbers every 15 minutes: phone used or not, use count, movement, time of day, weekday, charging, battery, the parent's learned profile |
| Output | 16 numbers: the chance of the next sign of life (use or movement) in each of the next 16 quarter-hours (4 hours) |
| Where it runs | On the parent's phone, ExecuTorch first, Kotlin fallback |
| Personal part | The phone builds the parent's own profile and calibrator: every night for 30 days, then weekly. Guards: 7-day wait before a day is learned (1 day in the first 2 weeks), life tags are skipped, a new routine needs a person's Yes |

The network itself is pretrained once (population). The personal profile is learned on the phone.

## 4. Training: where we are

There are now two training codes. They overlap.

1. **`ml/mapa_release`** (the dataset thread, committed overnight). It built the dataset **MaPa-Rhythm**:
   - Real strict-heartbeat phone logs from S3 and UbiqLog (people outside `real_check`), plus 200 parents
     x 120 days sampled from a generator fitted to those real logs, plus 10 programmatically constructed disruption scenarios.
   - Frozen splits: train 162 people, validation 39, test 39 (`final/`, read-only, C8 PASS).
   - The 11 `real_check` people are kept out of fitting and release.
   - It trained 8 CNN versions (`models/cnn-v1` to `cnn-v7-q97`) and checked each on validation alerts.
   - **Result: no version passes the gate yet.** Release checks C4 and C5 fail. Test evaluation has not run.
2. **`ml/mapa_train`** (written in this session). Trains experiments A and B from SPEC v0.1.2, checks that
   Python gives exactly the phone's numbers (parity), and reports next-interaction log-loss with per-person
   intervals. It reads the `converted/` folders, not the new `final/` Parquet release.

**Validation results so far** (from `trials.csv`; false alerts per person-week, then catch rate mild / clear / severe):

| Detector | False alerts / week | Catch: mild | clear | severe |
| --- | --- | --- | --- | --- |
| Fixed 4-hour rule | 6.35 | 0.90 | 1.00 | 1.00 |
| Statistics only | 3.73 | 0.86 | 0.94 | 1.00 |
| CNN v3, threshold 0.3 | 1.12 | 0.85 | 0.90 | 0.92 |
| CNN v1, threshold 0.3 | 0.017 | 0.81 | 0.84 | 0.89 |

The CNN gives **far fewer false alerts** (down to 0.017 per week) but **catches fewer** mild and clear cases than
the statistics baseline. That is the trade-off to decide. These are validation numbers, not test numbers.

Also: the generator fitted to real logs beats the hand-written one on hourly profile (0.21 vs 0.13 correlation) and
realism (AUC 0.67 vs 0.79, lower is better), but is **worse** on quiet-length distribution (KS 0.108 vs 0.077).

Honest wording for the pitch: "MaPa is pretrained on real smartphone logs. Its non-use data comes from a
statistical model learned from real phone use."

## 5. How we use Meta

| Meta technology | Where | Status |
| --- | --- | --- |
| **Muse Spark** (Meta Model API) | Family app only: daily note, "tell MaPa in your own words" to a life tag, Ask MaPa | Code is in the app. **No key yet, so no call has run.** This is what makes the project eligible. |
| Meta open-weights model on Baseten | Second cloud provider for the same family features | Code is in the app. No key yet. |
| **ExecuTorch** | On the parent's phone: runs the rhythm model | **Working** on the Pixel 6a (fixed 27 Oct). A bonus, not a rule of the event. |
| PyTorch | Training and export of the model | Working |

Rules: the parent's phone never calls Meta or any AI API. Cloud calls get a short summary with `[NAME]`, never
the name or raw data. Never the `-contributor` Muse Spark model. Keys stay in `local.properties`, never in git.

## 6. Modal ($100 credits): how to use them

The model is very small, so a GPU is not needed. Modal helps with three things:

1. **Many training runs in parallel.** Seeds, sizes, the alert threshold and the alert policy, all on the frozen
   `final/` splits. This is the fastest way to find a version that passes the gate, and to report spread over seeds.
2. **ExecuTorch export on Linux.** `mapa_release/README.md` says export needs a Linux environment (ExecuTorch
   0.7.0 and flatc). A Modal Linux image gives one reproducible export step.
3. **Generator retries** (fidelity F1) in parallel.

Before upload: only the release files go to Modal (the derived and licensed data in `final/`). Never the
`real_check` people. I have not checked Modal's prices; check the dashboard before large runs.

## 7. What to do next (7 days left)

1. **Muse Spark working end to end** (eligibility): add the key, test the Hindi daily note, the life tag from
   free text, and Ask MaPa. Record it for the demo.
2. **Pick the alert trade-off** (product decision): fewer false alerts (CNN) or more catches (statistics).
   A likely answer: the CNN plus a rule floor for long silences. Then run the frozen test set once.
3. **One training path**: use `mapa_release` for the release and its alert metrics. Keep `mapa_train` for the
   parity check and the log-loss report, and point it at `final/`. Do not run two trainers on the same data.
4. **Modal sweep** on the frozen splits, then export the chosen model to ExecuTorch and check it on the Pixel.
5. **Landing page and tech docs** for "How MaPa learns", the model card and privacy, instead of more app screens.
6. **Demo video and submission text** (`MaPa Meta hackathon plan.md`).

## 8. What others do, and what we can borrow (search 2026-10-28)

| Source | What they do | What we borrow |
| --- | --- | --- |
| Shen et al., arXiv 2509.23158 (IEEE BHI 2025), UT Austin | LSTM over daily feature sequences, 36 older adults; "routine-aware augmentation" (swap a day for a behaviourally similar day) and "demographic personalization" (weight similar people more). AUPRC 0.637 to 0.766. Data not public. | Day-swap augmentation for our few real days; weight training people by similarity (age group, source) |
| Shen et al., Innovation in Aging 2026 (TechSANS) | 145 daily features from unlocks, unlock duration, app-category time, steps, gait, location, typing, calls, 21 people 65+ | Already used for the age layer. Candidate private features: unlock duration and session count per bin |
| Gibietz et al., Gerontechnology 2026 | Autoencoder trained on normal days only, per person (CASAS smart homes); plans drift detection | Train "normal only" and score surprise; our guards already cover drift |
| Cejudo et al., 2025 | Predict tomorrow's routine, flag large prediction errors | Same idea as our expected-vs-today; report it as such |
| ElderWatch AI (app) | 14-day baseline; 4 risk levels with escalation; "wake pattern" and charging habits as signals; weekly report | **Graded alerts** instead of one yes/no alert; morning wake as its own signal |
| StillFine (app) | 3 tiers: a gentle reminder to the parent first, then family | **Ask the parent first** (one optional tap) before the family alert: fewer false alerts at the same catch rate |
| eNeighbor (app) | Watches only the morning's first phone use | A dedicated **morning check** with a tight window |
| CareTrigger (app) | Free, learned per-user patterns, status at any time | Keep the free on-demand status; compete on privacy and India |
| CASAS (WSU, public smart-home data) | Older adults living alone, sensor routines | A possible extra test of "routine" methods; not phone data; check the licence |

### 8b. From the full papers (read 2026-10-28)

**Shen et al. 2025** (arXiv 2509.23158, IEEE BHI 2025). 36 people aged 65+ (mean 75), iPhone, 147 daily
features, 30-day windows, bidirectional LSTM (256 units), leave-one-person-out. Results, participant level:
XGBoost on 30-day summaries AUC 0.505; LSTM 0.660; + day-swap augmentation 0.671; + similarity weighting 0.756;
with age, sex and education added, best AUC 0.780 / AUPRC 0.766.
- Valid day = at least 14 h of sensing between 6 AM and midnight; sensing coverage is itself a feature.
- Days with more than one time zone (travel) are dropped. *Borrow:* a time-zone change is a private travel hint;
  MaPa could propose a "Travelling" tag for the family to confirm (no GPS).
- Augmentation: for each day, the 5 nearest days of the same person, kept only if closer than the 10th
  percentile of all day distances; 5 synthetic sequences per real one. *Borrow for the real logs.*
- Weighting: softmax over inverse demographic distance; both the person-level and the batch-level softmax
  were needed for the gain. *Borrow:* weight training people by similarity (age group, source).
- Upload only on Wi-Fi and when charging or above 50% battery. *Matches our "train while charging" rule.*
- Their future work: use the 15-minute app-usage resolution instead of daily totals. **MaPa already models
  15-minute rhythm.** They also say gaps in data may carry information; we show gaps as "Monitoring unavailable".
- Their signals (typing, gait, unlocks) are for cognitive impairment, a medical goal. MaPa makes no such claim.

**Cejudo et al. 2025** (CASAS, 30 older adults, about 2,245 days). Predict tomorrow's routine (AttentionRNN best,
MSE 4.38, not significantly better than BiRNN; transformers skipped for too little data). Flag a day when the
prediction error's z-score passes a p-value, then keep it only if at least one activity is outside its normal
range (an explanation step). The p-value is set per group: 0.85 to 0.9 for frail people, 0.99 for healthy ones.
They could not measure false alarms (no labels).
*Borrow:* (1) a per-parent sensitivity the family chooses ("tell me sooner" vs "fewer alerts");
(2) an explanation of which part of the day was different; (3) our scenario label benchmark **does** measure
false alerts, which this work could not.

**Gibietz et al. 2026** (CASAS HH101). Autoencoder per person; inputs scaled by the person's own maximum per
room and per time-of-day phase (4 phases); threshold adapts on a sliding window of the last 14 normal days;
retrains only on normal days. F1 83.6% rising to 89% with the phase scaling and the sliding threshold.
*Borrow, for our gate problem:* a **per-parent adaptive threshold** on the CNN score, from that parent's last
14 normal days, instead of one global threshold. Our statistics baseline already adapts per person; the CNN does not.

## 9. Model work (28 Oct, afternoon)

- **Data:** final-v2 is frozen (train 162 / validation 39 / test 39 people; 1,624 behaviour episodes in train,
  83 in validation). Real bins: about 10,150. `moved` is unknown in all derived and real rows; v3 is requested
  (`DATA-REQUEST-v3.md`) to add movement from S3's motion summaries.
- **Step (a) result (validation, behaviour-only catches):** our CNN with an own line per parent and an 8-hour
  floor ties the statistics baseline but does not beat it (`MODEL-PLAN.md`, section 6).
- **Plan:** Codex trains one **two-lane hybrid** model (`TRAINING-GOAL.md`, `CODEX-ADDENDUM.md`): fast lane
  (CNN + GRU + attention over 24 h) and slow lane (LSTM over 30 daily summaries), personal context, 3 heads,
  surprise score, decision layer; day-one start (backfill, 2 setup questions, archetypes, short-history training);
  21 inputs including session starts. Train on final-v2 now; retrain on final-v3.
- **Wording:** "hybrid on-device rhythm model"; "trained on real smartphone logs and on parent timelines
  generated by a statistical model learned from those logs". Not "foundation model".

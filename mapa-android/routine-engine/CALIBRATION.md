# What the synthetic parents are calibrated to

There is no open dataset of phone use by people over 60, so the generator (`ml/mapa_rhythm/synth.py`) is calibrated to **summary statistics** that two studies published. It uses no data from either study. The constants live in `ml/mapa_rhythm/calibration.py`, and `python -m mapa_rhythm.calibration` re-checks them (200 parents × 84 days).

| Quantity | Published | Source | Generated |
|---|---|---|---|
| Unlocks per day | 28.7 ± 21.4 | [TS] Table 1 | 29.9 ± 26.4 |
| Unlocks per day, cross-check | about 24 (230k unlocks ÷ 113 people ÷ 12 weeks) | [GO] | (same run) |
| Hours a day the phone is away from home | 4.19 ± 3.69 (24 − home stay of 19.81) | [TS] Table 1 | 4.27 ± 3.55 |
| Hours a day of detected movement (walking 0.59 + automotive 0.91 + cycling 0.05 + running 0.001) | 1.55 | [TS] Table 1 | 1.61 (15-minute bins with any movement, phones with a sensor) |
| Phones switched off at night | 2 of 21 | [TS] Results | 0.095 per parent (drawn) |

Sources:
- **[TS]** Shen Y, Huang M, Park JH, Benge JF, Rousseau JF, Lester-Smith RA, Thomaz E. *Multimodal passive smartphone sensing in older adults: a guide for clinical scientists based upon an ongoing cohort study.* Innovation in Aging 2026;10(4):igag007. [doi:10.1093/geroni/igag007](https://academic.oup.com/innovateage/article/10/4/igag007/8443438). 21 adults, age 75.8 ± 4.9, iPhones, about 141 days each. Table 1 values are mean ± SD over all participant-days.
- **[GO]** Gordon ML, Gatys L, Guestrin C, Bigham JP, Trister A, Patel K. *Modeling patterns of smartphone usage and their relationship to cognitive health.* [arXiv:1911.05683](https://arxiv.org/abs/1911.05683) (2019). 113 adults aged 60 to 75 (median 66), 12 weeks, "more than 800k app launches and 230k phone unlock events".

Not used: the figure "84 adults aged 61 to 76, 186,968 unlocks" could not be traced to a published source. Christensen et al. 2016 (PLoS ONE, [doi:10.1371/journal.pone.0165331](https://doi.org/10.1371/journal.pone.0165331)) finds screen time falls 0.7 min per hour per decade of age, but its cohort runs only to the mid 60s.

## Assumptions (no published number found)

These numbers are marked `ASSUMPTION` or `TUNED` in `calibration.py`.

- **How the unlock spread splits.** The variation in [TS] is divided between people (log-SD 0.55) and days (0.35). Only the pooled spread is published.
- **Re-unlocks.** There are 0.5 extra unlocks within minutes of each unlock, 30% of them in the next 15 minutes.
- **Outings.** A typical outing lasts 2 hours, and phone use while out is half the rate at home. 20% of the 15-minute bins inside an outing contain some travel. Every outing starts and ends with 15 to 30 minutes of travel.
- **Tuning factors.** Unlocks are scaled up by 1.24 and away time by 1.15. These offset unwell and guest days, phone-left-home outings, halts and waking-hour limits, so that the generated averages meet the published ones.
- **Unchanged from before.** Everything else about each parent is still hand-set and not checked against data:
  - wake and sleep times
  - naps
  - morning walks
  - phone left at home about once a week
  - the injected 3 to 14 hour halts
  - illness, travel, guests and festivals
  - plumbing outages
  - movement sensor available on 75% of phones and catching 50% to 90% of moving bins
  - USER_INTERACTION events per unlock

## Limits

- [TS] is small, American, highly educated (17.7 years of schooling) and almost all White. [GO] is American and reports totals only.
- Neither study covers Indian parents, Android or the 15-minute timing of use. The time-of-day shape is still assumed.
- Real pilot data, with parents opting in, should replace all of this. The plan is to recalibrate the same constants from pilot aggregates, then pretrain again.
- shreyash's own Pixel bins are used only to test the pipeline (`harness replay`), never to calibrate. He is under 60.

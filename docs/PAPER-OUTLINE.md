# Paper outline (draft, 28 October 2026)

## Working title
**MaPa-Rhythm: Learning each older adult's phone-use rhythm on the device, with a benchmark that counts
false alarms**

## The one-sentence claim
A tiny on-device model of each person's 15-minute phone rhythm, plus simple guards, can notice an unusually
long quiet period within hours, and a labelled benchmark lets us measure both catches and false alarms,
which earlier routine-anomaly work could not.

## Why it is new (each point must be checked against the final related-work search)
1. **A public benchmark with labels for both sides.** Real public phone logs (S3, UbiqLog), a generator fitted
   to them, and 10 programmatically constructed scenarios with "programmatically constructed" and "expected" labels. Cejudo et al. 2025 could not
   measure false alarms because they had no labels; we can.
2. **15-minute rhythm, not daily totals.** Shen et al. 2025 name this as future work; we do it.
3. **On-device and private.** A 5,360-parameter causal CNN on ExecuTorch (measured on a Pixel 6a); only
   conclusions leave the phone; no location, calls or app names.
4. **Guards against learning illness as normal** (7-day wait, life tags, a person confirms a new routine) and
   an honest "monitoring unavailable" state, measured with a stale-as-fresh metric that must be 0.

## Sections
1. Introduction: families far from aging parents; fixed-timer apps; what we add.
2. Related work: passive sensing in older adults (TechSANS; Shen 2025, 2026); routine anomaly detection
   (Cejudo 2025, Gibietz 2026, CASAS); consumer apps (fixed timers vs learned routines).
3. Data: sources and licences; heartbeat-inferred observation; the generator and its fidelity checks
   (report the failures too: quiet-length KS was worse than the hand-written generator); the age layer and
   its limits (US aggregates, not Indian parents).
4. Benchmark: the 10 scenarios; the two labels; person splits (162 / 39 / 39); metrics (false alerts per
   person-week, catch rate by severity, time to alert, stale-as-fresh).
5. Model: one two-lane hybrid (fast lane: causal CNN + GRU + attention over 24 h; slow lane: LSTM over 30
   daily summaries, after Shen et al. 2025); personal context; next-use, current-use and unusual heads; a
   surprise score; per-parent adaptive line (Gibietz 2026); day-one start (backfill, setup answers, rhythm
   archetypes, short-history training). Baselines: 4-hour rule, statistics-only, our earlier CNN. Ablation: the
   hybrid minus one part at a time. Results by days since install.
6. On-device results: ExecuTorch vs Kotlin speed on a real phone; model size.
7. Results: test set one time; every number with people counts and 95% intervals; failures shown.
8. Limits and ethics: inferred, not verified, non-use; mostly younger real people; no clinical outcome;
   no medical claims; consent and privacy.
9. Conclusion and release: dataset and code on Hugging Face (CC BY 4.0 data, attribution to S3 and UbiqLog).

## Where to send it (goal: a solid, honest, lower-tier venue; dates must be checked on each official site)
- **arXiv preprint first** (free, fast, citable, counts as a public record).
- **Good fits on topic, peer-reviewed, indexed:** EAI PervasiveHealth or EAI MobiHealth (Springer proceedings);
  IEEE BHI (Shen et al. 2025 was there); Indian IEEE venues such as INDICON or TENCON (health tracks).
- **Broader IoT/mobile venues with dates found on 28 Oct 2026** (not checked further):
  IEEE WoWMoM 2027 (deadline 1 Dec 2026), DCOSS-IoT 2027 (27 Jan 2027), EAI AfricaTEK 2027 (18 Dec 2026).
  ACM MobiSys 2027 (4 Dec 2026) is a top venue, not low tier.
- **Avoid predatory conferences:** no IEEE/ACM/Springer/EAI proceedings, "acceptance in days", high fees for
  certain acceptance, or no named programme committee. They can lower the value of a paper count.
- Follow each venue's rules on disclosing AI writing help.

## What we still need before writing results
- The final test run (once, locked).
- The model comparison and the per-person threshold experiment on validation.
- A related-work search for "benchmark" and "on-device" claims, so we do not overclaim.

## Honest wording to reuse
"MaPa is pretrained on real smartphone logs. Its non-use data comes from a statistical model learned from
real phone use." "A programmatically constructed behavior scenario is not an observed emergency."

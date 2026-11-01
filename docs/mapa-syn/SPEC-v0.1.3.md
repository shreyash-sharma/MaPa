## Spec change v0.1.3: fair retry of the learned generator

The first `synth_learned` sample failed F1, F2 and F4 against `synth_hand` (2026-10-27). It stays a failed diagnostic. Two test-design flaws may explain part of it, so we fix the test before we change the generator.

**Test fixes (apply to both generators the same way)**

1. **Age layer after fidelity.** F1 to F4 compare against real people who are mostly younger. Run F1 to F4 on the learned generator before the over-60 age layer. Apply the age layer after it passes. `synth_hand` is compared as it is, and the report says so.
2. **Same observation mask.** Real held-out data is session-only. Before F1, F2 and F4, apply the real observation mask (strict-heartbeat bins only, same censoring) to the synthetic days too. Compare only what the real data can see.

**No tuning on the test**

3. Split `gen_fit` people 60/20/20: fit, tune, fidelity-test. Freeze the split now. Choose all generator settings on the tune set only. Run F1 to F5 on the fidelity-test set once per candidate. Log every candidate and its scores, including losers.

**Allowed generator changes (at most 4 candidates)**

4. Pool quiet lengths across people (hierarchical shrinkage), because 111 complete lengths are too few per person.
5. Use fewer condition cells: 3 hour blocks instead of 6, no weekday split.
6. Non-parametric quiet lengths: censored Kaplan-Meier, sampled by inverse CDF.
7. Hybrid: `synth_hand` day structure plus learned quiet lengths only.

**Decision rule**

8. If one candidate beats `synth_hand` on F1, F2 and F4 and passes F3 and F5, it becomes `synth_learned`.
9. If no candidate passes, `synth_hand` stays primary. We report the learned generator as a negative result in the card. The honest wording then becomes: "MaPa is pretrained on real smartphone logs and on synthetic parents calibrated to published over-60 studies."

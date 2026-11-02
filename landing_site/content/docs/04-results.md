# Evaluation results

## The headline

**v2, chosen after the first test.** In a Python policy simulation on 40 model-derived parent timelines (120 days each, 6,720 parent-days, 685.714 observed person-weeks; seed `2026110202`), the MaPa v2 policy:

- **Had an alert in every labeled behavior-case window:** 50/50 mild, 33/33 clear and 25/25 severe. Twenty-eight windows had no changed phone-use or observation inputs; see the case-level breakdown below.
- **Sent about 6× fewer false alerts than a fixed 4-hour rule:** 1.094 against 6.542 per observed person-week, while the 4-hour rule missed 20 of the 50 mild cases.
- **Matched the 12-hour rule's false-alert rate, with alerts in over ten times as many labeled windows:** both at 1.094, with MaPa alerting in 108 of 108 windows and the 12-hour rule in 10.
- **Cut false alerts by about 60% against the previous hybrid policy:** 1.094 against 2.750, with alerts in the same 108 of 108 windows.
- **Alerted within an hour:** median and 90th-percentile alert delay were both 60 minutes.
- **Never passed stale data off as fresh:** zero stale-as-fresh moments, and 160 of 160 observation-case windows with an alert in each severity group, reported separately from the behavior cases.

These are case-level counts on derived timelines with controlled labels, not a real-world or physical-phone evaluation, and not estimates of emergency detection.

## The full comparison

A false alert is a policy alert during an observed period without a labeled positive behavior case, divided by observed person-weeks. Positive-case detection means an alert landed in the labeled case window.

| Policy | False alerts / observed person-week | Mild cases | Clear cases | Severe cases |
|---|---:|---:|---:|---:|
| **MaPa v2 policy** | **1.094** (750 alerts) | **50/50** | **33/33** | **25/25** |
| Previous hybrid policy | 2.750 | 50/50 | 33/33 | 25/25 |
| Fixed 4-hour rule | 6.542 | 30/50 | 33/33 | 25/25 |
| Fixed 8-hour rule | 2.750 | 7/50 | 9/33 | 25/25 |
| Statistics-only policy | 3.709 | 13/50 | 22/33 | 25/25 |
| Fixed 12-hour rule | 1.094 | 5/50 | 3/33 | 2/25 |
| Fixed 24-hour rule | 0 | 0/50 | 0/33 | 0/25 |

The MaPa v2 false-alert rate has a 95% person-bootstrap interval of 1.008–1.189 per observed person-week. On this cohort, every aggregate target was met: at most 1.5 false alerts per observed person-week, at least 90% mild and 95% clear detection, 100% severe detection, and at most 37.8% alerts in expected-none cases.

![Comparison of MaPa policy and reference rules on the new-seed derived cohort](benchmark-v2.png)

## How v2 was chosen

v2 changed the decision policy, not the network: the checkpoint is unchanged from v1, byte for byte. The process was built so the headline could not be tuned into existence:

1. **Selected on training people only.** Candidate policies were compared on five person-held-out folds of the training split. The chosen rule passed every aggregate training gate: 0.9802 false alerts per person-week, 27.28% hard-negative alerts, and 99.21% mild, 98.86% clear and 100% severe catches, with zero stale-as-fresh.
2. **Locked before the test existed.** The policy settings were written down and hashed *before* the evaluation cohort was generated.
3. **Scored once, on new people.** The 40-parent cohort above comes from a new generator seed. Nothing was fitted or tuned on it, and it never touched validation, the old test or the real-log group.

The result is labeled "v2, chosen after the first test" because the idea came after the v1 test had been read; the v1 numbers stay published as they were.

## Real-log check

On 11 real-log identities held out from fitting, the policy produced zero alerts across 602 person-days (23.852 observed person-weeks). These timelines have no labels for natural behavior events, so this is a descriptive check, not a measured real-world false-alert rate or detection capability.

## History: v1

The earlier locked test used 39 timeline identities: 30 model-derived and nine real-log identities. MaPa v1 reported 2.4348 false alerts per person-week and detected 40/40 mild, 31/31 clear, and 22/22 severe labeled cases. Its hard-negative alert rate was 231/368 (62.77%). The fixed 8-hour rule had the same reported false-alert rate and detected 5/40 mild, 10/31 clear, and 22/22 severe cases. The v1 next-use forecast did not improve on the profile-only predictor (test log loss 0.4150 vs 0.4125).

These v1 numbers stay on the record. The v1 test had already been used and was not the evaluation cohort above, and v1 and v2 counts are never combined into one headline.

## What we're attacking next

**Hard-negative cases.** Across 492 expected-none cases, v2 alerted in 141 (28.66%), under the 37.8% target. The remaining errors are concentrated in two scenarios: MaPa alerted in all 65 `daytime_inactivity` and all 67 `phone_left_home` cases, while it alerted in 9 of 120 `normal_long_silence` cases and none of the 120 `delayed_morning` or 120 `life_tag_day` cases. Telling ordinary daytime quiet and a phone left at home apart from a concerning pattern is the next target.

**Unchanged inputs.** Some positive cases caused no change to the available phone-use or observation inputs because the timeline was already quiet: 20/50 mild, 4/33 clear and 4/25 severe. Where the inputs did change, detection was 30/30 mild, 29/29 clear and 21/21 severe. Both counts are shown because they answer different questions.

![Alerts in expected-none behavior cases by scenario](benchmark-v2-hard-negatives.png)

**Scope of the evidence.**

- The behavior-case figures come from controlled labels on model-derived timelines, not observed emergencies or naturally occurring disruptions.
- The v2 cohort is small and comes from the same statistical generator family; independent real-world validation is not available.
- The 11-person real-log check has no event labels and cannot measure sensitivity.
- Android parity, physical-device speed, battery use and a family pilot are the next milestones.

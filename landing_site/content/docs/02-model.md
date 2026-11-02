# The MaPa Rhythm Hybrid

Under 10,000 parameters. One model per person. MaPa Rhythm Hybrid pairs a compact time-series network with a per-person decision policy, and the product is the complete pipeline: personal scoring all the way through to the alert decision. It is built from proven building blocks (CNN, GRU, LSTM), not a new neural architecture, and sized to live on the phone itself.

| Item | Current documented configuration |
|---|---|
| Inputs | 21 features on 15-minute steps, including session-start information |
| Fast sequence path | Causal dilated CNN and a GRU layer |
| Longer-term context | LSTM over daily summaries, with a personal context vector |
| Outputs | Next-use estimates, current-use estimate, and an unusual-quiet score |
| Model size | 9,594 parameters |
| Export | ExecuTorch artifacts exist for the trained checkpoint |
| Runtime status | Android integration exists. Full on-device parity and performance for the v2 policy are next to verify on a physical device. |

## Inside the network

Four parts, each with one job. The parameter counts below add up to the full 9,594.

| Part | Shape | Job | Parameters |
|---|---|---|---:|
| Causal dilated CNN | 4 layers, 16 channels, kernel 3, dilations 1, 2, 4 and 8 | Reads the last 31 quarter-hours (about 7¾ hours) for local patterns such as a session ending or a long quiet starting | 3,984 |
| GRU memory | 37 inputs (16 CNN features plus the 21 raw inputs), 16 hidden units | Carries the shape of the day so far, one step at a time | 2,640 |
| Daily-summary LSTM | 9 daily features, 16 hidden units, up to 30 days | The slow lane: how this person's recent weeks have gone | 1,728 |
| Personal context | 7 profile and quiet-history inputs to 8 units | A compact fingerprint of this person's routine | 64 |
| Four output heads | Each reads the same 61 numbers (GRU 16, inputs 21, personal 8, daily 16) | Next-use hazards for 16 steps, use-now, unusual-quiet, confidence | 1,178 |

Everything is causal: each quarter-hour is scored only from what came before it, exactly as it will be on the phone. A causal attention block was part of the design space and was not retained in the final checkpoint.

## The 21 inputs

Every closed quarter-hour becomes 21 numbers. None of them is an app name, a message, a place or any content.

| Group | Inputs |
|---|---|
| Phone use | Whether and how much the phone was used in this bin, whether the bin was observed, and how long it has been quiet |
| Time | Time of day and day of week as smooth cycles, plus a day-type flag |
| Device | Charging, battery level |
| The person's profile | Their expected phone use over the coming quarter-hours and hours, and how confident that profile is |
| Movement | Moved in this bin; whether movement is known at all |
| The person's quiet history | Their typical long-quiet lengths from earlier days (90th and 99.5th percentile) |
| Sessions | Whether a new phone session started in this bin |

## Training recipe

The checkpoint was fitted on the frozen training split only: 162 people, 4,230,516 rows and 44,560 person-days, with validation and test never read during the fit. Training ran in three stages:

1. **Real rhythm first:** 3 epochs on 3,828 observed bins from 22 real people, so the network starts from how real phones are used.
2. **Normal rhythm at scale:** 5 epochs on the model-derived parent timelines.
3. **Behavior cases:** 7 epochs fine-tuning on labeled behavior cases, programmatically added to those timelines.

The fit is reproducible: a fixed seed (20261101), a hash-locked dataset manifest and a published checkpoint hash.

## From PyTorch to the phone

| Artifact | Size |
|---|---:|
| ExecuTorch step program (`rhythm-step.pte`, XNNPACK) | 103,684 bytes |
| ExecuTorch daily program (`rhythm-daily.pte`) | 123,136 bytes |
| Plain-text weights for the Kotlin path | 197,265 bytes |

On the export machine, the ExecuTorch programs match the PyTorch reference to within 2.4 × 10⁻⁶ over 192 streaming steps, against a tolerance of 10⁻³. The model, its exports and a streaming example are published as [MaPa-Rhythm-Base](https://huggingface.co/shreyashsharma/MaPa-Rhythm-Base) under Apache-2.0.

## Personal from day one

The system uses a person's available history to set a personal context and a personal alert limit, so the same quiet hour means different things for different people. The amount and quality of history set the confidence. Population starting values cover the first days, so the model starts sensible before it knows anyone. Missing or stale input is always shown as monitoring unavailable, never read as evidence that the person is inactive.

## How the results credit the model

The alert decision combines the neural scores with the person-specific limit and policy rules. The reported v2 results evaluate that complete policy around the frozen neural checkpoint, so they credit the whole pipeline, not the neural network alone. v2 changed the policy, not the weights: the checkpoint is byte-for-byte the v1 one. See `04-results.md` for the numbers and `08-architecture.md` for the policy itself.

## What's next

- **On-device numbers for v2.** The 0.36 ms Pixel 6a timing and the Python-to-phone parity figures belong to earlier artifacts. Pixel 6a latency, RAM, CPU, battery and end-to-end Kotlin parity for v2 are the next measurements.
- **Movement data.** The movement inputs are part of the input contract, but the current training data carries no movement, so they read as unknown today. A data release with motion summaries is requested.
- **A family pilot**, the first test on people living their own lives.

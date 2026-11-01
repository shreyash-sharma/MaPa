# MaPa routine engine: API

Package `app.mapa.routine`, module `:engine`. Plain Kotlin/JVM (bytecode target 17), no Android dependencies, no third-party runtime dependencies. The app module depends on it with `implementation(project(":routine-engine:engine"))` or by copying the module in.

Status: rules, five-state machine, alert gate, freshness resolver, profile, personal head, calibrator, the five learning guards, the training scheduler, snapshot/restore and the optional movement signal are written and tested (34 tests, `gradle :engine:test`, including Kotlin/Python parity against the trained model and a restore-equals-uninterrupted test). The trained model is in `models/`.

## Time

Everything is counted in 15-minute **bins of local time**. Bin `t` covers minutes `[t*15, t*15+15)` after local midnight of day 0. On the phone use the local epoch: `t = floor(localEpochMinutes / 15)` and `dowOffset = Bins.EPOCH_DOW` (3, since 1970-01-01 was a Thursday; 0 = Monday). Helpers live in `Bins` (`day`, `binOfDay`, `minuteOfDay`, `dow`, `daytype`).

## Entry points

| What | Call | Where it runs |
|---|---|---|
| Build the engine once per install | `RoutineEngine(config, backbone, prior, silencePrior, dowOffset, installT, silencePriorDays)` | Parent phone |
| Feed every closed 15-minute bin | `engine.onBin(obs: BinObservation, paused: Boolean): TickResult` | Parent phone, from the 15-minute WorkManager job |
| Life tag (unwell, travelling, guests, festival, check-in answer) | `engine.addLifeTag(LifeTag(startT, endT, kind))` | Parent phone; tags typed by the family arrive via sync |
| New-routine question waiting? | `engine.pendingQuestion: NewRoutineQuestion?` | Parent phone, synced to family |
| Family's answer | `engine.answerNewRoutine(yes: Boolean)` | Parent phone |
| Family-visible status and freshness | `FamilyStatusResolver().resolve(now, lastSyncedSnapshot)` and `.staleAlert(now, last)` | Server or family app |

Load the model with `val w = RhythmWeights.load(file)`, then `KotlinGruBackbone(w)` (pure Kotlin, works everywhere) or the LiteRT backbone on phones that have it; `prior = w.prior()`, `silencePrior = w["silence_prior"].data`, `silencePriorDays = w["silence_prior_days"].data[0].toDouble()`.

## Input: one tick

```kotlin
data class BinObservation(
    val t: Int,             // bin index (local time, see above)
    val interactions: Int,  // interaction events in the bin; -1 = could not observe (usage access lost, app not running)
    val charging: Boolean,
    val battery: Int,       // percent
    val moved: Boolean? = null, // movement: true moved, false sensor listening and still, null no signal
)
// plus `paused: Boolean` on onBin: parent paused sharing; nothing is collected or learned while paused
```

`moved` comes from core's `BinCounter.observations(..., moved)` (Activity Recognition on Android, Core Motion on iPhone later): true if the phone was walking, running, cycling or in a vehicle for any part of the bin, false if the sensor listened the whole bin and saw none, null with no permission, before registration, or while paused. It is optional. With `moved` always null the engine behaves as it did before movement existed (tested).

What movement does: a moved bin counts as a sign of life, so it ends a silence for the rules, the 8-hour floor and the model, and the personal profile learns "used or moved". Movement alone never makes the state `ACTIVE` (nobody touched the phone); for 30 minutes after it the state is `QUIET` with `Reason.RecentMovement`. First-use times, trends and the routine-shift question stay based on phone use only.

The engine derives the rest itself, so the app does not pass them: minutes since last interaction (`FeatureBuilder` tracks it), time of day and weekday (from `t` and `dowOffset`), recent counts, and the personal profile. Missed bins (WorkManager delayed by Doze, phone off) are filled in as unobserved automatically when the next bin arrives: just call `onBin` with the current bin. Bins must arrive in increasing order.

**Data freshness is not an engine input.** The phone reports what it saw; freshness is judged where the data is received, by `FamilyStatusResolver` (default: stale after 60 minutes without a sync, a monitoring alert after 120). That keeps "quiet" and "we can't see" separate by construction.

## Output: `TickResult`

| Field | Meaning |
|---|---|
| `decision.state` | `ACTIVE`, `QUIET`, `UNUSUAL_INACTIVITY`, `MONITORING_UNAVAILABLE`, `PAUSED_BY_PARENT` |
| `decision.reason` | Reason code for the language layer (below) |
| `score` | Deviation score 0..1: share of this parent's ordinary silences that were less unusual than the current one |
| `modelReady` / `decider` | Whether the model has cleared its confidence bar, and who decided (`RULES` or `MODEL`) |
| `expectedByT` | Bin by which phone activity would normally be expected (the forecast line), or null if not within 24 h |
| `alerts` | `Alert.UnusualInactivity`, `Alert.LowBattery` (already deduplicated, rest-period held, cooled down) |
| `snapshot` | `SyncedSnapshot` to upload: state, reason, last interaction bin, battery, charging. This is all that leaves the phone. |
| `trained`, `heldBack`, `trend` | Training that ran this tick, whether the anchor guard held a step back, and the weekly `TrendReport` when one is produced |

Confidence shown to the family is `Reason.LongerThanUsual.confidence` (same number as `score`, null while the model is not ready). `recurrenceWeeks` says how rare the silence is for this parent ("a quiet stretch like this happens about once in 6 weeks").

The model calls a silence unusual when silences this surprising happen less than `EngineConfig.modelMaxWeeklyRate` times a week for this parent (default 0.25, once in four weeks). Raise it for earlier alerts with more false ones; harness trade-offs are in README.md.

### Reason codes (`sealed interface Reason`)

| Code | Fields | Example wording (language layer) |
|---|---|---|
| `RecentInteraction` | `lastInteractionT` | "Phone last used at 11:42" |
| `RecentMovement` | `lastMovedT` | "Phone moved at 11:15 (out on a walk or a drive)" |
| `WithinRoutine` | `expectedByT` | "Usually picks up the phone by 7:40" |
| `LongerThanUsual` (`silentSinceT` is the last use or movement) | `silentSinceT`, `expectedByT`, `usualFirstActivityMinute`, `confidence`, `recurrenceWeeks`, `decidedBy`, `floorRule` | "Mom hasn't used her phone since 7:05, and she's usually active by 8:30" |
| `NoFreshData` | `lastSyncT`, `lastBattery`, `lastCharging` | "No update for 3 hours; the battery was at 6%" |
| `UsageAccessMissing` | none | "Usage access is off on Mom's phone" |
| `SharingPaused` | none | "Sharing paused" |

No reason carries medical, safety or emergency meaning.

## Training: how days get in

The app does not feed day records by hand. `onBin` builds a `DayRecord` per local day (counts per bin, plus the model's cached outputs) and files it in `engine.ledger` when the day closes. Training is triggered inside `onBin` by `TrainingScheduler`:

- Days 1 to 14: rules decide, model learns silently, retrain every night.
- Days 15 to 30: retrain every night; the model decides once it has 7 clean days and 30 completed silences; rules stay as a floor.
- Day 31 on: full retrain weekly (the night after Sunday), calibrator refresh nightly.
- Only 00:00 to 05:00, on charge at 30%+, after 30 idle minutes; after two missed nights a phone at 60%+ trains anyway.

## Persistence across process death

```kotlin
val text: String = engine.snapshot()          // after each onBin, write to app-private storage
val engine = RoutineEngine.restore(config, backbone, prior, silencePrior, text, silencePriorDays)
```

The snapshot is versioned text (`mapa-engine-state 3 ...`; it now stores movement per bin, and versions 1 and 2 from earlier today are not readable, so rebuild from the journal once), no dependencies, and round-trips all mutable state: ledger days and tags, profile, personal head, calibrator, readiness, GRU hidden state, silence trackers, scheduler, state-machine latch, alert gate, anchor, new-routine detector and trend baseline. Config, weights and priors are passed again, not stored. `SnapshotTest` checks that a restored engine produces identical `TickResult`s to an uninterrupted one over 70 simulated days. Size is roughly 12 KB per stored day (the ledger keeps up to 63 days, so up to about 800 KB); write it to a file, not SharedPreferences.

Guards (all in `GuardConfig`): 7-day quarantine buffer with flagged, poorly covered and tagged days dropped; life tags (a tag entered later still removes its days at the next retrain); anchored baseline (weekly step limit and a maximum drift from the month-one model); new-routine question after 3 weekly checks with the same 45+ minute shift in first phone use; weekly trend report worded as phone patterns.

## New routine

```kotlin
engine.pendingQuestion?.let { q ->
    // q.shiftMinutes (> 0 = later), q.weeks, q.sinceDay
    // show: "Dad's mornings have started about an hour later for 3 weeks. Is this his new routine?"
}
engine.answerNewRoutine(yes = true)   // adopts: retrains on days since q.sinceDay, resets the anchor
engine.answerNewRoutine(yes = false)  // keeps the old baseline, keeps flagging, asks again after 3 weeks
```

## Model files and which backbone to run

Bundle in `assets/`:

| File | Needed | What |
|---|---|---|
| `models/rhythm_v2.weights.txt` | Always | GRU weights (18 inputs, with movement), population prior (`prior`), silence prior (`silence_prior`, `silence_prior_days`). Replaces `rhythm_v1.weights.txt`, which no longer loads (the engine refuses mismatched weights). Load with `RhythmWeights.parse(assets.open(...).reader())`. |
| `models/rhythm_step_int8.tflite` | Only if using LiteRT | Same GRU step, int8 weights, float I/O. 20 KB. |

**Default: `KotlinGruBackbone`.** The model has 10,864 parameters and runs once per 15 minutes (about 10k multiply-adds), so LiteRT buys nothing measurable on battery or latency, adds a native dependency, and the Kotlin path is exactly what the parity test and harness verify. Keep LiteRT as an option: `android/LiteRtBackbone.kt` is a ready drop-in (not compiled here, no Android toolchain), and the prior tables still come from the weights file either way.

LiteRT signature (`serving_default`), measured by `ml/mapa_rhythm/export.py`:

| Tensor | Name | Shape, type |
|---|---|---|
| Features in | `args_0` | float32[1,18] |
| Hidden state in | `args_1` | float32[1,48] |
| Hazard logits out | `output_0` | float32[1,16] |
| Hidden state out | `output_1` | float32[1,48] |

Int8 (dynamic-range weights) vs float on fresh synthetic parents: mean next-bin probability error 0.0006, max 0.0069, log-loss 0.2748 vs 0.2747. A fully static int8 file (int8 activations) drifts through the recurrence (mean error 0.050) and is not recommended.

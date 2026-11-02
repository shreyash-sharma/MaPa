# Architecture

All the intelligence runs on the parent's own phone. Phone-activity signals are scored right there, and only a status and a short summary travel to the family. If updates stop, the family sees "Monitoring unavailable", never a false all-clear.

![MaPa activity scoring and family summary flow: permitted phone-activity signals, 21 inputs summarized in 15-minute steps, are scored on the phone owner's device by the MaPa Rhythm Hybrid and a personal policy; only a status and short summary reach the family member's view, and stale input is shown as Monitoring unavailable](architecture.svg)

## The system at a glance

| Layer | Where it runs | What it does |
|---|---|---|
| Collection | Parent phone | Reads only *when* the phone was used (Android Usage Access event times) and turns it into 96 quarter-hour bins a day |
| Routine engine | Parent phone | Pure Kotlin. Features, the five states, the alert gate, the personal profile, the learning guards and the training schedule |
| MaPa Rhythm Hybrid | Parent phone | The 9,594-parameter network. ExecuTorch first, with a pure-Kotlin path on the same weights |
| Decision policy v2 | Parent phone | Turns the network's scores and the person's own limit into a state and, rarely, an alert |
| Sync | Firestore | Derived state only, written by the parent's phone alone, readable only by invited family members |
| Family app | Family phones | Cards, the day dial, history, the rhythm view, alerts and the daily note |

## 1. Sensing: time, not content

The parent app asks for one Android permission, Usage Access, and reads only the *times* of interaction events. It never reads app names, messages, photos, the screen, the camera, the microphone or GPS.

Every 15 minutes a WorkManager job closes the current bin, backed by an alarm and an unlock trigger for phones that defer background work. Each bin records:

- how many interaction events happened, or `-1` when the phone could not observe (Usage Access lost, app stopped)
- whether the phone was charging, and the battery level
- optionally, whether the phone moved (Activity Recognition), which counts as a sign of life but never on its own makes the state Active

If Android delays the job, the missed bins are filled in as *unobserved* when the next one arrives. Unobserved is never treated as quiet, and that one rule keeps "quiet" and "we can't see" apart all the way to the family's screen.

## 2. Scoring on the phone

Each closed bin becomes 21 inputs: the phone-use state, time of day and weekday, charging and battery, the person's learned profile, their typical quiet lengths, and a session-start flag (full list in `02-model.md`). The Rhythm Hybrid reads them as a stream and produces four outputs every quarter-hour:

- the chance of the next phone use in each of the next 16 quarter-hours (4 hours ahead)
- how likely the phone is in use right now
- an unusual-quiet score
- a confidence

The forecast the family sees, such as "usually picks up the phone by 7:40", blends the network's hazards (15%) with the person's own profile (85%), a mix chosen on held-out training folds.

The runtime is layered for reliability: the ExecuTorch programs (XNNPACK) load first and are checked against the Kotlin implementation on a fixed input. If anything is off, the pure-Kotlin path runs the same weights.

## 3. The decision policy

The network scores; the policy decides. v2, chosen after the first test, works in four moves:

1. **Combine.** The behavior score and a running surprise score are averaged into one number.
2. **Compare with this person.** The number is checked against a personal, confidence-adjusted limit learned from that person's own history, so an hour of quiet means different things for different people.
3. **Backstop.** Twelve hours (48 bins) of continuous *observed* daytime quiet always alerts, whatever the score says. Missing bins never count toward it.
4. **Respect context.** On a day tagged unwell, travelling, guests, festival or similar, unusual-quiet alerts are suppressed and the day stays out of the personal history.

## 4. States and the alert gate

Every quarter-hour ends in exactly one of five states:

| State | Meaning |
|---|---|
| Active | The phone was used in the last 30 minutes |
| Quiet | No recent use, still within this person's usual pattern |
| Unusual | Quiet for longer than usual for this person; stays until the next interaction |
| Monitoring unavailable | Updates are missing or stale; never shown as Active or Quiet |
| Paused by parent | The phone owner paused sharing; only the fact of the pause is shared |

Alerts pass a gate before anyone's phone buzzes: one alert per quiet stretch, a 3-hour cooldown, alerts that fall in overnight rest hours held until morning, and at most one low-battery warning per discharge. Every alert carries a plain reason code and the times behind it, such as "not used since 7:05; usually active by 8:30". None carries medical or emergency meaning.

## 5. Learning on the phone

The network ships pretrained. What the phone learns is the *person*: their activity profile by day type, their typical quiet lengths and their personal alert limit.

- **Schedule:** nightly for the first 30 days, then weekly on Sunday night. Only between midnight and 05:00, on charge at 30% or more, after 30 idle minutes; after two missed nights, a phone at 60% or more trains anyway.
- **Who decides:** rules decide alerts for days 1 to 14 while the model learns in the background; from day 15 the model decides once it has 7 clean days and 30 completed quiet stretches. Rules remain as a floor.
- **Guards against mislearning:**
  - a 7-day quarantine before a day can teach (a 1-day fast lane while learning)
  - tagged days are skipped, even if the tag is added later
  - each update can move only a bounded distance from the last, with a maximum drift from the month-one baseline
  - a shifted routine (the same 45-minute-plus change for 3 weeks) becomes normal only after a person answers "Yes, this is the new routine"
  - slow drift is shown to the family as a weekly rhythm trend, never as an alarm

All engine state survives Android process restarts through a versioned snapshot. A unit test checks that a restored engine produces identical results to an uninterrupted one over 70 simulated days.

## 6. What syncs, and who can read it

The parent's phone signs in anonymously; its account *is* the parent record. Family members sign in with email and join only by redeeming a code. Everything that leaves the phone is built in one place in the code, so the privacy promise can be audited in a single file.

| Firestore path | Written by | Contents |
|---|---|---|
| `parents/{id}/status/current` | Parent phone only | State, reason code with times, battery and charging, the forecast, the note facts, model facts (runtime, size, step time) |
| `parents/{id}/days/{date}` | Parent phone only | Today's 96 bins reduced to used, not used or not observed, plus the usual band |
| `parents/{id}/events` | Parent phone only | The "Why did this change?" timeline |
| `parents/{id}/trends` | Parent phone only | Weekly rhythm trend |
| `parents/{id}/tags` | Parent or a member | Life tags of a fixed kind, at most 14 days long |
| `parents/{id}/routine` | Parent phone; a member may answer once | The new-routine question and its answer |

The security rules enforce it: only the parent's phone can write derived data, only invited members can read it, invite codes are fetched by exact code and can never be listed, invites expire within 25 hours and family-started pairings within 8 days. While sharing is paused, the status carries only the fact of the pause: no times, battery, note or forecast.

## 7. The family side

The family app shows each parent as a card: the state, today's activity over the usual band, when activity is usually expected, a plain-language reason, the daily note and a "Why did this change?" timeline. Tabs cover Today, History (7-day view and trend) and Rhythm, which shows the on-device model to the family with real facts from the parent's phone.

Alerts do not need a server. The family app raises them itself, from a live listener while open and from a 15-minute background check otherwise, including "updates stopped" after 2 hours without a sync. A status with no sync for 60 minutes is shown as Monitoring unavailable. On Android's power-saving Doze mode, expect 15 to 30 minutes of delay. Cloud Functions for instant server push, a stale sweep and retention are written and ready for the paid Firebase plan; the in-app checks stay as a backup, and alerts are de-duplicated.

## 8. The daily note

The parent's phone runs no language model. It syncs the note's *facts* (times, counts, battery), and the family member's phone writes the note in that member's language. The writer chain is built in: with the opt-in "Use Meta AI" switch on, it is Meta's Muse Spark, then the Meta open model on Baseten, then on-device Gemini Nano, then a template; with it off, Gemini Nano or the template. End-to-end runs with a live key are part of the demo verification in `07-pilot-guide.md`. The prompt carries a `[NAME]` placeholder, never the parent's name, and the phone puts the name back.

Every AI-written sentence passes the claims guard before anyone sees it:

- at most 280 characters
- no medical, safety, emergency or all-clear language, in English or Hindi
- every clock time must come from the facts, and the only percentage allowed is the battery
- the note must be in the requested language

A note that fails is replaced by the template. It is never edited and shown.

## 9. Scale on the free tier

The pilot runs on Firebase's free Spark plan. Each parent phone writes about 200 documents a day against a quota of 20,000, so the free tier fits roughly 75 to 100 parents (an estimate). On Spark, the parent's phone deletes its own synced data after 30 days.

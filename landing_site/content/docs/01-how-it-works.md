# How MaPa works

MaPa gives families who live apart a calm, live read on a parent's day, from the phone they already carry. No cameras. No wearables. No daily check-ins. A personal model learns one person's own rhythm and flags an unusually quiet morning in hours, not a day later.

1. **Setup is one tap for the phone owner.** The family sets it up; the phone owner chooses to share, and can stop sharing at any time.
2. **The phone reads timing, not content.** MaPa uses phone-use timing and selected device signals. It does not need app names to score a routine.
3. **A personal model learns their rhythm.** It compares recent activity with the person's own history and reports one of five states: active, quiet, unusual, monitoring unavailable, or paused.
4. **The family gets context, not surveillance.** A short daily summary and the last update time, built to prompt a thoughtful check-in.
5. **It never fakes an all-clear.** Stale data is shown as monitoring unavailable, never as a current status.

## Why a learned rhythm beats a timer

Most check-in tools run on a fixed timer or a daily button press. A timer has to pick one number for everyone: short, and it cries wolf every time someone sleeps in; long, and a quiet morning goes unnoticed until evening.

MaPa learns when *this* person usually picks up their phone, on *this* kind of day, and how long their ordinary quiet stretches run. A quiet 9 a.m. means something different for an early riser than for a night owl. In the v2 derived-data simulation, the policy produced an alert in each of 108 labeled behavior-case windows at about one-sixth the false-alert rate of a 4-hour timer. Twenty-eight windows had no changed phone-use or observation inputs; see the full results and limits in `04-results.md`.

## A morning with MaPa

An illustrative example, not a real person's data:

| Time | On the parent's phone | What the family sees |
|---|---|---|
| 7:10 | No use yet; their phone is usually picked up by 7:40 | Quiet. "Usually picks up the phone by 7:40" |
| 7:40 | Still quiet, still within their normal range | Quiet |
| 9:10 | Quiet for far longer than usual for this person | Unusual, with the reason: "Not used since last night; usually active by 7:40". One alert, with a one-tap Call |
| 9:25 | The phone is picked up | Active. The alert clears itself |

If the parent's phone stops reporting altogether (off, no network, no battery), the family sees "No update since…" and Monitoring unavailable, never a reassuring green.

## The first 30 days

- **Day 1:** setup. The family sends a link; the phone owner reads one screen, taps Yes, and switches on Usage Access. Starting values from the population mean MaPa is sensible from the first hour.
- **Days 1 to 14:** simple rules decide alerts while the personal model learns in the background.
- **Day 15 on:** the model takes over once it has enough clean days, with the rules kept as a floor.
- **Every night for the first month, then weekly:** the phone relearns the person's rhythm, only while charging and idle. Days tagged unwell, travelling, guests or festival are skipped, and a changed routine becomes the new normal only when a person confirms it.

Details are in `08-architecture.md`.

## Who controls what

| The phone owner | The family |
|---|---|
| Says yes, or no, to sharing | Invites the phone owner and sets things up |
| Can pause sharing at any time; the family sees only "Paused" | Sees the state, the day's pattern, the usual times and a daily note |
| Can see who is connected and remove anyone | Can tag a day as different, so it is not learned or alerted on |
| Can stop sharing and delete everything | Answers "Is this the new routine?" when a routine shifts for weeks |

## Built for check-ins

People have different routines, and a quiet phone has many ordinary explanations. MaPa is a check-in companion, not a health or safety service: it cannot tell whether someone needs help. If you are concerned, contact the person directly or use local emergency services.

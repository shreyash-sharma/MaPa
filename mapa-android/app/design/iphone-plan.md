# MaPa on iPhone: plan (2026-10-19)

Status: plan only, nothing built. Claims marked *unverified* need a prototype or Apple's confirmation.

## What stays the same on both platforms
- **The product promise:** the model runs on the parent's phone; only conclusions leave it (state, reason,
  "usually active by", today's 96 slots as yes/no/unknown, the AI note after the claims guard). Raw usage,
  movement types, places and app names never leave the phone.
- **Shared code:** `core/` and `routine-engine/` are plain Kotlin, so they move to **Kotlin
  Multiplatform** and ship inside the iPhone app unchanged (rhythm model, illness guards, phrases, claims
  guard, card builder). UI stays native: SwiftUI on iPhone, Compose on Android.
- **Setup:** the family member sets everything up and papa only taps Yes. The invite (language, name,
  relation) travels in a link or QR code.
- **Languages:** reviewed text per language in one shared file loaded by both apps; on-device translation
  only for live text (ML Kit has an iOS SDK; Apple's Translation framework on iOS 18+ is the alternative).

## 1. Family app on iPhone (build first)
No platform blockers. Firebase Auth, Firestore and push (APNs through FCM) all work on iOS. Same screens as
Android: home, parent detail with the day dial, Call, "Mark today as different", history, settings.
Many of the adult children are on iPhone, so this is the larger audience and the lower risk.

## 2. Parent app on iPhone (prototype first)
iPhone has no equivalent of Android's Usage Access. Apple's Screen Time data runs in a sealed extension
that cannot make network requests or move data out (developers report they cannot even pass it to their
own app). So the iPhone parent app works from signals Apple does allow, within Apple's rules:

### Signal A: movement (Core Motion), primary
- **What:** the phone's own record of still / walking / running / cycling / in a vehicle, plus step
  counts. Apple keeps about the **last 7 days** of history (*per an Apple forum reply, unverified*).
- **Permission:** one ordinary "Motion & Fitness" prompt. No special approval from Apple.
- **How MaPa uses it:** fill the 96 slots per day with `moved` = true / false / unknown, the same field
  the Android movement signal now feeds (see below). The model learns papa's movement rhythm the same way.
- **Why it suits iPhone:** iOS rarely lets apps run in the background, but because the system keeps the
  history, MaPa can read a whole day in one batch (background refresh, or overnight while charging).
- **Limits:** a phone left on a table reads as still; a parent who walks around the house without the
  phone looks inactive. Alerts are slower than Android, because reads happen when iOS lets the app run.

### Signal B: phone use via Screen Time (DeviceActivityMonitor), secondary, needs Apple approval
- **Idea:** schedule the Screen Time monitor in 15-minute windows with a small usage threshold (for
  example one minute). When the threshold is reached, the monitor extension writes only "used in this
  window: yes" into MaPa's own app storage. The model reads those yes/no slots on the phone; only
  conclusions are sent. No usage data, app names or totals leave the extension.
- **Status:** one developer reports writing aggregates from the monitor extension to an App Group and
  syncing them *(unverified)*. Others report the report extension cannot pass data out at all.
- **Gate:** needs Apple's **Family Controls** entitlement for distribution, granted by hand, case by case,
  and the parent's Screen Time authorization (one system prompt). Whether App Review accepts sending
  conclusions derived from it is not settled publicly; Apple told one developer to book an App Review
  session.

### Not available on iPhone
Unlock events in the background, other apps' usage, battery history beyond what the app sees while running.

### Background work on iPhone
- `BGAppRefreshTask` for short, opportunistic runs; `BGProcessingTask` overnight while charging (model
  retraining, as on Android).
- Silent pushes can wake the app occasionally but iOS rate-limits them; never rely on them for alerts.
- Consequence: on iPhone, "Unusual" is decided when the app gets a run, so the family may hear later
  than on Android. The family app must show "Last update" honestly (it already does).

### AI notes on iPhone
Apple's on-device Foundation Models framework (iOS 26, Apple Intelligence devices) can write the daily note
like Gemini Nano does on Android; older iPhones and unsupported languages fall back to templates
*(Hindi support unverified)*. Notes still pass the claims guard.

## 3. The Android movement signal (built today)
- Google Play services **Activity Recognition** transitions (walking, running, cycling, in a vehicle),
  delivered to a broadcast, low power, nothing kept running.
- Stored on the phone as start/end times only (`rhythm/movement.txt`, last 4 days), deleted with
  "Stop and delete everything".
- Per slot `moved: Boolean?`: true if any movement overlapped the slot, false if the signal was listening
  for the whole slot and saw none, null if no permission / not yet listening / paused. Tested in
  `core` (`MovementBinsTest`, 4 tests) and on a Pixel 6a.
- Permission: one "Allow physical activity" tap right after papa says Yes.
- The Routine engine work is adding `moved` to the model's input (16 to 18 features, plus a
  signal-present mask; movement alone never makes a day "Active"). Until that lands, the app computes and
  logs the slots but does not feed them.
- Shared with iPhone: the same `moved` slots come from Core Motion there, so one model serves both.

## 4. Prototype plan for the iPhone parent side (about a week)
1. Day 1-2: Core Motion reader. Fill 96 slots from history; run on a test iPhone for several days.
2. Day 2-3: compare movement slots with how the person's day actually went; measure how often a day
   reads "still" while the person was up.
3. Day 3-4: request the Family Controls entitlement; build the DeviceActivityMonitor threshold experiment
   in development builds; check whether the yes/no slots reach the app.
4. Day 4-5: background runs: how often iOS lets MaPa read and decide, overnight retraining.
5. Book an App Review session (Meet with Apple) with the conclusions-only design.

**Go / no-go:** ship the iPhone parent app if movement alone catches a quiet morning within a few hours
on most test days, with Screen Time as an upgrade if Apple approves it. Otherwise ship the family app on
iPhone and keep the parent side on Android.

## Open questions
- Does Apple accept a product whose purpose is family awareness under the Family Controls entitlement?
- How many older parents in India carry iPhones versus Android phones? (no data yet)
- Apple Foundation Models language coverage for Hindi and other Indian languages.

## Sources
- Screen Time extension sandbox: https://matteing.com/writing/a-curious-way-to-grab-screen-time-data-on-ios
- Apple forums, report extension data: https://developer.apple.com/forums/thread/766786
- Apple forums, server sync question: https://developer.apple.com/forums/thread/791393
- Family Controls entitlement review: https://drobinin.com/consulting/screen-time-family-controls/building-with-screen-time-and-family-controls/
- Core Motion activity manager: https://developer.apple.com/documentation/coremotion/cmmotionactivitymanager
- 7-day motion history (forum): https://developer.apple.com/forums/thread/685532
- ML Kit translation on iOS: https://developers.google.com/ml-kit/language/translation/ios
- Apple Translation framework: https://developer.apple.com/documentation/translation/translationsession
- Android Activity Recognition: https://developer.android.com/codelabs/activity-recognition-transition
- Similar apps: https://parentsareok.app/ , https://www.iamfine.app/

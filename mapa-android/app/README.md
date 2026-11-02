# MaPa app (MVP)

Two Android apps and a Firebase backend, built around the on-device rhythm model from `../routine-engine`.

- **MaPa** (`parent/`, `app.mapa.parent`) runs on the parent's phone. Consent first, then it reads
  only *when* the phone was used (Usage Access event times, no app names, no content, no location),
  turns that into 15-minute bins, and runs the rhythm model on the phone. The model trains on the
  phone: nightly for the first 30 days, then weekly, with the illness guards from the engine.
  An on-device open LLM writes a short daily note from structured facts. Only derived state leaves the phone.
- **MaPa Family** (`family/`, `app.mapa.family`) shows each parent as a card: one of five states,
  today's activity over the usual band, when activity is usually expected, a plain-language reason,
  the AI note, and a "Why did this change?" timeline. Family can tag a day (unwell, travelling,
  guests, festival) and answer the "new routine?" question.
- **Backend** (`backend/`): Firestore rules. The pilot runs on the free Spark plan: the family app
  checks for alerts in the background and the parent's phone deletes its own data after 30 days.
  `backend/functions` (server push, stale sweep, retention) is ready for when we move to Blaze.

## Layout

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin/JVM. Engine host (journal + snapshot), bins, wire format, English/Hindi phrases, AI note prompt and claims guard, family card builder. 26 unit tests. |
| `shared/` | Android library: Firebase setup (real project or local emulator), Firestore paths, theme, rhythm chart and parent card UI. |
| `parent/` | Parent app: onboarding and consent, collection (WorkManager + backup alarm + unlock), engine runtime, AI note model, sync, invites, pause, delete everything. |
| `family/` | Family app: email sign-in, pairing by code, live cards, timeline, weekly trend, tags, routine question, alerts. |
| `../routine-engine/engine` | The rhythm model (owned by the Routine engine work). Included as `:routine-engine`; never edited here. |
| `backend/` | `firestore.rules`, `functions/` (TypeScript), `rules-test/` (security rule tests). |
| `tools/typecheck/` | Cloud-sandbox-only compile check against stubs. Not part of the app build. |

## Current Hybrid decision policy (2026-11-02)

The parent app's `ModelStore` now selects `rhythm_hybrid-v2-policy+cnn-cal1`. The trained 9,594-parameter neural checkpoint and its ExecuTorch programs are unchanged from v1. V2 changes the decision policy around those weights:

- Use the personalized model score and threshold.
- Keep a 12-hour continuous observed daytime-quiet backstop.
- Suppress model and rule alerts on an explicitly tagged day.
- Do not let the outer 8-hour `RuleBaseline` floor override v2.

The v1 decision policy remains available in `HybridDecisionPolicy.V1` for reproduction. V2 results come from a derived-parent evaluation, not observed emergencies. The two highest false-alert scenarios remain `daytime_inactivity` and `phone_left_home`.

## What is shared, and what never leaves the phone

| Leaves the phone (Firestore) | Stays on the phone |
|---|---|
| State, reason code, times like "usually active by 7:45" | Raw usage events and timestamps |
| Today's 96 bins as yes/no/not-observed, and the usual band | App names (never read) |
| Battery level and charging | The model's weights and training journal |
| The AI note text (after the claims guard) | The note model and its prompt |

The AI note never mentions health or safety, never says "all is well", and can only use times
present in the facts. Anything else falls back to a template.

## Run it with no Firebase account (local emulator)

Nothing here creates a real Firebase project. Without a `google-services.json` the apps connect
to the Firebase Emulator Suite under the demo project `demo-mapa`, which is free and offline.

1. Install Node 20+ and Java 17+, then `npm i -g firebase-tools`.
2. Start the backend:
   ```
   cd backend/functions && npm install && npm run build && cd ..
   firebase emulators:start --project demo-mapa
   ```
   The Emulator UI is at http://localhost:4000.
3. Open this folder (`mapa-android/app`) in Android Studio (Ladybug or newer) and let Gradle sync.
   `routine-engine` must sit next to it, at `mapa-android/routine-engine`.
4. Run `parent` on one emulator and `family` on another. Android emulators reach your PC at
   `10.0.2.2`, which is the default.
   For a real phone on the same Wi-Fi, put your PC's LAN address in `gradle.properties`:
   `mapa.emulatorHost=192.168.1.20`.
5. On the parent app: go through consent, grant Usage Access, tap **Invite a family member**. In debug builds,
   **Replace history with 21 sample days** fills the model with a synthetic routine so the card has something to show.
6. On the family app: create an account, tap **Add a parent**, enter the code.

Alerts without a server: the family app raises them itself, from a live listener while it is open
and from a 15-minute background check (`FamilyCheckWorker`) otherwise. That includes "updates
stopped" after 2 hours of silence. Expect 15-30 minutes of delay under Doze.

## The AI note model

The parent app uses MediaPipe LLM Inference with an open model file at
`files/models/note-model.task`. Default: Gemma 3 270M instruction-tuned (int4/int8 `.task` bundle).
Without the file, notes come from templates, and the card says so.

To install it on a debug build:
```
adb push gemma3-270m-it.task /data/local/tmp/note-model.task
adb shell run-as app.mapa.parent mkdir -p files/models
adb shell run-as app.mapa.parent cp /data/local/tmp/note-model.task files/models/note-model.task
```
Or set `mapa.noteModelUrl=https://...` in `gradle.properties`; the app downloads it once on Wi-Fi
while charging.

## Tests

- Core: `./gradlew :core:test` (26 tests: engine host restore and replay, the model flagging a silent
  morning, wire format, paused/stale handling, Hindi and English text, claims guard).
- Security rules: `cd backend/rules-test && npm install && npm test` (8 tests on the Firestore emulator).

## Going to a real Firebase project

Decision (2026-10-19): pilot on the free **Spark** plan, no card.

1. Create a Firebase project, add Android apps `app.mapa.parent` and `app.mapa.family`, and drop each
   `google-services.json` into `parent/` and `family/`. The google-services plugin turns on by itself.
2. Enable Anonymous and Email/Password sign-in.
3. `firebase deploy --only firestore` (rules and indexes). Do not deploy functions on Spark.

Spark's Firestore quota is 20,000 writes a day. Each parent phone writes about 200 a day, so the
free tier fits roughly 75-100 parents (estimate). Moving to Blaze later means deploying
`backend/functions` for instant push; the in-app checks stay as a backup and alerts are de-duplicated.

## Status (2026-11-02)

- Verified: routine-engine Kotlin unit tests pass, including the v1/v2 floor boundary, v2 tag suppression,
  and outer-rule integration. Parent Kotlin compilation and debug APK builds for both apps pass with the emulator backend.
- The parent debug APK includes the v2 decision policy around the frozen neural checkpoint. The model package is published at
  [MaPa-Rhythm-Base](https://huggingface.co/shreyashsharma/MaPa-Rhythm-Base).
- Not measured: installation on a physical phone, v2 runtime latency, RAM, CPU or battery use.
- These are debug APKs for demo/testing. They connect to the Firebase Emulator Suite and need it running for parent-family sync.

## Meta AI Developer Hackathon additions (2026-10-27)

**Rhythm CNN on ExecuTorch (parent app).** The parent app now prefers a causal dilated 1D-CNN over the GRU.
`routine-engine/ml/mapa_rhythm/train_cnn.py` trains four sizes; `export_executorch.py` writes a `.pte` (XNNPACK).
`parent/build.gradle.kts` bundles `rhythm_cnn_<size>.weights.txt` and `.pte` as `rhythm_cnn.*` assets
(`-Pmapa.cnnSize=s` by default). `ModelStore` loads the weights, then `ExecuTorchBackbone` loads the program and checks it against
the Kotlin CNN on a fixed input. Any problem means the Kotlin CNN (`KotlinCnnBackbone`, same weights) runs instead; the old GRU is the
last resort. The parent's More screen has an "Under the hood" section: model id, runtime in use, time per step on this phone.

**Meta AI in the family app (opt-in).** Settings has a "Use Meta AI" switch, hidden when the build has no key and off by default.
When on, the daily note is written by Muse Spark, then the Meta open model on Baseten (`meta-models/muse-spark-open-1026`), then Gemini
Nano, then the template. Every note still passes `ClaimsGuard`. The prompt carries `[NAME]`, never the parent's name; the phone swaps
the name back in. The parent screen "tell MaPa in your own words" sends the member's typed text plus the current local time to the
model, gets back `kind/start/end` as JSON, validates it (`CheckIn.parse`), and shows one Add button. A person confirms; the model never
decides an alert.

**Keys (local testing only).** Put them in `app/local.properties` (git-ignored) or `~/.gradle/gradle.properties`:

```
mapa.museSparkKey=...
mapa.basetenKey=...
```

A key inside an APK can be extracted. A shipped build must call a server proxy instead (Cloud Functions, needs the Blaze plan).
Never use the `-contributor` Muse Spark model: it lets Meta train on prompts.

**Model facts in the status (2026-10-27).** `parents/{pid}/status/current` also carries `runtime`, `params`, `modelBytes`
and `stepMicros`: facts about the rhythm model on the parent's phone (which runtime, how many network parameters, the
model file size, the mean time of one step). They are not usage data. A paused status leaves them out. Only the parent's
phone writes the status (`firestore.rules`), so no rule change was needed. The family app's Rhythm tab shows them.

## Status 2026-10-28 (see `docs/STATUS.md` in the repo root for the full refresher)

- **ExecuTorch runs on the Pixel 6a.** The `.pte` was compressed in the APK, so `openFd` failed; `parent/build.gradle.kts`
  now keeps `.pte` uncompressed (`androidResources.noCompress += "pte"`). Measured: 123 µs per step on ExecuTorch
  (XNNPACK), 11 to 15 µs with the Kotlin CNN. The Kotlin CNN stays as the fallback.
- **Stitch design (v4)** in both apps; see `design/MaPa-design.md`. Fonts: Newsreader and Manrope (OFL, `shared/licenses`).
- **Family tabs:** Today, History (7-day bars, trend, timeline by day), Rhythm (the on-device model for the family:
  expected vs today, when MaPa speaks up, a 24-hour timer vs MaPa, what was learned, and the model card).
  Ask MaPa is on each parent's detail screen.
- **Model facts in the status:** `runtime`, `params` (weights and biases only: 5,360 for size s), `modelBytes`, `stepMicros`.
- **Muse Spark and Baseten:** code is in place, but no key has been added, so no cloud call has run yet.

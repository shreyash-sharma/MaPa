# Research: more languages, and low-battery location (2026-10-19)

Nothing here is built. Sources are linked at the end.

## 1. Can MaPa use the phone's Google Translate?

**Not the Translate app itself.** Google Translate has no public API that other apps can call. Its
"Tap to Translate" works as an overlay the person uses, not something an app can invoke.

**But the same kind of engine is available to apps: ML Kit on-device Translation.** Free, works offline,
runs on the phone. Its language list includes Hindi, Bengali, Gujarati, Kannada, Marathi, Tamil, Telugu
and Urdu. Caveats: it translates through English (Hindi to Tamil goes Hindi to English to Tamil), each
language is a separate model download (size not verified), and quality is machine-translation quality.
I did not confirm Malayalam, Punjabi or Odia support.

**Recommendation**
- **Don't machine-translate the consent screen at runtime.** "What is shared, what never is" must be
  exact. Instead, draft each language's strings (about 60 on the parent side) with ML Kit or an LLM, have a
  native speaker check them, and ship them in the app. That is how to add Tamil, Marathi, Bengali etc.
- **Use ML Kit on-device for live text**, such as translating papa's AI note into the family member's
  language on the family member's phone. Nothing leaves the phone.
- **Voice:** "Listen" uses Android's text-to-speech, which ships voices for many Indian languages
  (per-language availability on each phone not verified).

**The child picks the language: already built.** The family member chooses the language in "Set up a
parent"; it travels inside the invite (link or QR), and papa's phone opens directly in that language
(tested today: Hindi). Adding languages only means adding strings.

## 2. Location sharing without draining the battery

**First, a conflict.** The brief (v0.2) and the project rules say MaPa never uses GPS or location, and
that location never leaves the phone. Location sharing is a product decision that changes the privacy
promise in onboarding and the store listing.

**How others do it**
- **Life360:** turns GPS off while you are still and listens for cheaper signals (Wi-Fi, cell towers,
  geofences) that you have started moving; GPS comes back on only while moving. Its "low drain" claims
  are its own, not independently verified; one travel review measured about 20% battery on a busy day.
- **iOS apps** use "significant location change", which wakes the app when the phone changes cell tower,
  without GPS.
- **Google's own Android guidance:** prefer passive location (reuse fixes other apps already requested,
  no extra cost) or balanced/low-power accuracy (Wi-Fi and cell, about a city block), batch updates,
  use geofences (built for low battery), and avoid continuous high accuracy. Since Android 8, apps in the
  background only get a location a few times an hour anyway.

**Cheapest pattern that would fit MaPa (if you decide to add it)**
1. **On demand only:** the family member taps "Where is papa now?", a push wakes papa's phone, it takes
   one low-power fix, sends it, and keeps nothing. Battery cost is close to zero because nothing runs in
   between.
2. **Optional "home" geofence:** "Papa left home at 10:05 / is back home". Geofences are the
   battery-friendliest location feature Android has.

**The real costs are not battery**
- **Another permission for papa:** background location needs a second system screen ("Allow all the
  time"), which is exactly the hassle we just removed.
- **Play Store review:** background location requires a declaration form, a demo video and a prominent
  disclosure, and is only allowed for an app's core feature.

**Recommendation:** keep it out of release 1. If families ask for it, add on-demand location plus a home
geofence as a separate opt-in that papa turns on, and update the brief and privacy text first.

## Sources
- ML Kit translation languages: https://developers.google.com/ml-kit/language/translation/translation-language-support
- Google Translate "Tap to Translate" (2016): https://techcrunch.com/2016/05/11/google-translate-gets-in-app-translations-on-android-offline-mode-on-ios
- Life360 battery approach (VentureBeat): https://venturebeat.com/mobile/life360-battery
- Life360 location sharing: https://www.life360.com/location-sharing
- Location sharing and battery (HeyPolo): https://heypolo.com/blog/how-does-location-sharing-affect-your-battery
- Travel app comparison: https://theroadtotimbuktu.com/best-location-sharing-apps-pros-and-cons/
- Android: optimize location for battery: https://developer.android.com/guide/topics/location/battery
- Android: real-world location scenarios: https://developer.android.com/develop/sensors-and-location/location/battery/scenarios
- Android 8 background location limits: https://developer.android.com/about/versions/oreo/background-location-limits
- Play policy on background location: https://support.google.com/googleplay/android-developer/answer/9799150

## 3. iPhone (added 2026-10-19)

**Translation works on iPhone too.**
- ML Kit Translation has an iOS SDK (GoogleMLKit/Translate), on-device, same engine and languages as
  Android, so one approach covers both apps. https://developers.google.com/ml-kit/language/translation/ios
- Apple's own Translation framework (iOS 18+, `TranslationSession`) is also on-device and includes Hindi;
  other Indian languages not verified. https://developer.apple.com/documentation/translation/translationsession
- Plan for both platforms: the reviewed UI text lives in one shared file per language that both apps
  load; on-device translation is only for live text (the daily note).

**The harder iPhone problem is the parent's side, not language.**
- iOS has no equivalent of Android's Usage Access. Screen Time data (DeviceActivity) runs in a sandboxed
  extension that cannot make network requests or move data out; developers report they cannot export it.
  https://matteing.com/writing/a-curious-way-to-grab-screen-time-data-on-ios ,
  https://developer.apple.com/forums/thread/766786
- A possible route (unverified, needs a prototype): DeviceActivityMonitor schedules with small usage
  thresholds per time window, recording only "used in this window: yes/no" via an App Group. It needs
  Apple's Family Controls entitlement and the parent's authorization.
- The family app on iPhone has no such problem.

**Sharing code:** `core/` and the rhythm engine are plain Kotlin, so Kotlin Multiplatform can share the
model, phrases and claims guard with an iOS app; the UI stays native.

**Recommendation:** build the family app for iPhone first (many children are on iPhone), and run a short
prototype of the parent side on iPhone before promising it.

### What others do on iPhone (added 2026-10-19)
- Parents Are OK (iOS + Android): watches unlocks, app use and phone movement; alerts if the phone is not
  touched or moved for 24 hours (adjustable, sleep hours excluded). No GPS. https://parentsareok.app/
- CareTrigger: alerts family when the phone is inactive for unusually long.
  https://apps.apple.com/us/app/caretrigger-senior-safety/id6762222686
- I Am Fine: uses the phone's built-in step counter; missed activity emails a contact. https://www.iamfine.app/
- None publish how they read unlocks on iPhone. The documented, permission-light signal is Core Motion:
  motion-activity history (still/walking/in a vehicle) for the last 7 days and pedometer steps, behind one
  "Motion & Fitness" permission and no special Apple approval.
  https://developer.apple.com/documentation/coremotion/cmmotionactivitymanager ,
  https://developer.apple.com/forums/thread/685532
- MaPa on iPhone: fill the same 96 daily bins from motion history (phone moved / not moved / unknown)
  instead of phone use; the rhythm model, guards and conclusions-only upload stay the same. Motion is
  history the system keeps, so it can be read in one batch at night even if the app rarely runs.
  Limits: a phone left on a table reads as still; needs a prototype to check how well motion tracks a
  parent's day.

## 4. Phone-usage data for people 60+ (added 2026-10-19)
**No open dataset of 60+ phone use was found.** The studies that collected it keep it private:
- Gordon et al. 2019 (Apple, arXiv:1911.05683): 113 adults aged 60-75, about 230k unlocks over 12 weeks,
  roughly 24 unlocks a day. No data release found. https://arxiv.org/abs/1911.05683
  (Correction, 2026-10-19: an earlier note said "84 adults aged 61-76, 186,968 unlocks"; that figure came
  from a search summary and could not be verified.)
- Shen et al. 2026 (Innovation in Aging, UT Austin): 21 adults, around age 76, 28.7 ± 21.4 unlocks a day;
  data sharing not stated. https://academic.oup.com/innovateage/article/10/4/igag007/8443438
- One-year 65+ cognitive-impairment study (unlocks), still recruiting in 2025. https://arxiv.org/abs/2509.23158
- 340-user, 4-week logging study (ages not on the abstract page). https://arxiv.org/abs/1807.02472
- Kaggle "smartphone usage" sets are mostly synthetic or survey-based: not usable.
- Teen data (ABCD) exists for approved researchers; wrong age group.

**Credible path:** calibrate the synthetic pretraining generator to *published aggregates* from these
papers (licence-safe: statistics, not data), request data access from the study authors, and collect real
opt-in data from pilot parents (the only source that matches Indian 60+ users).

# MaPa telemetry spike

A deliberately tiny native Android app (Kotlin, two AndroidX libraries, no UI framework) that answers the week 1 to 2 question from the product brief:

> Can we honestly show whether activity reports are current, for several days in a row, on cheap Android phones?

It records **when** the phone was used and **whether the app was alive to notice**, across Doze, reboots, and OEM battery managers (Xiaomi, Realme, Samsung, Vivo). It is a measuring instrument, not the product.

## What it records, and what it never records

| Records (on the phone, in app-private storage) | Never records |
|---|---|
| Timestamps of unlocks, screen on/off, app opens, from Android's Usage Access history. Event **type and time only**; package names are read by the iterator and dropped | Location, camera, microphone, contacts, SMS, calls |
| Battery %, charging, battery saver, Doze state | Names of apps used, screen contents, notifications |
| Which wake-up path ran (WorkManager, alarm, boot, manual) and the gap since the last one | Accessibility services (not requested, ever) |
| Reboots (boot count), why Android killed the process (Android 11+), standby bucket, network type | Hardware ids, IMEI, phone number. The only id is a random UUID |

Nothing leaves the phone unless a tester types a sync URL into the app. Even then, only the minimal summary a family dashboard would need is sent: last interaction time, battery, health flags, phone model (for the test matrix). Collection starts only after the phone's owner ticks the consent box, and "Stop test and delete data" removes everything.

## How it works

```
                 every ~15 min                         on boot
   WorkManager ──────────────┐   AlarmManager ──┐   BootReceiver ──┐
   (HeartbeatWorker)         │   (setAndAllow-  │                  │
                             ▼    WhileIdle)    ▼                  ▼
                        Heartbeat.run(source) ── device snapshot (battery, Doze, bucket, network)
                             │                └─ Usage Access backfill since last heartbeat
                             │                └─ optional sync POST → server/collector.py
                             ▼
                      events.jsonl  ◄── SystemEventReceiver (screen on/off, unlock, Doze changes;
                                         only while the process is alive)
```

Two design choices are the point of the experiment:

1. **Two independent schedulers.** WorkManager and an inexact `setAndAllowWhileIdle` alarm both fire roughly every 15 minutes, tagged by source. The report shows which one each OEM throttles.
2. **Live receiver vs Usage Access backfill.** Screen broadcasts only reach a live process. Usage Access history is kept by the OS even while we are dead. Comparing the two tells us how much of the day the process is actually alive, and whether backfill recovers what we missed.

Source files, all in `app/src/main/java/app/mapa/telemetry/`:

| File | Role |
|---|---|
| `Collector.kt` | Application entry, consent gate, logs process starts and Android 11+ exit reasons |
| `Heartbeat.kt` | One heartbeat: snapshot, usage backfill, reboot detection, sync |
| `UsageProbe.kt` | Usage Access check and event summary (no package names kept) |
| `Device.kt` | Battery, Doze, battery saver, standby bucket, background restriction, network |
| `Scheduler.kt` | WorkManager periodic work and the backup alarm |
| `Receivers.kt` | Worker, alarm, boot and live system-event receivers |
| `Sync.kt` | Optional POST of the minimal summary |
| `Report.kt` | On-screen summary |
| `OemSettings.kt` | Deep links into Xiaomi/Realme/Oppo/Vivo/Samsung auto-start screens |
| `MainActivity.kt` | Consent screen and test dashboard, built in code |

Other folders: `server/collector.py` (stdlib-only receiver with a freshness page), `tools/analyze.py` (turns logs into a reliability report), `docs/TEST_PROTOCOL.md` (the real-device test plan), `docs/results-template.csv`.

## Build

### Android Studio (easiest, works on Windows)

1. Install Android Studio (Ladybug 2024.2 or newer).
2. File → Open → this `telemetry-spike` folder. Let Gradle sync; accept the SDK 35 download if asked. If Studio offers to upgrade the Android Gradle Plugin, that is fine.
3. Build → Build App Bundle(s) / APK(s) → Build APK(s). The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Command line

Needs JDK 17+ and an Android SDK with platform 35 (Studio installs both). Point Gradle at the SDK with `local.properties` (`sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`) or `ANDROID_HOME`.

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

Use the **debug** build for the test. It is debuggable, which lets you pull logs over USB with `run-as`.

## Sideload onto a test phone

**With USB (recommended for your own test phones)**

1. On the phone: Settings → About phone → tap *Build number* 7 times → Developer options → enable *USB debugging*. Xiaomi also needs *Install via USB* (requires a Mi account sign-in).
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

**Without a computer (for a parent's phone)**

Send `app-debug.apk` over WhatsApp, Drive or email, open it on the phone, and allow "Install unknown apps" for that app when prompted. Play Protect may warn about an unknown developer; choose "Install anyway". Turn the permission back off afterwards.

## First run on the phone

1. Open **MaPa Test**, read the consent text, tick the box, tap **Start test**.
2. **1. Allow Usage Access** → find MaPa Test → allow. Without this the app can only see the screen while it happens to be alive.
3. Steps 2 and 3 (battery exemption and OEM auto-start) are **test variables**. Follow `docs/TEST_PROTOCOL.md` for when to turn them on.
4. Tap **Record a heartbeat now** and check the summary updates.

## Getting results out

- In the app: **Share test log** sends `mapa-<model>-<date>.jsonl` to WhatsApp, Drive, email, and so on.
- Over USB: `adb exec-out run-as app.mapa.telemetry.spike cat files/events.jsonl > redmi9a.jsonl`
- Then: `python3 tools/analyze.py redmi9a.jsonl samsung-a05.jsonl > report.md`

The report gives, per phone: heartbeats per wake-up path versus expected, median/p90/max gaps, the share of time the newest heartbeat was older than 30/60/180 minutes, the five worst gaps with likely causes (reboot, process killed, Doze, battery saver), and live receiver versus Usage Access coverage.

## Optional: live sync server

Lets you watch freshness the way a family member would, and measures real end-to-end delivery (network included).

```bash
cd server
MAPA_KEY=pick-a-secret python3 collector.py     # port 8787, writes heartbeats.jsonl
```

- **Same Wi-Fi only:** in the app, set the sync URL to `http://<laptop-ip>:8787/v1/heartbeat?key=pick-a-secret`. Cleartext HTTP is allowed in this spike build for exactly this.
- **Phones at other homes:** they need a public HTTPS URL. Run the collector on any small VM, or expose your laptop with `cloudflared tunnel --url http://localhost:8787` and use the printed `https://…trycloudflare.com/v1/heartbeat?key=…` URL. The laptop must stay on.
- Open `http://localhost:8787/` for the freshness table. Run `python3 tools/analyze.py server/heartbeats.jsonl` for the server-side staleness numbers.

## Known limits

- **Not yet built into an APK or run on a phone.** The code was written in a cloud sandbox where Google's Maven and SDK downloads are blocked. All Kotlin sources were compiled against the Android 15 framework API (Robolectric `android-all` 15) with hand-written stubs for the five AndroidX calls used. The first full build happens in Android Studio, so expect a small fix or two there.
- WorkManager's minimum period is 15 minutes, and both schedulers are inexact by design. Gaps up to about 30 minutes are normal, not failures.
- `USER_INTERACTION` usage events are often hidden from third-party apps. Unlocks (`keyguardHidden`) and app opens (`foreground`) are the reliable interaction signals; screen-on alone is not counted because notifications can light the screen.
- Usage Access history is retained for a limited number of days by Android, so backfill is capped at 3 days.
- No push (FCM) wake-ups and no foreground service yet. Both are candidate fixes if the test shows OEMs starve the two schedulers. Try them only after the baseline numbers are in.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and cleartext HTTP are fine for a sideloaded test build but need Play policy review before any store release.

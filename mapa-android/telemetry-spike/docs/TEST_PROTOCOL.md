# Real-device test protocol

**Gate from the brief:** can we honestly show whether activity reports are current for several consecutive days on cheap Android hardware?

"Honestly" has two halves, and both are measured:

1. **Freshness:** how often is the newest report recent enough to trust?
2. **Truthfulness:** whenever reporting stopped, would a family viewer have seen *monitoring unavailable* instead of a stale "all fine"? Server-side age makes this true by construction. The test checks that the phone side never hides an outage, for example missing Usage Access must show up as unknown, not "quiet".

## Proposed pass criteria (targets to test, not results)

Per phone, over a 72-hour hands-off run with normal owner use, in the **hardened** arm:

| Metric | Target | Source |
|---|---|---|
| Newest heartbeat older than 60 min | < 5% of the time, outside phone-off periods | `analyze.py`, "Share of the window" |
| Longest gap while the phone was on | ≤ 3 h | `analyze.py`, worst gaps |
| Back after a reboot without opening the app | ≤ 30 min | S4 below |
| Interactions recovered by Usage Access backfill | every unlock the owner remembers | Activity signal section |
| Battery cost of the app | < 1% per day | `dumpsys batterystats` or Settings → Battery |
| Outages visible as stale on the server | 100% | server report vs phone log |

If the **default** arm already meets these on a brand, that brand needs no onboarding friction. If even the hardened arm fails, that brand needs a different mechanism (FCM high-priority push, foreground service) or is a kill/pivot signal per brief section 13.

## Device matrix

Cover at least one of each. Note the exact model, Android version and OEM skin version in the results sheet.

| Slot | Example handsets | Why |
|---|---|---|
| Xiaomi / Redmi / POCO (MIUI or HyperOS) | Redmi 9A, Redmi 12C, Redmi A3 | Most aggressive auto-start and battery saver defaults; very common with parents in India |
| Realme / Oppo (Realme UI / ColorOS) | Realme C35, Narzo N53 | Aggressive "sleep standby optimisation" |
| Vivo / iQOO (Funtouch OS / OriginOS) | Vivo Y16, Y21 | Background power management whitelist |
| Samsung (One UI, budget) | Galaxy M14, A05 | "Sleeping apps" / "Deep sleeping apps" lists, adaptive battery |
| Floor test: Android Go, ~1–2 GB RAM | Redmi Go, Nokia C-series, Samsung A03 Core | Low-memory killer pressure |
| Control: near-stock | Motorola, Pixel | Baseline for pure AOSP Doze behaviour |

## Two arms per phone

Run each phone in both arms, ideally 72 h each, back to back. If you have two units of the same model, run them in parallel instead.

| Arm | Usage Access | Battery optimisation | OEM auto-start / background |
|---|---|---|---|
| **A, default** | allowed | left as default | left as default |
| **B, hardened** | allowed | "Don't optimise" / "Unrestricted" | auto-start on, removed from sleeping lists, locked in recents where the OEM supports it |

Where to find the hardened settings (menu names move between versions; record what you actually found):

- **Xiaomi:** Settings → Apps → Manage apps → MaPa Test → Autostart ON; Battery saver → No restrictions. Also lock the app in Recents (long-press its card → lock).
- **Realme / Oppo:** Settings → Apps → App management → MaPa Test → Battery usage → allow background activity, allow auto launch. Settings → Battery → More settings → turn off "Optimise battery use" for the app.
- **Vivo:** Settings → Battery → Background power consumption management → MaPa Test → Don't restrict. i Manager → App manager → Autostart → ON.
- **Samsung:** Settings → Apps → MaPa Test → Battery → Unrestricted. Settings → Battery → Background usage limits → make sure it is not in Sleeping or Deep sleeping apps; add to "Never sleeping apps".

The app's "3. Phone maker's auto-start settings" button tries to open the right screen and falls back to App info.

## Before each run

1. Install the debug APK, complete consent, grant Usage Access.
2. Optional but recommended: start `server/collector.py` and set the sync URL so you also get the server view.
3. Reset battery stats: `adb shell dumpsys batterystats --reset`.
4. Write the start time, arm, model, Android version and battery % in `results-template.csv`.

## Scenarios

Run S1 to S13 within each arm. S13 is the gate; the others explain its numbers. For each, write down the start time so you can find it in the report's worst-gaps list.

| # | Scenario | Steps | What to look for |
|---|---|---|---|
| S1 | Normal day | Owner uses the phone as usual for 24 h | Gaps per path; live vs backfill coverage |
| S2 | Overnight idle | Unplugged, screen off, untouched, 8 h | Gap growth as Doze deepens; which path survives |
| S3 | Forced Doze | `adb shell dumpsys battery unplug` then `adb shell dumpsys deviceidle force-idle`; wait 1 h; `adb shell dumpsys deviceidle unforce` and `adb shell dumpsys battery reset` | Heartbeats during idle maintenance windows only |
| S4 | Reboot | Power off, power on, unlock, **do not open the app** | `boot` event and first `work`/`alarm` heartbeat time after boot |
| S5 | Swipe from recents | Swipe MaPa Test away (and on Xiaomi, tap "clear all") | Does it come back on its own? Exit reason recorded? |
| S6 | Force stop | App info → Force stop | Expected: silence until the app is opened (Android rule). Server must show stale |
| S7 | Battery saver | Turn on battery saver / ultra saver for 2 h | Gap growth; `powerSave` in heartbeats |
| S8 | No network | Airplane mode 1 h, then back | Local heartbeats continue; sync errors; server shows stale then recovers |
| S9 | Usage Access revoked | Revoke in settings for 1 h, then re-grant | Heartbeats show `usageAccess: false`; must count as unknown, never quiet |
| S10 | Flat battery | Let it die, charge, power on | Boot recovery; final heartbeat battery level |
| S11 | OEM cleaner | Run the phone's cleaner / boost / "optimise now" button | Process exit reason; time to next heartbeat |
| S12 | Standby buckets | `adb shell am set-standby-bucket app.mapa.telemetry.spike rare` (then `restricted` on Android 12+) for 2 h each | How bucket stretches each path |
| S13 | **72 h hands-off** | Normal owner use, nobody opens the app | The gate metrics above |

## After each run

1. In the app: **Share test log**, or `adb exec-out run-as app.mapa.telemetry.spike cat files/events.jsonl > <model>-<arm>.jsonl`.
2. Battery: `adb shell dumpsys batterystats app.mapa.telemetry.spike > <model>-<arm>-battery.txt` and a screenshot of Settings → Battery → app usage.
3. `python3 tools/analyze.py <model>-<arm>.jsonl [server/heartbeats.jsonl] > <model>-<arm>-report.md`
4. Fill one row per phone per arm in `results-template.csv`.
5. Put logs and reports in a dated folder next to this spike (for example `results/2026-10-27/`), so the routine engine work can reuse the activity timelines.

## Running it on a parent's phone

Only with the parent's informed agreement, in person or on a call where you walk through the consent screen together. They tick the box themselves. Explain how to pause and how to stop and delete. Leave the sync URL empty unless they agree to it. Collect the log by asking them to tap **Share test log**.

## What would change the plan

- One scheduler consistently beats the other on a brand → use that one as the primary path in the MVP.
- Both starve on a brand even when hardened → prototype FCM high-priority push wake-ups and/or a low-importance foreground service, and rerun S2, S5, S13 on that brand.
- Backfill recovers interactions that live receivers missed → the MVP can tolerate process death as long as *some* heartbeat runs every hour; freshness, not liveness, is the real constraint.
- Usage Access is routinely revoked or hard to grant for parents → onboarding problem to solve before anything else.

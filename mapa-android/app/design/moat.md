# MaPa's moat (why we win), written 2026-10-19, corrected 2026-10-28

> **Correction 2026-10-28.** A market search found apps that also claim to learn each person's own phone
> routine (table below). "A learned per-parent rhythm" alone is **not** unique, and we must not say
> "nobody else has this". What we found no one claiming is the **combination** in "Our moat" below.
> All rival facts come from their own pages; we did not install or test them.

Every agent working on MaPa: protect these. If a change weakens one, say so before building it.

## Who we compete with
Checked from their own sites and store pages, not by installing them:
- **Parents Are OK** (iPhone + Android): unlocks, app use, movement; alerts after a **fixed 24 hours** of
  no activity (adjustable, sleep hours excluded). No GPS.
- **CareTrigger** (free): alerts when the phone is inactive "unusually long"; doesn't say how it decides.
- **I Am Fine:** step counter; emails a contact when activity is missed.
- **I'm Alive:** parent taps a daily check-in; automatic alerts are paid ($29.99/yr).

All four are **alarms**: silent until something looks wrong, then one alert.

### Rivals that also claim a learned routine (search 2026-10-28)
| App | What it claims | Differences we can see on its own pages |
| --- | --- | --- |
| **CareTrigger** (Hyperbola Ltd, free, iPhone + Android) | "learns what is normal and what is abnormal for each user individually"; "Learns usage patterns", "Adaptive alerts" | Does not say how, or whether it runs on the phone. No daily picture, no languages but English. Earlier note above ("doesn't say how") stands. |
| **ElderWatch AI** ($4.99 to $9.99/month) | 14-day personal baseline from 6 signals, then deviation alerts; self-reports 2,400+ families | Uses **location and call** signals. Does not say where processing runs. Weekly/monthly reports. |
| **How Are You?!** (Android) | "7-day AI learning phase" building personal rules | Sends **GPS** in alerts; fall detection. |
| **eNeighbor** (Curlew Labs, iPhone) | Notices the parent's morning phone use; alerts family if the morning pattern breaks | Morning only, as described. |
| **Grand** (iPhone) | Unusual inactivity or pattern shifts | Uses audio and motion signals. |
| **Touchkin StayClose** (India, older) | Machine learning on phone sensor data; alerts on unusual patterns | Same company as Wysa; current status unclear. |

India: we found check-in apps (Haal Chaal, Athnova Care on WhatsApp in Hindi) and health devices (Dozee,
BitwellBand), but no live app that learns the parent's phone rhythm with child-led setup.

## Our moat
1. **A learned rhythm per parent, on the parent's phone** (not unique on its own; see the table). The on-device model learns each parent's own day
   (when they're usually up, normal gaps, weekday vs Sunday, slow drift). It can say "Papa is usually up
   by 7:40, it's 9:10" within hours; a 24-hour timer notices the next day.
2. **A calm daily picture, not just an alarm.** The day dial, "within his usual pattern" and the AI note
   give families a reason to glance daily, not only in a crisis.
3. **Built not to cry wolf or mislearn.** Life tags (unwell, travelling, guests, festival) are kept out
   of training; a shifted routine becomes normal only after a human confirms it; each weekly model can
   only move a bounded distance; slow drift is shown as a weekly trend, never as an alarm.
4. **Honest about stale data.** Never an all-clear when the phone has gone quiet ("No update since 3:10 PM").
5. **India-first and parent-effortless.** The child does setup; the parent taps Yes once (plus one
   switch). Hindi now, more Indian languages next; the child picks the language and it travels in the invite.
6. **Conclusions only.** The model runs on the parent's phone; raw usage, movement types, places and app
   names never leave it.

## What to say (and not say)
- Say: "Unlike fixed-timer apps, MaPa learns each parent's own rhythm, on the parent's phone, with guards so it never
  learns an unwell week as normal, and it never shows old data as fine."
- Say: "We found no app that combines on-device learning, conclusions-only privacy (no location, calls or app
  names), anti-mislearning guards, an honest stale state, and Hindi child-led setup." (true as of 28 Oct 2026)
- Do not say: "Nobody else has this", "the only app that learns", or claim rivals are all fixed timers.

## Where we are not ahead (yet)
- On iPhone our signals are the same as theirs (movement, steps): there the edge is the model, not data.
- They are live on iPhone today; we are not.
- CareTrigger is free.

## Related
- Screen plan: `design/MaPa-screens.md`; iPhone plan: `design/iphone-plan.md`; design system: `design/MaPa-design.md`

# Privacy by design

Conclusions leave the phone. Nothing else does.

**Stays on the parent's phone:** which apps are used, messages, calls, photos, location (MaPa never uses GPS), camera, microphone and anything on the screen.

**What the family sees:** when the phone was last used (the time only), whether today looks like the usual day, and the battery level.

**The parent is in control:** the parent can pause sharing, see who can see, stop sharing and delete everything, at any time.

**AI:** the parent's phone never calls an AI service. In the family app, Meta's Muse Spark open model (served on Baseten) writes the daily note from a short summary without the parent's name; the phone puts the name back. Questions about health or safety are answered on the phone with a fixed reply and are never sent.

## Exactly what leaves the phone

| Leaves the phone | Never leaves the phone |
|---|---|
| The state and a reason code, with times such as "usually active by 7:45" | Raw usage events and their timestamps |
| Today's 96 quarter-hours as used, not used or not observed, and the usual band | Use counts (reduced to yes or no before upload) |
| Battery level and whether it is charging | App names, which MaPa never reads |
| The facts for the daily note: times, counts and battery | Messages, calls, photos, the screen, camera, microphone, location |
| Facts about the model itself: runtime, size and step time | The model's weights, its training history and the personal profile |

All of it is assembled in a single file in the app's code, so the promise can be audited in one place rather than trusted across a codebase.

## Pausing means pausing

When the parent taps Pause, the phone publishes the pause immediately, before the next quarter-hour closes. While paused, nothing is collected or learned, and the family sees only "Paused by parent": no times, no battery, no note and no forecast.

## Locked down by the server rules

- The parent's phone is the only writer of anything derived from the parent's phone.
- Only family members the parent has accepted can read it, and the parent can see and remove any of them.
- Invite codes are fetched by exact code and can never be listed, so they cannot be guessed by browsing; they expire within 25 hours.
- A family member can add a life tag, of a fixed kind and at most 14 days long, and answer the new-routine question once. Nothing else.
- On the free plan used for the pilot, the parent's phone deletes its own synced data after 30 days.

## AI that knows its limits

The daily note is written from structured facts, never from raw data. The prompt carries a `[NAME]` placeholder; the parent's name is swapped back in on the family member's phone. Before anyone sees a sentence, a claims guard checks it: no medical, safety, emergency or all-clear language in English or Hindi, no time that is not in the facts, no percentage except the battery, and nothing over 280 characters. A note that fails is replaced by a template, never edited and shown. MaPa never uses the Muse Spark variant that allows prompts to be used for training.

MaPa is not a medical or emergency service.

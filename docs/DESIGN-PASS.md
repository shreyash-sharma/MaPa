# Design pass, 30 October 2026

Screens: Pixel 6a, dark mode, local test backend, 21 sample days. Before/after sheet:
`_stage/design-pass/before-after.png`. All screens before: `_stage/design-pass/before-sheet.png`.

## 1. What makes the landing page feel alive

1. One hero picture per section: the instrument dial (night sky, tick ring, terracotta arc, big serif number).
2. Strong type contrast: very large condensed serif, with one phrase in italic terracotta ("without ever asking.").
3. Warmth from one colour: terracotta from the emblem, used little, on the important word or number.
4. A halo behind the emblem: a soft radial glow, not a flat background.
5. Instrument details: hour ticks, rulers, folio labels. They make the page feel made, not templated.
6. Real, specific words: "Mummy", "Lucknow", "9:15". Never generic.
7. Calm motion: things draw in once, and then stay still.

## 2. Why the app felt dull

| Screen | What is dull | Why |
|---|---|---|
| Parent home | A greeting and two buttons on a black page | No picture. Nothing says "MaPa is with you". |
| Family Today card | Grey card, small sans sentence, thin day strip | The state is only in a small pill. The sentence has no hierarchy. |
| Day detail | Thin arc, no depth, small numbers | The arc is a line drawing. The two times are not the hero. |
| Rhythm tab | Good content, but flat boxes | No visual weight on the model's numbers. |
| All | Terracotta almost missing | The emblem's warmth is not in the app. |

## 3. What I changed (done, on the phone now)

| Screen | Change |
|---|---|
| Parent home | The emblem in a slow-breathing terracotta halo (the landing hero). The family member's name in italic terracotta ("Sharing with *Shreyash*"). Paused: no halo. |
| Family Today card | A soft aura in the state colour behind the top-right corner (sage = Active, mist blue = Quiet, terracotta = Unusual; none for stale or paused). The name in the large serif. The sentence in the serif, with its times in italic terracotta. |
| Day strip | Hour ticks under the track (longer at 6 AM, noon, 6 PM). A slow-breathing glow at "now". |
| Day arc | Draws itself in once (1.1 s). A warm sky glow under the curve. An hour ruler on the horizon. The "now" pebble breathes. |
| Readouts | Times in the large serif (instrument size). |
| Under the hood | It names the real model (MaPa Rhythm Hybrid). The step time is now the typical time on this phone, the median of the last 256 steps (183 µs on the Pixel 6a with ExecuTorch), not a mean that included the first warm-up steps. |

Rules kept:
- Stale or paused data gets no warmth, aura or glow.
- One action per screen. No new parent steps.
- Hindi strings unchanged. The type only got larger.
- Motion stops when Android animations are off (`Motion.kt`).

## 4. UI kit

- **Material 3 Expressive** (androidx.compose.material3 1.4 alpha): richer shapes, shape morphing, spring motion. But it is alpha and needs a Compose BOM upgrade three days before submission. That is a risk to the build.
- **Material 3 (current, stable) + our own Canvas drawing**: no build risk, and the hero pictures (arc, strip, halo) are custom anyway.

Decision: stay on stable Material 3 for the hackathon. Borrow the Expressive ideas by hand (bigger shapes, one calm motion, large type). Move to Expressive after the submission.

## 5. Next (not done)

1. Rhythm tab: give the model numbers (9,594 parameters, step time, 222 KB) the landing's dark "instrument" panel with large serif numerals.
2. Light mode check: the aura and glow were tuned on dark. Check contrast on paper.
3. History: one small arc per day (a row of tiny suns), not only a strip.
4. Empty states: the emblem halo with one sentence, not a bare text block.

## 6. Round 2 and day one (30 October, evening)

- Rhythm tab: the model card is now a dark instrument (the landing page's model section) in both themes: a 30-mark day ring with the count in the large serif, then parameters, step time and size.
- Step time: a warmed-up benchmark taken once when the model loads (30 warm-up steps, median of 100), because each 15-minute background run is a new process.
- History: one small "sun" per day for the last 7 days; a day with no data is a grey dotted outline.
- Light mode checked: aura, arc glow and instrument panel read well on paper.
- Day one: no "Day X of 14" box when the trained model decides. The card says "Getting to know Ma's day: N days so far. MaPa works from day one." Learned times appear from day 3. At setup the phone reads up to 7 days of past phone use (Android usage history) so the model starts with this parent's own days. Landing page copy changed the same way.
- Measured (HybridPipelineTest, golden parent, quiet after 07:45): "please call" at 15:15 with 0 days of history, 14:45 with 1 to 14 days; "worth a look" from 08:00 to 09:30. No "please call" on the ordinary days. The tested "please call" setting is conservative: it fires only 30 to 60 minutes before the 8-hour floor.

# MaPa design system (v4, 2026-10-27)

Who reads it: parents in India, often 60+, sometimes without glasses, often in Hindi; and their adult
children checking in between meetings. The app's one job is a calm, honest glance at a parent's day.
It must never look like an all-clear when data is stale.

v4 follows the Google Stitch export "Kinship editorial" (`stitch_mapa_routine_monitoring_app/`,
`sumi_alabaster/DESIGN.md`), chosen by shreyash on 2026-10-27. Earlier versions (indigo dial, mist and
sage) are in the history below. `shared/ui/Theme.kt` is the source of truth for every value here.

## Direction: kinship editorial
Cream paper, sumi-ink actions, terracotta for today's activity and the AI spark. Literary serif
headlines that read like a sentence about the day, not a dashboard. Generous space, tinted cards with
no borders, pill buttons. Calm attention, never clinical alarm.

## Colour
| Token | Light | Dark | Use |
|---|---|---|---|
| Paper (background) | #FCF9F4 | #141311 | Page |
| Card (surfaceContainerLow) | #F6F3EE | #1D1B19 | Cards, grouped lists |
| Wash | #FBEDE4 | #2A1F19 | Warm gradient at the top of a card (never on stale or paused data) |
| Sunk | #F0EDE9 | #24211E | Inputs, quiet blocks |
| Ink (onSurface) | #1C1C19 | #EDE7E0 | Text |
| Muted | #4B4640 | #B8AEA4 | Secondary text (at least 4.5:1) |
| Primary (buttons) | #1A1816 | #F3EEE8 | The one main action (sumi-ink pill) |
| Accent | #A23E18 | #F08A5D | Today's phone use, AI spark, "usually by" |
| Usual band | #FFB59C at 55% | #F08A5D at 30% | The parent's usual rhythm |

States, always with their word: Active #2F6E4A, Quiet #55657A, Unusual #A23E18 (earthen orange,
never alarm red), Monitoring unavailable #6B655E (grey), Paused by parent #7A5A3A.

## Type
- Newsreader (headlines) and Manrope (reading text, time readouts), as in Stitch. Both are SIL Open
  Font License; the files are `shared/src/main/res/font/newsreader.ttf` and `manrope.ttf`, and the
  licences are in `shared/licenses/`. Atkinson Hyperlegible stays bundled but is no longer used.
- Headlines: Newsreader 400, display 34 to 44, headline 23 to 30; times in the headline are italic.
- Reading text: Manrope. Body 18sp (line 28), small 16 and 15, labels 14 to 16. Time readouts:
  Manrope Light at headline size.
- Rule kept from v1: body 18sp or more, nothing under 14sp, sentence case, no all-caps labels,
  even where Stitch uses 11 to 15px text.
- Hindi: one language per screen. Neither font has Devanagari; Android falls back to its Noto
  Sans or Serif Devanagari.

## Layout and shape
- 20dp page gutter; spacing 4 / 8 / 12 / 16 / 24 / 32.
- Cards 24dp corners, tinted, no border, no shadow. Grouped lists use the same card colour.
- Main action: full-width pill, 64dp tall, ink fill, label then an arrow; an icon (Call) sits in its
  own round badge. Second action: 56dp tinted pill with a faint outline.
- Status: a pill with a dot and the state word. Freshness: a small dot plus "Updated ...".
- Small labels ("Today", the date): 14sp, slight letter-spacing, sentence case.
- Home card extras from Stitch: a learning box while learning ("Learning the rhythm", "Day 4 of
  14", a bar, "10 days left") in place of the day bar, and "View day" with an arrow at the bottom.
- Detail extras from Stitch: two time readouts under the arc, "Last phone use" (accent rule when
  Unusual) and "Usually first use" (hairline rule), each with a one-line source.

## The day picture (replaces the round dial)
- Home card: the Stitch day bar. One rounded strip from 5 AM to 11 PM; the usual rhythm as a soft
  terracotta halo; today's phone use as solid rounded segments; now as an ink marker; the expected
  time as a dashed tick; labels 6 AM, 12 PM, 6 PM, 10 PM.
- Parent detail: the Stitch sky arc. The hours from 6 AM run along a soft curve; warm columns under
  the curve show when this parent usually uses the phone; today's use lies on the curve as solid
  segments; hours with no data are dotted grey; an open circle marks "usually by"; a glowing dot
  marks now. A key below names both colours.
- Stale or unavailable data turns both pictures grey with no glow and no warm wash.

## Screens
- Family home: date, then a serif greeting, then one card per parent: name (serif), state pill, one
  sentence, "Updated ...", and the day bar.
- Parent detail: name and freshness, state pill, the sentence as a serif headline, the forecast (only
  if it adds a time not already said), the sky arc in a warm card, the AI note, quiet model and
  battery lines, then Call (ink pill when Unusual or Monitoring unavailable).
- Parent welcome: a "Welcome" chip, Listen as a pill in the top bar, the request as a serif headline,
  the promise in a block with a terracotta rule, two promise cards (what never leaves this phone,
  what your family sees), two language cards, one ink "Yes, share" pill.

## Family tab bar (added 2026-10-27, shreyash's decision)
- The Stitch dock: a rounded pill at the bottom; the selected tab is a lighter pill with the accent
  colour; icon plus a 16sp label; each tab at least 52dp tall. Plain words: Today, History, Rhythm.
- Shown only on the three top-level screens. Detail, set-up and settings have none. A notification
  still opens the exact parent screen. The parent app has no tab bar.
- Today: the home. History: parent picker (if more than one), the last 7 days as day bars with first
  use, the weekly trend, and every change grouped by day. Rhythm: the model made visible: "Expected vs
  today" (the model's expected chance per 15 minutes against today's use), "MaPa will speak up if...",
  "A fixed timer vs MaPa" (only when both times exist), "What MaPa has learned" (days, last training,
  guards), and the model card (parameters, time per step, size, runtime, "Only conclusions leave the
  phone"). Missing values show nothing; old data is grey and says so.
- Ask MaPa lives on each parent's detail screen, with "Past days and questions" leading to History.

## Where v4 does not follow Stitch, on purpose
No tab bar in the parent app (the parent needs no navigation); the family dock uses plain words. No invented content (folio
numbers, "diurnal passage", deltas, "recorded unlock", morning tea). No hard words (Horizon,
Observation, Folio, Inquire). No tiny or all-caps text. No mixed Hindi and English on one screen.
No extra cards on the parent welcome. Unusual is never a red alarm.

## Designing for older parents (unchanged)
Sources: NN/g "UX Design for Seniors" (free summary), JMIR 2023 review of mobile-app guidelines for
older adults, Android's 48dp touch-target guidance. Large text that follows the phone's font size;
big, separated targets (56 to 72dp for main actions); no sliders, dropdowns or date pickers; no sound
unless tapped; plain, specific labels; the family fills everything in and the parent only says yes.
Not yet verified: the largest font size, TalkBack, other OEM settings screens.

## History
- v1 (2026-10-19): indigo ink, marigold, the round day dial, Atkinson Hyperlegible.
- v2 (2026-10-19): calm: glance, focus, deep; one focal element per screen.
- v3 (2026-10-19): mist and sage palette.
- v4 (2026-10-27): Stitch "kinship editorial"; the dial is replaced by the day bar and the sky arc.

# MaPa screen plan (calm, release 1)

Written 2026-10-19 with the UI/UX Pro Max skill (UX rules for onboarding, empty states, navigation,
confirmations, progressive disclosure) and the calm principles in `MaPa-design.md`.

## How I got here

**Start from jobs, not screens.** Each user has very few real jobs. A screen exists only if a job needs it.

| Who | Jobs, in order of how often they happen |
|---|---|
| Family member (adult child, busy) | 1. Glance: is today like papa's usual day? 2. When MaPa says something is off, decide whether to call. 3. Understand why it changed. 4. Answer MaPa's question when a routine shifts. 5. Mark a day as different. 6. Add a parent. 7. Manage notifications and account. |
| Parent (60+, often Hindi, low tech confidence) | 1. Understand and agree, once. 2. Feel in control: see what is shared, pause, stop. 3. Bring family in. 4. Mark a day as different. 5. Keep MaPa running (permissions). 6. Delete everything. |

**What the jobs say about structure**
- The family member's most important screen is **not in the app. It is the notification.** Most days
  they should never need to open MaPa. That is what calm means for this product.
- The second most important screen is **"something is off"**. It is where anxiety peaks, so it must be
  the clearest, most honest screen we have, with the one useful action: call.
- The parent should almost never open the app after setup. Their home is a reassurance and a control,
  not a dashboard.
- Both apps have at most 3 levels: glance, focus, deep (Oura's model). **No bottom navigation**: there
  are not enough top-level places to earn one (`bottom-nav-limit`, `nav-hierarchy`), and tabs add chrome.

**Rules applied from the skill**
- `progressive-disclosure`: secondary things live one tap down (legend, history, tags, settings).
- `empty-states` / `empty-data-state`: every list and the dial have a real empty and learning state.
- `confirmation-dialogs`, `destructive-emphasis`, `destructive-nav-separation`: delete, remove and leave
  are confirmed, in red, away from normal actions.
- `escape-routes`, `back-behavior`: every flow has Back; onboarding can go back and the parent can skip
  the optional steps.
- `deep-linking`: every notification opens the exact screen and state it is about.
- `modal-vs-navigation`: pairing and invite move from dialogs to full screens, because they are flows.

## The screens

Status: **Built** (exists, calm pass done), **Change** (exists, needs work), **New**.

### Family app (6 screens, 4 notifications)

| # | Screen | Level | Job | What is on it (and nothing else) | Status |
|---|---|---|---|---|---|
| F1 | Sign in | – | 6, 7 | Wordmark, one sentence of what MaPa is, email + password, Sign in, Create account. | Built |
| F2 | Add a parent | flow | 6 | "Ask papa to open MaPa and tap Invite." Six code boxes, your name, Connect. Success lands on the new parent's detail. | Change (dialog → screen) |
| F3 | Home | glance | 1 | Greeting. One quiet card per parent: name, state, one sentence, small dial. Add a parent (text). Empty state if none. | Built |
| F4 | Parent | focus | 1, 2, 3, 4, 5 | Large dial and one sentence. Then, only when relevant: MaPa's question, the latest 2 changes, "Mark today as different". AI note below. | Built, needs states (below) |
| F5 | History | deep | 3 | Full "Why did this change?" timeline, grouped by day. | New (now a "Show all" toggle) |
| F6 | This week | deep | 3 | Seven small dials, one per day, and the weekly rhythm trend in two sentences (slow drift shown here, never as an alert). | New |
| F7 | Settings | deep | 7 | Language, notifications (quiet hours, which alerts), each parent (stop following), account, sign out. | New (now a ⋮ menu) |

**F4 must be designed per state.** These are the five states from the brief, plus learning:

| State | What the screen says | Primary action |
|---|---|---|
| Learning (days 1-14) | Dial fills day by day. "MaPa is learning papa's rhythm. Day 4 of 14." No usual band yet. | none |
| Active / Quiet | "Phone last used at 6:15 PM. Within papa's usual pattern." | none |
| **Unusual** | Plain facts only: "Phone not used since 9:10 AM. Papa usually uses it by 7:40." The dial shows the gap. No health words. | **Call papa** (large). Secondary: "Today is different" |
| Monitoring unavailable | "No update since 3:10 PM. The phone may be off or offline." Dial greys out after the last update. Never an all-clear. | Call papa |
| Paused by parent | "Papa paused sharing at 2:00 PM." Dial hidden. | none |

**Notifications** (one channel each so people can tune them; `deep-linking` to F4 in that state):
- **Unusual**: "Papa: phone not used since 9:10 AM" (default importance, the only one that can sound).
- **Updates stopped**: "Papa: no updates since 3:10 PM" (silent if within quiet hours).
- **MaPa has a question**: "Has papa's morning moved later?" (low importance, opens the question).
- **Papa's week**: Sunday evening, one line (low importance, opens F6). Off by default.
- Low battery stays a line on the card, not a notification, to keep the volume down.

### Parent app: almost nothing to do (revised 2026-10-19)

Feedback: the parent side must be minimal. MaPa is for aging parents, not tech-savvy users.
So **the family member does the setup work**, and the parent only says yes.

**Setup flow, started by the family member in F2 "Add a parent":**
1. Family member enters papa's name, relation (Papa, Mummy, Nani...), language, and picks papa's
   number from their contacts (this also powers **Call papa**, so the parent never types a number).
2. MaPa makes an invite link. The family member sends it on WhatsApp, or holds papa's phone and scans a
   QR code during a visit. The link carries the invite, so **the parent never types a code**.
3. On papa's phone, after install, the app opens straight to:

| # | Parent screen | What papa does |
|---|---|---|
| P1 | **"Shreyash wants to see your daily phone rhythm."** In papa's language, read aloud automatically. Two lines on what is shared and what never is (tap "What is shared?" for more). Big **Yes, share** and a quiet No. | One tap |
| P2 | **Allow one setting.** One big button opens the exact switch with a picture of it; MaPa notices when it is on and moves on by itself. The family member can do this during setup. | One switch |
| P3 | **Home**: "Sharing with Shreyash." One big **Pause** button. Nothing else on the screen. | Nothing, ever |

Removed from the parent: name and role entry, choosing a language (it comes from the invite, with a
switch on P1), the 3-page explainer (now one optional "What is shared?" page), inviting family, day tags
(family does this), battery and autostart rows (MaPa asks the family member to help if it sees gaps),
the dial and the AI note on Home (that is for the family; papa sees a plain sentence).

Kept for the parent, because control must stay theirs: **Pause**, and behind a small "More" link:
who can see (remove someone), and **Stop and delete everything** (confirmed, in red).

## Decisions
1. ~~Call papa needs a number~~: resolved. The family member picks it from their own contacts in F2.
2. Family sign-in: email for the pilot, Google sign-in next (recommended).
3. Weekly digest: off by default (recommended).

## Build order
1. New parent flow: P1 consent from an invite link, P2 one setting, P3 one-sentence Home.
2. F2 Add a parent as the setup flow (name, relation, contact, link or QR).
3. F4 states (Unusual with Call, Unavailable, Paused, Learning).
4. Notifications: channels, copy, deep links into F4.
5. F7 Settings, F5 History, F6 This week.

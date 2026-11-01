# How MaPa works: four flows (2026-10-19)

## 1. Data: what stays on papa's phone, what leaves

```mermaid
flowchart LR
  subgraph Phone["Papa's phone (everything raw stays here)"]
    U["Phone use times<br/>(Usage Access)"] --> B["96 slots a day<br/>used / not / unknown"]
    M["Movement times<br/>(Activity Recognition)"] --> B
    B --> E["Rhythm model<br/>+ illness guards"]
    E --> S["Conclusion:<br/>Active / Quiet / Unusual<br/>+ reason + usually-by time"]
    E --> N["Note facts<br/>(times, counts, battery)"]
  end
  S --> F[("Firebase<br/>conclusions only")]
  N --> F
  F --> K["Family app:<br/>card, day dial, alerts"]
  F --> G["Family phone writes the note<br/>(Gemini Nano, claims guard;<br/>template if no Nano)"]
  G --> K
  X["Never leaves:<br/>app names, messages,<br/>places, movement types,<br/>raw timestamps"]:::no
  classDef no fill:#f6e3e3,stroke:#b3261e,color:#5a1a14
```

## 2. The learning loop on papa's phone

```mermaid
flowchart TD
  A["Every 15 min: new slots"] --> Q["7-day quarantine<br/>(a day waits before it can teach)"]
  Q --> T{"Life tag on that day?<br/>unwell / travelling / guests / festival"}
  T -- yes --> Skip["Skipped: not learned"]
  T -- no --> R["Retrain on the phone<br/>while charging and idle"]
  R --> C{"When?"}
  C -- "days 1-30" --> N1["Nightly"]
  C -- "after day 30" --> W1["Weekly, Sunday night"]
  N1 --> G["Guard: new model can only move<br/>a bounded distance from the last"]
  W1 --> G
  G --> D{"Routine shifted for weeks?"}
  D -- yes --> H["Ask family: 'new routine?'<br/>becomes normal only on Yes"]
  D -- no --> OK["Model updated"]
  G --> Drift["Slow drift shown as a<br/>weekly rhythm trend (never an alarm)"]
  OK --> Who{"Day 1-14?"}
  Who -- yes --> Rules["Rules decide alerts,<br/>model runs in shadow"]
  Who -- no --> Model["Model decides once confident"]
```

## 3. An alert: from first silence to the Call button

```mermaid
sequenceDiagram
  participant P as Papa's phone
  participant E as Rhythm model (on phone)
  participant F as Firebase
  participant K as Your phone (family app)
  P->>E: 7:40 no use, no movement (usually used by 7:40)
  E->>E: Quiet, still within his normal range
  P->>E: 9:10 still silent
  E->>E: Longer than usual for him (model or 8-hour rule floor)
  E->>F: State Unusual + reason (facts only)
  F->>K: Alert "Papa: phone not used since 7:40"
  K->>K: Opens papa's screen: the gap on the dial, plain facts
  K-->>P: Call papa (one tap)
  Note over P,F: If papa's phone itself goes silent (off, no network), the family app shows<br/>"No update since 3:10 PM" after 2 hours, never an all-clear.
```

## 4. Does live data improve the shared starting model?

```mermaid
flowchart LR
  subgraph Today
    S0["Starting model<br/>(synthetic data calibrated to<br/>published 60+ statistics)"] --> P1["Each phone personalises<br/>its own copy"]
    P1 -.->|"nothing goes back"| S0
  end
  subgraph Later["Later (planned, not built)"]
    P2["Each phone"] -->|"model updates only,<br/>never data (federated learning)"| AGG["Combine updates"] --> S1["Better starting model<br/>for new parents"]
    D["Opt-in pilot parents<br/>(consented data donation)"] --> S1
  end
```

Today: **no**. Each phone learns only for its own parent and nothing flows back, which keeps the privacy
promise simple. Later: federated learning (phones send model updates, never data) and opt-in data from
pilot families can improve the starting model for new parents. Both need their own consent wording.

**Decision 2026-10-21 (Option C):** the parent's phone runs no language model. It syncs the note's
structured facts; each family member's phone writes the note with Gemini Nano (ML Kit Prompt API) in
that member's language, checked by the claims guard, with the template note as the fallback. Release
sizes: parent app 3.0 MB (was 52 MB with Gemma/MediaPipe), family app 3.5 MB.

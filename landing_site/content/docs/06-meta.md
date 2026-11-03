# Technology

Meta's stack, end to end: a compact model exported with ExecuTorch for the phone, and Meta's Muse Spark open model for the words the family reads.

- **ExecuTorch** is the export path for the compact phone model. ExecuTorch artifacts exist for the trained checkpoint and the Android integration is built; end-to-end v2 parity and real-device performance are the next measurements.
- **Muse Spark Open**, served through Baseten, writes the optional family-app text from short, name-free summaries. It is not part of parent-phone scoring. On this website it also translates the page live, behind a language allowlist, per-visitor limits and a monthly token cap.
- **Five parent-app language choices:** English, Hindi, Marathi, Tamil, and French. The family selects a language in the invite; Muse Spark Open via Baseten translates parent-interface copy and the app caches it on the phone.
- **Firebase** is configured for the Android project. Demo sign-in, pairing, access rules and sync are verified next in a dedicated demo environment before they are described as working.

## The full stack

| Layer | Technology | Role |
|---|---|---|
| Parent app | Kotlin, Jetpack, WorkManager | Consent, collection every 15 minutes, the engine, sync, pause and delete |
| Routine engine | Pure Kotlin (JVM 17), no third-party runtime dependencies | Features, states, alert gate, personal profile, guards, training schedule |
| On-device model | PyTorch for training, ExecuTorch 0.7.0 with XNNPACK on the phone | The 9,594-parameter Rhythm Hybrid, with a pure-Kotlin path on the same weights |
| Family app | Kotlin, Jetpack Compose | Cards, day dial, History, Rhythm, Ask MaPa, alerts |
| Language | Muse Spark; Meta's open model on Baseten; Gemini Nano on device; templates | The daily note and Ask MaPa, every line checked by the claims guard |
| Backend | Firebase Auth, Firestore, security rules; Cloud Functions ready for later | Conclusions-only sync between the parent's phone and the family |
| Model release | Hugging Face, Apache-2.0 | Checkpoint, ExecuTorch programs, model card and evaluation files |

## ExecuTorch on the phone

The network is exported as two ExecuTorch programs with the XNNPACK backend: a 103,684-byte step program that runs every quarter-hour, and a 123,136-byte daily program for the slow lane. On the export machine they match the PyTorch reference to within 2.4 × 10⁻⁶ over 192 streaming steps. In the app, the ExecuTorch program is checked against the Kotlin implementation on a fixed input when it loads; if anything disagrees, the Kotlin path runs the same weights. The family app's Rhythm tab shows which runtime is in use, the model's size and the time per step, as reported by the parent's phone.

## Muse Spark in the family app

Meta AI is opt-in: a "Use Meta AI" switch in Settings, off by default. When on, it powers three things, each working from structured, name-free facts:

- **The daily note**, a short line about the day's pattern, in the family member's language.
- **"Tell MaPa in your own words"**: a typed message such as "away until Sunday for a wedding" becomes a proposed life tag with a start and an end. A person confirms it with one tap; the model never decides an alert.
- **Ask MaPa**: questions about the shared summaries. Questions asking for a health or safety judgement get a fixed reply on the phone and are never sent.

## This website

The site you are reading is a static Next.js export served from Modal. English is the default and Hindi ships with the site. Other supported languages are translated live by Muse Spark Open on Baseten on the first visit, checked so that numbers, placeholders and markup survive, then stored and reused for every later visitor. A language allowlist, a per-visitor limit and a monthly token cap keep the cost predictable.

MaPa reports activity-pattern scores and policy states; it does not detect emergencies. Family members decide whether to check in.

# Roadmap: demo and pilot

Both Android apps, the phone owner's and the family's, are built and compile today. The next milestones take them from the bench to real families.

## Built today

- **The parent app:** a family-sent link, one welcome screen, one Yes and one Android switch, in English and Hindi. Collection every 15 minutes, the routine engine, sync, pause and delete everything.
- **The family app:** Today, History and Rhythm tabs; a parent detail screen with the day's arc, Call, "Mark today as different", the new-routine question and Ask MaPa.
- **The model:** the 9,594-parameter Rhythm Hybrid with the v2 decision policy, exported to ExecuTorch and published with its model card and evaluation files.
- **The backend:** Firestore security rules with their own test suite, and Cloud Functions for server push ready for the paid plan.
- **Running end to end on the Firebase Emulator Suite**, so the full parent-to-family loop can be exercised without a cloud project.

## Next milestones

1. Verify the apps on physical phones: Pixel 6a performance, battery use and current-policy parity.
2. Complete family pairing and sync in a dedicated Firebase project, with authentication, access rules and deletion checked.
3. Launch the browser demo for judges (see `09-judge-demo.md`). It starts a Limrun Android emulator only after a judge chooses an app, then releases it when the judge stops or leaves the session.

## Before a real pilot

1. Use a dedicated Firebase project and check authentication, pairing, access rules, and deletion behavior.
2. Test the current APKs on the intended Android versions and device families. Record failures and power-management restrictions.
3. Confirm that stale phone input produces “Monitoring unavailable.”
4. Use informed consent and a clear process to pause and delete sharing.
5. Treat pilot feedback as usability feedback. Accuracy claims wait for a defined, approved evaluation.

## After the pilot

Directions we are building toward, none of them shipped yet:

- **Sharper hard cases.** Telling ordinary daytime quiet and a phone left at home apart from a concerning pattern, the two scenarios named in `04-results.md`.
- **Movement as a signal.** Training data with motion summaries, so a walk or a drive counts as a sign of life in the model as well as in the rules.
- **Asking the parent first.** An optional gentle prompt on the parent's own phone before the family is alerted.
- **Learning together, privately.** Federated learning, where phones share model updates and never data, to improve the starting model for new families. This needs its own consent and is not built yet.
- **Voice check-ins** and a richer Ask MaPa over the shared summaries.

No family pilot result is available yet, and MaPa is not designed for emergency response.

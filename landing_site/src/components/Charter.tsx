import { Pledge } from "@/components/Pledge";
import { Reveal } from "@/components/Reveal";

const CONTRAST = [
  {
    often: { title: "Cameras in the living room", body: "A home stops feeling like home when a lens follows the afternoon rest." },
    mapa: { title: "The phone they already use", body: "MaPa learns from when the phone is used, moved and charged. Never what is on it." },
  },
  {
    often: { title: "A button to press every morning", body: "It puts the work on the parent, and a missed tap at 9 AM can mean anything." },
    mapa: { title: "Nothing to remember", body: "No wristband, no daily check-in. The family sets it up; the parent taps Yes." },
  },
  {
    often: { title: "Alarms first", body: "Frequent alerts teach a family to panic, or to stop looking." },
    mapa: { title: "Quiet unless it matters", body: "If today looks like their usual day, MaPa stays quiet. The picture is there when you look." },
  },
];

const STAYS = ["Raw usage", "App names", "Messages", "Location", "Audio", "Screen contents"];
const SHARED = ["The state: Active, Quiet, Unusual, Monitoring unavailable or Paused by parent", "The expected-activity forecast", "The daily note, written from a short summary"];

const TENETS = [
  { title: "I — On the phone", body: "The rhythm model runs on your parent’s phone. Only conclusions leave it." },
  { title: "II — Consent first", body: "Your parent says yes first, and can pause, revoke or delete at any time." },
  { title: "III — Facts, not diagnoses", body: "No medical, health or safety claims. Never an all-clear when the data is old." },
];

export function Charter() {
  return (
    <section id="privacy" className="paper-canvas slipcase-paper relative z-50 -mt-8 w-full border-t border-line-strong/50">
      <div className="hairline-b flex items-center justify-between bg-surface/90 px-5 py-3 font-mono text-[13px] tracking-wide text-ink-faint backdrop-blur-sm lg:px-16">
        <span className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-[2px] bg-ink" />
          <span className="font-semibold text-ink">Folio V · Charter of dignity</span>
        </span>
        <span className="hidden md:block">Conclusions only</span>
        <span className="font-medium text-terracotta-ink">Privacy</span>
      </div>

      <div className="mx-auto max-w-7xl space-y-24 px-5 pt-16 pb-28 lg:px-16">
        <Reveal className="grid grid-cols-1 gap-12 lg:grid-cols-12 lg:gap-20">
          <div className="space-y-6 lg:col-span-4">
            <p data-reveal className="font-mono text-xs tracking-wide text-terracotta-ink">Codex IV · The covenant</p>
            <h2 data-reveal className="font-display text-5xl font-normal leading-[1.02] sm:text-6xl lg:text-7xl">
              What leaves the phone is a conclusion, <span className="italic">not a life.</span>
            </h2>
            <p data-reveal className="font-serif text-xl font-light leading-relaxed text-ink-muted">
              The difference between watching and caring is not technical. It is a choice about what to look at.
            </p>
            <p data-reveal className="hairline-t pt-4 font-serif text-sm italic text-ink-muted">
              “At 06:45 on the veranda, the tea cools in brass cups. No cameras. No wristband alarms. Only a day beginning.”
            </p>
          </div>

          <div className="grid grid-cols-1 gap-12 md:grid-cols-2 lg:col-span-8 lg:hairline-l lg:pl-12">
            <div className="space-y-8">
              <p data-reveal className="hairline-b pb-3 font-mono text-xs text-ink-faint">01 · What watching often looks like</p>
              {CONTRAST.map(({ often }) => (
                <div key={often.title} data-reveal className="space-y-2">
                  <h3 className="font-serif text-lg font-normal">{often.title}</h3>
                  <p className="text-sm leading-relaxed text-ink-muted">{often.body}</p>
                </div>
              ))}
            </div>
            <div className="space-y-8">
              <p data-reveal className="hairline-b pb-3 font-mono text-xs font-medium text-terracotta-ink">02 · What MaPa does instead</p>
              {CONTRAST.map(({ mapa }) => (
                <div key={mapa.title} data-reveal className="space-y-2">
                  <h3 className="font-serif text-lg font-normal">{mapa.title}</h3>
                  <p className="text-sm leading-relaxed text-ink-muted">{mapa.body}</p>
                </div>
              ))}
            </div>
          </div>
        </Reveal>

        <Reveal className="grid grid-cols-1 gap-6 rounded-3xl border border-line bg-surface-warm/60 p-6 sm:p-10 md:grid-cols-2" gap={70}>
          <div>
            <h3 data-reveal className="mb-4 flex items-center gap-2 border-b border-line pb-3 text-sm font-semibold tracking-wide">
              <span className="h-2 w-2 rounded-full bg-ink" /> Stays on the phone
            </h3>
            <ul className="space-y-3 text-[18px] text-ink-muted">
              {STAYS.map((s) => (
                <li key={s} data-reveal className="flex gap-3">
                  <span aria-hidden className="text-ink-faint">—</span>
                  {s}
                </li>
              ))}
            </ul>
          </div>
          <div>
            <h3 data-reveal className="mb-4 flex items-center gap-2 border-b border-line pb-3 text-sm font-semibold tracking-wide text-terracotta-ink">
              <span className="h-2 w-2 rounded-full bg-terracotta" /> Shared with family
            </h3>
            <ul className="space-y-3 text-[18px] text-ink-muted">
              {SHARED.map((s) => (
                <li key={s} data-reveal className="flex gap-3">
                  <span aria-hidden className="text-terracotta-ink">✓</span>
                  {s}
                </li>
              ))}
            </ul>
          </div>
        </Reveal>

        <div className="hairline-t grid grid-cols-1 items-center gap-12 pt-16 lg:grid-cols-12">
          <Pledge />
          <Reveal className="space-y-6 lg:col-span-5 lg:hairline-l lg:pl-12" gap={120}>
            <p data-reveal className="font-mono text-xs tracking-wide text-ink-faint">Covenants of restraint</p>
            {TENETS.map((t) => (
              <div key={t.title} data-reveal className="hairline-b space-y-1 pb-4 last:border-0">
                <p className="font-mono text-xs font-semibold text-ink">{t.title}</p>
                <p className="text-sm leading-relaxed text-ink-muted">{t.body}</p>
              </div>
            ))}
          </Reveal>
        </div>
      </div>
    </section>
  );
}

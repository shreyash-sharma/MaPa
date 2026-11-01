import { Reveal } from "@/components/Reveal";

const QUESTIONS = [
  {
    q: "Is MaPa a medical or emergency device?",
    a: "No. It shows routine patterns, not health or safety. MaPa does not monitor vital signs, detect falls or respond to emergencies. It tells you whether your parent’s phone day looks like their usual day.",
  },
  {
    q: "Does my parent need to do anything daily?",
    a: "No. The family sets MaPa up; your parent sees one welcome screen, taps Yes and turns on one Android switch. After that there are no daily check-ins or buttons.",
  },
  {
    q: "What does the family actually see?",
    a: "A calm daily picture: the current state (Active, Quiet, Unusual, Monitoring unavailable or Paused by parent), how today compares with the expected rhythm, and a short daily note. Never messages, screen contents, app names or location.",
  },
  {
    q: "Can my parent turn it off?",
    a: "Yes. Your parent can pause, revoke or delete at any time from their phone. While paused, the family sees “Paused by parent”, never a guess.",
  },
  {
    q: "Which phones does it work on?",
    a: "Your parent’s phone needs to be an Android phone. The family can use the MaPa Android app or the web dashboard.",
  },
  {
    q: "Is it available now?",
    a: "MaPa is in early access. Join the list and we will write when it opens to more families.",
  },
];

export function Faq() {
  return (
    <section id="faq" className="paper-canvas slipcase-paper relative z-[70] -mt-8 w-full border-t border-line-strong/50">
      <div className="hairline-b flex items-center justify-between bg-surface/90 px-5 py-3 font-mono text-[13px] tracking-wide text-ink-faint backdrop-blur-sm lg:px-16">
        <span className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-[2px] bg-ink" />
          <span className="font-semibold text-ink">Folio VII · Questions</span>
        </span>
        <span className="font-medium text-terracotta-ink">FAQ</span>
      </div>

      <div className="mx-auto max-w-4xl px-5 pt-16 pb-28 lg:px-16">
        <Reveal className="mb-12 text-center">
          <p data-reveal className="mb-3 font-mono text-xs tracking-wide text-terracotta-ink">Plainly answered</p>
          <h2 data-reveal className="font-display text-5xl font-normal sm:text-6xl lg:text-7xl">Frequently asked questions</h2>
        </Reveal>

        <Reveal className="divide-y divide-line-strong/60 border-y border-line-strong/60" gap={70} rise={16}>
          {QUESTIONS.map((item, i) => (
            <details key={item.q} data-reveal className="group py-3" open={i === 0}>
              <summary className="flex min-h-14 cursor-pointer list-none items-center justify-between gap-6 rounded-lg py-2 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-terracotta">
                <h3 className="font-serif text-xl font-normal transition-colors group-hover:text-terracotta-ink sm:text-2xl">{item.q}</h3>
                <span aria-hidden className="font-serif text-2xl text-ink-faint transition-transform duration-300 group-open:rotate-45">
                  +
                </span>
              </summary>
              <p className="mt-2 mb-3 max-w-2xl text-[18px] leading-relaxed text-ink-muted">{item.a}</p>
            </details>
          ))}
        </Reveal>
      </div>
    </section>
  );
}

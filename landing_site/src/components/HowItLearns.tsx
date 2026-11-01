"use client";

import { animate, createScope, onScroll, stagger } from "animejs";
import { useEffect, useRef } from "react";
import { playOnEnter, prefersReducedMotion, showInstantly } from "@/lib/motion";

const STEPS = [
  {
    n: "01",
    title: "Learns their rhythm",
    body: "A small model on your parent’s phone learns when they usually wake, use the phone and rest. It retrains only while the phone is charging and idle.",
    foot: "Learning stays on the phone",
  },
  {
    n: "02",
    title: "Compares today with usual",
    body: "An expected-activity forecast for the next four hours, so a quiet morning stands out in hours, not after a fixed day.",
    foot: "“Usually by 7:40. It’s 9:10.”",
  },
  {
    n: "03",
    title: "Writes a calm note",
    body: "A short daily note for the family in English or Hindi, written by AI from a brief summary with your parent’s name taken out. Never from raw data.",
    foot: "Conclusions, not details",
  },
];

const TIMELINE = [
  { when: "Day 1", what: "Pretrained on real phone logs and on rhythms learned from them. At setup it reads up to a week of past phone use, so it knows her from day one." },
  { when: "From day 1", what: "The model decides from day one, with an 8-hour safety floor underneath. Each new day of her own rhythm makes it surer." },
  { when: "Week 2+", what: "Her own rhythm has taken over from the general one it started from." },
  { when: "Day 30+", what: "Retraining moves from nightly to weekly, on Sunday night." },
];

const GUARDS = [
  { tag: "Life tags", body: "Days tagged unwell, travelling, guests or festival are skipped when learning." },
  { tag: "Patience", body: "A new day waits before it is learned (a day at first, a week later), so an unwell week never becomes the new normal." },
  { tag: "Bounds", body: "Each weekly update can only move a small, bounded step from the last." },
  { tag: "A person decides", body: "A changed routine becomes the new normal only after a person says yes." },
  { tag: "Slow drift", body: "Gradual change is shown to the family as a weekly rhythm trend, not hidden." },
];

export function HowItLearns() {
  const root = useRef<HTMLElement>(null);

  useEffect(() => {
    const el = root.current;
    if (!el) return;
    if (prefersReducedMotion()) {
      showInstantly(el.querySelectorAll<HTMLElement>("[data-reveal]"));
      return;
    }
    const scope = createScope({ root }).add(() => {
      const reveal = (selector: string, trigger: string, gap = 110) =>
        playOnEnter(
          animate(selector, {
            opacity: [0, 1],
            y: [30, 0],
            filter: ["blur(6px)", "blur(0px)"],
            duration: 1100,
            delay: stagger(gap),
            ease: "outExpo",
            autoplay: false,
          }),
          trigger,
        );
      reveal(".learn-head [data-reveal]", ".learn-head");
      reveal(".learn-step", ".learn-steps", 140);
      reveal(".learn-guard", ".learn-guards", 90);
      reveal(".learn-node", ".learn-rail", 160);

      animate(".learn-rail-fill", {
        scaleX: [0, 1],
        ease: "linear",
        autoplay: onScroll({ target: ".learn-rail", enter: "bottom-=10% top", leave: "center+=10% center", sync: 0.4 }),
      });
    });
    return () => scope.revert();
  }, []);

  return (
    <section ref={root} id="learn" className="paper-canvas slipcase-paper relative z-30 -mt-8 w-full border-t border-line-strong/50">
      <div className="hairline-b flex items-center justify-between bg-surface/90 px-5 py-3 font-mono text-[13px] tracking-wide text-ink-faint backdrop-blur-sm lg:px-16">
        <span className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-[2px] bg-ink" />
          <span className="font-semibold text-ink">Folio III · How it learns</span>
        </span>
        <span className="hidden md:block">One model per parent, on their own phone</span>
        <span className="font-medium text-terracotta-ink">AI-first</span>
      </div>

      <div className="mx-auto max-w-7xl space-y-24 px-5 pt-16 pb-28 lg:px-16">
        <div className="learn-head grid grid-cols-1 gap-8 lg:grid-cols-12">
          <div className="space-y-4 lg:col-span-5">
            <p data-reveal className="font-mono text-xs tracking-wide text-terracotta-ink">Codex II · The method</p>
            <h2 data-reveal className="font-display text-5xl font-normal leading-[1.02] sm:text-6xl lg:text-7xl">
              A rhythm learned, <span className="italic">not a timer.</span>
            </h2>
          </div>
          <p data-reveal className="font-serif text-xl font-light leading-relaxed text-ink-muted lg:col-span-7">
            A fixed timer waits for the same deadline every day. MaPa learns what a usual day looks like for your parent, so it
            can notice a morning that stays quiet for much longer than usual, within hours.
          </p>
        </div>

        <div className="learn-steps grid grid-cols-1 gap-10 md:grid-cols-3 lg:gap-14">
          {STEPS.map((s) => (
            <article key={s.n} data-reveal className="learn-step hairline-t flex flex-col justify-between pt-6">
              <div>
                <span className="mb-4 block font-serif text-2xl text-terracotta-ink">{s.n}</span>
                <h3 className="mb-3 font-serif text-2xl font-normal">{s.title}</h3>
                <p className="text-[18px] leading-relaxed text-ink-muted">{s.body}</p>
              </div>
              <p className="mt-8 border-t border-line pt-4 font-mono text-[14px] text-ink-faint">{s.foot}</p>
            </article>
          ))}
        </div>

        <div className="space-y-8">
          <p className="font-mono text-xs tracking-wide text-ink-faint">The first month</p>
          <div className="learn-rail relative">
            <div className="absolute top-[7px] right-0 left-0 hidden h-px bg-line-strong md:block" />
            <div className="learn-rail-fill absolute top-[7px] right-0 left-0 hidden h-px origin-left scale-x-0 bg-terracotta md:block" />
            <ol className="grid grid-cols-1 gap-8 md:grid-cols-4">
              {TIMELINE.map((step) => (
                <li key={step.when} data-reveal className="learn-node relative space-y-3 md:pt-8">
                  <span className="absolute top-0 left-0 hidden h-[15px] w-[15px] rounded-full border border-terracotta bg-surface md:block" />
                  <p className="font-mono text-xs text-terracotta-ink">{step.when}</p>
                  <p className="max-w-xs text-[18px] leading-relaxed text-ink-muted">{step.what}</p>
                </li>
              ))}
            </ol>
          </div>
        </div>

        <div className="space-y-8">
          <div className="max-w-2xl space-y-3">
            <p className="font-mono text-xs tracking-wide text-ink-faint">Guards against crying wolf</p>
            <p className="font-serif text-2xl font-light leading-snug text-ink">
              False alarms wear out a family’s trust. MaPa is built to learn carefully and speak up sparingly.
            </p>
          </div>
          <div className="learn-guards grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-5">
            {GUARDS.map((g, i) => (
              <div key={g.tag} data-reveal className="learn-guard rounded-2xl border border-line bg-surface p-6 shadow-[0_1px_0_rgba(26,24,22,0.04)]">
                <p className="mb-4 font-mono text-[14px] font-medium text-terracotta-ink">
                  {String(i + 1).padStart(2, "0")} / {g.tag}
                </p>
                <p className="text-[18px] leading-relaxed text-ink">{g.body}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}

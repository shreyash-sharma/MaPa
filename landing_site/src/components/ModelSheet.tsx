"use client";

import { animate, createScope, stagger } from "animejs";
import Link from "next/link";
import { useEffect, useRef } from "react";
import { PhoneLearning } from "@/components/PhoneLearning";
import { playOnEnter, prefersReducedMotion, showInstantly } from "@/lib/motion";

const FIGURES = [
  { value: 9594, format: (v: number) => Math.round(v).toLocaleString("en-US"), unit: "", label: "parameters" },
  { value: 21, format: (v: number) => String(Math.round(v)), unit: "", label: "inputs every 15 minutes" },
  { value: 60, format: (v: number) => String(Math.round(v)), unit: " min", label: "median alert delay, v2 simulation" },
];

const SPEC = [
  ["Architecture", "MaPa Rhythm Hybrid: a causal dilated CNN and a GRU for recent hours, an LSTM over daily summaries with a personal context vector"],
  ["Inputs", "21 features on 15-minute steps, including session-start information"],
  ["Outputs", "Next-use estimates, a current-use estimate and an unusual-quiet score"],
  ["Decision", "A person-specific alert limit and policy rules, set from the person’s own history"],
  ["Runs on", "ExecuTorch artifacts exist for the trained checkpoint. On-device parity and speed for v2 are not yet verified on a physical phone"],
  ["Trained on", "S3 and UbiqLog phone logs, and model-derived parent timelines generated from a model fitted to those logs"],
];

export function ModelSheet() {
  const root = useRef<HTMLElement>(null);

  useEffect(() => {
    const el = root.current;
    if (!el) return;
    const numbers = el.querySelectorAll<HTMLElement>(".figure-value");
    if (prefersReducedMotion()) {
      showInstantly(el.querySelectorAll<HTMLElement>("[data-reveal]"));
      return;
    }
    const scope = createScope({ root }).add(() => {
      numbers.forEach((node, i) => {
        const fig = FIGURES[i];
        const counter = { v: 0 };
        const count = animate(counter, {
          v: fig.value,
          duration: 2200,
          delay: i * 160,
          ease: "outExpo",
          onUpdate: () => (node.textContent = fig.format(counter.v)),
          autoplay: false,
        });
        playOnEnter(count, ".figures");
      });

      playOnEnter(
        animate(".model-sheet [data-reveal]", { opacity: [0, 1], y: [24, 0], duration: 1000, delay: stagger(70), ease: "outExpo", autoplay: false }),
        ".model-sheet",
      );

    });
    return () => {
      scope.revert();
      numbers.forEach((node, i) => (node.textContent = FIGURES[i].format(FIGURES[i].value)));
    };
  }, []);

  return (
    <section ref={root} id="model" className="nocturne-canvas slipcase-dark relative z-40 -mt-8 w-full border-t border-white/10 text-nocturne-ink">
      <div className="night-hairline-b flex items-center justify-between bg-nocturne/90 px-5 py-3 font-mono text-[13px] tracking-wide text-nocturne-muted backdrop-blur-sm lg:px-16">
        <span className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-[2px] bg-terracotta-glow" />
          <span className="font-semibold text-nocturne-ink">Folio IV · The model</span>
        </span>
        <span className="hidden text-nocturne-faint md:block">Model card</span>
        <span className="font-medium text-terracotta-glow">On-device</span>
      </div>

      <div className="model-sheet mx-auto max-w-7xl space-y-16 px-5 pt-16 pb-28 lg:px-16">
        <div className="night-hairline-b grid grid-cols-1 items-baseline gap-8 pb-10 lg:grid-cols-12">
          <div className="space-y-3 lg:col-span-5">
            <p data-reveal className="font-mono text-xs tracking-wide text-terracotta-glow">Codex III · Small by design</p>
            <h2 data-reveal className="font-display text-5xl font-normal leading-[1.02] sm:text-6xl lg:text-7xl">
              One small model, <span className="italic">per parent.</span>
            </h2>
          </div>
          <p data-reveal className="font-serif text-xl font-light leading-relaxed text-nocturne-muted lg:col-span-7">
            Small enough to live on your parent’s phone. Speed and battery on a real phone have not been measured for this version yet.
          </p>
        </div>

        <div className="figures grid grid-cols-1 gap-6 sm:grid-cols-3">
          {FIGURES.map((f) => (
            <div key={f.label} data-reveal className="rounded-2xl border border-nocturne-line bg-nocturne-elevated/50 p-6">
              <p className="font-display text-6xl sm:text-7xl">
                <span className="figure-value tabular">{f.format(f.value)}</span>
                {f.unit}
              </p>
              <p className="mt-2 font-mono text-xs text-nocturne-muted">{f.label}</p>
            </div>
          ))}
        </div>

        <PhoneLearning />

        <dl className="grid grid-cols-1 gap-x-12 lg:grid-cols-2">
          {SPEC.map(([k, v]) => (
            <div key={k} data-reveal className="night-hairline-b grid grid-cols-3 gap-4 py-4">
              <dt className="font-mono text-[14px] text-nocturne-faint">{k}</dt>
              <dd className="col-span-2 text-base leading-relaxed text-nocturne-ink/90">{v}</dd>
            </div>
          ))}
        </dl>

        <div data-reveal className="flex flex-col gap-3 sm:flex-row">
          <Link
            href="/docs/model/"
            className="inline-flex min-h-11 items-center justify-center rounded-full border border-nocturne-line-strong px-6 text-base text-nocturne-ink transition-colors hover:border-terracotta-glow hover:text-terracotta-glow"
          >
            The full model card →
          </Link>
          <Link
            href="/docs/results/"
            className="inline-flex min-h-11 items-center justify-center rounded-full border border-nocturne-line-strong px-6 text-base text-nocturne-ink transition-colors hover:border-terracotta-glow hover:text-terracotta-glow"
          >
            Results and their limits →
          </Link>
        </div>
      </div>
    </section>
  );
}

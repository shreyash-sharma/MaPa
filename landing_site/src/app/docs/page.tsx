import type { Metadata } from "next";
import Link from "next/link";
import { DOCS, getDoc } from "@/lib/docs";

export const metadata: Metadata = {
  title: "Documentation · MaPa",
  description: "The MaPa routine engine: one small model per person, on their own phone. How it works, the data behind it and the numbers.",
};

/** Headline numbers, each straight from the v2 results page. */
const STATS = [
  { value: "108/108", label: "labeled behavior-case windows with an alert" },
  { value: "~6×", label: "fewer false alerts than a 4-hour timer" },
  { value: "60 min", label: "median time to alert" },
  { value: "9,594", label: "parameters, sized for the phone" },
];

export default function DocsIndex() {
  const docs = DOCS.map((d) => getDoc(d.slug)!);
  return (
    <div className="space-y-12">
      <header className="max-w-3xl space-y-4">
        <p className="font-mono text-xs tracking-wide text-terracotta-ink">Documentation</p>
        <h1 className="font-display text-5xl leading-[1.02] text-ink sm:text-6xl">
          The routine engine that spots a quiet morning <span className="italic text-terracotta-ink">in hours, not a day.</span>
        </h1>
        <p className="font-serif text-xl leading-relaxed text-ink-muted">
          One small model per person, learning on their own phone. Only conclusions leave it. Here is how it works, what it learns
          from, and how it performs.
        </p>
      </header>

      <section aria-label="Headline numbers" className="space-y-3">
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          {STATS.map((s) => (
            <div key={s.label} className="rounded-2xl border border-line-strong/60 bg-surface/70 p-5">
              <p className="font-display text-4xl leading-none text-ink sm:text-5xl">{s.value}</p>
              <p className="mt-2 text-[14px] leading-snug text-ink-muted">{s.label}</p>
            </div>
          ))}
        </div>
        <p className="text-[13px] text-ink-faint">
          v2 policy, chosen after the first test, in a Python simulation on 40 model-derived parent timelines. The 108/108 count includes 28 windows where phone-use and observation inputs did not change.{" "}
          <Link href="/docs/results/" className="text-terracotta-ink underline-offset-4 hover:underline">
            See the full results
          </Link>
          .
        </p>
      </section>

      <ol className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {docs.map((d, i) => (
          <li key={d.slug}>
            <Link
              href={`/docs/${d.slug}/`}
              className="group flex h-full flex-col gap-3 rounded-2xl border border-line-strong/60 bg-surface/70 p-6 transition-colors hover:border-terracotta/60 hover:bg-surface"
            >
              <span className="flex items-center justify-between text-[13px] text-ink-faint">
                <span className="tabular">{String(i + 1).padStart(2, "0")}</span>
                <span aria-hidden className="transition-transform group-hover:translate-x-1">→</span>
              </span>
              <span className="font-display text-3xl leading-tight text-ink">{d.title}</span>
              <span className="line-clamp-3 text-[15px] leading-relaxed text-ink-muted">{d.summary}</span>
            </Link>
          </li>
        ))}
      </ol>

      <aside className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="rounded-2xl border border-line-strong/60 bg-surface/70 p-6">
          <p className="text-[13px] text-ink-faint">Try it in a browser</p>
          <p className="mt-2 font-serif text-lg leading-relaxed text-ink">The phone owner and family apps, with example data only.</p>
          <Link href="/try/" className="mt-4 inline-flex min-h-11 items-center rounded-full bg-ink px-5 text-surface transition-colors hover:bg-terracotta-ink">
            Try the demo →
          </Link>
        </div>
        <div className="rounded-2xl border border-line-strong/60 bg-surface/70 p-6">
          <p className="text-[13px] text-ink-faint">Built for check-ins</p>
          <p className="mt-2 font-serif text-lg leading-relaxed text-ink">
            MaPa reads routines, not health. It prompts a thoughtful call; it is not an emergency service.
          </p>
        </div>
      </aside>
    </div>
  );
}

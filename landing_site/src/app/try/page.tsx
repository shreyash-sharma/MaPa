import type { Metadata } from "next";
import Link from "next/link";
import { Footer } from "@/components/Footer";
import { Masthead } from "@/components/Masthead";
import { DemoPlayers } from "./DemoPlayers";

export const metadata: Metadata = {
  title: "Try the live demo · MaPa",
  description: "Try MaPa's parent and family Android apps side by side in live Limrun emulators, with 7-day and 30-day histories.",
};

const APPS = [
  {
    id: "parent" as const,
    name: "Parent app",
    role: "Phone owner view",
    steps: ["Explore the daily rhythm and last observed use.", "See the phone owner's privacy context."],
  },
  {
    id: "family" as const,
    name: "Family app",
    role: "Family view",
    steps: ["See the same daily rhythm from the family view.", "Preview a gentle check-in message. Nothing is sent."],
  },
];

export default function TryPage() {
  return (
    <>
      <Masthead />
      <main className="paper-canvas min-h-screen">
        <div className="hairline-b flex items-center justify-between gap-3 px-5 py-2 font-mono text-xs tracking-wide text-ink-faint lg:px-16">
          <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-terracotta" />Live app demo</span>
          <span className="hidden sm:block">Two Android emulators · Hosted by Limrun</span>
          <span>No account needed</span>
        </div>

        <div className="mx-auto max-w-[1680px] px-5 pb-20 pt-8 sm:pt-10 lg:px-10">
          <header className="mb-7 flex flex-col gap-5 border-b border-line-strong/60 pb-7 md:flex-row md:items-end md:justify-between">
            <div className="max-w-2xl">
              <p className="font-mono text-xs uppercase tracking-[0.16em] text-terracotta-ink">MaPa · live demo</p>
              <h1 className="mt-2 font-display text-4xl leading-[1.05] text-ink sm:text-5xl">Try both Android apps <span className="italic text-terracotta-ink">live.</span></h1>
              <p className="mt-3 max-w-xl text-base leading-relaxed text-ink-muted">Two real Android app builds, running side by side in cloud emulators. Start both to compare the parent and family experience.</p>
            </div>
            <div className="flex">
              <a href="https://github.com/shreyash-sharma/MaPa/releases/tag/v0.1.0" target="_blank" rel="noreferrer" className="inline-flex min-h-12 items-center justify-center gap-2.5 rounded-full border border-[#d1d9e0] bg-[#f6f8fa] px-6 text-sm font-medium text-[#24292f] shadow-sm transition hover:-translate-y-0.5 hover:border-[#c5ccd3] hover:bg-[#eef1f4] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-terracotta-ink">
                <svg aria-hidden="true" viewBox="0 0 24 24" className="h-[18px] w-[18px] fill-current">
                  <path d="M12 .9a11.1 11.1 0 0 0-3.51 21.63c.56.1.76-.24.76-.54v-2.08c-3.1.68-3.76-1.31-3.76-1.31-.5-1.3-1.24-1.65-1.24-1.65-1.01-.7.08-.69.08-.69 1.12.08 1.7 1.15 1.7 1.15.99 1.7 2.6 1.21 3.23.93.1-.72.39-1.21.7-1.49-2.47-.28-5.07-1.24-5.07-5.51 0-1.22.44-2.21 1.15-2.99-.12-.28-.5-1.42.11-2.95 0 0 .94-.3 3.05 1.14a10.6 10.6 0 0 1 5.55 0c2.11-1.44 3.05-1.14 3.05-1.14.61 1.53.23 2.67.11 2.95.72.78 1.15 1.77 1.15 2.99 0 4.28-2.6 5.22-5.08 5.5.4.35.76 1.02.76 2.06v3.09c0 .3.2.65.77.54A11.1 11.1 0 0 0 12 .9Z" />
                </svg>
                <span>APK Download (GitHub)</span>
                <span className="text-base leading-none" aria-hidden="true">↗</span>
              </a>
            </div>
          </header>

          <DemoPlayers apps={APPS} />

          <div className="mt-7 flex flex-col gap-4 border-t border-line-strong/60 pt-5 text-sm text-ink-muted sm:flex-row sm:items-center sm:justify-between">
            <p>Google Play submission is in progress. These are cloud emulators, not physical phones.</p>
            <div className="flex gap-5">
              <Link href="/docs/data/" className="text-terracotta-ink underline underline-offset-4">Data and privacy</Link>
              <Link href="/docs/results/" className="text-terracotta-ink underline underline-offset-4">Research results</Link>
            </div>
          </div>
          <p className="mt-4 max-w-4xl text-xs leading-relaxed text-ink-faint">Demo data: identity-free, model-derived histories for Ma (7 days) and Papa (30 days); alerts are illustrative. No calls, messages, or push notifications are sent.</p>
        </div>
      </main>
      <Footer />
    </>
  );
}

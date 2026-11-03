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
    role: "Papa · 30-day phone-owner view",
    steps: ["Review today's rhythm and recent check-ins.", "Browse a month of daily activity."],
  },
  {
    id: "family" as const,
    name: "Family app",
    role: "Ma · 7-day family view",
    steps: ["See the rhythm shared with family.", "Review alerts and preview a check-in message."],
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
          <header className="mb-7 grid gap-5 border-b border-line-strong/60 pb-7 md:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] md:items-center">
            <div className="max-w-2xl">
              <p className="font-mono text-xs uppercase tracking-[0.16em] text-terracotta-ink">MaPa · live demo</p>
              <h1 className="mt-2 font-display text-4xl leading-[1.05] text-ink sm:text-5xl">See both sides of MaPa<span className="italic text-terracotta-ink">.</span></h1>
              <p className="mt-3 max-w-xl text-base leading-relaxed text-ink-muted">Papa's 30-day phone-owner view beside Ma's 7-day family view. Open both Android apps live in the browser.</p>
            </div>
            <div className="flex justify-center md:col-start-2 md:row-start-1">
              <a href="https://github.com/shreyash-sharma/MaPa/releases/tag/v0.3.0" target="_blank" rel="noreferrer" aria-label="Download Android APKs on GitHub" className="github-glass inline-flex min-h-12 items-center justify-center gap-2.5 rounded-full px-6 text-sm font-medium">
                <svg aria-hidden="true" viewBox="0 0 24 24" className="h-[18px] w-[18px] fill-current">
                  <path d="M12 .9a11.1 11.1 0 0 0-3.51 21.63c.56.1.76-.24.76-.54v-2.08c-3.1.68-3.76-1.31-3.76-1.31-.5-1.3-1.24-1.65-1.24-1.65-1.01-.7.08-.69.08-.69 1.12.08 1.7 1.15 1.7 1.15.99 1.7 2.6 1.21 3.23.93.1-.72.39-1.21.7-1.49-2.47-.28-5.07-1.24-5.07-5.51 0-1.22.44-2.21 1.15-2.99-.12-.28-.5-1.42.11-2.95 0 0 .94-.3 3.05 1.14a10.6 10.6 0 0 1 5.55 0c2.11-1.44 3.05-1.14 3.05-1.14.61 1.53.23 2.67.11 2.95.72.78 1.15 1.77 1.15 2.99 0 4.28-2.6 5.22-5.08 5.5.4.35.76 1.02.76 2.06v3.09c0 .3.2.65.77.54A11.1 11.1 0 0 0 12 .9Z" />
                </svg>
                <span>Download Android APKs</span>
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

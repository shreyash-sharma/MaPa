import type { Metadata } from "next";
import Link from "next/link";
import { Footer } from "@/components/Footer";
import { Masthead } from "@/components/Masthead";
import { DemoPlayers } from "./DemoPlayers";

export const metadata: Metadata = {
  title: "Try the live demo · MaPa",
  description: "Try MaPa's parent and family Android apps side by side in live Limrun emulators.",
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
            <div className="flex flex-wrap gap-x-5 gap-y-2 text-sm">
              <a href="https://github.com/shreyash-sharma/MaPa/releases/download/v0.1.0/MaPa-Parent-v0.1.0.apk" className="text-terracotta-ink underline underline-offset-4">Parent APK</a>
              <a href="https://github.com/shreyash-sharma/MaPa/releases/download/v0.1.0/MaPa-Family-v0.1.0.apk" className="text-terracotta-ink underline underline-offset-4">Family APK</a>
              <a href="https://github.com/shreyash-sharma/MaPa/releases/tag/v0.1.0" target="_blank" rel="noreferrer" className="text-ink-muted underline underline-offset-4">Release notes</a>
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
          <p className="mt-4 max-w-4xl text-xs leading-relaxed text-ink-faint">One identity-free, model-derived day powers both apps. No calls, messages, or notifications are sent.</p>
        </div>
      </main>
      <Footer />
    </>
  );
}

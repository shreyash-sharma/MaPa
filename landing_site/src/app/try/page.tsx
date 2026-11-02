import type { Metadata } from "next";
import Link from "next/link";
import { Footer } from "@/components/Footer";
import { Masthead } from "@/components/Masthead";
import { DemoPlayers } from "./DemoPlayers";

export const metadata: Metadata = {
  title: "Try the demo · MaPa",
  description: "The MaPa phone owner and family Android apps, streamed in the browser with example data.",
};

const APPS = [
  {
    id: "parent" as const,
    name: "Parent app",
    role: "Phone owner view",
    steps: ["Review the example activity chart and usual first-use time.", "Notice that the sample state describes phone activity, not a person's safety.", "This sample mode reads no device activity."],
  },
  {
    id: "family" as const,
    name: "Family app",
    role: "Family view",
    steps: ["Review the same fictional example from a family view.", "Read the sample note and last-use time.", "This emulator does not sync with the parent app."],
  },
];

export default function TryPage() {
  return (
    <>
      <Masthead />
      <main className="paper-canvas relative w-full">
        <div className="hairline-b flex items-center justify-between gap-4 px-5 py-2.5 font-mono text-[13px] tracking-wide text-ink-faint lg:px-16">
          <span className="flex items-center gap-2">
            <span className="h-1.5 w-1.5 rounded-full bg-terracotta" />
            Folio X · Try the demo
          </span>
          <span className="hidden md:block">Real Android builds, streamed in your browser</span>
          <span>Example data only</span>
        </div>

        <div className="mx-auto max-w-7xl space-y-10 px-5 pt-10 pb-24 lg:px-16 lg:pt-14">
          <header className="max-w-3xl space-y-4">
            <p className="font-mono text-xs tracking-wide text-terracotta-ink">Browser demo</p>
            <h1 className="font-display text-5xl leading-[1.02] text-ink sm:text-6xl">
              Try both apps, <span className="italic text-terracotta-ink">no phone needed.</span>
            </h1>
            <p className="font-serif text-xl leading-relaxed text-ink-muted">
              MaPa summarizes phone-activity patterns to support a family check-in. The model cannot determine whether a person is safe or
              needs help.
            </p>
          </header>

          <DemoPlayers apps={APPS} />

          <aside className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="rounded-2xl border border-line-strong/60 bg-surface/70 p-6">
              <p className="text-[13px] text-ink-faint">Sample preview</p>
              <p className="mt-2 text-[15px] leading-relaxed text-ink-muted">
                Limrun opens one debug build at a time in a cloud Android emulator. Sample mode bypasses sign-in and pairing. The screens show
                the same fixed fictional story; they do not connect to a real family account.
              </p>
            </div>
            <div className="rounded-2xl border border-line-strong/60 bg-surface/70 p-6">
              <p className="text-[13px] text-ink-faint">About the results</p>
              <p className="mt-2 text-[15px] leading-relaxed text-ink-muted">
                The benchmark uses model-derived timelines and programmatically added behavior cases. It is not evidence of real-world
                emergency detection. See the{" "}
                <Link href="/docs/results/" className="text-terracotta-ink underline-offset-4 hover:underline">
                  results
                </Link>{" "}
                and the{" "}
                <Link href="/docs/data/" className="text-terracotta-ink underline-offset-4 hover:underline">
                  data and its limits
                </Link>
                .
              </p>
            </div>
          </aside>
        </div>
      </main>
      <Footer />
    </>
  );
}

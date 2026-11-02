import type { Metadata } from "next";
import Link from "next/link";
import { Footer } from "@/components/Footer";
import { Masthead } from "@/components/Masthead";
import { DemoPlayers } from "./DemoPlayers";

export const metadata: Metadata = {
  title: "Try the live demo · MaPa",
  description: "Explore MaPa's parent and family Android apps side by side in Limrun cloud emulators with a model-derived sample from the frozen MaPa dataset.",
};

const APPS = [
  {
    id: "parent" as const,
    name: "Parent app",
    role: "Phone owner view",
    steps: ["See Alex's model-derived dataset day, including a quieter-than-usual morning.", "Review the rhythm summary and the phone owner's privacy context.", "No account or live phone telemetry is used in this demo."],
  },
  {
    id: "family" as const,
    name: "Family app",
    role: "Family view",
    steps: ["See the same model-derived morning from a family view.", "Read the context and preview a gentle check-in message.", "The message is a local preview; the sample apps do not sync or send it."],
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
              Try both Android apps <span className="italic text-terracotta-ink">live.</span>
            </h1>
            <p className="font-serif text-xl leading-relaxed text-ink-muted">
              Explore the MaPa parent and family apps in Android emulators hosted by Limrun and streamed into this page. This is a live app
              demo in cloud emulators, not a physical phone.
            </p>
            <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-[15px]">
              <a href="https://github.com/shreyash-sharma/MaPa/releases/download/v0.1.0/MaPa-Parent-v0.1.0.apk" className="text-terracotta-ink underline underline-offset-4 hover:text-terracotta">Download Parent APK</a>
              <a href="https://github.com/shreyash-sharma/MaPa/releases/download/v0.1.0/MaPa-Family-v0.1.0.apk" className="text-terracotta-ink underline underline-offset-4 hover:text-terracotta">Download Family APK</a>
              <a href="https://github.com/shreyash-sharma/MaPa/releases/tag/v0.1.0" target="_blank" rel="noreferrer" className="text-ink-muted underline underline-offset-4 hover:text-ink">GitHub release notes</a>
            </div>
            <p className="text-sm text-ink-faint">Google Play submission is in progress.</p>
          </header>

          <DemoPlayers apps={APPS} />

          <aside className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="rounded-2xl border border-line-strong/60 bg-surface/70 p-6">
              <p className="text-[13px] text-ink-faint">Sample preview</p>
              <p className="mt-2 text-[15px] leading-relaxed text-ink-muted">
                Limrun opens each debug build in its own cloud Android emulator, so both apps can run side by side. Sample mode bypasses
                sign-in and pairing. Both screens read the same model-derived dataset sample; they do not connect to a real family account.
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

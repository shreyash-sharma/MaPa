import type { Metadata } from "next";
import Link from "next/link";
import { Footer } from "@/components/Footer";
import { Masthead } from "@/components/Masthead";

export const metadata: Metadata = {
  title: "Try the demo · MaPa",
  description: "The MaPa phone owner and family Android apps, streamed in the browser with example data.",
};

/** Only a public Appetize embed link is accepted; anything else counts as not configured. */
function embed(url: string | undefined): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    return u.protocol === "https:" && (u.hostname === "appetize.io" || u.hostname.endsWith(".appetize.io")) ? u.toString() : null;
  } catch {
    return null;
  }
}

const APPS = [
  {
    id: "parent",
    name: "Parent app",
    role: "On the phone owner’s device",
    url: embed(process.env.NEXT_PUBLIC_APPETIZE_PARENT_EMBED_URL),
    steps: ["Read the consent screen; sharing starts only after Yes.", "Open the home screen to see the current state.", "Find Pause: the phone owner can stop sharing at any time."],
  },
  {
    id: "family",
    name: "Family app",
    role: "On a family member’s phone",
    url: embed(process.env.NEXT_PUBLIC_APPETIZE_FAMILY_EMBED_URL),
    steps: ["Open today’s view: the state and when it last updated.", "Read the daily note.", "Look for “Monitoring unavailable” when an update is old."],
  },
];

export default function TryPage() {
  const ready = APPS.filter((a) => a.url).length;
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
            {ready < APPS.length && (
              <p role="status" className="inline-flex rounded-xl border border-line-strong/70 bg-surface px-4 py-2 text-[15px] text-ink-muted">
                {ready === 0 ? "The browser demo is planned and not available yet." : "One app is available; the other is still being prepared."}
              </p>
            )}
          </header>

          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            {APPS.map((app) => (
              <section key={app.id} aria-labelledby={`${app.id}-title`} className="flex flex-col gap-4 rounded-3xl border border-line-strong/60 bg-surface/70 p-5 sm:p-6">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <div>
                    <h2 id={`${app.id}-title`} className="font-display text-3xl text-ink">
                      {app.name}
                    </h2>
                    <p className="text-[15px] text-ink-muted">{app.role}</p>
                  </div>
                  <span className="rounded-full border border-terracotta/50 bg-terracotta-soft px-3 py-1 text-[13px] text-terracotta-ink">Example data</span>
                </div>

                {app.url ? (
                  <>
                    <div className="mx-auto w-full max-w-[380px] overflow-hidden rounded-[2rem] border border-line-strong bg-ink">
                      <iframe
                        src={app.url}
                        title={`${app.name}, streamed from Appetize`}
                        className="block aspect-[9/19] w-full"
                        allow="fullscreen; clipboard-write"
                        allowFullScreen
                        loading="lazy"
                      />
                    </div>
                    <a href={app.url} target="_blank" rel="noreferrer" className="self-center text-[15px] text-terracotta-ink underline-offset-4 hover:underline">
                      Open full screen in a new tab ↗
                    </a>
                  </>
                ) : (
                  <div className="flex min-h-[18rem] flex-col items-center justify-center gap-2 rounded-2xl border border-dashed border-line-strong bg-surface-warm/60 p-8 text-center">
                    <p className="font-display text-2xl text-ink">Browser demo is being prepared</p>
                    <p className="max-w-xs text-[15px] text-ink-muted">This app is not streaming yet. Nothing on this page is running it.</p>
                  </div>
                )}

                <div>
                  <p className="text-[13px] text-ink-faint">What to look at</p>
                  <ol className="mt-1.5 list-decimal space-y-1 pl-5 text-[15px] leading-relaxed text-ink-muted">
                    {app.steps.map((s) => (
                      <li key={s}>{s}</li>
                    ))}
                  </ol>
                </div>
              </section>
            ))}
          </div>

          <aside className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="rounded-2xl border border-line-strong/60 bg-surface/70 p-6">
              <p className="text-[13px] text-ink-faint">Not verified in this demo</p>
              <p className="mt-2 text-[15px] leading-relaxed text-ink-muted">
                Sign-in, pairing and live sync between the two apps have not been verified here. The demo is not connected to any real family
                account or data.
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

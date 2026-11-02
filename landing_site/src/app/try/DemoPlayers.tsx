"use client";

import { RemoteControl, type RemoteControlConnectionState } from "@limrun/ui";
import Link from "next/link";
import { useEffect, useState } from "react";

type DemoApp = {
  id: "parent" | "family";
  name: string;
  role: string;
  steps: string[];
};

type DemoSession = {
  id: string;
  role: DemoApp["id"];
  endpointWebSocketUrl: string;
  token: string;
  sessionKey: string;
};

type DemoConnections = Partial<Record<DemoApp["id"], RemoteControlConnectionState | null>>;
type LaunchDialog = { app: DemoApp; phase: "starting" | "ready" | "error"; detail?: string };

async function stopSession(session: DemoSession): Promise<void> {
  await fetch("/api/demo/stop", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: session.id, sessionKey: session.sessionKey }),
    keepalive: true,
  }).catch(() => undefined);
}

export function DemoPlayers({ apps }: { apps: DemoApp[] }) {
  const [sessions, setSessions] = useState<Partial<Record<DemoApp["id"], DemoSession>>>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [connections, setConnections] = useState<DemoConnections>({});
  const [launch, setLaunch] = useState<LaunchDialog | null>(null);

  useEffect(() => {
    if (!Object.keys(sessions).length) return;
    const onPageHide = () => {
      void Promise.all(Object.values(sessions).filter((session): session is DemoSession => Boolean(session)).map(stopSession));
    };
    window.addEventListener("pagehide", onPageHide);
    return () => window.removeEventListener("pagehide", onPageHide);
  }, [sessions]);

  async function chooseApp(app: DemoApp) {
    if (busy) return;
    setBusy(true);
    setMessage("");
    setConnections((current) => ({ ...current, [app.id]: null }));
    try {
      const session = sessions[app.id];
      if (session) {
        await stopSession(session);
        setSessions((current) => {
          const next = { ...current };
          delete next[app.id];
          return next;
        });
        setMessage(`${app.name} stopped. Its emulator has been released.`);
        return;
      }

      setMessage("Starting the Android emulator. This usually takes a short while.");
      setLaunch({ app, phase: "starting" });
      const response = await fetch("/api/demo/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ role: app.id, owner: crypto.randomUUID() }),
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error ?? result.detail ?? "The demo could not start.");
      setSessions((current) => ({ ...current, [app.id]: result as DemoSession }));
      setMessage(`${app.name} emulator ready. You can start the other app too.`);
      setLaunch({ app, phase: "ready" });
    } catch (error) {
      const detail = error instanceof Error ? error.message : "The demo could not start. Please try again.";
      setMessage(detail);
      setLaunch((current) => current?.app.id === app.id ? { ...current, phase: "error", detail } : current);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {launch && (
        <div className="fixed inset-0 z-[200] flex items-center justify-center overflow-y-auto bg-ink/65 p-4 backdrop-blur-sm" role="presentation">
          <section role="dialog" aria-modal="true" aria-labelledby="sample-data-dialog-title" className="my-auto w-full max-w-2xl rounded-3xl border border-line-strong/70 bg-surface p-6 shadow-2xl sm:p-8">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="font-mono text-xs uppercase tracking-[0.16em] text-terracotta-ink">MaPa-Rhythm dataset sample</p>
                <h2 id="sample-data-dialog-title" className="mt-2 font-display text-3xl text-ink">
                  {launch.phase === "starting" ? `Preparing ${launch.app.name.toLowerCase()}` : launch.phase === "ready" ? "Your sample app is ready" : "The demo could not start"}
                </h2>
              </div>
              {launch.phase !== "starting" && (
                <button type="button" onClick={() => setLaunch(null)} className="inline-flex min-h-11 items-center rounded-full border border-line-strong px-4 text-sm text-ink-muted hover:text-ink">
                  {launch.phase === "ready" ? "View app" : "Close"}
                </button>
              )}
            </div>

            <p role="status" aria-live="polite" className="mt-4 flex items-center gap-2 text-[15px] text-ink-muted">
              {launch.phase === "starting" && <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-terracotta border-r-transparent" aria-hidden="true" />}
              {launch.phase === "starting" ? "Limrun is starting the Android emulator and opening the sample screen." : launch.phase === "ready" ? "Limrun is connected. You can keep this emulator open and start the other app too." : launch.detail}
            </p>

            <div className="mt-6 grid gap-4 sm:grid-cols-2">
              <div className="rounded-2xl border border-line-strong/50 bg-white/70 p-5">
                <h3 className="font-display text-xl text-ink">Data being loaded</h3>
                <ul className="mt-3 list-disc space-y-2 pl-5 text-sm leading-relaxed text-ink-muted">
                  <li>One full day from the frozen final-v2 training dataset: train split, derived subset, `model_derived` provenance.</li>
                  <li>96 fifteen-minute bins: interaction counts, observation coverage, charging, battery, and a profile rhythm.</li>
                  <li>Selected for a quieter-than-profile morning with later activity. The recorded bin values are unchanged.</li>
                  <li>The dataset sample was generated by a model fitted to S3 and UbiqLog logs. It is not a real person's phone record; identity is omitted.</li>
                </ul>
                <Link href="/docs/data/" className="mt-4 inline-block text-sm text-terracotta-ink underline underline-offset-4">Dataset origin and limits</Link>
              </div>
              <div className="rounded-2xl border border-line-strong/50 bg-white/70 p-5">
                <h3 className="font-display text-xl text-ink">What to expect</h3>
                <ul className="mt-3 list-disc space-y-2 pl-5 text-sm leading-relaxed text-ink-muted">
                  <li>Parent app: activity rhythm, last observed use, usual first use, and sharing context.</li>
                  <li>Family app: the same sample summary and an optional gentle check-in message preview.</li>
                  <li>No push notifications, calls, messages, Firebase sync, or live phone collection. The message is only a preview.</li>
                </ul>
              </div>
            </div>

            {launch.phase === "starting" && (
              <p className="mt-5 rounded-xl bg-terracotta-soft px-4 py-3 text-sm leading-relaxed text-terracotta-ink">
                The sample is bundled in the demo APK. Limrun is installing and opening the app; no personal dataset rows are sent to the emulator.
              </p>
            )}
          </section>
        </div>
      )}

      <div className="flex flex-col items-start gap-4 rounded-3xl border border-line-strong/60 bg-surface/70 p-6 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="font-display text-2xl text-ink">Choose an app to explore</p>
          <p className="mt-1 max-w-2xl text-[15px] leading-relaxed text-ink-muted">
            Start both apps to compare the parent and family views side by side. Each runs in its own Limrun Android emulator and shows the same model-derived day from the frozen MaPa dataset. Stop either when you finish; both also expire automatically.
          </p>
          {message && <p role="status" aria-live="polite" className="mt-3 text-[14px] text-terracotta-ink">{message}</p>}
        </div>
        <span className="shrink-0 rounded-full border border-terracotta/50 bg-terracotta-soft px-4 py-2 text-[13px] font-medium text-terracotta-ink">Both run together</span>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {apps.map((app) => {
          const session = sessions[app.id];
          const active = Boolean(session);
          const connection = connections[app.id];
          return (
            <section key={app.id} aria-labelledby={`${app.id}-title`} className="flex flex-col gap-5 rounded-3xl border border-line-strong/60 bg-surface/70 p-6 sm:p-8">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <div>
                  <h2 id={`${app.id}-title`} className="font-display text-3xl text-ink">{app.name}</h2>
                  <p className="text-[15px] text-ink-muted">{app.role}</p>
                </div>
                <span className="rounded-full border border-terracotta/50 bg-terracotta-soft px-3 py-1 text-[13px] text-terracotta-ink">Sample mode</span>
              </div>

              <div className="mx-auto flex aspect-[9/19] w-full max-w-[380px] items-center justify-center overflow-hidden rounded-[2rem] border border-line-strong bg-ink">
                {active && session ? (
                  <RemoteControl
                    url={session.endpointWebSocketUrl}
                    token={session.token}
                    sessionId={session.id}
                    openUrl="mapa://demo/sample"
                    className="h-full w-full"
                    onConnectionStateChange={(state) => setConnections((current) => ({ ...current, [app.id]: state }))}
                    onTerminated={() => {
                      setSessions((current) => {
                        const next = { ...current };
                        delete next[app.id];
                        return next;
                      });
                      setMessage(`${app.name} emulator ended. Start it again to reopen the demo.`);
                    }}
                  />
                ) : (
                  <div className="flex h-full w-full flex-col items-center justify-center gap-3 bg-[radial-gradient(ellipse_at_top,#3f3c39,#171615_60%)] p-8 text-center text-white">
                    <span className="font-mono text-xs uppercase tracking-[0.2em] text-white/60">Limrun · Android</span>
                    <span className="font-display text-3xl">{app.name}</span>
                    <span className="max-w-[15rem] text-sm leading-relaxed text-white/70">Choose this app to start the live emulator.</span>
                  </div>
                )}
              </div>

              {active && connection && (
                <p role="status" className="self-center text-[13px] text-ink-faint">
                  {connection === "connected" ? "Connected" : connection === "connecting" ? "Connecting…" : connection === "terminated" ? "Session ended" : "Reconnecting…"}
                </p>
              )}

              <button
                type="button"
                aria-pressed={active}
                disabled={busy}
                onClick={() => void chooseApp(app)}
                className="inline-flex min-h-12 items-center justify-center self-start rounded-full bg-terracotta px-6 py-3 text-[15px] font-medium text-white transition hover:bg-terracotta-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-terracotta disabled:cursor-wait disabled:opacity-60"
              >
                {busy ? "Please wait…" : active ? "Stop this demo" : `Start ${app.name.toLowerCase()}`}
              </button>

              <div>
                <p className="text-[13px] text-ink-faint">What to look at</p>
                <ol className="mt-1.5 list-decimal space-y-1 pl-5 text-[15px] leading-relaxed text-ink-muted">
                  {app.steps.map((step) => <li key={step}>{step}</li>)}
                </ol>
              </div>
            </section>
          );
        })}
      </div>
    </>
  );
}

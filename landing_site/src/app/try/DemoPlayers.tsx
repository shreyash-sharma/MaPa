"use client";

import { RemoteControl, type RemoteControlConnectionState } from "@limrun/ui";
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

async function stopSession(session: DemoSession): Promise<void> {
  await fetch("/api/demo/stop", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: session.id, sessionKey: session.sessionKey }),
    keepalive: true,
  }).catch(() => undefined);
}

export function DemoPlayers({ apps }: { apps: DemoApp[] }) {
  const [session, setSession] = useState<DemoSession | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [connection, setConnection] = useState<RemoteControlConnectionState | null>(null);
  const [accessCode, setAccessCode] = useState("");

  useEffect(() => {
    if (!session) return;
    const onPageHide = () => {
      void stopSession(session);
    };
    window.addEventListener("pagehide", onPageHide);
    return () => window.removeEventListener("pagehide", onPageHide);
  }, [session]);

  async function chooseApp(app: DemoApp) {
    if (busy) return;
    setBusy(true);
    setMessage("");
    setConnection(null);
    try {
      if (session?.role === app.id) {
        await stopSession(session);
        setSession(null);
        setMessage("Demo stopped. The emulator has been released.");
        return;
      }
      if (session) {
        await stopSession(session);
        setSession(null);
      }

      setMessage("Starting the Android emulator. This usually takes a short while.");
      const response = await fetch("/api/demo/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ role: app.id, owner: crypto.randomUUID(), accessCode }),
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error ?? result.detail ?? "The demo could not start.");
      setSession(result as DemoSession);
      setMessage("Emulator ready. Connecting to the sample app…");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The demo could not start. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="flex flex-col items-start gap-4 rounded-3xl border border-line-strong/60 bg-surface/70 p-6 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="font-display text-2xl text-ink">Choose an app to explore</p>
          <p className="mt-1 max-w-2xl text-[15px] leading-relaxed text-ink-muted">
            Limrun starts one Android emulator when you choose an app. Switching apps stops the current emulator first. The sample screen uses no account and reads no phone activity.
          </p>
          <label className="mt-4 flex max-w-sm flex-col gap-1.5 text-sm text-ink-muted">
            Judge access code
            <input
              type="password"
              autoComplete="off"
              value={accessCode}
              onChange={(event) => setAccessCode(event.target.value)}
              className="min-h-11 rounded-xl border border-line-strong bg-white px-4 text-ink outline-none focus:border-terracotta"
              placeholder="Enter the code shared with judges"
            />
          </label>
          {message && <p role="status" aria-live="polite" className="mt-3 text-[14px] text-terracotta-ink">{message}</p>}
        </div>
        <span className="shrink-0 rounded-full border border-terracotta/50 bg-terracotta-soft px-4 py-2 text-[13px] font-medium text-terracotta-ink">One emulator at a time</span>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {apps.map((app) => {
          const active = session?.role === app.id;
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
                    onConnectionStateChange={setConnection}
                    onTerminated={() => {
                      setSession(null);
                      setMessage("The emulator ended. Start the app again to reopen the demo.");
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

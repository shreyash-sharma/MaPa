"use client";

import { RemoteControl, type RemoteControlConnectionState } from "@limrun/ui";
import { useEffect, useRef, useState } from "react";

type DemoApp = { id: "parent" | "family"; name: string; role: string; steps: string[] };
type DemoSession = { id: string; role: DemoApp["id"]; endpointWebSocketUrl: string; token: string; sessionKey: string };
type DemoConnections = Partial<Record<DemoApp["id"], RemoteControlConnectionState | null>>;

async function stopSession(session: DemoSession): Promise<void> {
  await fetch("/api/demo/stop", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: session.id, sessionKey: session.sessionKey }), keepalive: true,
  }).catch(() => undefined);
}

export function DemoPlayers({ apps }: { apps: DemoApp[] }) {
  const [sessions, setSessions] = useState<Partial<Record<DemoApp["id"], DemoSession>>>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [connections, setConnections] = useState<DemoConnections>({});
  const appGridRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!Object.keys(sessions).length) return;
    const onPageHide = () => void Promise.all(Object.values(sessions).filter((s): s is DemoSession => Boolean(s)).map(stopSession));
    window.addEventListener("pagehide", onPageHide);
    return () => window.removeEventListener("pagehide", onPageHide);
  }, [sessions]);

  async function startApp(app: DemoApp): Promise<DemoSession> {
    const response = await fetch("/api/demo/start", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ role: app.id, owner: crypto.randomUUID() }),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error ?? result.detail ?? `${app.name} could not start.`);
    return result as DemoSession;
  }

  async function startApps(targets: DemoApp[]) {
    if (busy || !targets.length) return;
    setBusy(true);
    setMessage(`Starting ${targets.map((app) => app.name.toLowerCase()).join(" and ")} in separate Android emulators…`);
    setConnections((current) => Object.fromEntries(targets.map((app) => [app.id, null])));
    const outcomes = await Promise.allSettled(targets.map(async (app) => {
      const session = await startApp(app);
      setSessions((current) => ({ ...current, [app.id]: session }));
      return app;
    }));
    const done = outcomes.flatMap((result, index) => result.status === "fulfilled" ? [targets[index].name] : []);
    const errors = outcomes.flatMap((result, index) => result.status === "rejected" ? [`${targets[index].name}: ${result.reason instanceof Error ? result.reason.message : "Could not start."}`] : []);
    setMessage(errors.length ? `${done.join(" and ")} ${done.length === 1 ? "is" : "are"} running. ${errors.join(" ")}` : `${done.join(" and ")} running side by side.`);
    setBusy(false);
    requestAnimationFrame(() => appGridRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }));
  }

  async function toggleApp(app: DemoApp) {
    if (busy) return;
    const session = sessions[app.id];
    if (!session) return void startApps([app]);
    setBusy(true);
    await stopSession(session);
    setSessions((current) => { const next = { ...current }; delete next[app.id]; return next; });
    setConnections((current) => ({ ...current, [app.id]: null }));
    setMessage(`${app.name} stopped. Its emulator is released.`);
    setBusy(false);
  }

  const missing = apps.filter((app) => !sessions[app.id]);
  const bothActive = apps.every((app) => Boolean(sessions[app.id]));

  return (
    <>
      <section aria-label="Live Android app demos">
        <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-sm text-ink-muted">Two independent Android emulators. Launch both together or open one app.</p>
          <button type="button" disabled={busy || bothActive} onClick={() => void startApps(missing)} className="inline-flex min-h-12 shrink-0 items-center justify-center gap-2 rounded-full bg-terracotta-ink px-7 py-3 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5 hover:bg-terracotta focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-terracotta-ink disabled:cursor-not-allowed disabled:opacity-55">
            {busy ? "Starting…" : bothActive ? "Both apps are running" : missing.length === 1 ? `Start ${missing[0].name}` : "Launch both apps"}
            {!busy && !bothActive && <span aria-hidden="true">→</span>}
          </button>
        </div>
        {message && <p role="status" aria-live="polite" className="mb-4 rounded-xl bg-white/70 px-4 py-3 text-sm text-terracotta-ink">{message}</p>}

        <div ref={appGridRef} className="scroll-mt-6 grid grid-cols-1 gap-5 lg:grid-cols-2">
          {apps.map((app) => {
            const session = sessions[app.id];
            const active = Boolean(session);
            const connection = connections[app.id];
            return <section key={app.id} aria-labelledby={`${app.id}-title`} className="rounded-3xl border border-line-strong/60 bg-surface/70 p-4 sm:p-5">
              <div className="mb-4 flex items-center justify-between gap-3">
                <div><h2 id={`${app.id}-title`} className="font-display text-2xl text-ink">{app.name}</h2><p className="text-sm text-ink-muted">{app.role}</p></div>
                <span className={`rounded-full px-3 py-1 text-xs ${active ? "bg-green-100 text-green-900" : "border border-line-strong/60 bg-white/70 text-ink-muted"}`}>{active ? connection === "connected" ? "Live" : "Starting" : "Ready to launch"}</span>
              </div>
              <div className="mx-auto flex aspect-[9/17] w-full max-w-[400px] items-center justify-center overflow-hidden rounded-[2rem] border border-line-strong bg-ink shadow-xl">
                {active && session ? <RemoteControl url={session.endpointWebSocketUrl} token={session.token} sessionId={session.id} openUrl="mapa://demo/sample" className="h-full w-full" onConnectionStateChange={(state) => setConnections((current) => ({ ...current, [app.id]: state }))} onTerminated={() => { setSessions((current) => { const next = { ...current }; delete next[app.id]; return next; }); setMessage(`${app.name} emulator ended. You can start it again.`); }} /> :
                  <div className="flex h-full w-full flex-col items-center justify-center gap-3 bg-[radial-gradient(ellipse_at_top,#45413d,#171615_64%)] p-8 text-center text-white"><span className="font-mono text-[10px] uppercase tracking-[0.2em] text-white/60">Limrun · Android</span><span className="font-display text-3xl">{app.name}</span><span className="text-sm text-white/70">Start the app to open it live.</span></div>}
              </div>
              <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <ul className="list-disc pl-5 text-xs leading-relaxed text-ink-muted">{app.steps.map((step) => <li key={step}>{step}</li>)}</ul>
                <button type="button" aria-pressed={active} disabled={busy} onClick={() => void toggleApp(app)} className="min-h-11 shrink-0 rounded-full border border-line-strong px-5 text-sm font-medium text-ink transition hover:border-terracotta hover:text-terracotta-ink disabled:opacity-50">{active ? "Stop app" : `Start ${app.name}`}</button>
              </div>
            </section>;
          })}
        </div>
      </section>
    </>
  );
}

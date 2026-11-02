"use client";

import { RemoteControl, type RemoteControlConnectionState } from "@limrun/ui";
import Link from "next/link";
import { useEffect, useState } from "react";

type DemoApp = { id: "parent" | "family"; name: string; role: string; steps: string[] };
type DemoSession = { id: string; role: DemoApp["id"]; endpointWebSocketUrl: string; token: string; sessionKey: string };
type DemoConnections = Partial<Record<DemoApp["id"], RemoteControlConnectionState | null>>;
type LaunchState = { targets: DemoApp[]; phase: "starting" | "ready" | "error"; done: string[]; errors: string[] };

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
  const [launch, setLaunch] = useState<LaunchState | null>(null);

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
    setMessage("");
    setLaunch({ targets, phase: "starting", done: [], errors: [] });
    setConnections((current) => Object.fromEntries(targets.map((app) => [app.id, null])));
    const outcomes = await Promise.allSettled(targets.map(async (app) => {
      const session = await startApp(app);
      setSessions((current) => ({ ...current, [app.id]: session }));
      setLaunch((current) => current ? { ...current, done: [...current.done, app.id] } : current);
      return app;
    }));
    const done = outcomes.flatMap((result, index) => result.status === "fulfilled" ? [targets[index].name] : []);
    const errors = outcomes.flatMap((result, index) => result.status === "rejected" ? [`${targets[index].name}: ${result.reason instanceof Error ? result.reason.message : "Could not start."}`] : []);
    setLaunch({ targets, phase: errors.length ? (done.length ? "ready" : "error") : "ready", done: outcomes.flatMap((r, i) => r.status === "fulfilled" ? [targets[i].id] : []), errors });
    setMessage(errors.length ? `${done.join(" and ")} ${done.length === 1 ? "is" : "are"} running. ${errors.join(" ")}` : `${done.join(" and ")} running side by side.`);
    setBusy(false);
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
      {launch && (
        <div className="fixed inset-0 z-[200] flex items-center justify-center overflow-y-auto bg-ink/65 p-4 backdrop-blur-sm" role="presentation">
          <section role="dialog" aria-modal="true" aria-labelledby="launch-title" className="my-auto w-full max-w-xl rounded-3xl border border-line-strong/70 bg-surface p-6 shadow-2xl sm:p-8">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="font-mono text-xs uppercase tracking-[0.16em] text-terracotta-ink">Sample data disclosure</p>
                <h2 id="launch-title" className="mt-2 font-display text-3xl text-ink">{launch.phase === "starting" ? "Launching the apps" : launch.phase === "error" ? "Could not start the demo" : "Your demo is ready"}</h2>
              </div>
              {launch.phase !== "starting" && <button type="button" onClick={() => setLaunch(null)} className="min-h-11 rounded-full border border-line-strong px-4 text-sm text-ink-muted">Close</button>}
            </div>
            <p role="status" aria-live="polite" className="mt-3 text-sm leading-relaxed text-ink-muted">
              {launch.phase === "starting" ? `Limrun is opening ${launch.targets.map((app) => app.name.toLowerCase()).join(" and ")} in separate Android emulators.` : launch.errors.length ? launch.errors.join(" ") : "Both views show the same day. Close this panel to explore."}
            </p>
            <div className="mt-5 space-y-2">
              {launch.targets.map((app) => {
                const ready = launch.done.includes(app.id);
                const failed = launch.errors.some((error) => error.startsWith(`${app.name}:`));
                return <div key={app.id} className="flex items-center justify-between rounded-xl border border-line-strong/50 bg-white/70 px-4 py-3 text-sm">
                  <span className="text-ink">{app.name}</span><span className={ready ? "text-terracotta-ink" : failed ? "text-red-700" : "text-ink-faint"}>{ready ? "Ready" : failed ? "Could not start" : "Starting…"}</span>
                </div>;
              })}
            </div>
            <details className="mt-5 rounded-2xl bg-white/70 p-4 text-sm text-ink-muted">
              <summary className="cursor-pointer font-medium text-ink">What data is shown and what happens?</summary>
              <ul className="mt-3 list-disc space-y-2 pl-5 leading-relaxed">
                <li>Both apps use the same identity-free day from the frozen MaPa-Rhythm training dataset. Its provenance is <code>model_derived</code>; it was generated by a model fitted to S3 and UbiqLog logs. It is not an observed record from a real person.</li>
                <li>The sample has 96 fifteen-minute bins with interaction counts, observation coverage, charging, battery, and a profile rhythm. The sample is bundled in each APK; the page does not send personal rows.</li>
                <li>The parent app shows a rhythm summary. The family app shows the same summary and a message preview. No push notification, call, message, Firebase sync, or live phone collection occurs.</li>
              </ul>
              <Link href="/docs/data/" className="mt-3 inline-block text-terracotta-ink underline underline-offset-4">Dataset origin and limits</Link>
            </details>
            {launch.phase === "starting" && <p className="mt-4 text-xs text-ink-faint">Sessions stop when you stop them or leave this page. Idle sessions expire after 3 minutes; all sessions expire after 10 minutes.</p>}
          </section>
        </div>
      )}

      <section aria-label="Live Android app demos">
        <div className="mb-5 flex flex-col gap-4 rounded-3xl border border-terracotta/35 bg-terracotta-soft/70 p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6">
          <div>
            <p className="font-display text-2xl text-ink">Compare the two views</p>
            <p className="mt-1 text-sm text-ink-muted">Each app gets its own emulator. Both show the same example day.</p>
          </div>
          <button type="button" disabled={busy || bothActive} onClick={() => void startApps(missing)} className="inline-flex min-h-14 shrink-0 items-center justify-center gap-2 rounded-full bg-ink px-8 py-3 text-base font-semibold text-white shadow-md transition hover:bg-terracotta-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink disabled:cursor-not-allowed disabled:opacity-50">
            {busy ? "Starting…" : bothActive ? "Both apps are running" : missing.length === 1 ? `Start ${missing[0].name}` : "Launch both apps"}
            {!busy && !bothActive && <span aria-hidden="true">→</span>}
          </button>
        </div>
        {message && <p role="status" aria-live="polite" className="mb-4 rounded-xl bg-white/70 px-4 py-3 text-sm text-terracotta-ink">{message}</p>}

        <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
          {apps.map((app) => {
            const session = sessions[app.id];
            const active = Boolean(session);
            const connection = connections[app.id];
            return <section key={app.id} aria-labelledby={`${app.id}-title`} className="rounded-3xl border border-line-strong/60 bg-surface/70 p-4 sm:p-5">
              <div className="mb-4 flex items-center justify-between gap-3">
                <div><h2 id={`${app.id}-title`} className="font-display text-2xl text-ink">{app.name}</h2><p className="text-sm text-ink-muted">{app.role}</p></div>
                <span className={`rounded-full px-3 py-1 text-xs ${active ? "bg-green-100 text-green-900" : "border border-line-strong/60 bg-white/70 text-ink-muted"}`}>{active ? connection === "connected" ? "Live" : "Starting" : "Ready to launch"}</span>
              </div>
              <div className="mx-auto flex aspect-[9/17] w-full max-w-[340px] items-center justify-center overflow-hidden rounded-[2rem] border border-line-strong bg-ink shadow-xl">
                {active && session ? <RemoteControl url={session.endpointWebSocketUrl} token={session.token} sessionId={session.id} openUrl="mapa://demo/sample" className="h-full w-full" onConnectionStateChange={(state) => setConnections((current) => ({ ...current, [app.id]: state }))} onTerminated={() => { setSessions((current) => { const next = { ...current }; delete next[app.id]; return next; }); setMessage(`${app.name} emulator ended. You can start it again.`); }} /> :
                  <div className="flex h-full w-full flex-col items-center justify-center gap-3 bg-[radial-gradient(ellipse_at_top,#45413d,#171615_64%)] p-8 text-center text-white"><span className="font-mono text-[10px] uppercase tracking-[0.2em] text-white/60">Limrun · Android</span><span className="font-display text-3xl">{app.name}</span><span className="text-sm text-white/70">Start the demo to open the live app.</span></div>}
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

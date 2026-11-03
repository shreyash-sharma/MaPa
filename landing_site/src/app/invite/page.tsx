import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import InviteContent from "./InviteContent";

export const metadata: Metadata = {
  title: "Join MaPa · MaPa",
  description: "Download the MaPa Parent app and accept your family invitation.",
};

export default function InvitePage() {
  return (
    <>
      <main className="paper-canvas flex min-h-screen items-center justify-center px-5 py-12">
        <section className="w-full max-w-xl rounded-[2rem] border border-line-strong/60 bg-white/70 p-7 shadow-sm sm:p-10">
          <Link href="/" className="font-display text-2xl text-ink">MaPa</Link>
          <p className="mt-9 font-mono text-xs uppercase tracking-[0.16em] text-terracotta-ink">Family invitation</p>
          <h1 className="mt-3 font-display text-4xl leading-tight text-ink sm:text-5xl">Join your family on MaPa.</h1>
          <p className="mt-4 text-base leading-relaxed text-ink-muted">
            Download the Parent app, open it, enter the invitation code below, then review and accept the request.
          </p>
          <Suspense fallback={<div className="mt-8 h-28 animate-pulse rounded-2xl bg-black/5" />}>
            <InviteContent />
          </Suspense>
          <a
            href="https://github.com/shreyash-sharma/MaPa/releases/tag/v0.3.1"
            className="github-glass mt-7 flex min-h-14 w-full items-center justify-center gap-3 rounded-full px-6 text-sm font-medium"
          >
            <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5 fill-current">
              <path d="M12 .9a11.1 11.1 0 0 0-3.51 21.63c.56.1.76-.24.76-.54v-2.08c-3.1.68-3.76-1.31-3.76-1.31-.5-1.3-1.24-1.65-1.24-1.65-1.01-.7.08-.69.08-.69 1.12.08 1.7 1.15 1.7 1.15.99 1.7 2.6 1.21 3.23.93.1-.72.39-1.21.7-1.49-2.47-.28-5.07-1.24-5.07-5.51 0-1.22.44-2.21 1.15-2.99-.12-.28-.5-1.42.11-2.95 0 0 .94-.3 3.05 1.14a10.6 10.6 0 0 1 5.55 0c2.11-1.44 3.05-1.14 3.05-1.14.61 1.53.23 2.67.11 2.95.72.78 1.15 1.77 1.15 2.99 0 4.28-2.6 5.22-5.08 5.5.4.35.76 1.02.76 2.06v3.09c0 .3.2.65.77.54A11.1 11.1 0 0 0 12 .9Z" />
            </svg>
            Download MaPa for Android
            <span aria-hidden="true">↗</span>
          </a>
          <p className="mt-4 text-center text-xs leading-relaxed text-ink-faint">
            The invitation code is private. Share it only with the person you invited.
          </p>
        </section>
      </main>
    </>
  );
}

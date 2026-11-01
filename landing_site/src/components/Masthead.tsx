"use client";

import { animate, createScope, onScroll } from "animejs";
import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import { prefersReducedMotion } from "@/lib/motion";

const NAV = [
  { href: "#learn", label: "How it learns" },
  { href: "#privacy", label: "Privacy" },
  { href: "#model", label: "The model" },
  { href: "#faq", label: "FAQ" },
];

function clock(timeZone: string) {
  return new Intl.DateTimeFormat("en-GB", { timeZone, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(new Date());
}

export function Masthead() {
  const root = useRef<HTMLElement>(null);
  const [times, setTimes] = useState({ lucknow: "--:--", london: "--:--" });
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  useEffect(() => {
    const tick = () => setTimes({ lucknow: clock("Asia/Kolkata"), london: clock("Europe/London") });
    tick();
    const id = window.setInterval(tick, 20_000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    if (prefersReducedMotion()) return;
    const scope = createScope({ root }).add(() => {
      animate(".read-progress", {
        scaleX: [0, 1],
        ease: "linear",
        autoplay: onScroll({ target: document.body, enter: "start start", leave: "end end", sync: true }),
      });
    });
    return () => scope.revert();
  }, []);

  return (
    <header
      ref={root}
      className="sticky top-0 z-[100] w-full hairline-b bg-surface px-5 lg:px-16 py-1.5 text-[15px] text-ink-faint"
    >
      <div className="flex items-center justify-between gap-4">
        <a href="#top" className="group inline-flex min-h-11 items-center gap-2.5" onClick={() => setOpen(false)}>
          <Image src="/mapa-emblem-hd.png" alt="" width={20} height={23} className="transition-transform duration-300 group-hover:scale-110" />
          <span className="font-display text-xl text-ink">MaPa</span>
          <span aria-hidden className="hidden sm:inline text-line-strong">/</span>
          <span className="hidden sm:inline text-ink-muted">Diurnal kinship</span>
        </a>

        <nav aria-label="Sections" className="hidden md:flex items-center gap-2">
          {NAV.map((item) => (
            <a key={item.href} href={item.href} className="inline-flex min-h-11 items-center px-2.5 text-ink-muted transition-colors hover:text-terracotta-ink">
              {item.label}
            </a>
          ))}
        </nav>

        <div className="flex items-center gap-3 sm:gap-6">
          <div className="hidden lg:flex items-center gap-3 tabular" aria-label="Local times">
            <span>Lucknow <span className="text-ink">{times.lucknow}</span></span>
            <span aria-hidden className="text-line-strong">·</span>
            <span>London <span className="text-ink">{times.london}</span></span>
          </div>
          <a
            href="#access"
            className="inline-flex min-h-11 items-center rounded-full bg-ink px-5 text-surface transition-colors hover:bg-terracotta-ink"
          >
            Early access
          </a>
          <button
            type="button"
            className="inline-flex h-11 w-11 items-center justify-center rounded-full border border-line-strong text-ink md:hidden"
            aria-expanded={open}
            aria-controls="mobile-nav"
            aria-label={open ? "Close menu" : "Open menu"}
            onClick={() => setOpen((v) => !v)}
          >
            <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden>
              {open ? (
                <path d="M4 4l10 10M14 4L4 14" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
              ) : (
                <path d="M3 6h12M3 12h12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
              )}
            </svg>
          </button>
        </div>
      </div>
      {open && (
        <nav id="mobile-nav" aria-label="Sections" className="md:hidden hairline-t mt-1.5 pb-2">
          <ul className="flex flex-col">
            {NAV.map((item) => (
              <li key={item.href}>
                <a
                  href={item.href}
                  onClick={() => setOpen(false)}
                  className="flex min-h-12 items-center font-display text-2xl text-ink transition-colors hover:text-terracotta-ink"
                >
                  {item.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>
      )}
      <span aria-hidden className="read-progress absolute bottom-0 left-0 h-px w-full origin-left scale-x-0 bg-terracotta" />
    </header>
  );
}

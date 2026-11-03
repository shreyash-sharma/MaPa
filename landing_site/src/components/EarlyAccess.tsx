"use client";

import { animate, createScope, stagger } from "animejs";
import Image from "next/image";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { playOnEnter, prefersReducedMotion, showInstantly } from "@/lib/motion";
import { site } from "@/lib/site";

type Status = { tone: "idle" | "ok" | "error"; text: string };

export function EarlyAccess() {
  const root = useRef<HTMLElement>(null);
  const [email, setEmail] = useState("");
  const [sending, setSending] = useState(false);
  const [status, setStatus] = useState<Status>({ tone: "idle", text: "" });

  useEffect(() => {
    const el = root.current;
    if (!el) return;
    if (prefersReducedMotion()) {
      showInstantly(el.querySelectorAll<HTMLElement>("[data-reveal]"));
      return;
    }
    const scope = createScope({ root }).add(() => {
      const anim = animate("[data-reveal]", {
        opacity: [0, 1],
        y: [30, 0],
        filter: ["blur(8px)", "blur(0px)"],
        duration: 1200,
        delay: stagger(120),
        ease: "outExpo",
        autoplay: false,
      });
      playOnEnter(anim, el, "bottom-=15% top");
      animate(".seal", { rotate: [-6, 6], duration: 4200, ease: "inOutSine", loop: true, alternate: true });
    });
    return () => scope.revert();
  }, []);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const value = email.trim();
    if (!form.checkValidity() || !value) {
      setStatus({ tone: "error", text: "Please enter a valid email address." });
      return;
    }

    if (site.waitlistEndpoint) {
      setSending(true);
      setStatus({ tone: "idle", text: "Saving…" });
      try {
        const res = await fetch(site.waitlistEndpoint, {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({ email: value }),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        setEmail("");
        setStatus({ tone: "ok", text: "Thank you. You are on the list, and we will write when early access opens." });
      } catch {
        setStatus({ tone: "error", text: "That did not go through. Please try again in a moment." });
      } finally {
        setSending(false);
      }
      return;
    }

    if (site.contactEmail) {
      const subject = encodeURIComponent("MaPa early access");
      const body = encodeURIComponent(`Please add ${value} to the MaPa early access list.`);
      window.location.href = `mailto:${site.contactEmail}?subject=${subject}&body=${body}`;
      setStatus({ tone: "idle", text: "Your email app should open with a short note to send us." });
      return;
    }

    setStatus({ tone: "idle", text: "Early access sign-up is not open yet. Your email was not saved." });
  };

  return (
    <section ref={root} id="access" className="nocturne-canvas slipcase-dark relative z-[80] -mt-8 w-full border-t border-white/10 text-nocturne-ink">
      <div className="night-hairline-b flex items-center justify-between bg-nocturne/90 px-5 py-3 font-mono text-[13px] tracking-wide text-nocturne-muted backdrop-blur-sm lg:px-16">
        <span className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-[2px] bg-terracotta-glow" />
          <span className="font-semibold text-nocturne-ink">Folio VIII · Early access</span>
        </span>
        <span className="font-medium text-terracotta-glow">Invitation</span>
      </div>

      <div className="mx-auto max-w-3xl space-y-10 px-5 pt-24 pb-28 text-center lg:px-16">
        <div data-reveal className="mx-auto flex h-20 w-16 items-center justify-center rounded-2xl border border-nocturne-line-strong bg-surface p-2 shadow-lg">
          <Image src="/mapa-emblem-hd.png" alt="" width={44} height={51} className="seal" />
        </div>
        <div className="space-y-4">
          <p data-reveal className="font-mono text-xs tracking-wide text-terracotta-glow">Early access</p>
          <h2 data-reveal className="font-display text-5xl font-normal leading-[1.02] sm:text-7xl lg:text-8xl">A calmer way to stay close.</h2>
          <p data-reveal className="mx-auto max-w-xl font-serif text-xl font-light leading-relaxed text-nocturne-muted">
            No hardware to ship. You set it up, your parent taps Yes. Be among the first families to try MaPa.
          </p>
        </div>

        <form data-reveal noValidate onSubmit={submit} className="mx-auto flex max-w-md flex-col items-stretch gap-3 sm:flex-row sm:flex-wrap sm:items-center">
          <label htmlFor="waitlist-email" className="w-full text-left text-base text-nocturne-muted">
            Your email address
          </label>
          <input
            id="waitlist-email"
            type="email"
            name="email"
            required
            autoComplete="email"
            placeholder="name@example.com"
            aria-describedby="waitlist-status"
            aria-invalid={status.tone === "error"}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="flex-grow rounded-full border border-nocturne-line-strong bg-white/5 px-5 py-3.5 text-sm text-nocturne-ink placeholder:text-nocturne-faint focus:outline-none focus:ring-2 focus:ring-terracotta-glow"
          />
          <button
            type="submit"
            disabled={sending}
            className="min-h-11 whitespace-nowrap rounded-full bg-terracotta-ink px-6 py-3.5 text-base tracking-wide text-white shadow-md transition-colors hover:bg-[#8a3313] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-terracotta-glow active:scale-[0.98] disabled:opacity-60"
          >
            Join the early access list
          </button>
        </form>
        <p
          id="waitlist-status"
          role="status"
          aria-live="polite"
          className={`min-h-6 text-base ${status.tone === "error" ? "text-[#fe8357]" : status.tone === "ok" ? "text-terracotta-glow" : "text-nocturne-muted"}`}
        >
          {status.text}
        </p>
        <p data-reveal className="font-mono text-xs text-nocturne-faint">
          60 parent-app languages · <a href="https://huggingface.co/meta-models/Muse-Spark-Open-816B-A42B" target="_blank" rel="noreferrer" className="underline decoration-nocturne-faint underline-offset-2 hover:text-nocturne">Muse Spark Open · trained on 100+ languages</a> · Live via Baseten
        </p>
      </div>
    </section>
  );
}

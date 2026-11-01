"use client";

import { animate, createScope, stagger, utils, type JSAnimation, type Scope } from "animejs";
import { useEffect, useRef, useState } from "react";
import { playOnEnter, prefersReducedMotion, showInstantly } from "@/lib/motion";

const PLEDGE_HI = "निगरानी नहीं, बस एक शांत तस्वीर — कि उनका दिन हमेशा की तरह चल रहा है।";
const PLEDGE_EN = "Not surveillance. Just a calm picture that their day is going the way it always does.";
const BARS = 28;

function hindiVoice(): SpeechSynthesisVoice | undefined {
  return window.speechSynthesis?.getVoices().find((v) => v.lang.toLowerCase().startsWith("hi"));
}

/** Reads the Hindi pledge aloud with the device's own Hindi voice; the button only appears if one exists. */
export function Pledge() {
  const root = useRef<HTMLDivElement>(null);
  const scope = useRef<Scope | null>(null);
  const wave = useRef<JSAnimation | null>(null);
  const [canSpeak, setCanSpeak] = useState(false);
  const [speaking, setSpeaking] = useState(false);

  useEffect(() => {
    if (!("speechSynthesis" in window)) return;
    const check = () => setCanSpeak(Boolean(hindiVoice()));
    check();
    window.speechSynthesis.addEventListener("voiceschanged", check);
    return () => {
      window.speechSynthesis.removeEventListener("voiceschanged", check);
      window.speechSynthesis.cancel();
    };
  }, []);

  useEffect(() => {
    const el = root.current;
    if (!el) return;
    if (prefersReducedMotion()) {
      showInstantly(el.querySelectorAll<HTMLElement>("[data-reveal]"));
      return;
    }
    scope.current = createScope({ root }).add(() => {
      const anim = animate("[data-reveal]", {
        opacity: [0, 1],
        y: [24, 0],
        filter: ["blur(6px)", "blur(0px)"],
        duration: 1100,
        delay: stagger(120),
        ease: "outExpo",
        autoplay: false,
      });
      playOnEnter(anim, el);
    });
    return () => {
      wave.current?.revert();
      scope.current?.revert();
    };
  }, []);

  // The player mounts late (voices load asynchronously), after the section reveal has run.
  useEffect(() => {
    const player = root.current?.querySelector(".pledge-player");
    if (!canSpeak || !player || prefersReducedMotion()) return;
    const anim = animate(player, { opacity: [0, 1], y: [16, 0], duration: 900, ease: "outExpo" });
    return () => {
      anim.revert();
    };
  }, [canSpeak]);

  useEffect(() => {
    const bars = root.current?.querySelectorAll<HTMLElement>(".wave-bar");
    if (!bars) return;
    wave.current?.revert();
    wave.current = null;
    if (!speaking || prefersReducedMotion()) return;
    wave.current = animate(bars, {
      scaleY: () => [utils.random(0.2, 0.5, 2), utils.random(0.7, 1.6, 2)],
      duration: () => utils.random(260, 520),
      delay: stagger(24),
      ease: "inOutSine",
      loop: true,
      alternate: true,
    });
  }, [speaking]);

  const toggle = () => {
    const synth = window.speechSynthesis;
    if (speaking) {
      synth.cancel();
      setSpeaking(false);
      return;
    }
    const voice = hindiVoice();
    if (!voice) return;
    const u = new SpeechSynthesisUtterance(PLEDGE_HI);
    u.voice = voice;
    u.lang = voice.lang;
    u.rate = 0.88;
    u.onend = () => setSpeaking(false);
    u.onerror = () => setSpeaking(false);
    synth.cancel();
    synth.speak(u);
    setSpeaking(true);
  };

  return (
    <div ref={root} id="pledge" className="space-y-8 lg:col-span-7">
      <div className="space-y-3">
        <p data-reveal className="font-mono text-xs tracking-wide text-ink-faint">
          Our pledge · <span className="text-terracotta-ink">हिन्दी</span>
        </p>
        <h3 data-reveal lang="hi" className="font-deva text-4xl font-normal sm:text-5xl">
          सुनिए: हमारा वादा
        </h3>
        <p data-reveal lang="hi" className="font-deva text-xl italic leading-relaxed text-ink-muted sm:text-2xl">
          “{PLEDGE_HI}”
        </p>
        <p data-reveal className="font-serif text-base italic text-ink-faint">{PLEDGE_EN}</p>
      </div>

      {canSpeak && (
        <div className="pledge-player hairline-t hairline-b flex items-center gap-6 py-5">
          <button
            type="button"
            onClick={toggle}
            aria-pressed={speaking}
            aria-label={speaking ? "Stop the Hindi pledge" : "Listen to the pledge in Hindi"}
            className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-ink text-surface transition-colors duration-300 hover:bg-terracotta"
          >
            {speaking ? (
              <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden><rect x="3" y="3" width="4" height="12" fill="currentColor" /><rect x="11" y="3" width="4" height="12" fill="currentColor" /></svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden><path d="M5 3l10 6-10 6z" fill="currentColor" /></svg>
            )}
          </button>
          <div className="flex-grow space-y-2">
            <div className="flex h-8 items-center gap-1" aria-hidden>
              {Array.from({ length: BARS }, (_, i) => (
                <span key={i} className={`wave-bar block h-full w-1 origin-center rounded-full ${i % 5 === 2 ? "bg-terracotta" : "bg-ink/60"}`} style={{ transform: "scaleY(0.25)" }} />
              ))}
            </div>
            <p className="font-mono text-[13px] text-ink-faint">Read aloud by your device’s own Hindi voice</p>
          </div>
        </div>
      )}
    </div>
  );
}

"use client";

import { animate, createScope, createTimeline, onScroll, splitText, stagger, utils, type Scope, type Timeline } from "animejs";
import Image from "next/image";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { prefersReducedMotion, showInstantly } from "@/lib/motion";

type Mark = {
  /** Pieces animated one after another. Indic scripts are split by word, never by letter, so conjuncts stay intact. */
  parts: string[];
  label: string;
  code: string;
  lang: string;
  font: string;
  size: string;
  /** Line height that holds this script's tallest and deepest glyphs. */
  leading: string;
  dir?: "rtl";
};

const MARKS: Mark[] = [
  { parts: ["M", "a", "P", "a"], label: "English", code: "", lang: "English", font: "font-display", size: "text-[88px] sm:text-[128px] md:text-[160px]", leading: "leading-[1.08]" },
  { parts: ["माँ", "-", "पापा"], label: "माँ-पापा", code: "HI", lang: "Hindi", font: "font-deva", size: "text-[56px] sm:text-[84px] md:text-[106px]", leading: "leading-[1.3]" },
  { parts: ["அம்மா", "-", "அப்பா"], label: "அம்மா-அப்பா", code: "TA", lang: "Tamil", font: "font-tamil", size: "text-[40px] sm:text-[62px] md:text-[80px] lg:text-[92px]", leading: "leading-[1.3]" },
  { parts: ["అమ్మ", "-", "నాన్న"], label: "అమ్మ-నాన్న", code: "TE", lang: "Telugu", font: "font-telugu", size: "text-[46px] sm:text-[72px] md:text-[92px]", leading: "leading-[1.35]" },
  { parts: ["মা", "-", "বাবা"], label: "মা-বাবা", code: "BN", lang: "Bengali", font: "font-bengali", size: "text-[54px] sm:text-[82px] md:text-[104px]", leading: "leading-[1.3]" },
  { parts: ["आई", "-", "बाबा"], label: "आई-बाबा", code: "MR", lang: "Marathi", font: "font-deva", size: "text-[56px] sm:text-[84px] md:text-[106px]", leading: "leading-[1.3]" },
  { parts: ["ماں", "-", "باپ"], label: "ماں-باپ", code: "UR", lang: "Urdu", font: "font-urdu", size: "text-[40px] sm:text-[60px] md:text-[74px]", leading: "leading-[2.2]", dir: "rtl" },
  { parts: ["Maman", " & ", "Papa"], label: "Maman & Papa", code: "FR", lang: "French", font: "font-display", size: "text-[64px] sm:text-[96px] md:text-[128px]", leading: "leading-[1.12]" },
  { parts: ["お母さん", "・", "お父さん"], label: "お母さん・お父さん", code: "JA", lang: "Japanese", font: "font-jp", size: "text-[36px] sm:text-[60px] md:text-[80px]", leading: "leading-[1.15]" },
  { parts: ["엄마", "・", "아빠"], label: "엄마・아빠", code: "KO", lang: "Korean", font: "font-kr", size: "text-[50px] sm:text-[78px] md:text-[100px]", leading: "leading-[1.15]" },
];

const CYCLE_MS = 7000;
const ENGLISH_MS = 11000;
const ENGLISH_EVERY = 4;
const OTHERS = MARKS.length - 1;
/** The cycle as an endless run of slots: English every third slot, the other languages in order between. */
const markOf = (slot: number) =>
  slot % ENGLISH_EVERY === 0 ? 0 : 1 + (((ENGLISH_EVERY - 1) * Math.floor(slot / ENGLISH_EVERY) + (slot % ENGLISH_EVERY) - 1) % OTHERS);
/** One full pass of the pattern. The strip holds three, so it can slide forever and quietly hop back to the middle one. */
const PERIOD = ENGLISH_EVERY * OTHERS;
const SLOTS = Array.from({ length: PERIOD * 3 }, (_, i) => markOf(i));
const FIRST = PERIOD;
const nameOf = (mark: Mark) => (mark.code ? `${mark.lang}: ${mark.label}` : "English: MaPa");

export function Hero() {
  const root = useRef<HTMLElement>(null);
  const scope = useRef<Scope | null>(null);
  const transition = useRef<Timeline | null>(null);
  const current = useRef(0);
  const busy = useRef(false);
  const slot = useRef(FIRST);
  const timer = useRef<number | undefined>(undefined);
  const [activeSlot, setActiveSlot] = useState(FIRST);
  const [announce, setAnnounce] = useState("");

  /** Slides the strip so slot `k` sits under the fixed lens in the middle, and fits the lens to its label. */
  const centerSlot = useCallback((k: number, instant = false) => {
    const win = root.current?.querySelector<HTMLElement>(".lang-window");
    const strip = root.current?.querySelector<HTMLElement>(".lang-strip");
    const lens = root.current?.querySelector<HTMLElement>(".lang-lens");
    const pill = strip?.children[k] as HTMLElement | undefined;
    if (!win || !strip || !lens || !pill) return;
    const offset = (el: HTMLElement) => win.clientWidth / 2 - (el.offsetLeft + el.offsetWidth / 2);
    const home = k >= FIRST + PERIOD ? k - PERIOD : k < FIRST ? k + PERIOD : k;
    const settle = () => {
      if (home === k) return;
      slot.current = home;
      setActiveSlot(home);
      utils.set(strip, { x: offset(strip.children[home] as HTMLElement) });
    };
    if (instant || prefersReducedMotion()) {
      utils.set(strip, { x: offset(pill) });
      utils.set(lens, { width: pill.offsetWidth });
      settle();
      return;
    }
    animate(strip, { x: offset(pill), duration: 900, ease: "outExpo", onComplete: settle });
    animate(lens, { width: pill.offsetWidth, duration: 700, ease: "outExpo" });
  }, []);

  const go = useCallback(
    (next: number) => {
      const el = root.current;
      if (!el || next === current.current) return;
      const slides = el.querySelectorAll<HTMLElement>(".mark-slide");
      if (busy.current) {
        transition.current?.complete();
        busy.current = false;
      }
      slides.forEach((slide, i) => {
        if (i !== current.current && i !== next) utils.set(slide, { opacity: 0 });
      });
      const prev = slides[current.current];
      const incoming = slides[next];
      current.current = next;

      if (prefersReducedMotion()) {
        utils.set(prev, { opacity: 0 });
        utils.set(incoming, { opacity: 1 });
        utils.set(incoming.querySelectorAll(".glyph, .mark-meta"), { opacity: 1, y: 0, filter: "blur(0px)" });
        return;
      }

      busy.current = true;
      utils.set(incoming, { opacity: 1 });
      utils.set(incoming.querySelectorAll(".glyph, .mark-meta"), { opacity: 0, y: 18, filter: "blur(8px)" });
      transition.current?.cancel();
      transition.current = createTimeline({ onComplete: () => (busy.current = false) })
        .add(prev.querySelectorAll(".glyph, .mark-meta"), {
          opacity: 0,
          y: -12,
          filter: "blur(6px)",
          duration: 360,
          delay: stagger(35),
          ease: "inQuad",
          onComplete: () => utils.set(prev, { opacity: 0 }),
        })
        .add(
          incoming.querySelectorAll(".glyph"),
          { opacity: 1, y: 0, filter: "blur(0px)", duration: 900, delay: stagger(80), ease: "outExpo" },
          "-=120",
        )
        .add(incoming.querySelectorAll(".mark-meta"), { opacity: 1, y: 0, filter: "blur(0px)", duration: 700, ease: "outExpo" }, "-=650");
    },
    [],
  );

  const advanceTo = useCallback(
    (k: number) => {
      slot.current = k;
      setActiveSlot(k);
      centerSlot(k);
      go(markOf(k));
    },
    [centerSlot, go],
  );

  /** English holds longer; the slot pattern brings it back every third turn. */
  const restartCycle = useCallback(() => {
    window.clearTimeout(timer.current);
    if (prefersReducedMotion()) return;
    const schedule = () => {
      timer.current = window.setTimeout(
        () => {
          advanceTo(slot.current + 1);
          schedule();
        },
        markOf(slot.current) === 0 ? ENGLISH_MS : CYCLE_MS,
      );
    };
    schedule();
  }, [advanceTo]);

  const pause = () => window.clearTimeout(timer.current);
  const choose = (k: number) => {
    advanceTo(k);
    setAnnounce(nameOf(MARKS[markOf(k)]));
  };

  useEffect(() => {
    const el = root.current;
    if (!el) return;
    centerSlot(FIRST, true);
    // Script fonts arrive late and change pill widths; re-centre once they have.
    document.fonts?.ready.then(() => centerSlot(slot.current, true));

    if (prefersReducedMotion()) {
      showInstantly(el.querySelectorAll<HTMLElement>("[data-hero]"));
      return;
    }

    const headline = el.querySelector<HTMLElement>(".hero-headline");
    // A translated headline cannot be split word by word, so outside English it simply fades in.
    const html = document.documentElement;
    const english = (html.dataset.lang ?? "en") === "en" && html.dataset.i18n !== "harvest";
    const split = headline && english ? splitText(headline, { words: { wrap: "clip" } }) : null;

    scope.current = createScope({ root }).add(() => {
      const firstGlyphs = el.querySelectorAll(".mark-slide:first-child .glyph");
      utils.set(firstGlyphs, { opacity: 0, y: 40, filter: "blur(12px)" });
      utils.set(".hero-stage .mark-slide:first-child .mark-meta", { opacity: 0 });
      if (split) utils.set(split.words, { y: "110%" });

      createTimeline({ defaults: { ease: "outExpo", duration: 1400 } })
        .add(".hero-ring", { opacity: [0, 1], scale: [0.82, 1], delay: stagger(140) }, 0)
        .add(".hero-halo", { opacity: [0, 1], scale: [0.6, 1], duration: 2200 }, 0)
        .add(".hero-emblem", { opacity: [0, 1], y: [14, 0], scale: [0.85, 1] }, 150)
        .add(".hero-stage", { opacity: [0, 1], duration: 10 }, 200)
        .add(firstGlyphs, { opacity: 1, y: 0, filter: "blur(0px)", duration: 1600, delay: stagger(110) }, 260)
        .add(".hero-stage .mark-slide:first-child .mark-meta", { opacity: [0, 1], y: [10, 0] }, 800)
        .add(".lang-bar-wrap", { opacity: [0, 1], y: [12, 0] }, 950)
        .add(".hero-headline", split ? { opacity: [0, 1], duration: 10 } : { opacity: [0, 1], y: [24, 0], duration: 1200 }, 1000)
        .add(split ? split.words : [], { y: "0%", duration: 1200, delay: stagger(45) }, 1000)
        .add(".hero-fade", { opacity: [0, 1], y: [18, 0], delay: stagger(110) }, 1300);

      animate(".hero-ring-dash", { rotate: 360, duration: 140_000, ease: "linear", loop: true });
      animate(".hero-halo-inner", { scale: [1, 1.12], opacity: [0.75, 1], duration: 6000, ease: "inOutSine", loop: true, alternate: true });

      animate(".hero-parallax", {
        y: [0, 120],
        opacity: [1, 0.15],
        ease: "linear",
        autoplay: onScroll({ target: el, enter: "start start", leave: "start end", sync: true }),
      });
    });

    restartCycle();
    const onVisibility = () => (document.hidden ? window.clearTimeout(timer.current) : restartCycle());
    const onResize = () => centerSlot(slot.current, true);
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("resize", onResize);

    return () => {
      window.clearTimeout(timer.current);
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("resize", onResize);
      transition.current?.cancel();
      scope.current?.revert();
      split?.revert();
    };
  }, [centerSlot, restartCycle]);

  return (
    <section ref={root} id="top" className="relative z-10 w-full paper-canvas">
      <div className="hairline-b flex items-center justify-between px-5 lg:px-16 py-2.5 text-[13px] font-mono tracking-wide text-ink-faint select-none">
        <span className="flex items-center gap-2">
          <span className="h-1.5 w-1.5 rounded-full bg-terracotta" />
          Folio I · Tractatus diurnus
        </span>
        <span className="hidden sm:block">For families who live apart</span>
        <span>Edition 2026</span>
      </div>

      <div className="relative flex min-h-[calc(100svh-7.5rem)] flex-col items-center justify-center overflow-hidden px-5 pt-6 pb-14 sm:pt-8 lg:px-16">
        <div aria-hidden className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div data-hero className="hero-ring absolute h-[820px] w-[820px] rounded-full border border-line-strong/60" />
          <div data-hero className="hero-ring absolute h-[640px] w-[640px] rounded-full border border-dashed border-line-strong/50">
            <div className="hero-ring-dash absolute inset-0 rounded-full border-t border-terracotta/40" />
          </div>
          <div data-hero className="hero-ring absolute h-[460px] w-[460px] rounded-full border border-line/70" />
          <div className="absolute h-full w-px bg-gradient-to-b from-transparent via-line/50 to-transparent" />
        </div>
        <div aria-hidden data-hero className="hero-halo pointer-events-none absolute h-[480px] w-[480px]">
          <div className="hero-halo-inner h-full w-full rounded-full bg-gradient-to-tr from-terracotta/15 via-[#fe8357]/10 to-transparent blur-3xl" />
        </div>

        <div className="hero-parallax relative z-10 mx-auto flex w-full max-w-4xl flex-col items-center text-center">
          <Image
            data-hero
            src="/mapa-emblem-hd.png"
            alt=""
            width={48}
            height={55}
            priority
            className="hero-emblem mb-2 hidden select-none sm:block"
          />

          <h1 className="sr-only">MaPa: a calm daily picture of your parent&apos;s day, learned on their own phone</h1>

          <div
            aria-hidden
            data-hero
            className="hero-stage relative flex h-[150px] w-full items-center justify-center overflow-hidden select-none sm:h-[190px] md:h-[224px]"
          >
            {MARKS.map((mark, i) => (
              <div
                key={mark.lang}
                className="mark-slide absolute inset-0 flex flex-col items-center justify-center"
                style={{ opacity: i === 0 ? 1 : 0 }}
              >
                <p dir={mark.dir} translate="no" className={`${mark.font} ${mark.size} ${mark.leading} font-light tracking-[-0.03em] text-ink`}>
                  {mark.parts.map((part, j) => (
                    <span key={j} className="glyph whitespace-pre">
                      {part}
                    </span>
                  ))}
                </p>
                <p className="mark-meta mt-3 flex items-center gap-2 font-mono text-[14px] tracking-wide text-ink-faint sm:text-xs">
                  <span>{mark.lang}</span>
                </p>
              </div>
            ))}
          </div>

          <div data-hero className="lang-bar-wrap mt-2 mb-7 w-full max-w-3xl">
            <div
              role="group"
              aria-roledescription="carousel"
              aria-label="What families call their parents. Use the left and right arrow keys to change."
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
                e.preventDefault();
                choose(slot.current + (e.key === "ArrowRight" ? 1 : -1));
              }}
              onPointerEnter={pause}
              onPointerLeave={restartCycle}
              onFocus={pause}
              onBlur={restartCycle}
              className="lang-window relative h-[56px] w-full overflow-hidden rounded-full border border-line-strong/60 bg-surface-dim/80 backdrop-blur-md focus:outline-none focus-visible:ring-2 focus-visible:ring-terracotta"
            >
              <span aria-hidden className="lang-lens pointer-events-none absolute top-1 bottom-1 left-1/2 w-24 -translate-x-1/2 rounded-full bg-ink shadow-sm" />
              <div className="absolute inset-0 [mask-image:linear-gradient(to_right,transparent,black_16%,black_84%,transparent)]">
                <div translate="no" className="lang-strip absolute top-1 bottom-1 left-0 flex items-center">
                  {SLOTS.map((m, k) => (
                    <button
                      key={k}
                      type="button"
                      tabIndex={-1}
                      aria-hidden
                      onClick={() => {
                        choose(k);
                        restartCycle();
                      }}
                      className={`inline-flex h-full items-center whitespace-nowrap rounded-full px-4 text-[15px] transition-colors duration-500 ${
                        activeSlot === k ? "text-surface" : "text-ink-muted hover:text-ink"
                      }`}
                    >
                      {MARKS[m].label}
                    </button>
                  ))}
                </div>
              </div>
            </div>
            <span className="sr-only" aria-live="polite">
              {announce}
            </span>
          </div>

          <h2 data-hero className="hero-headline font-display text-[44px] leading-[1.02] font-normal text-ink sm:text-6xl lg:text-[80px]">
            Know their day began as usual, <span className="italic font-normal text-terracotta-ink">without ever asking.</span>
          </h2>
          <p data-hero className="hero-fade mx-auto mt-4 max-w-xl font-serif text-lg leading-relaxed font-light text-ink-muted">
            Your parent usually picks up their phone by 7:40. Hours later and miles away, you can see their morning began as usual —
            not because anyone watched them, but because their phone quietly came to life.
          </p>

          <div data-hero className="hero-fade mt-7 flex flex-col items-center justify-center gap-4 sm:flex-row">
            <a
              href="/try/"
              className="group inline-flex items-center gap-2.5 rounded-full bg-ink px-8 py-3.5 text-sm font-medium tracking-wide text-surface shadow-sm transition-all duration-300 hover:bg-terracotta active:scale-[0.98]"
            >
              Try live demo
              <span aria-hidden className="transition-transform duration-300 group-hover:translate-x-1">→</span>
            </a>
            <a href="#model-live" className="inline-flex min-h-11 items-center gap-1.5 px-4 text-base text-ink-muted transition-colors hover:text-ink">
              Watch it learn, live <span aria-hidden>↓</span>
            </a>
            <Link href="/docs/" className="inline-flex min-h-11 items-center gap-1.5 px-4 text-base text-terracotta-ink transition-colors hover:text-ink">
              Read the technical docs <span aria-hidden>→</span>
            </Link>
          </div>

          <div data-hero className="hero-fade mt-12 w-full max-w-xl hairline-t hairline-b py-3.5 font-mono text-[14px] text-ink-muted">
            <ul className="grid grid-cols-3 divide-x divide-line-strong/50">
              <li className="flex items-center justify-center gap-1.5">
                <span className="h-1.5 w-1.5 rounded-full bg-terracotta" />
                Zero cameras
              </li>
              <li className="flex items-center justify-center">Learns on-device</li>
              <li className="flex items-center justify-center">9,594 parameters</li>
            </ul>
          </div>
        </div>
      </div>
    </section>
  );
}

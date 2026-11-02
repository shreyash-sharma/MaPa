"use client";

import { animate, createScope, createTimeline, onScroll, stagger, svg, utils, type FunctionValue, type Scope } from "animejs";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { playOnEnter } from "@/lib/motion";
import { Reveal } from "@/components/Reveal";

/* A pinned 24-hour dial of one parent's day. Scrolling scrubs one timeline through four chapters.
   Every series here is an illustrative shape, not real model output. */

const C = 300;
const TAU = Math.PI * 2;
const ang = (min: number) => (min / 1440) * TAU - Math.PI / 2;
const round2 = (v: number) => Math.round(v * 100) / 100;
/** Rounded so the server and the browser render identical coordinates; trig differs in the last digits. */
const pt = (min: number, r: number) => [round2(C + r * Math.cos(ang(min))), round2(C + r * Math.sin(ang(min)))] as const;

function arc(r: number, m0: number, m1: number) {
  const [x0, y0] = pt(m0, r);
  const [x1, y1] = pt(m1, r);
  const large = m1 - m0 > 720 ? 1 : 0;
  return `M ${x0} ${y0} A ${r} ${r} 0 ${large} 1 ${x1} ${y1}`;
}

/** a parent's Thursday rhythm: hour of day → chance of a sign of life, linearly interpolated. */
const KEYS: [number, number][] = [
  [0, 0.04], [5, 0.05], [6, 0.12], [6.75, 0.35], [7.5, 0.72], [8, 0.8], [10.5, 0.74], [11, 0.44],
  [12.75, 0.4], [13.25, 0.52], [13.6, 0.14], [15.5, 0.12], [16, 0.46], [18, 0.56], [19, 0.66],
  [21, 0.58], [22, 0.34], [23, 0.1], [24, 0.04],
];
function rhythm(hour: number) {
  for (let i = 1; i < KEYS.length; i++) {
    const [h1, v1] = KEYS[i];
    const [h0, v0] = KEYS[i - 1];
    if (hour <= h1) return v0 + ((hour - h0) / (h1 - h0)) * (v1 - v0);
  }
  return 0.04;
}

const BINS = 96;
const R0 = 196;
const TICK = 66;
const PROFILE = Array.from({ length: BINS }, (_, i) => rhythm((i * 15 + 7.5) / 60));
const WOBBLE = Array.from({ length: BINS }, (_, i) => 0.88 + ((i * 37) % 23) / 92);
const NOW = 9 * 60 + 15;
const NOW_BIN = Math.floor(NOW / 15);
const USUAL_START = 7 * 60 + 45;
const LAST_USE = 22 * 60 + 50;

/** Three weeks of first phone use (minutes after midnight), drifting 40 minutes later. Two tagged days skipped. */
const DAYS = Array.from({ length: 21 }, (_, i) => {
  const wobble = [4, -6, 2, 8, -4, 0, 6, -8, 4, -2, 6, 0, -6, 4, 2, -4, 8, -2, 0, 4, -4][i];
  const skipped = i === 6 ? "Unwell" : i === 13 ? "Travelling" : null;
  const min = skipped === "Unwell" ? 10 * 60 + 20 : skipped === "Travelling" ? 5 * 60 + 50 : 7 * 60 + 40 + (i / 20) * 40 + wobble;
  return { min, r: 128 + i * 3, skipped };
});

const STATES = [
  { name: "Active", tag: "Common", body: "The phone is in use within your parent’s usual rhythm.", foot: "Rhythm as usual", dot: "bg-terracotta" },
  { name: "Quiet", tag: "Expected", body: "A quiet stretch the model expected: early morning, an afternoon rest.", foot: "Nothing to do", dot: "border border-ink-faint" },
  { name: "Unusual", tag: "Look closer", body: "Much quieter than usual for this time of day. A plain fact: “usually by 7:45, it’s 9:15.”", foot: "Maybe a good time to call", dot: "bg-[#fe8357] animate-pulse" },
  { name: "Monitoring unavailable", tag: "Honest", body: "Phone off, battery empty or no recent data. Never shown as an all-clear.", foot: "Truth without false calm", dot: "bg-ink-faint/50" },
  { name: "Paused by parent", tag: "Their choice", body: "Your parent paused sharing. The family sees that, never a guess.", foot: "Dignity intact", dot: "border border-dashed border-ink-faint" },
];

/** The face inside the dial: a sky that turns with a parent's day. The moon or sun sits at "now". Decorative, not sensed. */
const FACE_R = 170;
const ORBIT_R = 112;
const deg = (min: number) => (min / 1440) * 360;
const BODY_Y = C - ORBIT_R;
/** Stars on a golden-angle spiral, kept clear of the centre readout. */
const STARS = Array.from({ length: 48 }, (_, i) => {
  const r = 160 * Math.sqrt((i + 0.5) / 48);
  const a = i * 2.39996;
  return { x: round2(C + r * Math.cos(a)), y: round2(C + r * Math.sin(a)), r: round2(0.7 + ((i * 7) % 5) * 0.28) };
}).filter((s) => Math.abs(s.x - C) > 92 || Math.abs(s.y - C) > 54);
const RAYS = Array.from({ length: 12 }, (_, i) => {
  const a = (i / 12) * TAU;
  return { x1: round2(C + 17 * Math.cos(a)), y1: round2(BODY_Y + 17 * Math.sin(a)), x2: round2(C + 26 * Math.cos(a)), y2: round2(BODY_Y + 26 * Math.sin(a)) };
});

/** Dot grid inside the dial, with each dot's distance from the centre for a radial stagger. */
const GRID = (() => {
  const dots: { x: number; y: number; d: number }[] = [];
  for (let gx = -7; gx <= 7; gx++) {
    for (let gy = -7; gy <= 7; gy++) {
      const x = C + gx * 22;
      const y = C + gy * 22;
      const d = Math.hypot(x - C, y - C);
      if (d <= 164) dots.push({ x, y, d });
    }
  }
  return dots;
})();

/** Radial ripple: each dot waits in proportion to its distance from the centre. */
const gridDelay: FunctionValue = (_target, index) => GRID[index ?? 0].d * 5;

const CHAPTERS = [
  {
    key: "model",
    short: "The model",
    tag: "The model · on your parent’s phone",
    title: "One small model learns the shape of one parent’s day.",
    body: "Every 15 minutes it reads 21 inputs: phone-use timing, session starts and a few device signals. Trained on 21 of their days, it runs on their phone. Only conclusions leave it.",
    big: "21",
    small: "of their days learned",
  },
  {
    key: "forecast",
    short: "Forecast",
    tag: "Thursday · 9:15 AM · Forecast",
    title: "Today matches their Thursday rhythm.",
    body: "Forecast: their usual busy stretch runs until 11 AM. A quiet gap after lunch is normal for them.",
    foot: "Updated 3 minutes ago",
    big: "9:15",
    small: "now · next 4 hours forecast",
  },
  {
    key: "drift",
    short: "Drift",
    tag: "Another Thursday · 9:15 AM · Drift",
    title: "Their Thursday mornings usually start by 7:45. Today hasn’t started. It’s 9:15.",
    body: "A fixed 24-hour timer would still be waiting. MaPa knows within hours, because it knows their morning.",
    big: "1 h 30",
    small: "past their usual start",
  },
  {
    key: "learn",
    short: "Learns carefully",
    tag: "Three weeks later · Learns carefully",
    title: "Their mornings have moved 40 minutes later over 3 weeks.",
    body: "Is this their new routine? MaPa asks you before it learns it. Days tagged unwell or travelling never become “normal”.",
    big: "+40",
    small: "minutes, over three weeks",
  },
] as const;

/** Scroll-timeline units: a short hold on chapter one, then one segment per later chapter. */
const SEG = 1000;
const HOLD = 300;
const END = HOLD + SEG * (CHAPTERS.length - 1) + 200;

const origin = { transformOrigin: `${C}px ${C}px`, transformBox: "view-box" } as const;
const fill = { transformOrigin: "center", transformBox: "fill-box" } as const;

const mq = "(prefers-reduced-motion: reduce)";
const subscribeReduced = (cb: () => void) => {
  const m = window.matchMedia(mq);
  m.addEventListener("change", cb);
  return () => m.removeEventListener("change", cb);
};

export function ModelLive() {
  const root = useRef<HTMLElement>(null);
  const scope = useRef<Scope | null>(null);
  const reduced = useSyncExternalStore(subscribeReduced, () => window.matchMedia(mq).matches, () => false);
  const [answer, setAnswer] = useState<"yes" | "not-yet" | null>(null);
  const track = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = root.current;
    if (!el) return;

    scope.current = createScope({ root }).add(() => {
      const $ = (s: string) => el.querySelectorAll<SVGElement | HTMLElement>(s);
      const draw = (s: string) => svg.createDrawable(s);
      const later = ".ml-tick-today, .ml-day, .ml-ghost, .ml-callout, .ml-hot, .ml-label, .ml-hand";
      const fcArc = draw(".ml-fc-arc");
      const leaders = draw(".ml-leader");
      const mapaArc = draw(".ml-mapa-arc");
      const timerArc = draw(".ml-timer-arc");
      const usualArc = draw(".ml-usual-arc");
      const panels = [...el.querySelectorAll<HTMLElement>(".ml-panel")];

      if (reduced) {
        // A still frame of the forecast chapter: no motion, nothing from later chapters.
        utils.set($(".ml-day, .ml-ghost, .ml-hot, .ml-label"), { opacity: 0 });
        utils.set([...mapaArc, ...timerArc, ...usualArc], { draw: "0 0" });
        utils.set($(".ml-hand"), { rotate: (NOW / 1440) * 360 });
        utils.set($(".ml-fc-tick"), { stroke: "#e07a5f", strokeOpacity: 1 });
        utils.set($(".ml-orbit"), { rotate: deg(NOW) });
        utils.set($(".ml-nightsky, .ml-stars, .ml-moon, .ml-haze"), { opacity: 0 });
        return;
      }

      // Starting state. Chapter one stays readable; everything later is hidden until scrolled to.
      utils.set($(".ml-panel-1, .ml-panel-2, .ml-panel-3, .ml-readout-1, .ml-readout-2, .ml-readout-3"), { opacity: 0, y: 24 });
      utils.set($(later), { opacity: 0 });
      utils.set($(".ml-seg-1, .ml-seg-2, .ml-seg-3"), { opacity: 0.18 });

      // Hidden chapters are stacked on top of each other; they must not catch clicks or keyboard focus.
      const syncPanels = () => {
        panels.forEach((panel) => {
          panel.inert = parseFloat(panel.style.opacity || "1") < 0.5;
        });
      };
      syncPanels();
      utils.set([...fcArc, ...leaders, ...mapaArc, ...timerArc, ...usualArc], { draw: "0 0" });

      // The face opens on night: moon at midnight, stars out, no sun yet.
      const rays = draw(".ml-ray");
      utils.set($(".ml-sun, .ml-sunlight, .ml-clouds, .ml-haze"), { opacity: 0 });
      utils.set(rays, { draw: "0 0" });

      // Opening, played once on arrival: the rhythm grows around the clock and the grid ripples out.
      utils.set($(".ml-face"), { opacity: 0 });
      utils.set($(".ml-tick-base"), { scale: 0 });
      utils.set($(".ml-grid"), { opacity: 0, scale: 0.2 });
      utils.set($(".ml-intro, .ml-hours"), { opacity: 0 });
      playOnEnter(
        createTimeline({ autoplay: false, defaults: { ease: "outExpo" } })
          .add($(".ml-face"), { opacity: [0, 1], duration: 1400, ease: "outQuad" }, 0)
          .add($(".ml-tick-base"), { scale: [0, 1], duration: 900, delay: stagger(14), ease: "outElastic(1, .7)" }, 0)
          .add($(".ml-grid"), { opacity: [0, 1], scale: [0.2, 1], duration: 700, delay: gridDelay, ease: "outBack(2)" }, 200)
          .add($(".ml-hours"), { opacity: [0, 1], duration: 600 }, 900)
          .add($(".ml-intro"), { opacity: [0, 1], y: [20, 0], duration: 900, delay: stagger(120) }, 300),
        el,
        "bottom-=25% top",
      );

      const tl = createTimeline({
        defaults: { ease: "linear" },
        autoplay: onScroll({ target: track.current ?? el, enter: "top top", leave: "bottom bottom", sync: 0.35 }),
        onUpdate: syncPanels,
      });

      const start = (k: number) => HOLD + (k - 1) * SEG;
      tl.add($(".ml-panel-0, .ml-readout-0"), { opacity: [1, 0], y: [0, -24], duration: 160, ease: "inQuad" }, HOLD - 160).add(
        $(".ml-seg-0"),
        { opacity: [1, 0.35], duration: 160 },
        HOLD - 80,
      );
      for (let k = 1; k < CHAPTERS.length; k++) {
        tl.add($(`.ml-panel-${k}, .ml-readout-${k}`), { opacity: [0, 1], y: [24, 0], duration: 180, ease: "outQuad" }, start(k) + 40);
        tl.add($(`.ml-seg-${k}`), { opacity: [0.18, 1], duration: 160 }, start(k));
        if (k < CHAPTERS.length - 1) {
          tl.add($(`.ml-panel-${k}, .ml-readout-${k}`), { opacity: [1, 0], y: [0, -24], duration: 160, ease: "inQuad" }, start(k + 1) - 160);
          tl.add($(`.ml-seg-${k}`), { opacity: [1, 0.35], duration: 160 }, start(k + 1) - 80);
        }
      }
      tl.add($(".ml-ruler"), { left: ["0%", "100%"], duration: END }, 0);

      // II · Forecast: the hand sweeps to 9:15, today fills in behind it, the next four hours light up.
      const f = start(1);
      tl.add($(".ml-hand"), { opacity: [0, 1], duration: 60 }, f + 40)
        .add($(".ml-hand"), { rotate: [0, (NOW / 1440) * 360], duration: 340, ease: "inOutSine" }, f + 40)
        .add($(".ml-tick-today"), { opacity: [0, 1], duration: 30, delay: stagger(340 / NOW_BIN) }, f + 40)
        .add(fcArc, { draw: ["0 0", "0 1"], duration: 180, ease: "inOutQuad" }, f + 390)
        .add($(".ml-fc-tick"), { stroke: ["#faf7f2", "#e07a5f"], strokeOpacity: [0.35, 1], duration: 160 }, f + 390)
        .add(leaders, { draw: ["0 0", "0 1"], duration: 110, delay: stagger(80) }, f + 470)
        .add($(".ml-callout"), { opacity: [0, 1], duration: 90, delay: stagger(80) }, f + 530)
        .add($(".ml-callout"), { opacity: [1, 0], duration: 120 }, start(2) - 140)
        .add($(".ml-fc-tick"), { stroke: ["#e07a5f", "#faf7f2"], strokeOpacity: [1, 0.35], duration: 120 }, start(2) - 140)
        .add(fcArc, { draw: ["0 1", "1 1"], duration: 140 }, start(2) - 140)
        .add(leaders, { draw: ["0 1", "1 1"], duration: 120 }, start(2) - 140);

      // The face follows the hand: night gives way to day as it passes 6 AM, and the sun's rays draw in.
      const passes = (min: number) => f + 40 + (min / NOW) * 340;
      tl.add($(".ml-orbit"), { rotate: [0, deg(NOW)], duration: 340, ease: "inOutSine" }, f + 40)
        .add($(".ml-nightsky, .ml-stars, .ml-moon"), { opacity: [1, 0], duration: 90 }, passes(320))
        .add($(".ml-sun, .ml-sunlight, .ml-clouds"), { opacity: [0, 1], duration: 110 }, passes(345))
        .add(rays, { draw: ["0 0", "0 1"], duration: 90, delay: stagger(6) }, passes(420));

      // III · Drift: today empties, their usual start glows, MaPa's arc beats the 24-hour timer.
      const d = start(2);
      tl.add($(".ml-tick-today"), { opacity: [1, 0], duration: 120, delay: stagger(1.5, { from: "last" }) }, d + 20)
        .add($(".ml-hot"), { opacity: [0, 1], duration: 110 }, d + 140)
        .add(usualArc, { draw: ["0 0", "0 1"], duration: 120 }, d + 140)
        .add(mapaArc, { draw: ["0 0", "0 1"], duration: 300, ease: "inOutQuad" }, d + 240)
        .add(timerArc, { draw: ["0 0", "0 0.43"], duration: 300, ease: "inOutQuad" }, d + 240)
        .add(timerArc, { draw: ["0 0.43", "0 0.62"], duration: 300 }, d + 540)
        .add($(".ml-label"), { opacity: [0, 1], duration: 90, delay: stagger(60) }, d + 480)
        .add($(".ml-label, .ml-hot"), { opacity: [1, 0], duration: 120 }, start(3) - 140)
        .add(mapaArc, { draw: ["0 1", "1 1"], duration: 140 }, start(3) - 140)
        .add(timerArc, { draw: ["0 0.62", "0.62 0.62"], duration: 140 }, start(3) - 140)
        .add(usualArc, { draw: ["0 1", "1 1"], duration: 140 }, start(3) - 140)
        .add($(".ml-hand"), { opacity: [1, 0], duration: 120 }, start(3) - 140)
        // A quiet morning: haze over the sun, lifted again before the next chapter.
        .add($(".ml-haze"), { opacity: [0, 0.55], duration: 160 }, d + 140)
        .add($(".ml-sun"), { opacity: [1, 0.45], duration: 160 }, d + 140)
        .add($(".ml-haze"), { opacity: [0.55, 0], duration: 140 }, start(3) - 140)
        .add($(".ml-sun"), { opacity: [0.45, 1], duration: 140 }, start(3) - 140);

      // IV · Learns carefully: the rhythm rotates 40 minutes later over a ghost of the old one,
      // and three weeks of mornings spiral out, with tagged days left out.
      const l = start(3);
      tl.add($(".ml-ghost"), { opacity: [0, 1], duration: 160 }, l + 40)
        .add($(".ml-rhythm"), { rotate: [0, (40 / 1440) * 360], duration: 420, ease: "inOutSine" }, l + 120)
        .add($(".ml-grid"), { opacity: [1, 0.35], duration: 200 }, l + 40)
        .add($(".ml-day"), { opacity: [0, 1], scale: [0.2, 1], duration: 70, delay: stagger(18) }, l + 160)
        // The sun moves 40 minutes later with the rhythm.
        .add($(".ml-orbit"), { rotate: [deg(NOW), deg(NOW + 40)], duration: 420, ease: "inOutSine" }, l + 120);

      // Ambient life that is not tied to scroll.
      animate(".ml-pulse", { scale: [1, 1.5], opacity: [0.6, 0], duration: 1800, loop: true, ease: "outQuad" });
      animate($(".ml-star"), {
        opacity: [0.15, 1],
        duration: () => utils.random(900, 2600),
        delay: () => utils.random(0, 2500),
        loop: true,
        alternate: true,
        ease: "inOutSine",
      });
      animate($(".ml-sun-halo"), { scale: [1, 1.25], duration: 2600, loop: true, alternate: true, ease: "inOutSine" });
      animate($(".ml-rays"), { rotate: 360, duration: 60_000, loop: true, ease: "linear" });
      animate($(".ml-moon-glow"), { scale: [1, 1.35], opacity: [0.6, 1], duration: 3200, loop: true, alternate: true, ease: "inOutSine" });
      $(".ml-cloud").forEach((cloud, i) =>
        animate(cloud, { x: i ? [28, -28] : [-34, 34], duration: 11_000 + i * 3000, loop: true, alternate: true, ease: "inOutSine" }),
      );
    });

    return () => scope.current?.revert();
  }, [reduced]);

  useEffect(() => {
    if (!answer || reduced) return;
    const reply = root.current?.querySelector(".ml-reply");
    if (reply) animate(reply, { opacity: [0, 1], y: [8, 0], duration: 600, ease: "outExpo" });
  }, [answer, reduced]);

  const dial = (
    <svg viewBox="-40 -30 680 660" className="block h-full w-full overflow-visible" role="img" aria-label="A 24-hour dial of a parent’s usual day, drawn from their learned rhythm">
      <defs>
        <radialGradient id="mlGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#fe8357" stopOpacity="0.9" />
          <stop offset="100%" stopColor="#c85a32" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="mlNight" cx="50%" cy="40%" r="62%">
          <stop offset="0%" stopColor="#232a45" />
          <stop offset="100%" stopColor="#141311" />
        </radialGradient>
        <radialGradient id="mlSunlight" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#9a4a22" stopOpacity="0.6" />
          <stop offset="55%" stopColor="#5a2c18" stopOpacity="0.28" />
          <stop offset="100%" stopColor="#141311" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="mlSunHalo" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#ffb27a" stopOpacity="0.85" />
          <stop offset="100%" stopColor="#fe8357" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="mlMoonGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#dfe3f2" stopOpacity="0.4" />
          <stop offset="100%" stopColor="#dfe3f2" stopOpacity="0" />
        </radialGradient>
        <filter id="mlCloudBlur" x="-30%" y="-200%" width="160%" height="500%">
          <feGaussianBlur stdDeviation="5" />
        </filter>
        <clipPath id="mlFaceClip">
          <circle cx={C} cy={C} r={FACE_R} />
        </clipPath>
        <mask id="mlCrescent" maskUnits="userSpaceOnUse" x={C - 14} y={BODY_Y - 14} width="28" height="28">
          <circle cx={C} cy={BODY_Y} r="11" fill="#fff" />
          <circle cx={C + 5} cy={BODY_Y - 4} r="10" fill="#000" />
        </mask>
      </defs>

      <circle cx={C} cy={C} r="298" fill="#141311" />
      <circle cx={C} cy={C} r="298" fill="none" stroke="#2d2824" strokeWidth="2" />

      <g className="ml-face" clipPath="url(#mlFaceClip)">
        <circle className="ml-nightsky" cx={C} cy={C} r={FACE_R} fill="url(#mlNight)" />
        <g className="ml-stars">
          {STARS.map((s, i) => (
            <circle key={i} className="ml-star" cx={s.x} cy={s.y} r={s.r} fill="#f3ede2" />
          ))}
        </g>
        <g className="ml-clouds" filter="url(#mlCloudBlur)">
          <ellipse className="ml-cloud" cx={C - 58} cy={C + 74} rx="52" ry="10" fill="#faf7f2" opacity="0.12" />
          <ellipse className="ml-cloud" cx={C + 46} cy={C - 82} rx="40" ry="8" fill="#faf7f2" opacity="0.1" />
        </g>
        <g className="ml-orbit" style={origin}>
          <circle className="ml-sunlight" cx={C} cy={BODY_Y} r="210" fill="url(#mlSunlight)" />
          <g className="ml-sun">
            <circle className="ml-sun-halo" cx={C} cy={BODY_Y} r="32" fill="url(#mlSunHalo)" style={fill} />
            <g className="ml-rays" style={fill}>
              {RAYS.map((r, i) => (
                <line key={i} className="ml-ray" x1={r.x1} y1={r.y1} x2={r.x2} y2={r.y2} stroke="#ffb27a" strokeWidth="1.8" strokeLinecap="round" />
              ))}
            </g>
            <circle cx={C} cy={BODY_Y} r="11" fill="#ffb27a" />
          </g>
          <g className="ml-moon">
            <circle className="ml-moon-glow" cx={C} cy={BODY_Y} r="28" fill="url(#mlMoonGlow)" style={fill} />
            <circle cx={C} cy={BODY_Y} r="11" fill="#ece6da" mask="url(#mlCrescent)" />
          </g>
        </g>
        <circle className="ml-haze" cx={C} cy={C} r={FACE_R} fill="#2b2622" />
      </g>
      {CHAPTERS.map((_, i) => (
        <path key={i} className={`ml-seg ml-seg-${i}`} d={arc(290, i * 360 + 12, (i + 1) * 360 - 12)} fill="none" stroke={i === 2 ? "#fe8357" : "#e07a5f"} strokeWidth="5" strokeLinecap="round" />
      ))}
      {Array.from({ length: 144 }, (_, i) => {
        const m = i * 10;
        const major = i % 6 === 0;
        const [x0, y0] = pt(m, major ? 270 : 274);
        const [x1, y1] = pt(m, 280);
        return <line key={i} x1={x0} y1={y0} x2={x1} y2={y1} stroke="#3d3630" strokeWidth={major ? 1.4 : 0.8} />;
      })}

      <g className="ml-hours">
        {[
          [0, "12 AM"],
          [360, "6 AM"],
          [720, "12 PM"],
          [1080, "6 PM"],
        ].map(([m, label]) => {
          const [x, y] = pt(Number(m), 252);
          return (
            <text key={label} x={x} y={y} textAnchor="middle" dominantBaseline="middle" fill="#857c71" fontSize="15" fontFamily="var(--font-instrument)">
              {label}
            </text>
          );
        })}
      </g>

      {GRID.map((g, i) => (
        <circle key={i} className="ml-grid" cx={g.x} cy={g.y} r="1.6" fill="#857c71" style={fill} />
      ))}

      <g className="ml-ghost">
        {PROFILE.map((p, i) => {
          const m = i * 15 + 7.5;
          const [x0, y0] = pt(m, R0);
          const [x1, y1] = pt(m, R0 + 6 + p * TICK);
          return <line key={i} x1={x0} y1={y0} x2={x1} y2={y1} stroke="#faf7f2" strokeOpacity="0.14" strokeWidth="2.2" strokeLinecap="round" />;
        })}
      </g>

      <g className="ml-rhythm" style={origin}>
        {PROFILE.map((p, i) => {
          const m = i * 15 + 7.5;
          const [x0, y0] = pt(m, R0);
          const [x1, y1] = pt(m, R0 + 6 + p * TICK);
          const fc = i > NOW_BIN && i <= NOW_BIN + 16;
          return (
            <line
              key={i}
              className={`ml-tick-base ${fc ? "ml-fc-tick" : ""}`}
              x1={x0}
              y1={y0}
              x2={x1}
              y2={y1}
              stroke="#faf7f2"
              strokeOpacity="0.35"
              strokeWidth="2.6"
              strokeLinecap="round"
              style={{ transformOrigin: `${x0}px ${y0}px`, transformBox: "view-box" }}
            />
          );
        })}
        {PROFILE.slice(0, NOW_BIN + 1).map((p, i) => {
          const m = i * 15 + 7.5;
          const [x0, y0] = pt(m, R0);
          const [x1, y1] = pt(m, R0 + 6 + p * WOBBLE[i] * TICK);
          return <line key={i} className="ml-tick-today" x1={x0} y1={y0} x2={x1} y2={y1} stroke="#e07a5f" strokeWidth="2.6" strokeLinecap="round" />;
        })}
        {PROFILE.map((p, i) => {
          const m = i * 15 + 7.5;
          if (m < USUAL_START - 15 || m > NOW) return null;
          const [x0, y0] = pt(m, R0);
          const [x1, y1] = pt(m, R0 + 6 + p * TICK);
          return <line key={`h${i}`} className="ml-hot" x1={x0} y1={y0} x2={x1} y2={y1} stroke="#fe8357" strokeWidth="3" strokeLinecap="round" />;
        })}
      </g>

      <path className="ml-fc-arc" d={arc(R0 - 14, NOW, NOW + 240)} fill="none" stroke="#e07a5f" strokeWidth="3" strokeLinecap="round" />
      {[
        { m: 10 * 60 + 15, text: "Busy until 11 AM", dx: 1 },
        { m: 14 * 60 + 30, text: "Quiet after lunch · normal for them", dx: -1 },
      ].map(({ m, text, dx }) => {
        const [x0, y0] = pt(m, R0 + TICK + 30);
        const [x1, y1] = pt(m, 318);
        const x2 = x1 + dx * 26;
        return (
          <g key={text}>
            <path className="ml-leader max-md:hidden" d={`M ${x0} ${y0} L ${x1} ${y1} L ${x2} ${y1}`} fill="none" stroke="#6b645c" strokeWidth="1.2" />
            <text className="ml-callout max-md:hidden" x={x2 + dx * 8} y={y1} textAnchor={dx > 0 ? "start" : "end"} dominantBaseline="middle" fill="#1a1816" fontSize="20" fontFamily="var(--font-instrument)">
              {text}
            </text>
          </g>
        );
      })}

      <path className="ml-usual-arc" d={arc(R0 - 14, USUAL_START, NOW)} fill="none" stroke="#fe8357" strokeWidth="2" strokeDasharray="3 5" />
      <path className="ml-mapa-arc" d={arc(150, LAST_USE, 1440 + NOW)} fill="none" stroke="#fe8357" strokeWidth="4" strokeLinecap="round" />
      <path className="ml-timer-arc" d={arc(128, LAST_USE, LAST_USE + 1439)} fill="none" stroke="#857c71" strokeWidth="3" strokeLinecap="round" strokeDasharray="1 7" />
      {(() => {
        const [mx, my] = pt(NOW, 150);
        const [tx, ty] = pt(LAST_USE, 104);
        return (
          <>
            <circle className="ml-label" cx={mx} cy={my} r="6" fill="#fe8357" />
            <circle className="ml-label" cx={tx} cy={ty + 24} r="4" fill="none" stroke="#857c71" strokeWidth="1.5" />
          </>
        );
      })()}

      {DAYS.map((d, i) => {
        const [x, y] = pt(d.min, d.r);
        return d.skipped ? (
          <g key={i} className="ml-day" style={fill}>
            <circle cx={x} cy={y} r="5" fill="none" stroke="#a89f91" strokeWidth="1.4" />
            <line x1={x - 3} y1={y - 3} x2={x + 3} y2={y + 3} stroke="#a89f91" strokeWidth="1.2" />
          </g>
        ) : (
          <circle key={i} className="ml-day" cx={x} cy={y} r="4" fill="#fe8357" style={fill} />
        );
      })}

      <g className="ml-hand" style={origin}>
        <line x1={C} y1={C - 96} x2={C} y2={C - 286} stroke="#fe8357" strokeWidth="1.5" />
        <circle cx={C} cy={C - 286} r="5" fill="#fe8357" />
        <circle className="ml-pulse" cx={C} cy={C - 286} r="9" fill="url(#mlGlow)" style={fill} />
      </g>
    </svg>
  );

  return (
    <section
      ref={root}
      id="model-live"
      aria-label="The model, live"
      className="relative z-[25] -mt-8 w-full border-t border-line-strong/50 bg-surface-warm slipcase-paper"
    >
      <div className="hairline-b relative z-10 flex items-center justify-between bg-surface-warm/95 px-5 py-3 font-mono text-[13px] tracking-wide text-ink-faint lg:px-16">
        <span className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-[2px] bg-terracotta" />
          <span className="font-semibold text-ink">Folio II · The model, live</span>
        </span>
        <span className="hidden md:block">Scroll to run it</span>
        <span className="font-medium text-terracotta-ink">On-device AI</span>
      </div>

      {/* Phones cannot fit the heading beside the dial, so it scrolls above the pinned stage there. */}
      <div className="mx-auto max-w-7xl px-5 pt-14 pb-2 md:hidden">
        <p className="ml-intro mb-2 font-mono text-xs tracking-wide text-terracotta-ink">A 9,594-parameter model</p>
        <h2 className="ml-intro font-display text-5xl font-normal leading-[1.02]">
          Learning one parent’s day, <span className="italic text-terracotta-ink">quietly, on their phone.</span>
        </h2>
      </div>

      <div ref={track} className={reduced ? "" : "h-[420vh]"}>
      <div className={`${reduced ? "" : "sticky top-[56px] h-[calc(100svh-56px)]"} flex flex-col overflow-hidden`}>
        <div className="mx-auto hidden w-full max-w-7xl shrink-0 px-5 pt-6 md:block lg:px-16">
          <p className="ml-intro mb-1.5 font-mono text-xs tracking-wide text-terracotta-ink">A 9,594-parameter model</p>
          <h2 className="ml-intro font-display text-4xl leading-[1.05] font-normal text-ink lg:text-5xl">
            Learning one parent’s day, <span className="italic text-terracotta-ink">quietly, on their phone.</span>
          </h2>
        </div>
        <div
          className={`mx-auto grid min-h-0 w-full max-w-7xl flex-1 grid-cols-1 content-center items-center gap-4 px-5 py-4 md:grid-cols-12 md:gap-8 lg:px-16 ${
 reduced ? "" : "md:grid-rows-1 md:content-stretch"
 }`}
        >
          <div className="order-2 md:order-1 md:col-span-5">
            <div className={`relative ${reduced ? "space-y-8" : "min-h-[250px] sm:min-h-[230px] md:min-h-[300px]"}`}>
              {CHAPTERS.map((c, i) => (
                <div key={c.key} className={`ml-panel ml-panel-${i} ${reduced ? "" : "absolute inset-x-0 top-0"}`}>
                  <div className={i === 0 ? "ml-intro" : undefined}>
                  <p className="text-[14px] text-ink-faint">{c.tag}</p>
                  <p className="mt-2 font-display text-2xl leading-[1.1] text-ink sm:text-3xl xl:text-4xl">{c.title}</p>
                  <p className="mt-3 font-serif text-base italic leading-relaxed text-ink-muted sm:text-lg">{c.body}</p>
                  {"foot" in c && (
                    <p className="mt-3 flex items-center gap-2 text-[15px] text-ink-muted">
                      <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-terracotta" /> {c.foot}
                    </p>
                  )}
                  {c.key === "drift" && (
                    <ul className="mt-4 space-y-1.5 text-[15px]">
                      <li className="flex items-center gap-3 text-ink">
                        <span aria-hidden className="h-1 w-7 rounded-full bg-[#fe8357]" /> MaPa noticed at 9:15 AM
                      </li>
                      <li className="flex items-center gap-3 text-ink-muted">
                        <span aria-hidden className="w-7 border-t-2 border-dotted border-ink-faint" /> A 24-hour timer: still waiting until 10:50 PM
                      </li>
                    </ul>
                  )}
                  {c.key === "learn" && (
                    <div className="mt-4 space-y-2">
                      <div role="group" aria-label="Is this their new routine?" className="flex flex-wrap gap-3">
                        {([
                          ["yes", "Yes, it’s their new routine"],
                          ["not-yet", "Not yet"],
                        ] as const).map(([value, text]) => (
                          <button
                            key={value}
                            type="button"
                            aria-pressed={answer === value}
                            onClick={() => setAnswer(value)}
                            className={`inline-flex min-h-11 items-center rounded-full border px-5 text-base transition-colors ${
                              answer === value ? "border-terracotta bg-terracotta-soft text-ink" : "border-line-strong text-ink-muted hover:text-ink"
                            }`}
                          >
                            {text}
                          </button>
                        ))}
                      </div>
                      <p aria-live="polite" className="ml-reply min-h-6 text-base text-ink-muted">
                        {answer === "yes" && "Got it. MaPa will learn the later mornings, a small, bounded step at a time."}
                        {answer === "not-yet" && "Okay. MaPa keeps their usual rhythm and will ask again if the change holds."}
                      </p>
                    </div>
                  )}
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div
            className={`order-1 flex items-center justify-center md:order-2 md:col-span-7 ${reduced ? "" : "md:h-full md:min-h-0 md:[container-type:size]"}`}
          >
            <div
              className={`relative aspect-square w-[min(80vw,40svh)] ${reduced ? "md:w-[min(52vw,66svh)] lg:w-[min(46vw,68svh)]" : "md:w-[min(100cqw,100cqh)]"}`}
            >
              {dial}
              <div aria-hidden className="pointer-events-none absolute inset-0 flex items-center justify-center">
                <div className="relative h-24 w-40 text-center">
                  {CHAPTERS.map((c, i) => (
                    <div key={c.key} className={`ml-readout ml-readout-${i} absolute inset-0 flex flex-col items-center justify-center ${reduced && i !== 1 ? "hidden" : ""}`}>
                      <span className={`flex flex-col items-center ${i === 0 ? "ml-intro" : ""}`}>
                        <span className="font-display text-5xl leading-none text-nocturne-ink sm:text-6xl">{c.big}</span>
                        <span className="mt-1 text-[13px] leading-tight text-nocturne-muted">{c.small}</span>
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>

        {!reduced && (
          <div aria-hidden className="ml-ruler-wrap mx-auto mb-4 w-full max-w-7xl px-5 lg:px-16">
            <div className="relative flex h-8 items-center justify-between rounded-full bg-ink px-4">
              {Array.from({ length: 49 }, (_, i) => (
                <span key={i} className={`block w-px bg-nocturne-faint ${i % 12 === 0 ? "h-3.5" : "h-2"}`} />
              ))}
              <span className="absolute inset-y-0 right-4 left-4">
                <span className="ml-ruler absolute top-1/2 left-0 -mt-2 h-4 w-0.5 rounded bg-[#fe8357]" />
              </span>
            </div>
            <div className="relative mt-2 h-5 text-[13px] text-ink-faint">
              {CHAPTERS.map((c, i) => (
                <span key={c.key} className={`absolute whitespace-nowrap ${i === 0 ? "hidden sm:inline" : ""}`} style={{ left: `${(i === 0 ? 0 : (HOLD + (i - 1) * SEG) / END) * 100}%` }}>
                  {c.short}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>
      </div>

      <div className="mx-auto max-w-7xl px-5 pt-6 pb-24 lg:px-16">
        <Reveal className="hairline-t pt-10" gap={80}>
          <p data-reveal className="mb-6 text-[15px] text-ink-faint">
            Five honest states. Everything the family can ever see is one of these.
          </p>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
            {STATES.map((s, i) => (
              <article key={s.name} data-reveal className="space-y-2 rounded-2xl border border-line-strong/60 bg-surface/70 p-5">
                <div className="flex items-center justify-between text-[13px] text-ink-faint">
                  <span>
                    {String(i + 1).padStart(2, "0")} · {s.tag}
                  </span>
                  <span aria-hidden className={`h-2 w-2 rounded-full ${s.dot}`} />
                </div>
                <h3 className="font-display text-2xl leading-tight text-ink">{s.name}</h3>
                <p className="text-[15px] leading-relaxed text-ink-muted">{s.body}</p>
                <p className="text-[14px] text-terracotta-ink">{s.foot}</p>
              </article>
            ))}
          </div>
          <p data-reveal className="mt-6 font-serif text-lg italic text-ink-muted">MaPa never shows an all-clear when its data is old.</p>
        </Reveal>
      </div>
    </section>
  );
}

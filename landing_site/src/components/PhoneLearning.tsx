"use client";

import { animate, createScope, createTimeline, createTimer, stagger, svg, utils, type FunctionValue, type Scope, type Timer } from "animejs";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { prefersReducedMotion } from "@/lib/motion";
import { translateText } from "@/i18n/runtime";

/* MaPa learning inside Mummy's phone: 21 simulated days, then one quiet morning, on a loop.
   Every series and note here is illustrative. What leaves her phone is a short note, never her data. */

const round2 = (v: number) => Math.round(v * 100) / 100;

/** Mummy's usual chance of a sign of life by hour. */
const KEYS: [number, number][] = [
  [0, 0.04], [5, 0.05], [6, 0.12], [6.75, 0.35], [7.5, 0.72], [8, 0.8], [10.5, 0.74], [11, 0.44],
  [12.75, 0.4], [13.25, 0.52], [13.6, 0.14], [15.5, 0.12], [16, 0.46], [18, 0.56], [19, 0.66],
  [21, 0.58], [22, 0.34], [23, 0.1], [24, 0.04],
];
const hourOf = (min: number) => (((min % 1440) + 1440) % 1440) / 60;
function rhythm(min: number) {
  const hour = hourOf(min);
  for (let i = 1; i < KEYS.length; i++) {
    if (hour <= KEYS[i][0]) {
      const [h0, v0] = KEYS[i - 1];
      const [h1, v1] = KEYS[i];
      return v0 + ((hour - h0) / (h1 - h0)) * (v1 - v0);
    }
  }
  return 0.04;
}
/** The general rhythm a pretrained model starts from, before it has learned anyone in particular. */
const general = (min: number) => 0.2 + 0.36 * Math.exp(-((hourOf(min) - 10) ** 2) / 20);
const jitter = (n: number) => {
  const s = Math.sin(n * 12.9898) * 43758.5453;
  return s - Math.floor(s);
};

const pad = (n: number) => String(n).padStart(2, "0");
const clock = (min: number) => {
  const m = ((Math.round(min) % 1440) + 1440) % 1440;
  const h = Math.floor(m / 60);
  return `${h % 12 || 12}:${pad(m % 60)} ${h < 12 ? "AM" : "PM"}`;
};
/** London is 5 h 30 behind Lucknow in late October. */
const LONDON = -330;

// ---------- Mummy's phone ----------
const STEPS = 32;
const LAYERS = 6;
const NOW = STEPS - 1;
const NX0 = 40;
const DX = (320 - NX0) / NOW;
const NY0 = 418;
const NDY = 38;
const nx = (t: number) => round2(NX0 + t * DX);
const ny = (layer: number) => NY0 - layer * NDY;

/** The receptive tree of the newest output: kernel 3, dilation 2^(layer-1). */
const TREE = (() => {
  const nodes: Set<number>[] = Array.from({ length: LAYERS + 1 }, () => new Set<number>());
  const edges: { layer: number; from: number; to: number }[] = [];
  nodes[LAYERS].add(NOW);
  for (let layer = LAYERS; layer >= 1; layer--) {
    const d = 2 ** (layer - 1);
    for (const t of nodes[layer]) {
      for (const k of [0, 1, 2]) {
        const s = t - k * d;
        if (s < 0) continue;
        nodes[layer - 1].add(s);
        edges.push({ layer, from: s, to: t });
      }
    }
  }
  return { nodes, edges };
})();

/** Each connection as a curved synapse, with a fixed "usefulness" so some thicken more than others as it learns. */
const EDGES = TREE.edges.map((e, i) => {
  const x1 = nx(e.from);
  const y1 = ny(e.layer - 1);
  const x2 = nx(e.to);
  const y2 = ny(e.layer);
  const bend = Math.min(18, Math.abs(x2 - x1) * 0.22 + 4);
  return {
    layer: e.layer,
    d: `M ${x1} ${y1} Q ${round2((x1 + x2) / 2 - bend)} ${round2((y1 + y2) / 2 - bend * 0.35)} ${x2} ${y2}`,
    len: Math.round(Math.hypot(x2 - x1, y2 - y1) * 1.08),
    w: round2(0.3 + 0.7 * jitter(i * 3.7 + 1)),
  };
});

const BARS = 16;
const BAR_X = 40;
const BAR_W = 13;
const BAR_STEP = (280 - BAR_W) / (BARS - 1);
const BAR_BASE = 540;
const BAR_H = 66;
const MORNING = 6 * 60;

const DAYS = 21;
const TRAVEL_DAY = 8;
const CONFIDENT_DAY = 14;
const DOT_X0 = 46;
const DOT_DX = (314 - DOT_X0) / (DAYS - 1);
const DOT_Y = 596;

/** The family's phone and the path a note takes to reach it, for each layout. */
const LAYOUTS = {
  wide: {
    view: "0 0 580 740",
    fam: { x: 388, y: 150, w: 186, h: 392, rx: 30 },
    note: "M 300 664 C 372 664, 352 318, 404 318",
  },
  tall: {
    view: "0 0 360 1000",
    fam: { x: 18, y: 768, w: 324, h: 220, rx: 28 },
    note: "M 180 672 C 196 730, 168 760, 180 812",
  },
} as const;

// ---------- the story ----------
type State = "Learning" | "Active" | "Quiet" | "Unusual";
/** A note is the quiet evening summary; an alert is pushed straight away; an update closes an alert. */
type Note = { state: State; text: string; kind?: "note" | "alert" | "update" };
const CHIP = {
  note: { label: "Today’s note", fill: "#e07a5f" },
  alert: { label: "Alert", fill: "#fe8357" },
  update: { label: "Update", fill: "#e07a5f" },
} as const;

/** How long each simulated day takes. The first days, the travel day and day 15 slow down so they can be read. */
const PLAN = Array.from({ length: DAYS }, (_, d) => {
  const story = d < 3 || d === TRAVEL_DAY || d === CONFIDENT_DAY;
  return { ms: story ? 5200 : d < CONFIDENT_DAY ? 1500 : 1300, story };
});
const noteFor = (d: number): Note => {
  if (d === TRAVEL_DAY) return { state: "Active", text: "Travelling today, as you tagged it. This day won’t count as her normal." };
  if (d < 3) return { state: "Active", text: "Phone in use this morning. MaPa is still learning what is usual for her." };
  if (d < CONFIDENT_DAY) return { state: "Active", text: `Morning began around 7:${30 + (d % 6)}. Getting to know what is usual for her.` };
  return { state: "Active", text: "Morning began as usual at 7:32. Busy until about 11, then a quiet gap after lunch, normal for her." };
};

/** The live part: one quiet morning, a quarter-hour at a time, told the way the family would hear it. */
const LIVE: { min: number; ms: number; status?: string; note?: Note }[] = [
  { min: 7 * 60, ms: 1800, status: "A Thursday morning" },
  { min: 7 * 60 + 15, ms: 1600 },
  { min: 7 * 60 + 30, ms: 1600 },
  {
    min: 7 * 60 + 45,
    ms: 6200,
    status: "A little late · within her range, no alert",
    note: { state: "Quiet", text: "A little later than her usual 7:40, still within her normal range. No alert, nothing to do." },
  },
  { min: 8 * 60, ms: 1400 },
  { min: 8 * 60 + 15, ms: 1400 },
  { min: 8 * 60 + 30, ms: 1400 },
  { min: 8 * 60 + 45, ms: 1400 },
  { min: 9 * 60, ms: 1400 },
  {
    min: 9 * 60 + 15,
    ms: 8600,
    status: "Much quieter than usual · alerting the family",
    note: { state: "Unusual", kind: "alert", text: "Mummy usually picks up her phone by 7:40. It’s 9:15. Maybe a good time to call." },
  },
  {
    min: 9 * 60 + 30,
    ms: 8200,
    status: "Phone in use again · letting the family know",
    note: { state: "Active", kind: "update", text: "Phone in use since 9:24. A later start than usual. MaPa won’t treat one late morning as her new normal." },
  },
];
const SHE_PICKS_UP = 9 * 60 + 24;
const FADE_MS = 1400;
const LEARN_MS = PLAN.reduce((sum, p) => sum + p.ms, 0);
const CYCLE = LEARN_MS + LIVE.reduce((sum, s) => sum + s.ms, 0) + FADE_MS;

const PILL: Record<State, string> = {
  Learning: "pl-fam-state rounded-full border border-nocturne-line-strong px-2.5 py-0.5 text-[13px] text-nocturne-muted",
  Active: "pl-fam-state rounded-full bg-terracotta-glow/20 px-2.5 py-0.5 text-[13px] text-terracotta-glow",
  Quiet: "pl-fam-state rounded-full border border-nocturne-muted px-2.5 py-0.5 text-[13px] text-nocturne-ink",
  Unusual: "pl-fam-state rounded-full bg-[#fe8357] px-2.5 py-0.5 text-[13px] text-[#141311]",
};

const STEPS_COPY = [
  { when: "From day one", title: "Working, getting to know her", body: "The model decides from day one and gets surer every day." },
  { when: "Every night", title: "Retrains while charging", body: "On her phone, only while it is charging and idle." },
  { when: "Week two", title: "Confident", body: "Her own rhythm replaces the general one it started from." },
  { when: "Every evening", title: "A note, not her data", body: "Written by AI on her phone. Only the note reaches you." },
  { when: "Only when it matters", title: "An alert within hours", body: "A quiet morning reaches you hours in, not a day later. An ordinary late start sends nothing." },
];

const narrowQuery = "(max-width: 767px)";
const subscribeNarrow = (cb: () => void) => {
  const m = window.matchMedia(narrowQuery);
  m.addEventListener("change", cb);
  return () => m.removeEventListener("change", cb);
};

const sparkForward: FunctionValue = (target) => [14, -Number((target as Element).getAttribute("data-len"))];
const sparkBack: FunctionValue = (target) => [-Number((target as Element).getAttribute("data-len")), 14];

export function PhoneLearning() {
  const root = useRef<HTMLDivElement>(null);
  const scope = useRef<Scope | null>(null);
  const [step, setStep] = useState(0);
  const [sending, setSending] = useState(false);
  const narrow = useSyncExternalStore(subscribeNarrow, () => window.matchMedia(narrowQuery).matches, () => false);
  const layout = narrow ? LAYOUTS.tall : LAYOUTS.wide;

  useEffect(() => {
    const el = root.current;
    if (!el) return;
    const q = <T extends Element>(s: string) => el.querySelector<T>(s)!;
    const qa = <T extends Element>(s: string) => [...el.querySelectorAll<T>(s)];
    const text = (s: string, v: string) => {
      const node = el.querySelector(s);
      if (node && node.textContent !== v) node.textContent = v;
    };

    const inputs = qa<SVGCircleElement>(".pl-in");
    const bars = qa<SVGRectElement>(".pl-bar");
    const edges = qa<SVGPathElement>(".pl-edge");
    const rises = qa<SVGCircleElement>(".pl-rise");
    const dots = qa<SVGCircleElement>(".pl-dot-fill");
    let learned = 0;
    let rise = 0;

    const setBars = (values: number[], instant = false, color?: string) => {
      bars.forEach((bar, i) => {
        const h = values[i] * BAR_H;
        if (instant) {
          bar.setAttribute("height", String(h));
          bar.setAttribute("y", String(BAR_BASE - h));
          if (color) bar.setAttribute("fill", color);
        } else {
          animate(bar, { height: h, y: BAR_BASE - h, ...(color ? { fill: color } : {}), duration: 1100, delay: i * 30, ease: "outElastic(1, .8)" });
        }
      });
    };
    const morningShape = (l: number) =>
      Array.from({ length: BARS }, (_, i) => {
        const m = MORNING + i * 15 + 7.5;
        return general(m) + (rhythm(m) - general(m)) * l;
      });
    const setEdges = (l: number, instant = false) => {
      const width: FunctionValue = (t) => 0.5 + 1.7 * l * Number((t as Element).getAttribute("data-w"));
      const opacity: FunctionValue = (t) => 0.12 + 0.5 * l * Number((t as Element).getAttribute("data-w"));
      if (instant) utils.set(edges, { strokeWidth: width, strokeOpacity: opacity });
      else animate(edges, { strokeWidth: width, strokeOpacity: opacity, duration: 1100, ease: "outQuad" });
    };
    const activityOn = (day: number, min: number) => {
      const travel = day === TRAVEL_DAY;
      const r = rhythm(travel ? min - 150 : min) * (travel ? 0.6 : 1);
      return jitter(min + day * 977) < r ? 0.35 + 0.65 * r : 0.06;
    };
    const daylight = (on: boolean) => {
      animate(q(".pl-day"), { opacity: on ? 1 : 0, duration: on ? 600 : 500, ease: "inOutSine" });
      animate(q(".pl-night"), { opacity: on ? 0 : 1, duration: on ? 600 : 500, ease: "inOutSine" });
      animate(q(".pl-bolt"), { opacity: on ? 0 : 1, duration: 400 });
    };

    /** A forward pass: activity climbs the receptive tree into the forecast head. */
    const pulse = () => {
      for (let layer = 0; layer <= LAYERS; layer++) {
        const delay = layer * 110;
        animate(qa(`.pl-node-${layer}`), { r: [2.4, 4.4, 2.4], fill: ["#857c71", "#fe8357", "#a89f91"], duration: 560, delay: stagger(6, { start: delay }), ease: "outQuad" });
        if (layer > 0) {
          const sparks = qa<SVGPathElement>(`.pl-spark-${layer}`);
          utils.set(sparks, { stroke: "#fe8357" });
          animate(sparks, { strokeDashoffset: sparkForward, opacity: [1, 1, 0], duration: 460, delay: stagger(5, { start: delay - 160 }), ease: "inOutSine" });
        }
      }
      animate(q(".pl-head"), { scale: [1, 1.9, 1], opacity: [0.5, 1, 0.6], duration: 800, delay: LAYERS * 110 + 40, ease: "outQuad" });
    };
    /** Overnight retraining: a sweep runs back down the tree. */
    const sweepBack = () => {
      for (let layer = LAYERS; layer >= 1; layer--) {
        const sparks = qa<SVGPathElement>(`.pl-spark-${layer}`);
        utils.set(sparks, { stroke: "#faf7f2" });
        animate(sparks, { strokeDashoffset: sparkBack, opacity: [0.9, 0.9, 0], duration: 520, delay: stagger(5, { start: (LAYERS - layer) * 100 }), ease: "inOutSine" });
      }
    };

    // ---------- the note: written on her phone, delivered to yours ----------
    const deliver = (note: Note, famMin: number) => {
      const pill = q<HTMLElement>(".pl-fam-state");
      pill.className = PILL[note.state];
      pill.textContent = note.state;
      text(".pl-fam-note", note.text);
      text(".pl-fam-clock", clock(famMin + LONDON));
      animate(q(".pl-fam-card"), { opacity: [0.35, 1], y: [8, 0], duration: 700, ease: "outExpo" });
      animate(q(".pl-fam-frame"), { stroke: ["#fe8357", "#4a423b"], duration: 1400, ease: "outQuad" });
    };
    /** A push notification on the family's phone. An alert also makes the phone buzz. */
    const banner = (note: Note) => {
      const alert = note.kind === "alert";
      text(".pl-alert-kind", alert ? "MaPa · Alert" : "MaPa · Update");
      text(".pl-alert-title", `Mummy: ${note.state}`);
      text(".pl-alert-body", note.text);
      const box = q<HTMLElement>(".pl-alert");
      box.style.borderColor = alert ? "rgba(254,131,87,0.75)" : "rgba(168,159,145,0.45)";
      createTimeline()
        .add(box, { opacity: [0, 1], y: [-28, 0], duration: 650, ease: "outBack(1.6)" })
        .add(box, { opacity: [1, 0], y: [0, -18], duration: 500, ease: "inQuad" }, "+=4200");
      if (alert) animate(q(".pl-fam"), { x: [0, -5, 5, -4, 4, -2, 0], duration: 560, ease: "linear" });
    };
    const fly = (kind: keyof typeof CHIP, onArrive: () => void, duration = 1000) => {
      text(".pl-chip-label", CHIP[kind].label);
      q(".pl-chip-bg").setAttribute("fill", CHIP[kind].fill);
      const motion = svg.createMotionPath(q<SVGPathElement>(".pl-note-path"))!;
      animate(q(".pl-chip"), {
        translateX: motion.translateX,
        translateY: motion.translateY,
        opacity: [0, 1, 1, 0],
        scale: [0.6, 1, 1, 0.8],
        duration,
        ease: "inOutSine",
        onComplete: onArrive,
      });
    };
    const writeAndSend = (note: Note, atMin: number, typeMs: number) => {
      setSending(true);
      const node = q<HTMLElement>(".pl-today");
      const kind = note.kind ?? "note";
      const done = () =>
        fly(
          kind,
          () => {
            deliver(note, atMin);
            if (kind !== "note") banner(note);
            createTimer({ duration: 900, onComplete: () => setSending(false) });
          },
          typeMs === 0 ? 600 : 1000,
        );
      const words = translateText(note.text);
      if (typeMs === 0) {
        node.textContent = words;
        done();
        return;
      }
      const typed = { n: 0 };
      animate(typed, {
        n: words.length,
        duration: typeMs,
        ease: "linear",
        onUpdate: () => {
          node.textContent = words.slice(0, Math.round(typed.n));
        },
        onComplete: done,
      });
    };

    // ---------- learning days ----------
    const startDay = (d: number) => {
      text(".pl-badge-text", d < CONFIDENT_DAY ? `Day ${d + 1} · getting to know her, the model decides` : `Day ${d + 1} · confident, the model decides`);
      text(".pl-net-caption", "Today, as it happens");
      text(".pl-bars-caption", "Her mornings, as learned so far");
      text(".pl-bar-l", "6 AM");
      text(".pl-bar-r", "10 AM");
      text(".pl-status", d === TRAVEL_DAY ? "Day 9 · tagged travelling" : d === CONFIDENT_DAY ? "Confident now: her own rhythm leads" : "Learning her day");
      daylight(true);
      inputs.forEach((node) => node.setAttribute("fill-opacity", "0.06"));
      setStep(d < CONFIDENT_DAY ? 0 : 2);
    };
    const fillInput = (d: number, i: number) => {
      const min = MORNING + i * 30;
      text(".pl-clock", clock(min));
      inputs[i].setAttribute("fill-opacity", activityOn(d, min).toFixed(2));
      animate(inputs[i], { r: [0, 3.2], duration: 460, ease: "outBack(2.2)" });
      const dot = rises[rise++ % rises.length];
      dot.setAttribute("cx", String(nx(i)));
      animate(dot, { cy: [NY0 + 30, NY0], opacity: [0, 1, 0], duration: 420, ease: "outQuad" });
    };
    const evening = (d: number) => {
      text(".pl-clock", "9:00 PM");
      text(".pl-status", "Writing today’s note, on this phone");
      pulse();
      setStep(3);
      if (PLAN[d].story) createTimer({ duration: LAYERS * 110 + 160, onComplete: () => writeAndSend(noteFor(d), 21 * 60, 1300) });
      else writeAndSend(noteFor(d), 21 * 60, 0);
    };
    const night = (d: number) => {
      text(".pl-clock", "11:30 PM");
      daylight(false);
      setStep(1);
      if (d === TRAVEL_DAY) {
        text(".pl-status", "Travelling · not learned as normal");
        animate(q(".pl-dot-skip"), { opacity: [0, 1], duration: 500 });
        return;
      }
      text(".pl-status", "Retraining while charging");
      sweepBack();
      learned += 1;
      const l = learned / (DAYS - 1);
      createTimer({
        duration: 600,
        onComplete: () => {
          setEdges(l);
          setBars(morningShape(l), false, d >= CONFIDENT_DAY - 1 ? "#e07a5f" : "#857c71");
        },
      });
      animate(dots[d], { opacity: [0, 1], scale: [0.2, 1], duration: 600, ease: "outBack(2)" });
    };

    // ---------- one quiet morning ----------
    const liveActivity = (m: number) => (m >= SHE_PICKS_UP && m <= SHE_PICKS_UP + 60 ? 0.9 : m > 6 * 60 + 30 ? 0.06 : jitter(m) < rhythm(m) ? 0.35 + 0.65 * rhythm(m) : 0.06);
    const paintLive = (now: number) => {
      inputs.forEach((node, t) => node.setAttribute("fill-opacity", liveActivity(now - (NOW - t) * 15).toFixed(2)));
    };
    const liveBars = (now: number) => Array.from({ length: BARS }, (_, i) => rhythm(now + (i + 1) * 15));
    const startLive = () => {
      text(".pl-badge-text", "Live · the model decides");
      text(".pl-net-caption", "Latest 8 hours");
      text(".pl-bars-caption", "Her usual next 4 hours");
      text(".pl-bar-l", "+15 min");
      text(".pl-bar-r", "+4 h");
      daylight(true);
      setStep(4);
    };
    const liveStep = (k: number) => {
      const s = LIVE[k];
      text(".pl-clock", clock(s.min));
      if (s.status) text(".pl-status", s.status);
      paintLive(s.min);
      if (k > 0) {
        animate(q(".pl-stream"), { x: [DX, 0], duration: 800, ease: "outExpo" });
        animate(inputs[NOW], { r: [0, 3.2], duration: 600, ease: "outBack(2.4)" });
      }
      pulse();
      createTimer({ duration: LAYERS * 110 + 120, onComplete: () => setBars(liveBars(s.min), false, "#e07a5f") });
      if (s.note) {
        const note = s.note;
        createTimer({ duration: LAYERS * 110 + 300, onComplete: () => writeAndSend(note, s.min, note.kind === "alert" ? 900 : 1500) });
      }
    };

    const fadeOut = () => {
      text(".pl-status", "Replaying her first 21 days");
      animate(q(".pl-content"), { opacity: [1, 0.15], duration: FADE_MS - 300, ease: "inOutSine" });
    };
    const resetAll = () => {
      learned = 0;
      setEdges(0, true);
      setBars(morningShape(0), true, "#857c71");
      utils.set(dots, { opacity: 0 });
      utils.set(q(".pl-dot-skip"), { opacity: 0 });
      text(".pl-today", "");
      utils.set(q(".pl-alert"), { opacity: 0 });
      deliver({ state: "Learning", text: "MaPa is learning her rhythm. Notes arrive each evening." }, MORNING);
      animate(q(".pl-content"), { opacity: [0.15, 1], duration: 500, ease: "outQuad" });
    };

    // Reduced motion: the finished picture, still.
    const showFinal = () => {
      setEdges(1, true);
      setBars(morningShape(1), true, "#e07a5f");
      utils.set(dots, { opacity: 1 });
      utils.set(dots[TRAVEL_DAY], { opacity: 0 });
      utils.set(q(".pl-dot-skip"), { opacity: 1 });
      for (let i = 0; i < STEPS; i++) inputs[i].setAttribute("fill-opacity", activityOn(DAYS - 1, MORNING + i * 30).toFixed(2));
      const note = noteFor(DAYS - 1);
      text(".pl-badge-text", `Day ${DAYS} · confident, the model decides`);
      text(".pl-status", "Today’s note, written on this phone");
      text(".pl-today", translateText(note.text));
      text(".pl-clock", "9:00 PM");
      deliver(note, 21 * 60);
      setStep(3);
    };

    setEdges(0, true);
    setBars(morningShape(0), true, "#857c71");
    if (prefersReducedMotion()) {
      showFinal();
      return;
    }

    // One master clock walks a fixed script, so pausing it pauses the whole story.
    const script: { at: number; run: () => void }[] = [];
    let t0 = 0;
    PLAN.forEach((plan, d) => {
      const fill = plan.story ? 0.26 : 0.32;
      const eve = plan.story ? 0.29 : 0.36;
      const nite = plan.story ? 0.84 : 0.66;
      script.push({ at: t0, run: () => startDay(d) });
      for (let i = 0; i < STEPS; i++) script.push({ at: t0 + (i / STEPS) * fill * plan.ms + 1, run: () => fillInput(d, i) });
      script.push({ at: t0 + eve * plan.ms, run: () => evening(d) });
      script.push({ at: t0 + nite * plan.ms, run: () => night(d) });
      t0 += plan.ms;
    });
    script.push({ at: t0, run: startLive });
    LIVE.forEach((s, k) => {
      script.push({ at: t0 + 2, run: () => liveStep(k) });
      t0 += s.ms;
    });
    script.push({ at: t0, run: fadeOut });
    script.sort((a, b) => a.at - b.at);

    let master: Timer | null = null;
    let cursor = 0;
    let last = -1;
    scope.current = createScope({ root }).add(() => {
      master = createTimer({
        duration: CYCLE,
        loop: true,
        autoplay: false,
        onUpdate: (self) => {
          const t = self.iterationCurrentTime;
          if (t < last) {
            cursor = 0;
            resetAll();
          }
          last = t;
          while (cursor < script.length && script[cursor].at <= t) script[cursor++].run();
        },
      });
      animate(".pl-live", { opacity: [1, 0.25], duration: 900, loop: true, alternate: true, ease: "inOutSine" });
      animate(".pl-star", {
        opacity: [0.15, 1],
        duration: () => utils.random(900, 2400),
        delay: () => utils.random(0, 2000),
        loop: true,
        alternate: true,
        ease: "inOutSine",
      });
    });

    const io = new IntersectionObserver(([entry]) => (entry.isIntersecting ? master?.resume() : master?.pause()), { threshold: 0.2 });
    io.observe(el);
    return () => {
      io.disconnect();
      master?.revert();
      scope.current?.revert();
    };
  }, []);

  const fill = { transformOrigin: "center", transformBox: "fill-box" } as const;
  const { fam } = layout;
  const mono = { fontFamily: "var(--font-instrument)" } as const;

  return (
    <div ref={root} className="grid grid-cols-1 items-center gap-8 rounded-3xl border border-nocturne-line bg-nocturne-elevated/40 p-5 sm:p-8 lg:grid-cols-12 lg:gap-12">
      <div className="space-y-6 lg:col-span-5">
        <p className="flex items-center gap-2 text-[14px] text-nocturne-muted">
          <span aria-hidden className="pl-live h-2 w-2 rounded-full bg-[#fe8357]" />
          <span className="text-nocturne-ink">Simulated</span> · Mummy’s first 21 days, then one quiet morning
        </p>
        <h3 className="font-display text-3xl leading-tight text-nocturne-ink sm:text-4xl">
          It learns her day on her phone. <span className="italic text-terracotta-glow">You get a note, or an alert when it matters. Never her data.</span>
        </h3>
        <ol className="hidden space-y-2.5 md:block">
          {STEPS_COPY.map((s, i) => (
            <li
              key={s.title}
              aria-current={step === i ? "step" : undefined}
              className={`rounded-2xl border px-4 py-3 transition-colors duration-700 ${
                step === i || (i === 3 && sending) ? "border-terracotta-glow/60 bg-terracotta/10" : "border-nocturne-line"
              }`}
            >
              <p className="text-[13px] text-nocturne-faint">{s.when}</p>
              <p className="text-lg text-nocturne-ink">{s.title}</p>
              <p className="text-[15px] leading-relaxed text-nocturne-muted">{s.body}</p>
            </li>
          ))}
        </ol>
        <p className="hidden text-[14px] text-nocturne-faint md:block">
          One step: <span className="text-nocturne-ink">123 µs</span> on a Pixel 6a. Six layers with dilations 1 to 32 let the real model look back about
          32 hours.
        </p>
      </div>

      <div className="lg:col-span-7">
        <svg
          viewBox={layout.view}
          className="mx-auto block h-auto w-full max-w-[580px] overflow-visible"
          role="img"
          aria-label="Two phones. On Mummy’s phone, MaPa learns her daily rhythm over 21 simulated days, retraining at night while charging, and writes a short note each evening. Only that note reaches the family’s phone. Then one quiet morning: a little late at 7:45 sends no alert; at 9:15 the family gets an alert that she usually picks up her phone by 7:40, and at 9:30 an update that she is active again."
        >
          <defs>
            <clipPath id="plScreen">
              <rect x="16" y="16" width="328" height="708" rx="42" />
            </clipPath>
            <linearGradient id="plDayGrad" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="#2c1c14" />
              <stop offset="55%" stopColor="#171412" />
              <stop offset="100%" stopColor="#141311" />
            </linearGradient>
            <linearGradient id="plNightGrad" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="#1b2035" />
              <stop offset="60%" stopColor="#121320" />
              <stop offset="100%" stopColor="#0f0f14" />
            </linearGradient>
            <filter id="plGlow" x="-50%" y="-50%" width="200%" height="200%">
              <feGaussianBlur stdDeviation="2.5" result="blur" />
              <feMerge>
                <feMergeNode in="blur" />
                <feMergeNode in="SourceGraphic" />
              </feMerge>
            </filter>
          </defs>

          {/* Mummy's phone */}
          <rect x="0" y="168" width="5" height="54" rx="2.5" fill="#3d3630" />
          <rect x="0" y="236" width="5" height="54" rx="2.5" fill="#3d3630" />
          <rect x="355" y="210" width="5" height="84" rx="2.5" fill="#3d3630" />
          <rect x="3" y="3" width="354" height="734" rx="54" fill="#0b0a09" stroke="#4a423b" strokeWidth="2" />

          <g clipPath="url(#plScreen)">
            <rect className="pl-day" x="16" y="16" width="328" height="708" fill="url(#plDayGrad)" />
            <g className="pl-night" opacity="0">
              <rect x="16" y="16" width="328" height="708" fill="url(#plNightGrad)" />
              {Array.from({ length: 22 }, (_, i) => (
                <circle key={i} className="pl-star" cx={round2(30 + jitter(i + 0.3) * 300)} cy={round2(60 + jitter(i + 7.1) * 110)} r={round2(0.6 + jitter(i + 2.2) * 1.1)} fill="#f3ede2" />
              ))}
            </g>

            <g className="pl-content">
              <text className="pl-clock" x="40" y="48" fill="#faf7f2" fontSize="15" style={mono}>
                6:00 AM
              </text>
              <rect x="286" y="37" width="30" height="14" rx="3.5" fill="none" stroke="#a89f91" strokeWidth="1.3" />
              <rect x="317.5" y="41.5" width="2.5" height="5" rx="1" fill="#a89f91" />
              <rect x="288.5" y="39.5" width="18" height="9" rx="2" fill="#a89f91" />
              <path className="pl-bolt" d="M 303 38.5 L 297 45.5 L 301 45.5 L 299 50.5 L 305 43 L 301 43 Z" fill="#fe8357" opacity="0" />

              <text x="40" y="94" fill="#e07a5f" fontSize="14" style={mono}>
                MaPa · on Mummy’s phone
              </text>
              <text x="40" y="120" fill="#faf7f2" fontSize="22" style={mono}>
                Learning her rhythm
              </text>
              <rect x="36" y="136" width="288" height="30" rx="15" fill="#221f1c" stroke="#3d3630" />
              <text className="pl-badge-text" x="180" y="155.5" textAnchor="middle" fill="#faf7f2" fontSize="13" style={mono}>
                Day 1 · getting to know her, the model decides
              </text>

              {Array.from({ length: LAYERS + 1 }, (_, layer) => (
                <g key={`row${layer}`}>
                  {Array.from({ length: STEPS }, (_, t) => (
                    <circle key={t} cx={nx(t)} cy={ny(layer)} r="1.1" fill="#4a423b" />
                  ))}
                </g>
              ))}
              {EDGES.map((e, i) => (
                <path key={`e${i}`} className="pl-edge" data-w={e.w} d={e.d} fill="none" stroke="#a89f91" strokeOpacity="0.12" strokeWidth="0.5" />
              ))}
              <g filter="url(#plGlow)">
                {EDGES.map((e, i) => (
                  <path
                    key={`s${i}`}
                    className={`pl-spark-${e.layer}`}
                    data-len={e.len}
                    d={e.d}
                    fill="none"
                    stroke="#fe8357"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeDasharray={`12 ${e.len + 20}`}
                    strokeDashoffset={14}
                    opacity="0"
                  />
                ))}
              </g>
              <g className="pl-stream">
                {Array.from({ length: STEPS }, (_, t) => (
                  <circle key={`in${t}`} className="pl-in" cx={nx(t)} cy={ny(0)} r="3.2" fill="#e07a5f" fillOpacity="0.06" />
                ))}
              </g>
              {Array.from({ length: 4 }, (_, i) => (
                <circle key={`r${i}`} className="pl-rise" cx={nx(0)} cy={NY0 + 30} r="2" fill="#fe8357" opacity="0" />
              ))}
              {TREE.nodes.map((set, layer) => [...set].map((t) => <circle key={`n${layer}-${t}`} className={`pl-node-${layer}`} cx={nx(t)} cy={ny(layer)} r="2.4" fill="#857c71" />))}
              <circle className="pl-head" cx={nx(NOW)} cy={ny(LAYERS)} r="6" fill="#fe8357" opacity="0.6" filter="url(#plGlow)" style={fill} />
              <text className="pl-net-caption" x="40" y="444" fill="#857c71" fontSize="13" style={mono}>
                Today, as it happens
              </text>

              <text className="pl-bars-caption" x="40" y="466" fill="#faf7f2" fontSize="14" style={mono}>
                Her mornings, as learned so far
              </text>
              {Array.from({ length: BARS }, (_, i) => (
                <rect key={`b${i}`} className="pl-bar" x={round2(BAR_X + i * BAR_STEP)} y={BAR_BASE} width={BAR_W} height="0" rx="2" fill="#857c71" />
              ))}
              <line x1="40" x2="320" y1={BAR_BASE + 0.5} y2={BAR_BASE + 0.5} stroke="#3d3630" />
              <text className="pl-bar-l" x="40" y={BAR_BASE + 17} fill="#857c71" fontSize="13" style={mono}>
                6 AM
              </text>
              <text className="pl-bar-r" x="320" y={BAR_BASE + 17} textAnchor="end" fill="#857c71" fontSize="13" style={mono}>
                10 AM
              </text>

              {Array.from({ length: DAYS }, (_, i) => {
                const cx = round2(DOT_X0 + i * DOT_DX);
                return (
                  <g key={`d${i}`}>
                    <circle cx={cx} cy={DOT_Y} r="4.4" fill="none" stroke="#4a423b" strokeWidth="1.2" />
                    {i === TRAVEL_DAY ? (
                      <g className="pl-dot-skip" opacity="0">
                        <circle cx={cx} cy={DOT_Y} r="4.4" fill="none" stroke="#a89f91" strokeWidth="1.2" />
                        <line x1={cx - 3} y1={DOT_Y + 3} x2={cx + 3} y2={DOT_Y - 3} stroke="#a89f91" strokeWidth="1.2" />
                      </g>
                    ) : null}
                    <circle className="pl-dot-fill" cx={cx} cy={DOT_Y} r="4.4" fill="#e07a5f" opacity="0" style={fill} />
                  </g>
                );
              })}

              <text className="pl-status" x="40" y="628" fill="#e07a5f" fontSize="13" style={mono}>
                Learning her day
              </text>
              <foreignObject x="36" y="636" width="288" height="58">
                <p data-i18n-skip className="pl-today m-0 font-serif text-[14px] italic leading-snug text-nocturne-ink" />
              </foreignObject>
            </g>
            <rect x="140" y="704" width="80" height="4" rx="2" fill="#3d3630" />
          </g>
          <circle cx="180" cy="38" r="6.5" fill="#050505" stroke="#221f1c" />

          {/* Your phone, in London */}
          <g className="pl-fam">
          <rect className="pl-fam-frame" x={fam.x} y={fam.y} width={fam.w} height={fam.h} rx={fam.rx} fill="#0b0a09" stroke="#4a423b" strokeWidth="2" />
          <foreignObject x={fam.x + 12} y={fam.y + 14} width={fam.w - 24} height={fam.h - 28}>
            <div className="relative flex h-full flex-col gap-2.5 font-serif text-nocturne-muted">
              <div className="pl-alert pointer-events-none absolute inset-x-0 top-7 z-10 space-y-1 rounded-2xl border bg-[#2a1d17] p-3 opacity-0 shadow-lg shadow-black/50">
                <div className="flex items-center justify-between text-[13px]">
                  <span className="pl-alert-kind text-terracotta-glow">MaPa · Alert</span>
                  <span className="text-nocturne-faint">now</span>
                </div>
                <p className="pl-alert-title m-0 text-[15px] text-nocturne-ink">Mummy: Unusual</p>
                <p className="pl-alert-body m-0 text-[14px] leading-snug text-nocturne-ink">Mummy usually picks up her phone by 7:40.</p>
              </div>
              <div className="flex items-center justify-between text-[13px]">
                <span>Your phone · London</span>
                <span className="pl-fam-clock tabular text-nocturne-ink">12:30 AM</span>
              </div>
              <div className="pl-fam-card space-y-2 rounded-2xl border border-nocturne-line bg-[#1d1a17] p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[16px] text-nocturne-ink">Mummy</span>
                  <span className={PILL.Learning}>Learning</span>
                </div>
                <p className="pl-fam-note m-0 text-[14px] leading-snug text-nocturne-ink">MaPa is learning her rhythm. Notes arrive each evening.</p>
                <p className="m-0 text-[13px] text-nocturne-faint">Written on her phone</p>
              </div>
            </div>
          </foreignObject>
          </g>

          <path className="pl-note-path" d={layout.note} fill="none" stroke="none" />
          <g className="pl-chip" opacity="0" style={fill}>
            <rect className="pl-chip-bg" x="-46" y="-13" width="92" height="26" rx="13" fill="#e07a5f" />
            <text className="pl-chip-label" x="0" y="4.5" textAnchor="middle" fill="#141311" fontSize="13" style={mono}>
              Today’s note
            </text>
          </g>
        </svg>
        <p className="mt-4 text-center text-[15px] text-nocturne-muted md:hidden">
          <span className="text-nocturne-ink">{STEPS_COPY[step].title}.</span> {STEPS_COPY[step].body}
        </p>
      </div>
    </div>
  );
}

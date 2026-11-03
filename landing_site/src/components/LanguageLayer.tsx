"use client";

import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import config from "@/i18n/languages.json";
import { applyAttr, applyUnit, attrKeys, idOf, rootOf, unitOf, units, written, writtenAttrs, ATTRS, type Dict } from "@/i18n/dom";
import { setDictionary } from "@/i18n/runtime";

/* Shows the site in the visitor's language. Hindi ships with the site; the other active languages are translated
   by Meta's Muse Spark Open on first visit, stored on the server, and served to everyone after.
   The language is chosen before React runs (see layout.tsx), so the hero can skip its word-split animation. */

type Lang = { name: string; native: string; rtl?: boolean };
const LANGS = config.languages as Record<string, Lang>;
const BUNDLED = new Set(config.bundled);
const ENABLED = new Set(["en", ...config.bundled, ...config.live]);
const API = process.env.NEXT_PUBLIC_I18N_API ?? "";
const CACHE_DAYS = 3;

type Status = { tone: "work" | "done" | "error"; text: string } | null;

declare global {
  interface Window {
    __mapaHarvest?: () => { strings: Record<string, string> };
  }
}

function harvest() {
  const seen = new Map<string, string>();
  const take = (key: string) => seen.set(idOf(key), key);
  const sweep = (scope: ParentNode) => {
    units(scope).forEach((u) => take(u.key));
    attrKeys(scope).forEach((a) => take(a.key));
  };
  sweep(document.body);
  if (document.title) take(document.title);
  const observer = new MutationObserver((records) => {
    for (const r of records) {
      const root = rootOf(r.target);
      const u = root && unitOf(root);
      if (u) take(u.key);
      r.addedNodes.forEach((n) => n.nodeType === Node.ELEMENT_NODE && sweep(n as Element));
      if (r.type === "attributes") sweep(r.target.parentElement ?? document.body);
    }
  });
  observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  window.__mapaHarvest = () => ({ strings: Object.fromEntries(seen) });
}

function translatePage(dict: Dict, rtl: boolean) {
  const translateScope = (scope: ParentNode) => {
    for (const u of units(scope)) {
      const t = dict[idOf(u.key)];
      if (t) applyUnit(u, t, rtl);
    }
    for (const a of attrKeys(scope)) {
      const t = dict[idOf(a.key)];
      if (t) applyAttr(a.el, a.attr, t, a.nums);
    }
  };
  translateScope(document.body);
  // Next.js writes the <title> from metadata after hydration, so keep putting the translation back.
  const translateTitle = () => {
    const title = dict[idOf(document.title)];
    if (title && document.title !== title) document.title = title;
  };
  translateTitle();
  new MutationObserver(translateTitle).observe(document.head, { subtree: true, childList: true, characterData: true });

  // The animations keep writing English (badges, notes, clocks); translate each change as it lands.
  let pending = new Set<Element>();
  let scopes = new Set<Element>();
  let queued = false;
  const flush = () => {
    queued = false;
    const roots = pending;
    const subtrees = scopes;
    pending = new Set();
    scopes = new Set();
    roots.forEach((root) => {
      const u = root.isConnected ? unitOf(root) : null;
      const t = u && dict[idOf(u.key)];
      if (u && t) applyUnit(u, t, rtl);
    });
    subtrees.forEach((el) => el.isConnected && translateScope(el));
  };
  const queue = () => {
    if (!queued) {
      queued = true;
      requestAnimationFrame(flush);
    }
  };
  const observer = new MutationObserver((records) => {
    for (const r of records) {
      if (r.type === "characterData") {
        if (written.get(r.target) === (r.target as Text).data) continue;
        const root = rootOf(r.target);
        if (root) pending.add(root);
      } else if (r.type === "childList") {
        const root = rootOf(r.target);
        if (root) pending.add(root);
        r.addedNodes.forEach((n) => n.nodeType === Node.ELEMENT_NODE && scopes.add(n as Element));
      } else if (r.type === "attributes" && r.attributeName) {
        const el = r.target as Element;
        if (writtenAttrs.get(el)?.[r.attributeName] === el.getAttribute(r.attributeName)) continue;
        if (el.parentElement) scopes.add(el.parentElement);
      }
    }
    queue();
  });
  observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
}

function cached(lang: string): Dict | null {
  try {
    const raw = localStorage.getItem(`mapa-i18n:${lang}`);
    if (!raw) return null;
    const { savedAt, strings } = JSON.parse(raw);
    return Date.now() - savedAt < CACHE_DAYS * 864e5 ? strings : null;
  } catch {
    return null;
  }
}

async function live(lang: string, onProgress: (done: number, total: number) => void): Promise<Dict> {
  for (let attempt = 0; attempt < 120; attempt++) {
    const res = await fetch(`${API}/api/i18n/${lang}`, { cache: "no-store" });
    if (res.status === 200) {
      const data = await res.json();
      try {
        localStorage.setItem(`mapa-i18n:${lang}`, JSON.stringify({ savedAt: Date.now(), strings: data.strings }));
      } catch {}
      return data.strings;
    }
    if (res.status !== 202) throw new Error((await res.json().catch(() => ({}))).error ?? `HTTP ${res.status}`);
    const p = await res.json();
    onProgress(p.done ?? 0, p.total ?? 0);
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error("timed out");
}

export function LanguageLayer() {
  const [lang, setLang] = useState("en");
  const [status, setStatus] = useState<Status>(null);
  const pathname = usePathname();

  useEffect(() => {
    const html = document.documentElement;
    if (html.dataset.i18n === "harvest") {
      harvest();
      return;
    }
    const code = html.dataset.lang ?? "en";
    const info = LANGS[code];
    if (code === "en" || !info || !ENABLED.has(code)) return;
    // Reading the attribute the inline script set before hydration; nothing is fetched yet.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLang(code);
    const reveal = () => html.classList.remove("i18n-pending");
    const ready = (dict: Dict, text: string) => {
      setDictionary(dict);
      translatePage(dict, !!info.rtl);
      html.lang = code;
      reveal();
      setStatus({ tone: "done", text });
    };
    const fail = () => {
      reveal();
      setStatus({ tone: "error", text: `${info.name} is not available right now, so the page is in English.` });
    };

    const goLive = () => {
      const saved = cached(code);
      if (saved) {
        ready(saved, "Translated live by Meta’s Muse Spark Open · not yet reviewed");
        return;
      }
      reveal();
      setStatus({ tone: "work", text: `Translating into ${info.name} with Meta’s Muse Spark Open…` });
      live(code, (done, total) =>
        setStatus({ tone: "work", text: `Translating into ${info.name} with Meta’s Muse Spark Open… ${done} of ${total}` }),
      )
        .then((dict) => ready(dict, "Translated live by Meta’s Muse Spark Open · not yet reviewed"))
        .catch(fail);
    };

    if (!BUNDLED.has(code)) {
      goLive();
      return;
    }
    // A bundled file that is missing (not generated yet) falls back to the live path rather than to English.
    fetch(`/i18n/${code}.json`)
      .then((r) => (r.ok ? r.json() : Promise.reject()))
      .then((data) => ready(data.strings, data.edited ? "Translated by Meta’s Muse Spark Open, with edits by the MaPa team" : "Translated by Meta’s Muse Spark Open"))
      .catch(goLive);
  }, []);

  const choose = (code: string) => {
    try {
      localStorage.setItem("mapa-lang", code);
    } catch {}
    const url = new URL(window.location.href);
    url.searchParams.delete("lang");
    window.location.replace(url.toString());
  };

  const others = config.live.map((code) => [code, LANGS[code]] as const);

  // The docs and the demo page are in English only; their text is not in the translation catalog.
  if (/^\/(docs|try)(\/|$)/.test(pathname ?? "")) return null;

  return (
    <div translate="no" className="fixed right-3 bottom-3 z-[120] flex max-w-[min(92vw,22rem)] flex-col items-end gap-1.5 font-serif">
      {status && (
        <p
          role="status"
          className={`rounded-xl border px-3 py-1.5 text-[13px] leading-snug shadow-sm ${
            status.tone === "error" ? "border-terracotta/40 bg-surface text-terracotta-ink" : "border-line-strong/60 bg-surface/95 text-ink-muted"
          }`}
        >
          {status.tone === "work" && <span aria-hidden className="mr-1.5 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-terracotta align-middle" />}
          {status.text}
        </p>
      )}
      <label className="flex h-11 items-center gap-2 rounded-full border border-line-strong/70 bg-surface/95 pr-2 pl-3 text-[15px] text-ink shadow-sm backdrop-blur-md">
        <svg aria-hidden viewBox="0 0 20 20" className="h-4 w-4 text-ink-muted" fill="none" stroke="currentColor" strokeWidth="1.4">
          <circle cx="10" cy="10" r="7.5" />
          <path d="M2.5 10h15M10 2.5c2.2 2.2 2.2 12.8 0 15M10 2.5c-2.2 2.2-2.2 12.8 0 15" />
        </svg>
        <span className="sr-only">Language</span>
        <select value={lang} onChange={(e) => choose(e.target.value)} className="h-full cursor-pointer bg-transparent pr-1 outline-none">
          <option value="en">English</option>
          <optgroup label="Ready now">
            {config.bundled.map((code) => (
              <option key={code} value={code}>
                {LANGS[code].native} · {LANGS[code].name}
              </option>
            ))}
          </optgroup>
          <optgroup label="Translated on first visit">
            {others.map(([code, l]) => (
              <option key={code} value={code}>
                {l.native} · {l.name}
              </option>
            ))}
          </optgroup>
        </select>
      </label>
    </div>
  );
}

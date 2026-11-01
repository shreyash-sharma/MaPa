// Records every English string the site shows, including text the animations write later, into public/i18n/en.json.
// That catalog is what gets translated, at build time for the bundled languages and live for the rest.
//
//   npm run dev            (in another terminal)
//   node scripts/harvest-catalog.mjs [http://localhost:3000]
//
// Needs Chrome or Edge; set CHROME_PATH if it is not in the usual place. Takes about four minutes, because it waits
// for the phone story in "The model" to play through once at desktop width and once at phone width.

import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const URL_BASE = process.argv[2] ?? "http://localhost:3000";
const PORT = 9344;
const STORY_MS = 90_000;

const BROWSERS = [
  process.env.CHROME_PATH,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
].filter(Boolean);

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/** Same FNV-1a ids as src/i18n/dom.ts. */
function idOf(key) {
  let h = 0x811c9dc5;
  for (let i = 0; i < key.length; i++) {
    h ^= key.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36);
}

async function launch(width, height, mobile) {
  const exe = BROWSERS.find((p) => existsSync(p));
  if (!exe) throw new Error("No Chrome or Edge found; set CHROME_PATH");
  const profile = mkdtempSync(join(tmpdir(), "mapa-harvest-"));
  const proc = spawn(exe, ["--headless=new", "--disable-gpu", "--no-first-run", `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, "about:blank"], {
    stdio: "ignore",
  });
  let target;
  for (let i = 0; i < 60 && !target; i++) {
    await wait(250);
    try {
      target = (await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()).find((t) => t.type === "page");
    } catch {}
  }
  if (!target) throw new Error("browser did not start");
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener("open", r, { once: true }));
  let id = 0;
  const pending = new Map();
  ws.addEventListener("message", (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  });
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const n = ++id;
      pending.set(n, (msg) => (msg.error ? reject(new Error(`${method}: ${msg.error.message}`)) : resolve(msg.result)));
      ws.send(JSON.stringify({ id: n, method, params }));
    });
  await send("Page.enable");
  await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile });
  const run = async (expression) => {
    const res = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    if (res.exceptionDetails) throw new Error(res.exceptionDetails.exception?.description ?? res.exceptionDetails.text);
    return res.result.value;
  };
  return {
    send,
    run,
    close: () => {
      ws.close();
      proc.kill();
    },
  };
}

async function pass(width, height, mobile) {
  const b = await launch(width, height, mobile);
  try {
    await b.send("Page.navigate", { url: `${URL_BASE}/?i18n=harvest` });
    await wait(5000);
    // Walk the page so every reveal, chapter and scroll-driven text renders at least once.
    await b.run(`new Promise(async (done) => {
      document.documentElement.style.scrollBehavior = "auto";
      for (let y = 0; y < document.documentElement.scrollHeight; y += 260) { scrollTo(0, y); await new Promise((r) => setTimeout(r, 90)); }
      done();
    })`);
    // The "Learns carefully" answers only render after a tap.
    await b.run(`new Promise(async (done) => {
      const t = document.querySelector("#model-live > div:nth-of-type(3)");
      scrollTo(0, t.getBoundingClientRect().top + scrollY + 0.9 * (t.offsetHeight - innerHeight));
      await new Promise((r) => setTimeout(r, 1500));
      for (const b of document.querySelectorAll(".ml-panel-3 button")) { b.click(); await new Promise((r) => setTimeout(r, 400)); }
      done();
    })`);
    // Both form messages: a bad address, then a good one.
    await b.run(`new Promise(async (done) => {
      const input = document.querySelector("#waitlist-email");
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
      for (const value of ["not-an-email", "family@example.com"]) {
        set.call(input, value);
        input.dispatchEvent(new Event("input", { bubbles: true }));
        input.form.requestSubmit();
        await new Promise((r) => setTimeout(r, 600));
      }
      done();
    })`);
    // Let the phone story play through once; it only runs while on screen.
    await b.run(`document.querySelector(".pl-content").ownerSVGElement.scrollIntoView({ block: "center" })`);
    await wait(STORY_MS);
    return await b.run(`window.__mapaHarvest ? window.__mapaHarvest().strings : {}`);
  } finally {
    b.close();
    await wait(1000);
  }
}

const desktop = await pass(1440, 900, false);
console.log(`desktop: ${Object.keys(desktop).length} strings`);
const phone = await pass(390, 844, true);
console.log(`phone: ${Object.keys(phone).length} strings`);

const all = { ...desktop, ...phone };
const strings = Object.fromEntries(Object.entries(all).sort(([a], [b]) => a.localeCompare(b)));
const version = idOf(Object.values(strings).sort().join("\n"));
mkdirSync(join(ROOT, "public", "i18n"), { recursive: true });
writeFileSync(join(ROOT, "public", "i18n", "en.json"), JSON.stringify({ version, strings }, null, 1) + "\n", "utf8");
console.log(`wrote public/i18n/en.json: ${Object.keys(strings).length} strings, version ${version}`);

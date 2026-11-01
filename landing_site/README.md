# MaPa landing site

The public page for **MaPa**, built for the Meta AI Developer Hackathon. MaPa learns a parent's daily rhythm on their own
Android phone and gives the family a calm daily note, or an alert when a morning is much quieter than usual. Only a
conclusion ever leaves the parent's phone.

Live: https://shreyash-sharma--mapa-web-web.us-east.modal.run

Everything shown on the page (the dial, the phones, the notes, the numbers labelled "simulated") is illustrative. MaPa is
not a medical or emergency service.

## Stack

- Next.js 16 (App Router, static export), React 19, TypeScript, Tailwind CSS 4
- anime.js 4 for every animation: scroll-scrubbed timelines, SVG drawing and motion paths
- Instrument Serif, with Noto fonts for Indic, Urdu, Japanese and Korean script
- A small Python service on Modal for translations, calling Meta's **Muse Spark Open** on Baseten

## Run it

```bash
npm install
npm run dev            # http://localhost:3000
npm run lint
npm run build          # static export to out/
```

## Languages

The page can switch language at runtime, without a rebuild.

| Kind | Languages | How |
| --- | --- | --- |
| Source | English | The text in the components |
| Bundled | Hindi | Translated once by Muse Spark Open, saved in `public/i18n/hi.json` |
| Live | Tamil, French | Translated by Muse Spark Open the first time someone picks them, then stored on the server for everyone after |

The lists live in [`src/i18n/languages.json`](src/i18n/languages.json). Add a code to `live` to switch another language on.

How it works:

1. `scripts/harvest-catalog.mjs` walks the page in a headless browser, lets the animations play, and records every English
   string into `public/i18n/en.json` (288 strings). Numbers become placeholders (`{0}`) and inline styling becomes tags
   (`<0>…</0>`), so "Day 15" and "Day 16" share one translation.
2. In the browser, [`LanguageLayer`](src/components/LanguageLayer.tsx) and [`src/i18n/dom.ts`](src/i18n/dom.ts) find the
   same strings and write the translation into the existing text nodes, so React keeps ownership of the page. Text the
   animations write later is translated as it appears.
3. The language comes from `?lang=`, a saved choice, or the browser's languages. A picker sits bottom right.
4. [`server/`](server) serves the site and `GET /api/i18n/{lang}`. A finished language comes back at once; a new one
   starts a Muse Spark Open job and the page shows its progress. Every translation is checked (placeholders, tags, product
   names, length) before it is kept; a string that fails stays English.

Guards on the live path: only the listed languages, one job per language at a time, 4 new languages per visitor per hour,
and a monthly cap of 200,000 tokens (about $0.85 at Baseten's $4.25 per million output tokens).

### Commands

```bash
npm run i18n:harvest   # rebuild the English catalog (needs `npm run dev` running)
npm run i18n:bundle    # translate the bundled languages on Modal into public/i18n/
npm run i18n:local     # the translation API locally, with a stand-in model (no key, no cost)
npm run deploy         # build the site and deploy it with the API to Modal
```

## Keys

No key is in this repository. The Baseten key lives in a Modal secret:

```bash
modal secret create baseten BASETEN_API_KEY=<your key>
```

Without it the site still deploys: English and any stored translation are served, and new languages answer "not
available".

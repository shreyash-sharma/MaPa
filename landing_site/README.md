# MaPa landing site

The public page for **MaPa**, built for the Meta AI Developer Hackathon. MaPa learns a parent's daily rhythm on their own
Android phone and gives the family a calm daily note, or an alert when a morning is much quieter than usual. Only a
conclusion ever leaves the parent's phone.

Live homepage: https://shreyash-sharma--mapa-web-web.us-east.modal.run

The deployed site includes a technical docs route (`/docs`) and an Appetize demo route (`/try`). The docs page is live. The demo page is live, but its app panels currently say that the demo is not available because no Appetize embed URLs are configured.

The homepage animations and example screens are illustrative. The technical docs contain measured results from the v2
Python policy simulation on 40 model-derived timelines, with the data limits and hard-negative results shown beside them.
Those results do not establish real-world event detection or emergency detection. MaPa is not a medical or emergency service.

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

## Docs and demo

- `/docs/` is the technical documentation, rendered at build time from [`content/docs/`](content/docs) (the approved
  text; edit it there). Charts and the architecture diagram live in `public/docs/`. Tables become labelled cards on
  phones. The page list and figure captions are in [`src/lib/docs.ts`](src/lib/docs.ts).
- `/try/` can stream the Android apps from Appetize after setup. Set `NEXT_PUBLIC_APPETIZE_PARENT_EMBED_URL` and
  `NEXT_PUBLIC_APPETIZE_FAMILY_EMBED_URL` to the public embed links from each build's Share/Embed controls before
  `npm run build`. Without them each panel says the demo is being prepared. Never put an Appetize API token or any
  Firebase credential in a `NEXT_PUBLIC_*` variable.

## Languages

The page can switch language at runtime, without a rebuild.

| Kind | Languages | How |
| --- | --- | --- |
| Source | English | The text in the components |
| Bundled | Hindi | Translated once by Muse Spark Open, saved in `public/i18n/hi.json` |
| Live | Marathi, Tamil, French | Translated by Muse Spark Open the first time someone picks them, then stored on the server for everyone after |

The family invite carries the parent's language choice into the Android app. Muse Spark Open, served through Baseten, generates live parent-interface translations, which are cached on the parent's phone. MaPa's translation layer is built to extend across Muse Spark's multilingual reach.

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

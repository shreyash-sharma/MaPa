import { readFileSync } from "node:fs";
import { join } from "node:path";
import { Marked, type Tokens } from "marked";

/* The technical docs, rendered at build time from content/docs/*.md (the approved text, edited there, not here). */

export type DocMeta = { slug: string; file: string; nav: string };

export const DOCS: DocMeta[] = [
  { slug: "how-it-works", file: "01-how-it-works.md", nav: "How it works" },
  { slug: "architecture", file: "08-architecture.md", nav: "Architecture" },
  { slug: "model", file: "02-model.md", nav: "The model" },
  { slug: "data", file: "03-dataset.md", nav: "Data" },
  { slug: "results", file: "04-results.md", nav: "Results" },
  { slug: "privacy", file: "05-privacy.md", nav: "Privacy" },
  { slug: "technology", file: "06-meta.md", nav: "Technology" },
  { slug: "roadmap", file: "07-pilot-guide.md", nav: "Roadmap" },
];

/** Where a reference such as `04-results.md` in the text should point. */
const LINKS: Record<string, string> = {
  ...Object.fromEntries(DOCS.map((d) => [d.file, `/docs/${d.slug}/`])),
  "09-judge-demo.md": "/try/",
};

/** Captions sit under each figure; the alt text comes from the markdown itself. */
const FIGURES: Record<string, { caption: string; width: number; height: number }> = {
  "architecture.svg": {
    caption:
      "Activity features are scored on the phone owner’s device. Only a status and a short summary can be shared with a family member; stale input is shown as “Monitoring unavailable”.",
    width: 1000,
    height: 440,
  },
  "benchmark-v2.png": {
    caption:
      "v2, chosen after the first test. Left: false alerts per observed person-week, with 95% person-bootstrap intervals; MaPa sends about 6× fewer than a 4-hour timer. Right: labeled behavior-case windows with an alert, by severity, on 40 model-derived parent timelines of 120 days. Counts include 28 windows where phone-use and observation inputs did not change.",
    width: 2661,
    height: 1382,
  },
  "benchmark-v2-hard-negatives.png": {
    caption:
      "Alerts in expected-none cases, by scenario. Zero alerts on delayed mornings and life-tag days; daytime inactivity and a phone left at home are the two scenarios we are targeting next.",
    width: 2013,
    height: 959,
  },
};

export type Heading = { id: string; text: string };
export type Doc = DocMeta & { title: string; summary: string; html: string; headings: Heading[] };

const slugify = (s: string) =>
  s
    .toLowerCase()
    .replace(/<[^>]+>/g, "")
    .replace(/[^\p{L}\p{N}]+/gu, "-")
    .replace(/^-|-$/g, "");

const escapeAttr = (s: string) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
const plain = (s: string) => s.replace(/[*_`]/g, "").trim();
const ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', "#39": "'", apos: "'" };
/** Heading text goes into React as text, so marked's HTML entities must be decoded first. */
const decode = (s: string) => s.replace(/&(amp|lt|gt|quot|#39|apos);/g, (_, e: string) => ENTITIES[e]);

function render(markdown: string): { html: string; headings: Heading[] } {
  const headings: Heading[] = [];
  const marked = new Marked({
    gfm: true,
    renderer: {
      heading({ tokens, depth }: Tokens.Heading) {
        const text = this.parser.parseInline(tokens);
        const id = slugify(text);
        if (depth === 2) headings.push({ id, text: decode(plain(text.replace(/<[^>]+>/g, ""))) });
        return `<h${depth} id="${id}"><a href="#${id}" class="doc-anchor" aria-hidden="true" tabindex="-1">#</a>${text}</h${depth}>`;
      },
      codespan({ text }: Tokens.Codespan) {
        const target = LINKS[text];
        if (!target) return `<code>${text}</code>`;
        const label = DOCS.find((d) => d.file === text)?.nav ?? (text === "09-judge-demo.md" ? "the browser demo" : text);
        return `<a href="${target}">${label}</a>`;
      },
      link({ href, tokens }: Tokens.Link) {
        const external = /^https?:/.test(href);
        const target = LINKS[href] ?? href;
        return `<a href="${escapeAttr(target)}"${external ? ' target="_blank" rel="noreferrer"' : ""}>${this.parser.parseInline(tokens)}</a>`;
      },
      image({ href, text }: Tokens.Image) {
        const figure = FIGURES[href];
        const size = figure ? ` width="${figure.width}" height="${figure.height}"` : "";
        const src = `/docs/${escapeAttr(href)}`;
        // Wide figures get small on a phone; the full-size file is one tap away.
        const full = `<a href="${src}" target="_blank" rel="noreferrer" class="doc-full">Open full size ↗</a>`;
        return (
          `<figure class="doc-figure"><img src="${src}" alt="${escapeAttr(text)}"${size} loading="lazy" decoding="async" />` +
          `<figcaption>${figure ? `${figure.caption} ` : ""}${full}</figcaption>` +
          `</figure>`
        );
      },
      paragraph({ tokens }: Tokens.Paragraph) {
        const inner = this.parser.parseInline(tokens);
        // A figure is a block; it must not sit inside a <p>.
        return inner.startsWith("<figure") ? inner : `<p>${inner}</p>`;
      },
      table(token: Tokens.Table) {
        const labels = token.header.map((cell) => escapeAttr(plain(cell.text)));
        const align = (a: Tokens.TableCell["align"]) => (a ? ` style="text-align:${a}"` : "");
        const head = token.header.map((cell) => `<th scope="col"${align(cell.align)}>${this.parser.parseInline(cell.tokens)}</th>`).join("");
        const rows = token.rows
          .map(
            (row) =>
              `<tr>${row
                .map((cell, i) => `<td data-label="${labels[i]}"${align(cell.align)}>${this.parser.parseInline(cell.tokens)}</td>`)
                .join("")}</tr>`,
          )
          .join("");
        return `<div class="doc-table"><table><thead><tr>${head}</tr></thead><tbody>${rows}</tbody></table></div>`;
      },
    },
  });
  return { html: marked.parse(markdown, { async: false }), headings };
}

const ROOT = join(process.cwd(), "content", "docs");

export function getDoc(slug: string): Doc | null {
  const meta = DOCS.find((d) => d.slug === slug);
  if (!meta) return null;
  const source = readFileSync(join(ROOT, meta.file), "utf8");
  const [, title = meta.nav] = source.match(/^#\s+(.+)$/m) ?? [];
  const body = source.replace(/^#\s+.+$/m, "").trim();
  const firstParagraph = body.split(/\n\s*\n/).find((p) => p.trim() && !/^[#|!-]|^\d+\./.test(p.trim())) ?? "";
  const summary = plain(firstParagraph.replace(/\s+/g, " "));
  return { ...meta, title, summary, ...render(body) };
}

export function neighbours(slug: string): { prev?: DocMeta; next?: DocMeta } {
  const i = DOCS.findIndex((d) => d.slug === slug);
  return { prev: DOCS[i - 1], next: DOCS[i + 1] };
}

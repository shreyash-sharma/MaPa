/* Finds the page's text, keys it the same way at build time and in the browser, and writes translations in place.
   A unit is the text of one block element, with inline children kept as numbered tags (<0>…</0>) and numbers as
   placeholders ({0}), so "Day 15 · confident" and "Day 16 · confident" share one translation. Translations only
   change existing text nodes, never replace them, so React keeps ownership of every node it rendered. */

const INLINE = new Set(["SPAN", "EM", "STRONG", "B", "I", "A", "SMALL", "SUP", "SUB", "BR", "ABBR", "TIME", "MARK", "U", "S", "Q", "TSPAN"]);
const SKIP = "script,style,noscript,textarea,code,pre,select,option,[translate='no'],[data-i18n-skip]";
export const ATTRS = ["aria-label", "title", "placeholder", "alt"];
const NUM = /\d+(?:[.,:]\d+)*/g;
const LETTER = /\p{L}/u;
const MARKUP = /<\/?\d+\/?>|\{\d+\}/g;

export type Dict = Record<string, string>;

type Slot = { node: Text; path: string };
export type Unit = { root: Element; key: string; nums: string[]; slots: Slot[] };

/** FNV-1a, base 36: short, stable ids shared by the catalog, the bundles and the browser. */
export function idOf(key: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < key.length; i++) {
    h ^= key.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36);
}

const isInline = (el: Element) => INLINE.has(el.tagName.toUpperCase());

function excluded(el: Element): boolean {
  if (el.closest(SKIP)) return true;
  const tagged = el.closest("[lang]");
  return !!tagged && tagged !== document.documentElement && !/^en\b/i.test(tagged.getAttribute("lang") ?? "");
}

/** The block element whose text this node belongs to. */
export function rootOf(node: Node): Element | null {
  let el = node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element);
  while (el && isInline(el) && el.parentElement) el = el.parentElement;
  return el && !excluded(el) ? el : null;
}

const keyText = (s: string, nums: string[]) =>
  s.replace(NUM, (m) => {
    nums.push(m);
    return `{${nums.length - 1}}`;
  });

/** The catalog key for a plain string: whitespace collapsed, numbers as placeholders. */
export const keyOf = (s: string, nums: string[]) => keyText(s.replace(/\s+/g, " ").trim(), nums);

export function unitOf(root: Element): Unit | null {
  const nums: string[] = [];
  const slots: Slot[] = [];
  let out = "";
  let tags = 0;
  const walk = (el: Element, path: string) => {
    for (const child of Array.from(el.childNodes)) {
      if (child.nodeType === Node.TEXT_NODE) {
        const text = (child as Text).data;
        if (!text.trim()) {
          out += " ";
          continue;
        }
        out += keyText(text, nums);
        slots.push({ node: child as Text, path });
      } else if (child.nodeType === Node.ELEMENT_NODE) {
        const c = child as Element;
        if (!isInline(c)) continue;
        const n = tags++;
        if (c.tagName.toUpperCase() === "BR" || c.matches(SKIP)) {
          out += `<${n}/>`;
          continue;
        }
        out += `<${n}>`;
        walk(c, `${path}/${n}`);
        out += `</${n}>`;
      }
    }
  };
  walk(root, "");
  const key = out.replace(/\s+/g, " ").trim();
  if (!slots.length || !LETTER.test(key.replace(MARKUP, "")) || key === "MaPa") return null;
  return { root, key, nums, slots };
}

/** Every translatable unit under `scope`. */
export function units(scope: ParentNode = document.body): Unit[] {
  const roots = new Set<Element>();
  const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (!(n as Text).data.trim()) continue;
    const root = rootOf(n);
    if (root) roots.add(root);
  }
  const found: Unit[] = [];
  roots.forEach((root) => {
    const u = unitOf(root);
    if (u) found.push(u);
  });
  return found;
}

/** Attribute strings worth translating (labels, titles, placeholders, alt text). */
export function attrKeys(scope: ParentNode = document.body): { el: Element; attr: string; key: string; nums: string[] }[] {
  const found: { el: Element; attr: string; key: string; nums: string[] }[] = [];
  scope.querySelectorAll(ATTRS.map((a) => `[${a}]`).join(",")).forEach((el) => {
    if (excluded(el)) return;
    for (const attr of ATTRS) {
      const value = el.getAttribute(attr);
      if (!value || !LETTER.test(value)) continue;
      const nums: string[] = [];
      found.push({ el, attr, key: keyText(value.replace(/\s+/g, " ").trim(), nums), nums });
    }
  });
  return found;
}

const fill = (s: string, nums: string[]) => s.replace(/\{(\d+)\}/g, (_, i) => nums[Number(i)] ?? "");

/** What the browser last wrote, so its own writes are not taken for new English text. */
export const written = new WeakMap<Node, string>();

/** Writes a translation into the unit's existing text nodes. False if the tags do not line up. */
export function applyUnit(u: Unit, translation: string, rtl: boolean): boolean {
  const pieces: { path: string; text: string }[] = [];
  const stack: string[] = [];
  let last = 0;
  const text = fill(translation, u.nums);
  for (const m of text.matchAll(/<(\/?)(\d+)(\/?)>/g)) {
    const before = text.slice(last, m.index);
    if (before.trim()) pieces.push({ path: stack.length ? `/${stack.join("/")}` : "", text: before });
    last = (m.index ?? 0) + m[0].length;
    if (m[1]) stack.pop();
    else if (!m[3]) stack.push(m[2]);
  }
  const tail = text.slice(last);
  if (tail.trim()) pieces.push({ path: stack.length ? `/${stack.join("/")}` : "", text: tail });

  const writes: [Text, string][] = [];
  for (const path of new Set(u.slots.map((s) => s.path))) {
    const nodes = u.slots.filter((s) => s.path === path).map((s) => s.node);
    const parts = pieces.filter((p) => p.path === path).map((p) => p.text);
    if (!parts.length) return false;
    const values = parts.length === nodes.length ? parts : [parts.join(""), ...nodes.slice(1).map(() => "")];
    nodes.forEach((node, i) => writes.push([node, values[i].replace(/\s+/g, " ")]));
  }
  for (const [node, value] of writes) {
    if (node.data !== value) node.data = value;
    written.set(node, value);
  }
  if (rtl) u.root.setAttribute("dir", "auto");
  return true;
}

/** Translated attribute values, per element, for the same reason as `written`. */
export const writtenAttrs = new WeakMap<Element, Record<string, string>>();

export function applyAttr(el: Element, attr: string, translation: string, nums: string[]) {
  const value = fill(translation, nums);
  if (el.getAttribute(attr) !== value) el.setAttribute(attr, value);
  writtenAttrs.set(el, { ...writtenAttrs.get(el), [attr]: value });
}

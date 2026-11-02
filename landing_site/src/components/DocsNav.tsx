"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

type Item = { slug: string; nav: string };

/** The docs contents: a sticky column on wide screens, a scrolling row of pills on phones. */
export function DocsNav({ docs }: { docs: Item[] }) {
  const path = usePathname();
  const active = (slug: string) => path?.replace(/\/$/, "") === `/docs/${slug}`;
  const home = path?.replace(/\/$/, "") === "/docs";

  return (
    <nav aria-label="Documentation" className="lg:sticky lg:top-24 lg:self-start">
      <p className="mb-3 hidden font-mono text-[13px] tracking-wide text-ink-faint lg:block">Contents</p>
      <ol className="-mx-5 flex gap-2 overflow-x-auto px-5 pb-1 [scrollbar-width:none] lg:mx-0 lg:flex-col lg:gap-0.5 lg:overflow-visible lg:px-0">
        <li className="shrink-0">
          <Link
            href="/docs/"
            aria-current={home ? "page" : undefined}
            className={`flex min-h-11 items-center rounded-full border px-4 text-[15px] transition-colors lg:rounded-lg lg:border-0 lg:px-3 ${
              home ? "border-terracotta bg-terracotta-soft text-ink" : "border-line-strong/70 text-ink-muted hover:text-ink"
            }`}
          >
            Overview
          </Link>
        </li>
        {docs.map((d, i) => (
          <li key={d.slug} className="shrink-0">
            <Link
              href={`/docs/${d.slug}/`}
              aria-current={active(d.slug) ? "page" : undefined}
              className={`flex min-h-11 items-center gap-2.5 rounded-full border px-4 text-[15px] transition-colors lg:rounded-lg lg:border-0 lg:px-3 ${
                active(d.slug) ? "border-terracotta bg-terracotta-soft text-ink" : "border-line-strong/70 text-ink-muted hover:text-ink"
              }`}
            >
              <span className="hidden tabular text-[13px] text-ink-faint lg:inline">{String(i + 1).padStart(2, "0")}</span>
              {d.nav}
            </Link>
          </li>
        ))}
        <li className="shrink-0 lg:mt-4 lg:border-t lg:border-line-strong/50 lg:pt-4">
          <Link
            href="/try/"
            className="flex min-h-11 items-center rounded-full bg-ink px-4 text-[15px] text-surface transition-colors hover:bg-terracotta-ink lg:rounded-lg lg:px-3"
          >
            Try the demo →
          </Link>
        </li>
      </ol>
    </nav>
  );
}

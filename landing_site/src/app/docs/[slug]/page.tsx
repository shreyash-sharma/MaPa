import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { DOCS, getDoc, neighbours } from "@/lib/docs";

export const dynamicParams = false;

export function generateStaticParams() {
  return DOCS.map((d) => ({ slug: d.slug }));
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const doc = getDoc((await params).slug);
  return doc ? { title: `${doc.title} · MaPa docs`, description: doc.summary.slice(0, 160) } : {};
}

export default async function DocPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const doc = getDoc(slug);
  if (!doc) notFound();
  const { prev, next } = neighbours(slug);
  const number = DOCS.findIndex((d) => d.slug === slug) + 1;

  return (
    <div className="grid grid-cols-1 gap-10 xl:grid-cols-[minmax(0,1fr)_13rem]">
      <article className="min-w-0">
        <p className="mb-3 font-mono text-xs tracking-wide text-terracotta-ink">
          Docs · {String(number).padStart(2, "0")} · {doc.nav}
        </p>
        <h1 className="font-display text-5xl leading-[1.04] text-ink sm:text-6xl">{doc.title}</h1>
        <div className="doc-prose mt-8" dangerouslySetInnerHTML={{ __html: doc.html }} />

        <nav aria-label="More documentation" className="mt-16 grid grid-cols-1 gap-3 border-t border-line-strong/50 pt-8 sm:grid-cols-2">
          {prev ? (
            <Link href={`/docs/${prev.slug}/`} className="group rounded-2xl border border-line-strong/60 p-5 transition-colors hover:border-terracotta/60">
              <span className="text-[13px] text-ink-faint">← Previous</span>
              <span className="mt-1 block font-display text-2xl text-ink">{prev.nav}</span>
            </Link>
          ) : (
            <span />
          )}
          {next && (
            <Link href={`/docs/${next.slug}/`} className="group rounded-2xl border border-line-strong/60 p-5 text-right transition-colors hover:border-terracotta/60">
              <span className="text-[13px] text-ink-faint">Next →</span>
              <span className="mt-1 block font-display text-2xl text-ink">{next.nav}</span>
            </Link>
          )}
        </nav>
      </article>

      {doc.headings.length > 1 && (
        <aside aria-label="On this page" className="hidden xl:block">
          <div className="sticky top-24 space-y-2 border-l border-line-strong/60 pl-4">
            <p className="font-mono text-[13px] tracking-wide text-ink-faint">On this page</p>
            {doc.headings.map((h) => (
              <a key={h.id} href={`#${h.id}`} className="block text-[15px] leading-snug text-ink-muted transition-colors hover:text-terracotta-ink">
                {h.text}
              </a>
            ))}
          </div>
        </aside>
      )}
    </div>
  );
}

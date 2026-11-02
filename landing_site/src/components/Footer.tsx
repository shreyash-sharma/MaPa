import Image from "next/image";
import Link from "next/link";
import { site } from "@/lib/site";

const LINKS = [
  { href: "/#model-live", label: "The model, live" },
  { href: "/#learn", label: "How it learns" },
  { href: "/#model", label: "The model" },
  { href: "/#privacy", label: "Privacy" },
  { href: "/#faq", label: "FAQ" },
  { href: "/docs/", label: "Documentation" },
  { href: "/try/", label: "Try the demo" },
];

export function Footer() {
  return (
    <footer className="relative z-[90] border-t border-nocturne-line-strong bg-nocturne py-16 text-xs text-nocturne-muted">
      <div className="mx-auto max-w-7xl space-y-12 px-5 lg:px-16">
        <div className="grid grid-cols-1 items-start gap-12 md:grid-cols-12">
          <div className="space-y-4 md:col-span-6">
            <div className="flex items-center gap-3">
              <Image src="/mapa-emblem-hd.png" alt="" width={24} height={28} />
              <span className="font-display text-2xl text-nocturne-ink">MaPa</span>
            </div>
            <p className="max-w-sm font-serif text-sm leading-relaxed">
              A calm daily picture of a parent’s day, for families who live apart. Learned on the parent’s own phone.
            </p>
          </div>
          <nav aria-label="Footer" className="space-y-3 font-mono md:col-span-3">
            <p className="text-[14px] text-nocturne-ink">Contents</p>
            <ul>
              {LINKS.map((l) => (
                <li key={l.href}>
                  <Link href={l.href} className="inline-flex min-h-11 items-center transition-colors hover:text-nocturne-ink">
                    {l.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
          <div className="space-y-3 md:col-span-3">
            <p className="font-mono text-[14px] text-nocturne-ink">Colophon</p>
            <p className="leading-relaxed">Set in Instrument Serif. Motion by anime.js.</p>
            {site.contactEmail && (
              <a href={`mailto:${site.contactEmail}`} className="inline-block font-mono transition-colors hover:text-nocturne-ink">
                {site.contactEmail}
              </a>
            )}
          </div>
        </div>
        <div className="night-hairline-t flex flex-col items-center justify-between gap-4 pt-8 font-mono text-[14px] text-nocturne-faint sm:flex-row">
          <span>© 2026 MaPa</span>
          <span>Not a medical or emergency service.</span>
        </div>
      </div>
    </footer>
  );
}

import type { ReactNode } from "react";
import { DocsNav } from "@/components/DocsNav";
import { Footer } from "@/components/Footer";
import { Masthead } from "@/components/Masthead";
import { DOCS } from "@/lib/docs";

export default function DocsLayout({ children }: { children: ReactNode }) {
  return (
    <>
      <Masthead />
      <main className="paper-canvas relative w-full">
        <div className="hairline-b flex items-center justify-between gap-4 px-5 py-2.5 font-mono text-[13px] tracking-wide text-ink-faint lg:px-16">
          <span className="flex items-center gap-2">
            <span className="h-1.5 w-1.5 rounded-full bg-terracotta" />
            Folio IX · Technical documentation
          </span>
          <span className="hidden md:block">The engine, the data and the numbers</span>
          <span className="text-terracotta-ink">v2 · on-device AI</span>
        </div>
        <div className="mx-auto grid max-w-7xl grid-cols-1 gap-8 px-5 pt-8 pb-24 lg:grid-cols-[15rem_minmax(0,1fr)] lg:gap-14 lg:px-16 lg:pt-14">
          <DocsNav docs={DOCS.map(({ slug, nav }) => ({ slug, nav }))} />
          <div className="min-w-0">{children}</div>
        </div>
      </main>
      <Footer />
    </>
  );
}

"use client";

import { animate, createScope, stagger } from "animejs";
import { useEffect, useRef, type ReactNode } from "react";
import { playOnEnter, prefersReducedMotion, showInstantly } from "@/lib/motion";

type RevealProps = {
  children: ReactNode;
  className?: string;
  /** Delay between each `[data-reveal]` child, in ms. */
  gap?: number;
  /** Starting vertical offset, in px. */
  rise?: number;
};

/** Fades and lifts every `[data-reveal]` descendant into place the first time it scrolls into view. */
export function Reveal({ children, className, gap = 90, rise = 28 }: RevealProps) {
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = root.current;
    if (!el) return;
    const items = el.querySelectorAll<HTMLElement>("[data-reveal]");
    if (prefersReducedMotion()) {
      showInstantly(items);
      return;
    }
    const scope = createScope({ root }).add(() => {
      const anim = animate(items, {
        opacity: [0, 1],
        y: [rise, 0],
        filter: ["blur(6px)", "blur(0px)"],
        duration: 1100,
        delay: stagger(gap),
        ease: "outExpo",
        autoplay: false,
      });
      playOnEnter(anim, el);
    });
    return () => scope.revert();
  }, [gap, rise]);

  return (
    <div ref={root} className={className}>
      {children}
    </div>
  );
}

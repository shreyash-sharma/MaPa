import { onScroll, type JSAnimation, type TargetsParam, type Timeline } from "animejs";

export function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Makes elements hidden by the `.js [data-reveal]` / `[data-hero]` rule visible without animating. */
export function showInstantly(targets: Iterable<HTMLElement> | ArrayLike<HTMLElement>) {
  Array.from(targets as ArrayLike<HTMLElement>).forEach((el) => {
    el.style.opacity = "1";
  });
}

/**
 * Plays `anim` (created with `autoplay: false`) the first time `trigger` scrolls into view, then lets it finish.
 * Linking via `autoplay: onScroll()` would pause it when scrolling back out, leaving content half-faded.
 * Call inside a `createScope().add()` so the observer is reverted with the scope.
 */
export function playOnEnter(anim: JSAnimation | Timeline, trigger: TargetsParam, enter = "bottom-=10% top") {
  return onScroll({ target: trigger, enter, repeat: false, onEnter: () => anim.play() });
}

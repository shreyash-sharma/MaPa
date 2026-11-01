import { idOf, keyOf, type Dict } from "@/i18n/dom";

/* The active translation, for components that animate text themselves (the phone types its note one letter at
   a time, which the page translator would only ever see half-written). English when no translation is loaded. */

let active: Dict | null = null;

export function setDictionary(dict: Dict | null) {
  active = dict;
}

export function translateText(english: string): string {
  if (!active) return english;
  const nums: string[] = [];
  const t = active[idOf(keyOf(english, nums))];
  return t ? t.replace(/\{(\d+)\}/g, (_, i) => nums[Number(i)] ?? "") : english;
}

"use client";

import { useSearchParams } from "next/navigation";

export default function InviteContent() {
  const params = useSearchParams();
  const rawCode = params.get("code") ?? "";
  const normalized = rawCode.replace(/[^a-z0-9]/gi, "").toUpperCase().slice(0, 6);
  const displayCode = normalized.length === 6 ? `${normalized.slice(0, 3)}-${normalized.slice(3)}` : "";

  return (
    <div className="mt-7 rounded-2xl border border-line bg-[#f8f5f0] p-5 sm:p-6">
      <p className="text-sm text-ink-muted">Your invitation code</p>
      {displayCode ? (
        <p className="mt-2 font-mono text-3xl font-semibold tracking-[0.18em] text-ink" aria-label={`Invitation code ${displayCode}`}>
          {displayCode}
        </p>
      ) : (
        <p className="mt-2 font-medium text-ink">Ask your family member to send you the invitation code on WhatsApp.</p>
      )}
      <p className="mt-3 text-sm leading-relaxed text-ink-muted">
        In MaPa, choose “I have a code” and enter these six characters. You will see the request before you accept it.
      </p>
    </div>
  );
}

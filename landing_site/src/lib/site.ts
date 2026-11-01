// Both values are baked in at build time. Leave them unset until they are real:
// the waitlist must never say an email was saved when it was not.
export const site = {
  /** URL that accepts a JSON POST `{"email": "..."}` (e.g. Formspree or a Firebase function). */
  waitlistEndpoint: process.env.NEXT_PUBLIC_WAITLIST_ENDPOINT ?? "",
  /** Shown in the footer and used as a mailto fallback for the waitlist. */
  contactEmail: process.env.NEXT_PUBLIC_CONTACT_EMAIL ?? "",
};

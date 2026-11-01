/**
 * MaPa backend. Mirrors core/src/main/kotlin/app/mapa/core/wire (Paths.kt, StatusWire.kt).
 *
 * - onEventCreated: pushes alert events (unusual, low battery) to every family member's phones.
 * - staleSweep: every 15 minutes, finds parents whose phone stopped syncing and tells the family
 *   once per silence. Never an all-clear: this only ever says that updates stopped.
 * - retention: deletes events and day bins older than 30 days.
 * - chat: the family app's only route to Meta models. It holds the API keys (Secret Manager), so no
 *   key ships in an APK. Family accounts only: the parent's phone (anonymous sign-in) is refused.
 *   Prompts are never logged.
 *
 * Runs free on the Emulator Suite (project demo-mapa). Deploying to a real project needs the
 * Blaze plan, which can cost money.
 */
import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { logger } from "firebase-functions/v2";

initializeApp();
const db = getFirestore();

const REGION = "asia-south1";
const STALE_AFTER_MIN = 120;
const RETENTION_DAYS = 30;

// Set with: firebase functions:secrets:set MUSE_SPARK_KEY (and BASETEN_KEY). Never in git.
const MUSE_SPARK_KEY = defineSecret("MUSE_SPARK_KEY");
const BASETEN_KEY = defineSecret("BASETEN_KEY");

// Mirrors CloudProviders in core/src/main/kotlin/app/mapa/core/cloud/ChatClient.kt.
const PROVIDERS: Record<string, { url: string; model: string; key: typeof MUSE_SPARK_KEY }> = {
  "muse-spark-1.3": { url: "https://api.meta.ai/v1/chat/completions", model: "muse-spark-1.3", key: MUSE_SPARK_KEY },
  "muse-spark-open-1026": {
    url: "https://inference.baseten.co/v1/chat/completions",
    model: "meta-models/muse-spark-open-1026",
    key: BASETEN_KEY,
  },
};
const MAX_PROMPT_CHARS = 6000;
const MAX_SCHEMA_CHARS = 4000;
const MAX_TOKENS = 400;
const DAILY_CALLS = 200;

type Lang = "en" | "hi";

const titles: Record<string, Record<Lang, string>> = {
  unusual: { en: "Quieter than usual", hi: "सामान्य से ज़्यादा शांत" },
  lowBattery: { en: "Phone battery low", hi: "फ़ोन की बैटरी कम" },
  stale: { en: "Updates stopped", hi: "अपडेट रुक गए" },
};

function lang(profile: FirebaseFirestore.DocumentData | undefined): Lang {
  return profile?.lang === "hi" ? "hi" : "en";
}

async function pushToFamily(pid: string, eventId: string, kind: string, body: string): Promise<void> {
  const parent = (await db.doc(`parents/${pid}`).get()).data();
  const l = lang(parent);
  const title = `${parent?.name ?? "MaPa"}: ${titles[kind]?.[l] ?? "MaPa"}`;
  const members = await db.collection(`parents/${pid}/members`).get();
  const tokens: { uid: string; token: string }[] = [];
  for (const m of members.docs) {
    const t = await db.collection(`users/${m.id}/tokens`).get();
    t.docs.forEach((d) => tokens.push({ uid: m.id, token: d.id }));
  }
  if (tokens.length === 0) return;
  // Data-only message so the app shows it once, de-duplicated against the live listener.
  const res = await getMessaging().sendEachForMulticast({
    tokens: tokens.map((t) => t.token),
    data: { eventId, parentId: pid, kind, title, body },
    android: { priority: "high" },
  });
  // Drop tokens the device no longer has.
  await Promise.all(
    res.responses.map((r, i) => {
      const code = r.error?.code ?? "";
      if (code.includes("registration-token-not-registered") || code.includes("invalid-argument")) {
        return db.doc(`users/${tokens[i].uid}/tokens/${tokens[i].token}`).delete();
      }
      return null;
    }),
  );
}

export const onEventCreated = onDocumentCreated(
  { document: "parents/{pid}/events/{eventId}", region: REGION },
  async (e) => {
    const data = e.data?.data();
    const alert = data?.alert as string | undefined;
    if (!alert) return;
    await pushToFamily(e.params.pid, e.params.eventId, alert, (data?.text as string) ?? "");
  },
);

function staleText(name: string, l: Lang, minutes: number): string {
  const h = Math.floor(minutes / 60);
  const span = l === "hi" ? `${h} घंटे` : `${h} hour${h === 1 ? "" : "s"}`;
  return l === "hi"
    ? `${name} के फ़ोन से ${span} से कोई अपडेट नहीं आया है। फ़ोन बंद हो सकता है या इंटरनेट नहीं है। हम नहीं बता सकते कि अभी क्या हो रहा है; चाहें तो फ़ोन कर लें।`
    : `No updates from ${name}'s phone for ${span}. The phone may be off or offline. We can't tell what's happening right now; you might want to call.`;
}

export const staleSweep = onSchedule({ schedule: "every 15 minutes", region: REGION }, async () => {
  const cutoff = Timestamp.fromMillis(Date.now() - STALE_AFTER_MIN * 60_000);
  const stale = await db.collectionGroup("status").where("syncedAt", "<", cutoff).get();
  for (const doc of stale.docs) {
    const pid = doc.ref.parent.parent?.id;
    if (!pid) continue;
    const s = doc.data();
    const syncedAt = (s.syncedAt as Timestamp).toMillis();
    if (s.staleAlertedFor === syncedAt) continue; // already told the family about this silence
    if (s.state === "PAUSED_BY_PARENT") continue;
    const profile = (await db.doc(`parents/${pid}`).get()).data();
    if (!profile || profile.paused) continue;
    const l = lang(profile);
    const minutes = Math.round((Date.now() - syncedAt) / 60_000);
    const text = staleText(profile.name ?? "", l, minutes);
    const ref = db.collection(`parents/${pid}/events`).doc();
    await db.runTransaction(async (tx) => {
      const fresh = await tx.get(doc.ref);
      if (fresh.data()?.staleAlertedFor === syncedAt) return;
      tx.update(doc.ref, { staleAlertedFor: syncedAt });
      tx.set(ref, {
        at: Date.now(),
        t: s.t ?? 0,
        from: s.state ?? null,
        to: "MONITORING_UNAVAILABLE",
        reason: { code: "nofresh", lastSyncT: s.t ?? null, lastBattery: s.battery ?? null, lastCharging: s.charging ?? null },
        text,
        alert: "stale",
        by: "server",
        createdAt: Timestamp.now(),
      });
    });
    logger.info("stale alert", { pid, minutes });
  }
});

export const chat = onCall(
  { region: REGION, secrets: [MUSE_SPARK_KEY, BASETEN_KEY], timeoutSeconds: 30 },
  async (req) => {
    const auth = req.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Sign in first.");
    if (auth.token.firebase?.sign_in_provider === "anonymous") {
      throw new HttpsError("permission-denied", "Only family accounts use AI.");
    }
    const { provider, prompt, schema, maxTokens } = (req.data ?? {}) as Record<string, unknown>;
    const p = typeof provider === "string" ? PROVIDERS[provider] : undefined;
    if (!p) throw new HttpsError("invalid-argument", "Unknown provider.");
    if (typeof prompt !== "string" || !prompt.trim() || prompt.length > MAX_PROMPT_CHARS) {
      throw new HttpsError("invalid-argument", "Bad prompt.");
    }
    let parsedSchema: unknown = undefined;
    if (schema !== undefined && schema !== null) {
      if (typeof schema !== "string" || schema.length > MAX_SCHEMA_CHARS) throw new HttpsError("invalid-argument", "Bad schema.");
      try {
        parsedSchema = JSON.parse(schema);
      } catch {
        throw new HttpsError("invalid-argument", "Bad schema.");
      }
    }
    const key = p.key.value();
    if (!key) throw new HttpsError("failed-precondition", "No key on the server.");

    const day = new Date().toISOString().slice(0, 10);
    const usage = db.doc(`aiUsage/${auth.uid}_${day}`);
    const calls = await db.runTransaction(async (tx) => {
      const n = (((await tx.get(usage)).data()?.n as number | undefined) ?? 0) + 1;
      tx.set(usage, { n, uid: auth.uid, day });
      return n;
    });
    if (calls > DAILY_CALLS) throw new HttpsError("resource-exhausted", "Daily limit reached.");

    const body: Record<string, unknown> = {
      model: p.model,
      messages: [{ role: "user", content: prompt }],
      max_tokens: Math.min(Math.max(Math.floor(Number(maxTokens) || 200), 1), MAX_TOKENS),
      temperature: 0.2,
      // The open model reasons first; without this the answer can come back empty.
      ...(p.url.includes("baseten") ? { reasoning_effort: "none" } : {}),
    };
    if (parsedSchema !== undefined) {
      body.response_format = { type: "json_schema", json_schema: { name: "answer", strict: true, schema: parsedSchema } };
    }
    const res = await fetch(p.url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(20_000),
    });
    if (!res.ok) {
      logger.warn("chat upstream failed", { provider, status: res.status });
      throw new HttpsError("unavailable", `HTTP ${res.status}`);
    }
    const json = (await res.json()) as { choices?: { message?: { content?: unknown } }[] };
    const content = json.choices?.[0]?.message?.content;
    if (typeof content !== "string" || !content.trim()) throw new HttpsError("unavailable", "Empty answer.");
    return { content };
  },
);

export const retention = onSchedule({ schedule: "every day 03:30", timeZone: "Asia/Kolkata", region: REGION }, async () => {
  const cutoffMs = Date.now() - RETENTION_DAYS * 86_400_000;
  const cutoffDate = new Date(cutoffMs).toISOString().slice(0, 10);
  const parents = await db.collection("parents").listDocuments();
  for (const p of parents) {
    const old = [
      ...(await p.collection("events").where("at", "<", cutoffMs).limit(400).get()).docs,
      ...(await p.collection("days").where("date", "<", cutoffDate).limit(400).get()).docs,
    ];
    if (old.length === 0) continue;
    const batch = db.batch();
    old.forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  const usage = await db.collection("aiUsage").where("day", "<", new Date(Date.now() - 2 * 86_400_000).toISOString().slice(0, 10)).limit(400).get();
  if (!usage.empty) {
    const batch = db.batch();
    usage.docs.forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  const invites = await db.collection("invites").where("expiresAt", "<", Timestamp.now()).limit(400).get();
  if (!invites.empty) {
    const batch = db.batch();
    invites.docs.forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
});

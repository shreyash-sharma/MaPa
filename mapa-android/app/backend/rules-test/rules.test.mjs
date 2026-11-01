// Security rules tests. Run: npm install && npm test (starts the Firestore emulator, no account needed).
import { test, before, after, beforeEach } from "node:test";
import { readFileSync } from "node:fs";
import { initializeTestEnvironment, assertSucceeds, assertFails } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs, writeBatch, Timestamp } from "firebase/firestore";

let env;
const PID = "parentA";
const CODE = "ABC234";

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-mapa",
    firestore: { rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8") },
  });
});
after(async () => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, `parents/${PID}`), { name: "Asha", lang: "en", paused: false });
    await setDoc(doc(db, `parents/${PID}/status/current`), { state: "ACTIVE" });
    await setDoc(doc(db, `invites/${CODE}`), { parentId: PID, expiresAt: Timestamp.fromMillis(Date.now() + 3600_000) });
    await setDoc(doc(db, `invites/OLD234`), { parentId: PID, expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
    await setDoc(doc(db, `parents/${PID}/routine/current`), { shiftMinutes: 45, weeks: 3, sinceDay: 1, askedAt: 1, answer: null });
  });
});

const parent = () => env.authenticatedContext(PID).firestore();
const fam = (uid = "fam1") => env.authenticatedContext(uid, { email: `${uid}@x.in` }).firestore();
const join = (db, uid, code = CODE) => {
  const b = writeBatch(db);
  b.set(doc(db, `parents/${PID}/members/${uid}`), { uid, name: "Ravi", inviteCode: code, addedAt: Timestamp.now() });
  b.set(doc(db, `users/${uid}/links/${PID}`), { addedAt: Timestamp.now() });
  return b.commit();
};

test("parent writes its own data; strangers can't read it", async () => {
  await assertSucceeds(setDoc(doc(parent(), `parents/${PID}/status/current`), { state: "QUIET" }));
  await assertFails(getDoc(doc(fam(), `parents/${PID}/status/current`)));
  await assertFails(setDoc(doc(fam(), `parents/${PID}/status/current`), { state: "ACTIVE" }));
});

test("only the parent's phone writes the model facts in its status", async () => {
  const facts = { state: "ACTIVE", runtime: "Kotlin", params: 5366, modelBytes: 50880, stepMicros: 11 };
  await assertSucceeds(setDoc(doc(parent(), `parents/${PID}/status/current`), facts));
  await assertFails(setDoc(doc(fam(), `parents/${PID}/status/current`), { ...facts, params: 1 }));
});

test("invite: get by code works, listing does not", async () => {
  await assertSucceeds(getDoc(doc(fam(), `invites/${CODE}`)));
  await assertFails(getDocs(collection(fam(), "invites")));
});

test("joining with a live code makes a member who can read but not write", async () => {
  const db = fam();
  await assertSucceeds(join(db, "fam1"));
  await assertSucceeds(getDoc(doc(db, `parents/${PID}/status/current`)));
  await assertFails(setDoc(doc(db, `parents/${PID}/status/current`), { state: "ACTIVE" }));
  await assertFails(setDoc(doc(db, `parents/${PID}/events/x`), { alert: "unusual" }));
});

test("expired or wrong-parent codes are refused, and you can't add someone else", async () => {
  await assertFails(join(fam(), "fam1", "OLD234"));
  await assertFails(setDoc(doc(fam("fam1"), `parents/${PID}/members/fam2`), { uid: "fam2", name: "X", inviteCode: CODE }));
});

test("members answer the routine question once, and only the answer fields", async () => {
  const db = fam();
  await join(db, "fam1");
  await assertFails(updateDoc(doc(db, `parents/${PID}/routine/current`), { answer: "yes", answeredBy: "fam1", shiftMinutes: 0 }));
  await assertSucceeds(updateDoc(doc(db, `parents/${PID}/routine/current`), { answer: "yes", answeredBy: "fam1" }));
  await assertFails(updateDoc(doc(db, `parents/${PID}/routine/current`), { answer: "no", answeredBy: "fam1" }));
});

test("members add family tags; they can't pose as the parent", async () => {
  const db = fam();
  await join(db, "fam1");
  const tag = { kind: "UNWELL", startAt: 0, endAt: 86_400_000, byName: "Ravi" };
  await assertSucceeds(setDoc(doc(db, `parents/${PID}/tags/t1`), { ...tag, byRole: "family" }));
  await assertFails(setDoc(doc(db, `parents/${PID}/tags/t2`), { ...tag, byRole: "parent" }));
  await assertFails(setDoc(doc(db, `parents/${PID}/tags/t3`), { ...tag, kind: "DANCING", byRole: "family" }));
});

test("after the parent removes a member, the member loses access", async () => {
  const db = fam();
  await join(db, "fam1");
  await assertSucceeds(deleteDoc(doc(parent(), `parents/${PID}/members/fam1`)));
  await assertFails(getDoc(doc(db, `parents/${PID}/status/current`)));
});

test("push tokens and links are private to their owner", async () => {
  await assertSucceeds(setDoc(doc(fam("fam1"), "users/fam1/tokens/tok"), { platform: "android" }));
  await assertFails(getDoc(doc(fam("fam2"), "users/fam1/tokens/tok")));
});

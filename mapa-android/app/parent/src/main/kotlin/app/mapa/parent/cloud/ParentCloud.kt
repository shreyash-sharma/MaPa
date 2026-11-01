package app.mapa.parent.cloud

import android.util.Log
import app.mapa.core.pairing.InviteCodes
import app.mapa.core.wire.Fields
import app.mapa.core.wire.ParentProfile
import app.mapa.core.wire.ParentWire
import app.mapa.core.wire.RoutineDoc
import app.mapa.core.wire.RoutineWire
import app.mapa.core.wire.TagDoc
import app.mapa.core.wire.TagWire
import app.mapa.shared.Db
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.concurrent.TimeUnit

/**
 * Everything the parent's phone sends to or reads from the backend. Blocking calls with
 * timeouts: run them off the main thread. Writes are fire-and-forget into Firestore's
 * offline queue, so a tick without network still completes and syncs later.
 */
object ParentCloud {
    const val RETENTION_DAYS = 30
    private const val TAG = "MaPaCloud"
    private const val READ_TIMEOUT_S = 10L

    /** The parent's id is their anonymous Firebase user id; no phone number or email is collected. */
    val parentId: String? get() = FirebaseAuth.getInstance().currentUser?.uid

    fun signIn(): String {
        FirebaseAuth.getInstance().currentUser?.let { return it.uid }
        return Tasks.await(FirebaseAuth.getInstance().signInAnonymously(), 30, TimeUnit.SECONDS).user!!.uid
    }

    fun saveProfile(pid: String, p: ParentProfile, consentAt: Long) {
        Db.parent(pid).set(ParentWire.encode(p) + mapOf("consentAt" to consentAt, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
    }

    fun setPaused(pid: String, paused: Boolean) {
        Db.parent(pid).set(mapOf("paused" to paused, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
    }

    fun uploadStatus(pid: String, status: Map<String, Any?>) {
        Db.status(pid).set(status + (Fields.SYNCED_AT to FieldValue.serverTimestamp()))
    }

    fun uploadDay(pid: String, date: String, day: Map<String, Any?>) {
        Db.days(pid).document(date).set(day + ("updatedAt" to FieldValue.serverTimestamp()))
    }

    fun addEvent(pid: String, event: Map<String, Any?>) {
        Db.events(pid).add(event + ("createdAt" to FieldValue.serverTimestamp()))
    }

    fun uploadTrend(pid: String, date: String, trend: Map<String, Any?>) {
        Db.trends(pid).document(date).set(trend + ("createdAt" to FieldValue.serverTimestamp()))
    }

    fun askRoutine(pid: String, q: RoutineDoc) {
        Db.routine(pid).set(RoutineWire.encode(q))
    }

    fun markRoutineApplied(pid: String, at: Long) {
        Db.routine(pid).update("appliedAt", at)
    }

    fun addTag(pid: String, tag: TagDoc) {
        Db.tags(pid).document(tag.id).set(TagWire.encode(tag) + ("createdAt" to FieldValue.serverTimestamp()))
    }

    /** Life tags typed by the family or the parent. Null when the server can't be reached. */
    fun fetchTags(pid: String): List<TagDoc>? = read(Db.tags(pid).get(Source.SERVER))?.documents
        ?.mapNotNull { d -> d.data?.let { TagWire.decode(d.id, it) } }

    fun fetchRoutine(pid: String): RoutineDoc? = read(Db.routine(pid).get(Source.SERVER))?.data?.let { RoutineWire.decode(it) }

    /** What a family member set up for this parent, from the link they sent. */
    data class Pairing(val code: String, val familyUid: String, val familyName: String, val parentName: String, val role: String, val lang: String)

    /** Reads a pairing by code. Null when it doesn't exist, has expired or was already used. Blocking. */
    fun fetchPairing(code: String): Pairing? {
        signIn()
        val d = Tasks.await(Db.pairing(code).get(Source.SERVER), 30, TimeUnit.SECONDS)
        if (!d.exists() || d.getString("parentId") != null) return null
        if ((d.getTimestamp("expiresAt")?.toDate()?.time ?: 0L) < System.currentTimeMillis()) return null
        return Pairing(
            code, d.getString("familyUid") ?: return null, d.getString("familyName") ?: "",
            d.getString("parentName") ?: "", d.getString("relation") ?: "other", d.getString("lang") ?: "en",
        )
    }

    /** The parent said yes: accept the pairing and add the family member, in one batch. Blocking. */
    fun acceptPairing(p: Pairing): String {
        val pid = signIn()
        val batch = Db.fs.batch()
        batch.update(Db.pairing(p.code), mapOf("parentId" to pid, "acceptedAt" to FieldValue.serverTimestamp()))
        batch.set(Db.members(pid).document(p.familyUid), mapOf("uid" to p.familyUid, "name" to p.familyName, "pairingCode" to p.code, "addedAt" to FieldValue.serverTimestamp()))
        Tasks.await(batch.commit(), 30, TimeUnit.SECONDS)
        return pid
    }

    /** A fresh invite code, valid for 24 hours. Returns the code. */
    fun createInvite(pid: String): String {
        val code = InviteCodes.generate()
        val now = System.currentTimeMillis()
        Tasks.await(
            Db.invite(code).set(mapOf("parentId" to pid, "createdAt" to now, "expiresAt" to com.google.firebase.Timestamp(java.util.Date(now + InviteCodes.TTL_MS)))),
            30, TimeUnit.SECONDS,
        )
        return code
    }

    fun removeMember(pid: String, uid: String) {
        Db.members(pid).document(uid).delete()
    }

    /**
     * Retention without a server: the phone deletes its own events and day charts older than
     * [days]. Runs at most once a day. Blocking; needs network.
     */
    fun prune(pid: String, nowMs: Long, days: Int = RETENTION_DAYS) {
        val cutoffMs = nowMs - days * 86_400_000L
        val cutoffDate = java.time.Instant.ofEpochMilli(cutoffMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val old = Tasks.await(Db.events(pid).whereLessThan("at", cutoffMs).limit(400).get(Source.SERVER), 30, TimeUnit.SECONDS).documents +
            Tasks.await(Db.days(pid).whereLessThan("date", cutoffDate).limit(400).get(Source.SERVER), 30, TimeUnit.SECONDS).documents
        if (old.isEmpty()) return
        val batch = Db.fs.batch()
        old.forEach { batch.delete(it.reference) }
        Tasks.await(batch.commit(), 30, TimeUnit.SECONDS)
    }

    /**
     * Deletes everything this parent shared: every subcollection, the profile, open invites,
     * then the anonymous account itself. Blocking; needs network.
     */
    fun deleteEverything(pid: String, inviteCodes: Collection<String>) {
        for (sub in Db.PARENT_SUBCOLLECTIONS) {
            val docs = Tasks.await(Db.parent(pid).collection(sub).get(Source.SERVER), 30, TimeUnit.SECONDS).documents
            docs.chunked(400).forEach { chunk ->
                val batch = Db.fs.batch()
                chunk.forEach { batch.delete(it.reference) }
                Tasks.await(batch.commit(), 30, TimeUnit.SECONDS)
            }
        }
        inviteCodes.forEach { runCatching { Tasks.await(Db.invite(it).delete(), 10, TimeUnit.SECONDS) } }
        Tasks.await(Db.parent(pid).delete(), 30, TimeUnit.SECONDS)
        FirebaseAuth.getInstance().currentUser?.let { u -> runCatching { Tasks.await(u.delete(), 30, TimeUnit.SECONDS) } }
        FirebaseAuth.getInstance().signOut()
    }

    private fun <T> read(task: Task<T>): T? = try {
        Tasks.await(task, READ_TIMEOUT_S, TimeUnit.SECONDS)
    } catch (e: Exception) {
        Log.i(TAG, "read skipped: ${e.javaClass.simpleName}")
        null
    }
}

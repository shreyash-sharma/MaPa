package app.mapa.family

import android.content.Context
import app.mapa.core.language.Lang
import app.mapa.core.language.Person
import app.mapa.core.language.Phrases
import app.mapa.core.language.Role
import app.mapa.core.pairing.InviteCodes
import app.mapa.core.wire.AlertKind
import app.mapa.core.wire.DayDoc
import app.mapa.core.wire.DayWire
import app.mapa.core.wire.EventDoc
import app.mapa.core.wire.EventWire
import app.mapa.core.wire.ParentProfile
import app.mapa.core.wire.ParentWire
import app.mapa.core.wire.RoutineDoc
import app.mapa.core.wire.RoutineWire
import app.mapa.core.wire.StatusDoc
import app.mapa.core.wire.StatusWire
import app.mapa.core.cloud.TagProposal
import app.mapa.core.wire.TagDoc
import app.mapa.core.wire.TagWire
import app.mapa.core.wire.TrendWire
import app.mapa.routine.LifeTagKind
import app.mapa.routine.NewRoutineQuestion
import app.mapa.routine.TrendReport
import app.mapa.shared.Db
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.ZoneId

/** Everything the family app reads and writes. Reads are live listeners; nothing raw ever arrives here. */
object FamilyRepo {
    private val judgeDemo get() = app.mapa.family.BuildConfig.JUDGE_DEMO_ENABLED
    private val auth get() = FirebaseAuth.getInstance()
    val uid: String? get() = if (judgeDemo) "judge-demo-family" else auth.currentUser?.uid
    val email: String? get() = if (judgeDemo) "" else auth.currentUser?.email
    /** How the parent knows this member: the Google first name when there is one, else the email name. */
    val firstName: String? get() = if (judgeDemo) "Riya" else auth.currentUser?.displayName?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: email?.substringBefore('@')?.substringBefore('.')?.replaceFirstChar { it.uppercase() }
    private var alertWatch: ListenerRegistration? = null

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun createAccount(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
    }

    /**
     * "Continue with Google": the phone's own account picker (Credential Manager), then Firebase Auth. The first
     * time creates the MaPa account, so sign-in and sign-up are one step. [activity] shows the picker.
     */
    suspend fun signInWithGoogle(activity: Context) {
        val webClient = activity.getString(activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName))
        val option = com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClient)
            .build()
        val result = androidx.credentials.CredentialManager.create(activity).getCredential(
            activity, androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(option).build(),
        )
        val c = result.credential
        require(c is androidx.credentials.CustomCredential &&
            c.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "not a Google account" }
        val token = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(c.data).idToken
        auth.signInWithCredential(com.google.firebase.auth.GoogleAuthProvider.getCredential(token, null)).await()
    }

    fun signOut(ctx: Context) {
        if (judgeDemo) return
        alertWatch?.remove(); alertWatch = null
        FamilyCheckWorker.cancel(ctx)
        auth.signOut()
    }

    /** Push token (real project only) and the in-app alert listener. */
    fun onSignedIn(ctx: Context) {
        if (judgeDemo) return
        val u = uid ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Db.tokens(u).document(token).set(mapOf("createdAt" to System.currentTimeMillis(), "platform" to "android"))
        }
        if (alertWatch == null) alertWatch = AlertWatcher.start(ctx.applicationContext, u)
        FamilyCheckWorker.schedule(ctx)
    }

    /**
     * Family-started setup: the family member describes the parent and gets a code for the link.
     * The parent's phone accepts it (and adds this member) when the parent says yes. Valid 7 days.
     */
    suspend fun createPairing(parentName: String, relation: String, lang: String, myName: String): String? {
        if (judgeDemo) return null
        val u = uid ?: return null
        val code = InviteCodes.generate()
        val now = System.currentTimeMillis()
        val doc = mapOf(
            "familyUid" to u, "familyName" to myName.trim().take(60), "parentName" to parentName.trim().take(24),
            "relation" to relation, "lang" to lang, "parentId" to null, "createdAt" to now,
            "expiresAt" to com.google.firebase.Timestamp(java.util.Date(now + 7L * 24 * 60 * 60 * 1000)),
        )
        return runCatching { Db.pairing(code).set(doc).await(); code }.getOrNull()
    }

    /** The parent's id once they said yes, else null. */
    fun pairingAccepted(code: String): Flow<String?> = if (judgeDemo) flowOf(null) else callbackFlow {
        val reg = Db.pairing(code).addSnapshotListener { s, _ -> trySend(s?.getString("parentId")) }
        awaitClose { reg.remove() }
    }

    /** Adds the parent to this member's list once the parent has accepted. */
    suspend fun completePairing(pid: String) {
        if (judgeDemo) return
        val u = uid ?: return
        Db.links(u).document(pid).set(mapOf("addedAt" to FieldValue.serverTimestamp())).await()
    }

    /** The parent's number, picked from this member's contacts. Kept on this phone only, for "Call". */
    fun savePhone(ctx: Context, pid: String, number: String) =
        ctx.getSharedPreferences("mapa_family", Context.MODE_PRIVATE).edit().putString("phone_$pid", number).apply()

    fun phone(ctx: Context, pid: String): String? =
        ctx.getSharedPreferences("mapa_family", Context.MODE_PRIVATE).getString("phone_$pid", null)

    /** Pairs with a parent using the code shown on the parent's phone. Returns an error key or null. */
    suspend fun pair(rawCode: String, myName: String): PairResult {
        if (judgeDemo) return PairResult.OFFLINE
        val u = uid ?: return PairResult.NOT_SIGNED_IN
        val code = InviteCodes.normalize(rawCode) ?: return PairResult.BAD_CODE
        val invite = runCatching { Db.invite(code).get().await() }.getOrNull() ?: return PairResult.OFFLINE
        if (!invite.exists()) return PairResult.BAD_CODE
        val pid = invite.getString("parentId") ?: return PairResult.BAD_CODE
        val expires = invite.getTimestamp("expiresAt")?.toDate()?.time ?: 0L
        if (expires < System.currentTimeMillis()) return PairResult.EXPIRED
        val batch = Db.fs.batch()
        batch.set(Db.members(pid).document(u), mapOf("uid" to u, "name" to myName.trim(), "inviteCode" to code, "addedAt" to FieldValue.serverTimestamp()))
        batch.set(Db.links(u).document(pid), mapOf("addedAt" to FieldValue.serverTimestamp()))
        return runCatching { batch.commit().await(); PairResult.OK }.getOrDefault(PairResult.REFUSED)
    }

    suspend fun leave(pid: String) {
        if (judgeDemo) return
        val u = uid ?: return
        val batch = Db.fs.batch()
        batch.delete(Db.members(pid).document(u))
        batch.delete(Db.links(u).document(pid))
        batch.commit().await()
    }

    fun links(): Flow<List<String>> = if (judgeDemo) JudgeFamilySeed.parentsFlow() else callbackFlow {
        val u = uid ?: run { trySend(emptyList()); close(); return@callbackFlow }
        val reg = Db.links(u).addSnapshotListener(MetadataChanges.INCLUDE) { s, _ -> s?.let { trySend(confirmedLinks(it)) } }
        awaitClose { reg.remove() }
    }

    /**
     * Links the server has accepted. Right after [pair] the link exists only locally, before the
     * membership the rules check is committed; listening to that parent then fails with
     * PERMISSION_DENIED and the listener never recovers, so the card stays missing until restart.
     */
    fun confirmedLinks(s: QuerySnapshot): List<String> = s.documents.filter { !it.metadata.hasPendingWrites() }.map { it.id }

    fun profile(pid: String): Flow<ParentProfile?> = if (judgeDemo) JudgeFamilySeed.profile(pid) else doc(Db.parent(pid)) { ParentWire.decode(it) }
    fun status(pid: String): Flow<StatusDoc?> = if (judgeDemo) JudgeFamilySeed.status(pid) else doc(Db.status(pid)) { StatusWire.decode(it) }
    fun routine(pid: String): Flow<RoutineDoc?> = if (judgeDemo) JudgeFamilySeed.routine(pid) else doc(Db.routine(pid)) { RoutineWire.decode(it) }

    fun today(pid: String, zone: ZoneId): Flow<DayDoc?> = if (judgeDemo) JudgeFamilySeed.today(pid) else doc(Db.days(pid).document(LocalDate.now(zone).toString())) { DayWire.decode(it) }

    /** The last [n] days the parent's phone shared, today included, oldest first. Empty when offline. */
    suspend fun week(pid: String, n: Long = 7): List<DayDoc> = if (judgeDemo) JudgeFamilySeed.week(pid, n) else runCatching {
        Db.days(pid).orderBy("date", Query.Direction.DESCENDING).limit(n).get().await()
            .documents.mapNotNull { it.data?.let(DayWire::decode) }.sortedBy { it.date }
    }.getOrDefault(emptyList())

    fun events(pid: String, limit: Long = 20): Flow<List<Pair<String, EventDoc>>> = if (judgeDemo) JudgeFamilySeed.events(pid, limit) else callbackFlow {
        val reg = Db.events(pid).orderBy("at", Query.Direction.DESCENDING).limit(limit).addSnapshotListener { s, _ ->
            s?.let { trySend(it.documents.mapNotNull { d -> d.data?.let(EventWire::decode)?.let { e -> d.id to e } }) }
        }
        awaitClose { reg.remove() }
    }

    fun latestTrend(pid: String): Flow<TrendReport?> = if (judgeDemo) JudgeFamilySeed.trend(pid) else callbackFlow {
        val reg = Db.trends(pid).orderBy("day", Query.Direction.DESCENDING).limit(1).addSnapshotListener { s, _ ->
            s?.let { trySend(it.documents.firstOrNull()?.data?.let(TrendWire::decode)) }
        }
        awaitClose { reg.remove() }
    }

    fun tags(pid: String): Flow<List<TagDoc>> = if (judgeDemo) JudgeFamilySeed.tags(pid) else callbackFlow {
        val reg = Db.tags(pid).addSnapshotListener { s, _ ->
            s?.let { trySend(it.documents.mapNotNull { d -> d.data?.let { m -> TagWire.decode(d.id, m) } }) }
        }
        awaitClose { reg.remove() }
    }

    /** Marks today (in the parent's time zone) as unusual so the phone doesn't learn it as routine. */
    fun tagToday(pid: String, zone: ZoneId, kind: LifeTagKind, myName: String?) {
        if (judgeDemo) return
        val day = LocalDate.now(zone)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val id = "${day}_${kind.name.lowercase()}"
        Db.tags(pid).document(id).set(
            TagWire.encode(TagDoc(id, kind, start, end, "family", myName)) + ("createdAt" to FieldValue.serverTimestamp()),
        )
    }

    /** A tag the family member confirmed after Meta AI proposed it from their words. The rules cap it at 14 days. */
    fun addTag(pid: String, zone: ZoneId, p: TagProposal, myName: String?) {
        if (judgeDemo) return
        val day = java.time.Instant.ofEpochMilli(p.startMs).atZone(zone).toLocalDate()
        val id = "${day}_${p.kind.name.lowercase()}_${p.startMs / 60000}"
        Db.tags(pid).document(id).set(
            TagWire.encode(TagDoc(id, p.kind, p.startMs, p.endMs, "family", myName)) + ("createdAt" to FieldValue.serverTimestamp()),
        )
    }

    fun answerRoutine(pid: String, yes: Boolean) {
        if (judgeDemo) return
        Db.routine(pid).update(
            mapOf("answer" to if (yes) "yes" else "no", "answeredBy" to (uid ?: ""), "answeredAt" to FieldValue.serverTimestamp()),
        )
    }

    private fun <T> doc(ref: DocumentReference, decode: (Map<String, Any?>) -> T?): Flow<T?> = callbackFlow {
        val reg = ref.addSnapshotListener { s, _ -> if (s != null) trySend(s.data?.let(decode)) }
        awaitClose { reg.remove() }
    }

    fun person(p: ParentProfile?): Person = Person(p?.name ?: "", Role.of(p?.role))
    fun zone(p: ParentProfile?): ZoneId = runCatching { ZoneId.of(p?.zone ?: "Asia/Kolkata") }.getOrDefault(ZoneId.of("Asia/Kolkata"))
    fun question(r: RoutineDoc): NewRoutineQuestion = NewRoutineQuestion(r.shiftMinutes, r.weeks, r.sinceDay)
}

enum class PairResult { OK, BAD_CODE, EXPIRED, OFFLINE, REFUSED, NOT_SIGNED_IN }

/**
 * Raises a local notification for new alert events on every followed parent. Works with the
 * Emulator Suite (no push there); with a real project the backend's push arrives too and
 * [Alerts.show] de-duplicates by event id.
 */
object AlertWatcher {
    fun start(ctx: Context, uid: String): ListenerRegistration {
        val perParent = mutableMapOf<String, ListenerRegistration>()
        val startedAt = System.currentTimeMillis()
        val links = Db.links(uid).addSnapshotListener(MetadataChanges.INCLUDE) { s, _ ->
            val pids = s?.let(FamilyRepo::confirmedLinks)?.toSet() ?: return@addSnapshotListener
            (perParent.keys - pids).forEach { perParent.remove(it)?.remove() }
            (pids - perParent.keys).forEach { pid -> perParent[pid] = watch(ctx, pid, startedAt) }
        }
        return ListenerRegistration {
            links.remove()
            perParent.values.forEach { it.remove() }
            perParent.clear()
        }
    }

    private fun watch(ctx: Context, pid: String, since: Long): ListenerRegistration {
        var name = ""
        var lang = Lang.EN
        val profile = Db.parent(pid).addSnapshotListener { s, _ ->
            ParentWire.decode(s?.data ?: return@addSnapshotListener)?.let { name = it.name; lang = Lang.of(it.lang) }
        }
        val events = Db.events(pid).whereGreaterThan("at", since).addSnapshotListener { s, _ ->
            s?.documentChanges?.filter { it.type == com.google.firebase.firestore.DocumentChange.Type.ADDED }?.forEach { c ->
                val e = c.document.data.let(EventWire::decode) ?: return@forEach
                val kind = e.alert ?: return@forEach
                Alerts.show(ctx, c.document.id, title(name, kind, lang), e.text, pid, kind)
            }
        }
        return ListenerRegistration { profile.remove(); events.remove() }
    }

    fun title(name: String, kind: AlertKind, lang: Lang): String {
        val ph = Phrases(lang)
        return when (kind) {
            AlertKind.UNUSUAL -> "$name: " + ph.stateLabel(app.mapa.routine.RoutineState.UNUSUAL_INACTIVITY)
            AlertKind.STALE -> "$name: " + ph.stateLabel(app.mapa.routine.RoutineState.MONITORING_UNAVAILABLE)
            AlertKind.LOW_BATTERY -> if (lang == Lang.HI) "$name: फ़ोन की बैटरी कम" else "$name: phone battery low"
        }
    }
}

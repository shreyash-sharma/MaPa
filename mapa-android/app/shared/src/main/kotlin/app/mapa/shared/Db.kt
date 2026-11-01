package app.mapa.shared

import app.mapa.core.wire.Paths
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/** Typed shortcuts for the layout documented in [Paths]. */
object Db {
    val fs: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    fun parent(pid: String): DocumentReference = fs.collection(Paths.PARENTS).document(pid)
    fun status(pid: String): DocumentReference = parent(pid).collection(Paths.STATUS).document(Paths.STATUS_DOC)
    fun days(pid: String): CollectionReference = parent(pid).collection(Paths.DAYS)
    fun events(pid: String): CollectionReference = parent(pid).collection(Paths.EVENTS)
    fun members(pid: String): CollectionReference = parent(pid).collection(Paths.MEMBERS)
    fun tags(pid: String): CollectionReference = parent(pid).collection(Paths.TAGS)
    fun routine(pid: String): DocumentReference = parent(pid).collection(Paths.ROUTINE).document(Paths.ROUTINE_DOC)
    fun trends(pid: String): CollectionReference = parent(pid).collection(Paths.TRENDS)
    fun invite(code: String): DocumentReference = fs.collection(Paths.INVITES).document(code)
    fun pairing(code: String): DocumentReference = fs.collection(Paths.PAIRINGS).document(code)
    fun links(uid: String): CollectionReference = fs.collection(Paths.USERS).document(uid).collection(Paths.LINKS)
    fun tokens(uid: String): CollectionReference = fs.collection(Paths.USERS).document(uid).collection(Paths.TOKENS)

    /** Subcollections deleted when a parent deletes everything. */
    val PARENT_SUBCOLLECTIONS = listOf(Paths.STATUS, Paths.DAYS, Paths.EVENTS, Paths.MEMBERS, Paths.TAGS, Paths.ROUTINE, Paths.TRENDS)
}

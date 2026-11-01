@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.firebase.firestore

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp

class FirebaseFirestore {
    var firestoreSettings: FirebaseFirestoreSettings = TODO()
    fun useEmulator(host: String, port: Int) {}
    fun collection(path: String): CollectionReference = TODO()
    fun collectionGroup(id: String): Query = TODO()
    fun batch(): WriteBatch = TODO()
    companion object { @JvmStatic fun getInstance(): FirebaseFirestore = TODO() }
}
interface LocalCacheSettings
class PersistentCacheSettings : LocalCacheSettings {
    class Builder { fun build(): PersistentCacheSettings = TODO() }
    companion object { @JvmStatic fun newBuilder(): Builder = TODO() }
}
class FirebaseFirestoreSettings {
    class Builder { fun setLocalCacheSettings(s: LocalCacheSettings): Builder = this; fun build(): FirebaseFirestoreSettings = TODO() }
}
class FirebaseFirestoreException : Exception()
fun interface EventListener<T> { fun onEvent(value: T?, error: FirebaseFirestoreException?) }
fun interface ListenerRegistration { fun remove() }
enum class MetadataChanges { INCLUDE, EXCLUDE }
class SnapshotMetadata { fun hasPendingWrites(): Boolean = TODO(); fun isFromCache(): Boolean = TODO() }
enum class Source { DEFAULT, SERVER, CACHE }
class SetOptions { companion object { @JvmStatic fun merge(): SetOptions = TODO() } }
abstract class FieldValue { companion object { @JvmStatic fun serverTimestamp(): FieldValue = TODO(); @JvmStatic fun delete(): FieldValue = TODO() } }

open class Query {
    enum class Direction { ASCENDING, DESCENDING }
    fun orderBy(field: String, d: Direction): Query = TODO()
    fun limit(n: Long): Query = TODO()
    fun whereGreaterThan(field: String, v: Any): Query = TODO()
    fun whereEqualTo(field: String, v: Any?): Query = TODO()
    fun whereLessThan(field: String, v: Any): Query = TODO()
    fun get(): Task<QuerySnapshot> = TODO()
    fun get(source: Source): Task<QuerySnapshot> = TODO()
    fun addSnapshotListener(l: EventListener<QuerySnapshot>): ListenerRegistration = TODO()
    fun addSnapshotListener(m: MetadataChanges, l: EventListener<QuerySnapshot>): ListenerRegistration = TODO()
}
class CollectionReference : Query() {
    val id: String get() = TODO()
    fun document(id: String): DocumentReference = TODO()
    fun document(): DocumentReference = TODO()
    fun add(data: Any): Task<DocumentReference> = TODO()
}
class DocumentReference {
    val id: String get() = TODO()
    val parent: CollectionReference get() = TODO()
    fun collection(path: String): CollectionReference = TODO()
    fun get(): Task<DocumentSnapshot> = TODO()
    fun get(source: Source): Task<DocumentSnapshot> = TODO()
    fun set(data: Any): Task<Void> = TODO()
    fun set(data: Any, o: SetOptions): Task<Void> = TODO()
    fun update(data: Map<String, Any?>): Task<Void> = TODO()
    fun update(field: String, value: Any?, vararg more: Any?): Task<Void> = TODO()
    fun delete(): Task<Void> = TODO()
    fun addSnapshotListener(l: EventListener<DocumentSnapshot>): ListenerRegistration = TODO()
}
open class DocumentSnapshot {
    val id: String get() = TODO()
    val reference: DocumentReference get() = TODO()
    val metadata: SnapshotMetadata get() = TODO()
    open val data: Map<String, Any>? get() = TODO()
    fun exists(): Boolean = TODO()
    fun getString(f: String): String? = TODO()
    fun getLong(f: String): Long? = TODO()
    fun getTimestamp(f: String): Timestamp? = TODO()
}
class QueryDocumentSnapshot : DocumentSnapshot() { override val data: Map<String, Any> get() = TODO() }
class DocumentChange {
    enum class Type { ADDED, MODIFIED, REMOVED }
    val type: Type get() = TODO()
    val document: QueryDocumentSnapshot get() = TODO()
}
class QuerySnapshot : Iterable<QueryDocumentSnapshot> {
    val documents: List<DocumentSnapshot> get() = TODO()
    val documentChanges: List<DocumentChange> get() = TODO()
    val isEmpty: Boolean get() = TODO()
    override fun iterator(): Iterator<QueryDocumentSnapshot> = TODO()
}
class WriteBatch {
    fun set(ref: DocumentReference, data: Any): WriteBatch = this
    fun update(ref: DocumentReference, data: Map<String, Any?>): WriteBatch = this
    fun delete(ref: DocumentReference): WriteBatch = this
    fun commit(): Task<Void> = TODO()
}

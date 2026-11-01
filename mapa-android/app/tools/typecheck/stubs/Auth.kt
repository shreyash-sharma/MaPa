@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.firebase.auth

import com.google.android.gms.tasks.Task

class FirebaseAuth {
    val currentUser: FirebaseUser? get() = TODO()
    fun signInAnonymously(): Task<AuthResult> = TODO()
    fun signInWithEmailAndPassword(email: String, password: String): Task<AuthResult> = TODO()
    fun createUserWithEmailAndPassword(email: String, password: String): Task<AuthResult> = TODO()
    fun signOut() {}
    fun useEmulator(host: String, port: Int) {}
    companion object { @JvmStatic fun getInstance(): FirebaseAuth = TODO() }
}
interface AuthResult { val user: FirebaseUser? }
abstract class FirebaseUser {
    abstract val uid: String
    abstract val email: String?
    fun delete(): Task<Void> = TODO()
}

// Sandbox-only API stubs mirroring the real signatures (Firebase BoM 33.7, Play Services Tasks). Not shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.firebase

import android.content.Context
import java.util.Date

class FirebaseApp {
    companion object {
        @JvmStatic fun getApps(ctx: Context): List<FirebaseApp> = TODO()
        @JvmStatic fun initializeApp(ctx: Context, o: FirebaseOptions): FirebaseApp = TODO()
    }
}
class FirebaseOptions {
    class Builder {
        fun setProjectId(s: String): Builder = this
        fun setApplicationId(s: String): Builder = this
        fun setApiKey(s: String): Builder = this
        fun build(): FirebaseOptions = TODO()
    }
}
class Timestamp(date: Date) : Comparable<Timestamp> {
    fun toDate(): Date = TODO()
    override fun compareTo(other: Timestamp): Int = 0
}

@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.firebase.messaging

import android.content.Intent
import android.os.IBinder
import com.google.android.gms.tasks.Task

class FirebaseMessaging {
    val token: Task<String> get() = TODO()
    companion object { @JvmStatic fun getInstance(): FirebaseMessaging = TODO() }
}
abstract class FirebaseMessagingService : android.app.Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    open fun onMessageReceived(message: RemoteMessage) {}
    open fun onNewToken(token: String) {}
}
class RemoteMessage {
    val data: Map<String, String> get() = TODO()
    val messageId: String? get() = TODO()
    val notification: Notification? get() = TODO()
    class Notification { val title: String? get() = TODO(); val body: String? get() = TODO() }
}

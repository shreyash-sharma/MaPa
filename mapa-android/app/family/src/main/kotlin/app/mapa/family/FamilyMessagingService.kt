package app.mapa.family

import app.mapa.shared.Db
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Push from the backend (real Firebase project only). Data payload: eventId, title, body. */
class FamilyMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val id = d["eventId"] ?: message.messageId ?: return
        val kind = d["kind"]?.let { k -> app.mapa.core.wire.AlertKind.entries.firstOrNull { it.wire == k } }
        Alerts.show(this, id, d["title"] ?: message.notification?.title ?: "MaPa", d["body"] ?: message.notification?.body ?: return, d["pid"], kind)
    }

    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Db.tokens(uid).document(token).set(mapOf("createdAt" to System.currentTimeMillis(), "platform" to "android"))
    }
}

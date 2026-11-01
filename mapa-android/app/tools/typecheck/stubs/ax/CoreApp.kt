@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.app
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
object NotificationCompat {
    abstract class Style
    class BigTextStyle : Style() { fun bigText(t: CharSequence?): BigTextStyle = this }
    class Builder(c: Context, channel: String) {
        fun setSmallIcon(i: Int): Builder = this
        fun setContentTitle(t: CharSequence?): Builder = this
        fun setContentText(t: CharSequence?): Builder = this
        fun setStyle(s: Style?): Builder = this
        fun setContentIntent(p: PendingIntent?): Builder = this
        fun setAutoCancel(b: Boolean): Builder = this
        fun build(): Notification = TODO()
    }
}
class NotificationManagerCompat {
    fun notify(id: Int, n: Notification) {}
    companion object { @JvmStatic fun from(c: Context): NotificationManagerCompat = TODO() }
}

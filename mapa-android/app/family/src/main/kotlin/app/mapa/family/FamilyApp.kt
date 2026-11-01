package app.mapa.family

import android.Manifest
import app.mapa.core.wire.AlertKind
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.mapa.family.ui.MainActivity
import app.mapa.shared.Backend

class FamilyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Backend.init(this)
        CloudAi.load(this)
        Alerts.createChannel(this)
        FamilyRepo.onSignedIn(this)
    }
}

/**
 * Gentle alerts, never framed as an emergency. One channel per kind so each can be tuned in
 * Android settings: only "unusual" and "updates stopped" may make a sound; battery is quiet.
 * Tapping an alert opens that parent's screen.
 */
object Alerts {
    const val CHANNEL = "mapa-alerts" // older single channel: still used for pushes without a kind
    const val EXTRA_PID = "pid"
    private fun channel(kind: AlertKind?) = when (kind) {
        AlertKind.UNUSUAL -> "mapa-unusual"
        AlertKind.STALE -> "mapa-stale"
        AlertKind.LOW_BATTERY -> "mapa-battery"
        null -> CHANNEL
    }

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        fun ch(id: String, name: String, desc: String, importance: Int) =
            NotificationChannel(id, name, importance).apply { description = desc }
        nm.createNotificationChannels(listOf(
            ch(CHANNEL, "Changes in your parent's rhythm", "Other updates from MaPa", NotificationManager.IMPORTANCE_DEFAULT),
            ch("mapa-unusual", "Quieter than usual", "When phone activity is quieter than usual for your parent", NotificationManager.IMPORTANCE_DEFAULT),
            ch("mapa-stale", "Updates stopped", "When your parent's phone has not sent an update for a while", NotificationManager.IMPORTANCE_DEFAULT),
            ch("mapa-battery", "Low battery", "When your parent's phone battery is low", NotificationManager.IMPORTANCE_LOW),
        ))
    }

    /** Shows each event once, whether it arrived by push, the live listener or the background check. */
    fun show(ctx: Context, eventId: String, title: String, body: String, pid: String? = null, kind: AlertKind? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val seen = ctx.getSharedPreferences("mapa_family_alerts", Context.MODE_PRIVATE)
        if (seen.getBoolean(eventId, false)) return
        seen.edit().putBoolean(eventId, true).apply()
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { pid?.let { putExtra(EXTRA_PID, it) } }
        val open = PendingIntent.getActivity(ctx, eventId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel(kind))
            .setSmallIcon(app.mapa.shared.R.drawable.ic_stat_mapa)
            .setColor(ContextCompat.getColor(ctx, app.mapa.shared.R.color.mapa_notification))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(eventId.hashCode(), n)
    }
}

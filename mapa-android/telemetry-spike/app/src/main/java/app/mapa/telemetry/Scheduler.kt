package app.mapa.telemetry

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Two independent wake-up paths, so the test can tell which one Android/OEMs throttle:
 *  - WorkManager periodic work (15 min is the platform minimum).
 *  - An inexact AlarmManager.setAndAllowWhileIdle alarm (no exact-alarm permission needed).
 */
object Scheduler {
    private const val WORK = "mapa-heartbeat"
    private const val INTERVAL_MIN = 15L

    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HeartbeatWorker>(INTERVAL_MIN, TimeUnit.MINUTES).build(),
        )
        // Don't push an already-pending alarm further out every time the process starts.
        // Pending alarms are dropped on reboot and force-stop, so this re-arms after those.
        if (alarmIntent(app, PendingIntent.FLAG_NO_CREATE) == null) scheduleAlarm(app)
    }

    fun scheduleAlarm(ctx: Context) {
        val am = ctx.service<AlarmManager>()
        val pi = alarmIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(INTERVAL_MIN),
            pi,
        )
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        WorkManager.getInstance(app).cancelUniqueWork(WORK)
        alarmIntent(app, PendingIntent.FLAG_NO_CREATE)?.let {
            app.service<AlarmManager>().cancel(it)
            it.cancel()
        }
    }

    private fun alarmIntent(ctx: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, AlarmReceiver::class.java), flag or PendingIntent.FLAG_IMMUTABLE,
    )
}

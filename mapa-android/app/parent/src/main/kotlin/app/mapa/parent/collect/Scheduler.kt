package app.mapa.parent.collect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Two independent wake-up paths, carried over from the telemetry spike: WorkManager periodic
 * work (15 minutes is the platform minimum) and an inexact setAndAllowWhileIdle alarm as a
 * backup on OEMs that starve WorkManager. Either one runs the same tick.
 */
object Scheduler {
    private const val WORK = "mapa-tick"
    private const val NOW = "mapa-tick-now"
    private const val MODEL = "mapa-note-model"
    private const val INTERVAL_MIN = 15L

    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        val wm = WorkManager.getInstance(app)
        wm.enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TickWorker>(INTERVAL_MIN, TimeUnit.MINUTES).build(),
        )
        if (alarmIntent(app, PendingIntent.FLAG_NO_CREATE) == null) scheduleAlarm(app)
        // Older builds fetched an on-phone note model; the family phone writes notes now.
        wm.cancelUniqueWork(MODEL)
    }

    /** Runs a tick soon, e.g. right after consent or after a reboot. */
    fun tickNow(ctx: Context) {
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TickWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
        )
    }

    /**
     * Runs a tick just after the current 15-minute bin closes. The engine only sees closed
     * bins, so an unlock at 10:05 is reported by a tick at about 10:16.
     */
    fun tickAfterBinCloses(ctx: Context) {
        val binMs = INTERVAL_MIN * 60_000L
        val delay = binMs - System.currentTimeMillis() % binMs + 60_000L
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TickWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }

    fun scheduleAlarm(ctx: Context) {
        val pi = alarmIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        ctx.service<AlarmManager>().setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(INTERVAL_MIN),
            pi,
        )
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        WorkManager.getInstance(app).apply {
            cancelUniqueWork(WORK)
            cancelUniqueWork(NOW)
            cancelUniqueWork(MODEL)
        }
        alarmIntent(app, PendingIntent.FLAG_NO_CREATE)?.let {
            app.service<AlarmManager>().cancel(it)
            it.cancel()
        }
    }

    private fun alarmIntent(ctx: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, AlarmReceiver::class.java), flag or PendingIntent.FLAG_IMMUTABLE,
    )
}

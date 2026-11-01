package app.mapa.parent.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.mapa.parent.Prefs
import app.mapa.parent.rhythm.ParentRuntime
import kotlin.concurrent.thread

class TickWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        runCatching { ParentRuntime.tick(applicationContext, "work") }
            .onFailure { Log.e("MaPaTick", "tick failed", it) }
        return Result.success()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                ParentRuntime.tick(app, "alarm")
            } catch (e: Exception) {
                Log.e("MaPaTick", "alarm tick failed", e)
            } finally {
                if (Prefs(app).consented) Scheduler.scheduleAlarm(app)
                pending.finish()
            }
        }
    }
}

/** The only way MaPa comes back after a reboot without the parent opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!Prefs(app).consented) return
        Scheduler.ensure(app)
        MotionSignal.start(app)
        PowerLog.sample(app)
        Scheduler.tickNow(app)
    }
}

/**
 * Runtime-registered: an unlock after a long silence triggers a tick, so a family looking
 * at "Quiet" sees "Active" as soon as the bin with the unlock closes, not whenever WorkManager next runs.
 */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        val prefs = Prefs(context)
        if (!prefs.consented || prefs.paused) return
        PowerLog.sample(context.applicationContext)
        if (System.currentTimeMillis() - prefs.lastSyncAt > 16 * 60_000L) Scheduler.tickAfterBinCloses(context)
    }

    companion object {
        fun filter() = IntentFilter(Intent.ACTION_USER_PRESENT)
    }
}

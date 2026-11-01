package app.mapa.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import kotlin.concurrent.thread

class HeartbeatWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Heartbeat.run(applicationContext, "work")
        return Result.success()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                Heartbeat.run(app, "alarm")
            } finally {
                if (Prefs(app).consented) Scheduler.scheduleAlarm(app)
                pending.finish()
            }
        }
    }
}

/** Manifest receiver: the only way we come back after a reboot without the user opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!Prefs(app).consented) return
        EventLog.append(app, "boot", JSONObject().put("action", intent.action ?: ""))
        val pending = goAsync()
        thread {
            try {
                Collector.start(app)
                Heartbeat.run(app, "boot")
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Runtime-registered receiver. Android only delivers screen on/off to a live process,
 * so these events show how much of the day our process is actually alive.
 * The Usage Access backfill is the source of truth; this is the comparison.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.consented) return
        val action = intent.action ?: return
        val data = JSONObject().put("action", action.substringAfterLast('.'))
        when (action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> {
                if (prefs.paused) return
                EventLog.append(app, "screen", data)
            }
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                val pm = app.service<PowerManager>()
                EventLog.append(app, "system", data.put("deviceIdle", pm.isDeviceIdleMode))
            }
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val pm = app.service<PowerManager>()
                EventLog.append(app, "system", data.put("powerSave", pm.isPowerSaveMode))
            }
            else -> EventLog.append(app, "system", data)
        }
    }

    companion object {
        fun filter() = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
    }
}

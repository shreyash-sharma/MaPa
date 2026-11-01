package app.mapa.telemetry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject

class TelemetryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Collector.start(this)
    }
}

/** Starts collection for this process. Does nothing until the phone's owner has consented. */
object Collector {
    private var registered = false

    @Synchronized
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!Prefs(app).consented) return
        if (!registered) {
            registered = true
            EventLog.append(
                app, "process_start",
                JSONObject().put("model", Device.model()).put("sdk", Build.VERSION.SDK_INT),
            )
            logExitReasons(app)
            ContextCompat.registerReceiver(
                app, SystemEventReceiver(), SystemEventReceiver.filter(), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        Scheduler.ensure(app)
    }

    fun stop(ctx: Context) = Scheduler.cancel(ctx)

    /** Android 11+ tells us why our previous process died: low memory, force stop, OEM kill, etc. */
    private fun logExitReasons(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = Prefs(ctx)
        val am = ctx.service<ActivityManager>()
        val seen = prefs.lastExitTimestamp
        val fresh = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 20)
            .filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
        for (info in fresh) {
            EventLog.append(
                ctx, "process_exit",
                JSONObject()
                    .put("at", info.timestamp)
                    .put("reason", exitReason(info.reason))
                    .put("importance", info.importance)
                    .put("description", info.description ?: ""),
            )
        }
        fresh.lastOrNull()?.let { prefs.lastExitTimestamp = it.timestamp }
    }

    private fun exitReason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        else -> "code_$r"
    }
}

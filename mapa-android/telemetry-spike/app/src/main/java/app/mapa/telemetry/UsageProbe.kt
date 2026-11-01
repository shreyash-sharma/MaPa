package app.mapa.telemetry

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the system's own usage event history (requires Usage Access).
 *
 * Key property for MaPa: the OS keeps this history even while our process is dead,
 * so interactions missed during an OEM kill or Doze can be recovered on the next run.
 *
 * Privacy: we keep only event *types* and timestamps. Package names are read by the
 * iterator but never stored, logged or uploaded.
 */
object UsageProbe {

    // UsageEvents.Event constants. Some are API 28+; on older phones they simply never appear.
    private val TYPES = mapOf(
        1 to "foreground",       // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
        7 to "userInteraction",  // USER_INTERACTION (not always visible to third-party apps)
        15 to "screenOn",        // SCREEN_INTERACTIVE
        16 to "screenOff",       // SCREEN_NON_INTERACTIVE
        17 to "keyguardShown",   // KEYGUARD_SHOWN
        18 to "keyguardHidden",  // KEYGUARD_HIDDEN, i.e. unlock
        26 to "shutdown",        // DEVICE_SHUTDOWN
        27 to "startup",         // DEVICE_STARTUP
    )

    // Screen-on alone can be a notification lighting the screen, so it is not counted as interaction.
    private val INTERACTION = setOf("foreground", "userInteraction", "keyguardHidden")

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.service<AppOpsManager>()
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            ctx.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Summary of events in [from, to): counts per type, last interaction, and active minutes. */
    fun collect(ctx: Context, from: Long, to: Long): JSONObject? {
        if (!hasAccess(ctx)) return null
        val usm = ctx.service<UsageStatsManager>()
        val events = usm.queryEvents(from, to) ?: return null
        val e = UsageEvents.Event()
        val counts = sortedMapOf<String, Int>()
        val minutes = sortedSetOf<Long>()
        var lastInteraction = 0L
        var lastScreenOn = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val name = TYPES[e.eventType] ?: continue
            counts[name] = (counts[name] ?: 0) + 1
            if (name == "screenOn") lastScreenOn = maxOf(lastScreenOn, e.timeStamp)
            if (name in INTERACTION) {
                lastInteraction = maxOf(lastInteraction, e.timeStamp)
                minutes += e.timeStamp / 60_000
            }
        }
        return JSONObject()
            .put("from", from)
            .put("to", to)
            .put("counts", JSONObject(counts as Map<*, *>))
            .put("lastInteraction", lastInteraction)
            .put("lastScreenOn", lastScreenOn)
            .put("activeMinutes", JSONArray(minutes.toList()))
    }
}

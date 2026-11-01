package app.mapa.parent.collect

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * Reads Android's own usage event history (requires Usage Access). Adapted from the
 * telemetry spike's UsageProbe.kt.
 *
 * The OS keeps this history while MaPa's process is dead, so interactions missed during an
 * OEM kill or Doze are recovered on the next run. Privacy: only interaction *timestamps*
 * are returned. Package names are read by the iterator and dropped on the spot.
 */
object UsageProbe {
    // UsageEvents.Event types that mean a person used the phone. Screen-on alone is not
    // counted (a notification can light the screen); USER_INTERACTION is often hidden from
    // third-party apps, so unlocks and app opens carry the signal.
    private val INTERACTION = setOf(
        1, // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
        7, // USER_INTERACTION
        18, // KEYGUARD_HIDDEN (unlock)
    )

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

    /** Timestamps of interactions in [from, to), or null without Usage Access. */
    fun interactionTimes(ctx: Context, from: Long, to: Long): List<Long>? {
        if (!hasAccess(ctx)) return null
        val events = ctx.service<UsageStatsManager>().queryEvents(from, to) ?: return null
        val e = UsageEvents.Event()
        val out = ArrayList<Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType in INTERACTION) out += e.timeStamp
        }
        return out
    }
}

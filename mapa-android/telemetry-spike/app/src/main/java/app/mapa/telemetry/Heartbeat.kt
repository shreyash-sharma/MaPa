package app.mapa.telemetry

import android.content.Context
import org.json.JSONObject

/**
 * One heartbeat = device snapshot + Usage Access backfill since the last heartbeat + sync attempt.
 * `source` says which mechanism woke us (work, alarm, boot, manual) so we can compare
 * how each survives Doze and OEM battery managers.
 */
object Heartbeat {
    private const val DAY = 24L * 60 * 60 * 1000

    @Synchronized
    fun run(ctx: Context, source: String) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (!prefs.consented) return

        checkReboot(app, prefs)

        val now = System.currentTimeMillis()
        val beat = Device.snapshot(app).put("source", source).put("paused", prefs.paused)
        val prev = prefs.lastBeat(source)
        if (prev > 0) beat.put("gapMin", Math.round((now - prev) / 6_000.0) / 10.0)
        prefs.setLastBeat(source, now)

        if (!prefs.paused) {
            val cursor = prefs.usageCursor
            val from = if (cursor > 0) maxOf(cursor, now - 3 * DAY) else now - DAY
            UsageProbe.collect(app, from, now)?.let { usage ->
                beat.put("usage", usage)
                prefs.usageCursor = now
                val last = usage.optLong("lastInteraction")
                if (last > prefs.lastInteraction) prefs.lastInteraction = last
            }
        }
        beat.put("lastInteraction", prefs.lastInteraction)
        beat.put("sync", Sync.push(app, prefs, beat))
        EventLog.append(app, "heartbeat", beat)
    }

    private fun checkReboot(ctx: Context, prefs: Prefs) {
        val boot = Device.bootCount(ctx)
        val last = prefs.lastBootCount
        if (last != -1 && boot != last) {
            EventLog.append(ctx, "reboot_detected", JSONObject().put("previousBoot", last))
        }
        prefs.lastBootCount = boot
    }
}

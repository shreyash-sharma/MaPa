package app.mapa.telemetry

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-readable on-phone summary. tools/analyze.py does the same, in more depth, on exports. */
object Report {
    private val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)

    fun summarize(events: List<JSONObject>, now: Long): String = buildString {
        if (events.isEmpty()) return "No events yet."
        val first = events.first().optLong("t")
        appendLine("Logging since ${fmt.format(Date(first))} (${hours(now - first)} h)")
        appendLine()

        val beats = events.filter { it.optString("type") == "heartbeat" }
        appendLine("Heartbeats by wake-up path")
        for (source in listOf("work", "alarm", "boot", "manual")) {
            val s = beats.filter { it.optString("source") == source }
            if (s.isEmpty()) continue
            val gaps = s.filter { it.has("gapMin") }.map { it.getDouble("gapMin") }.sorted()
            append("  $source: ${s.size}, last ${ago(now - s.last().optLong("t"))}")
            if (gaps.isNotEmpty() && source in setOf("work", "alarm")) {
                append(", median gap ${gaps[gaps.size / 2].toInt()}m, max ${gaps.last().toInt()}m")
                append(", >30m: ${gaps.count { it > 30 }}, >60m: ${gaps.count { it > 60 }}")
            }
            appendLine()
        }

        val times = beats.map { it.optLong("t") }
        val worst = times.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L
        appendLine("  any path: worst gap ${worst / 60_000}m")
        appendLine()

        val sync = beats.mapNotNull { it.optJSONObject("sync")?.optString("status") }
        if (sync.any { it != "off" }) {
            appendLine("Sync: ${sync.count { it == "ok" }} ok / ${sync.count { it != "off" }} tried")
        } else {
            appendLine("Sync: off (local log only)")
        }

        val boots = events.map { it.optInt("boot") }.distinct().size
        appendLine("Boots seen: $boots, boot events: ${events.count { it.optString("type") == "boot" }}")
        appendLine("Process starts: ${events.count { it.optString("type") == "process_start" }}")
        val exits = events.filter { it.optString("type") == "process_exit" }
            .groupingBy { it.optString("reason") }.eachCount()
        if (exits.isNotEmpty()) appendLine("Process exits: $exits")

        val unlocks = beats.sumOf { it.optJSONObject("usage")?.optJSONObject("counts")?.optInt("keyguardHidden") ?: 0 }
        val presents = events.count { it.optString("type") == "screen" && it.optString("action") == "USER_PRESENT" }
        appendLine("Unlocks: $unlocks from Usage Access, $presents seen live by receiver")

        val lastInteraction = beats.lastOrNull()?.optLong("lastInteraction") ?: 0L
        if (lastInteraction > 0) {
            appendLine("Last phone interaction: ${fmt.format(Date(lastInteraction))} (${ago(now - lastInteraction)})")
        }
    }

    private fun hours(ms: Long) = ms / 3_600_000
    private fun ago(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000}m ago"
        else -> "${ms / 3_600_000}h ${(ms / 60_000) % 60}m ago"
    }
}

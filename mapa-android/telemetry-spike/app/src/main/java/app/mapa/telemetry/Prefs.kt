package app.mapa.telemetry

import android.content.Context
import java.util.UUID

/** Small key-value state. Nothing here leaves the phone except deviceId (a random UUID). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("mapa_spike", Context.MODE_PRIVATE)

    var consented: Boolean
        get() = sp.getBoolean("consented", false)
        set(v) = sp.edit().putBoolean("consented", v).apply()

    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) = sp.edit().putBoolean("paused", v).apply()

    var endpoint: String
        get() = sp.getString("endpoint", "") ?: ""
        set(v) = sp.edit().putString("endpoint", v.trim()).apply()

    /** Random per-install id. Never a hardware id, IMEI or phone number. */
    val deviceId: String
        get() = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("deviceId", it).apply()
        }

    var lastBootCount: Int
        get() = sp.getInt("lastBootCount", -1)
        set(v) = sp.edit().putInt("lastBootCount", v).apply()

    /** Upper bound of the last Usage Access query, so each event is counted once. */
    var usageCursor: Long
        get() = sp.getLong("usageCursor", 0L)
        set(v) = sp.edit().putLong("usageCursor", v).apply()

    var lastInteraction: Long
        get() = sp.getLong("lastInteraction", 0L)
        set(v) = sp.edit().putLong("lastInteraction", v).apply()

    var lastExitTimestamp: Long
        get() = sp.getLong("lastExitTimestamp", 0L)
        set(v) = sp.edit().putLong("lastExitTimestamp", v).apply()

    fun lastBeat(source: String): Long = sp.getLong("beat_$source", 0L)
    fun setLastBeat(source: String, t: Long) = sp.edit().putLong("beat_$source", t).apply()

    fun clearAll() = sp.edit().clear().apply()
}

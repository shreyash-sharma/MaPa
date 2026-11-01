package app.mapa.telemetry

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/**
 * Append-only JSON-lines log in app-private storage. One line per event.
 * Every line carries wall time (t), elapsed realtime (et) and the boot count,
 * so gaps can be attributed to reboots versus the app simply not running.
 */
object EventLog {
    private const val FILE = "events.jsonl"
    private const val MAX_BYTES = 20L * 1024 * 1024
    private val lock = Any()

    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun append(ctx: Context, type: String, data: JSONObject = JSONObject()) {
        val line = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("et", SystemClock.elapsedRealtime())
            .put("boot", Device.bootCount(ctx))
            .put("type", type)
        data.keys().forEach { line.put(it, data.get(it)) }
        synchronized(lock) {
            val f = file(ctx)
            if (f.length() < MAX_BYTES) f.appendText(line.toString() + "\n")
        }
    }

    fun readAll(ctx: Context): List<JSONObject> = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun copyTo(ctx: Context, dest: File) = synchronized(lock) {
        val f = file(ctx)
        if (f.exists()) f.copyTo(dest, overwrite = true) else dest.writeText("")
    }

    fun clear(ctx: Context) = synchronized(lock) { file(ctx).delete() }
}

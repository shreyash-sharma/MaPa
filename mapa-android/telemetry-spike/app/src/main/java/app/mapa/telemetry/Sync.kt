package app.mapa.telemetry

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pushes the minimal summary a family dashboard would need. No usage events, no app names.
 * If no endpoint is configured, sync is "off" and only the local log is kept.
 */
object Sync {

    fun push(ctx: Context, prefs: Prefs, beat: JSONObject): JSONObject {
        val endpoint = prefs.endpoint
        if (endpoint.isBlank()) return JSONObject().put("status", "off")

        val payload = JSONObject()
            .put("deviceId", prefs.deviceId)
            .put("sentAt", System.currentTimeMillis())
            .put("source", beat.optString("source"))
            .put("paused", prefs.paused)
            .put("lastInteraction", if (prefs.paused) JSONObject.NULL else prefs.lastInteraction)
            .put("battery", beat.optInt("battery", -1))
            .put("plugged", beat.optString("plugged"))
            .put("usageAccess", beat.optBoolean("usageAccess"))
            .put("ignoringBatteryOpt", beat.optBoolean("ignoringBatteryOpt"))
            .put("standbyBucket", beat.optString("standbyBucket", ""))
            .put("boot", Device.bootCount(ctx))
            .put("model", Device.model())
            .put("sdk", Build.VERSION.SDK_INT)
            .put("appVersion", appVersion(ctx))

        val started = SystemClock.elapsedRealtime()
        return try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            JSONObject()
                .put("status", if (code in 200..299) "ok" else "http_error")
                .put("code", code)
                .put("ms", SystemClock.elapsedRealtime() - started)
        } catch (e: Exception) {
            JSONObject()
                .put("status", "error")
                .put("error", e.javaClass.simpleName)
                .put("ms", SystemClock.elapsedRealtime() - started)
        }
    }

    private fun appVersion(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}

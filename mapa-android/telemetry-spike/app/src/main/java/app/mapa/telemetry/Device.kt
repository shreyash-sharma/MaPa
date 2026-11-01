package app.mapa.telemetry

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/** Point-in-time device health: battery, power/idle state, scheduling restrictions, network. */
object Device {

    fun bootCount(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        -1
    }

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun snapshot(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        val pm = app.service<PowerManager>()
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val json = JSONObject()
            .put("battery", if (level >= 0 && scale > 0) level * 100 / scale else -1)
            .put("plugged", pluggedName(plugged))
            .put("interactive", pm.isInteractive)
            .put("powerSave", pm.isPowerSaveMode)
            .put("deviceIdle", pm.isDeviceIdleMode)
            .put("ignoringBatteryOpt", pm.isIgnoringBatteryOptimizations(app.packageName))
            .put("usageAccess", UsageProbe.hasAccess(app))
            .put("network", network(app))
            .put("uptimeMin", SystemClock.elapsedRealtime() / 60_000)
        if (Build.VERSION.SDK_INT >= 28) {
            val usm = app.service<UsageStatsManager>()
            val am = app.service<ActivityManager>()
            json.put("standbyBucket", bucketName(usm.appStandbyBucket))
            json.put("backgroundRestricted", am.isBackgroundRestricted)
        }
        return json
    }

    private fun network(ctx: Context): String {
        val cm = ctx.service<ConnectivityManager>()
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "none") ?: return "none"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (ok) kind else "$kind-unvalidated"
    }

    private fun pluggedName(p: Int) = when (p) {
        0 -> "no"
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> "other"
    }

    // Values are UsageStatsManager.STANDBY_BUCKET_*; RESTRICTED (45) exists from API 30.
    fun bucketName(b: Int) = when (b) {
        5 -> "exempted"
        10 -> "active"
        20 -> "working_set"
        30 -> "frequent"
        40 -> "rare"
        45 -> "restricted"
        else -> "unknown_$b"
    }
}

/** System services we use exist on every Android phone; fail loudly if an OEM build lacks one. */
internal inline fun <reified T : Any> Context.service(): T = getSystemService(T::class.java)!!

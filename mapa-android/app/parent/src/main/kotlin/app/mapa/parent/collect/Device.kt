package app.mapa.parent.collect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Build

/** Battery and power state. Adapted from the telemetry spike's Device.kt. */
object Device {
    data class Power(val battery: Int, val charging: Boolean)

    fun power(ctx: Context): Power {
        val battery = ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return Power(if (level >= 0 && scale > 0) level * 100 / scale else -1, plugged != 0)
    }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean =
        ctx.service<PowerManager>().isIgnoringBatteryOptimizations(ctx.packageName)

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
}

/** System services we use exist on every Android phone; fail loudly if an OEM build lacks one. */
internal inline fun <reified T : Any> Context.service(): T = getSystemService(T::class.java)!!

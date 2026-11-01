package app.mapa.parent.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.mapa.core.PowerSample
import java.io.File

/**
 * Battery level and charging over time, kept on the phone so backfilled bins (after Doze or a
 * killed process) get the power state they really had. Sampled at every tick, unlock, boot and
 * plug/unplug while MaPa's process is alive. Last 4 days only.
 */
object PowerLog {
    private const val KEEP_MS = 4L * 24 * 60 * 60 * 1000

    @Synchronized
    fun sample(ctx: Context): PowerSample {
        val p = Device.power(ctx)
        val s = PowerSample(System.currentTimeMillis(), p.battery, p.charging)
        val f = file(ctx)
        val keep = read(ctx).filter { it.atMs > s.atMs - KEEP_MS } + s
        f.parentFile?.mkdirs()
        f.writeText(keep.joinToString("\n") { "${it.atMs},${it.battery},${if (it.charging) 1 else 0}" })
        return s
    }

    fun read(ctx: Context): List<PowerSample> = file(ctx).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { l ->
        l.split(',').takeIf { it.size == 3 }?.let { p -> runCatching { PowerSample(p[0].toLong(), p[1].toInt(), p[2] == "1") }.getOrNull() }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "rhythm/power.txt")
}

/** Runtime-registered: plug and unplug are the moments the power state changes. */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PowerLog.sample(context.applicationContext)
    }

    companion object {
        fun filter() = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
    }
}

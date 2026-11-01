package app.mapa.parent.collect

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.mapa.core.MoveSpan
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import java.io.File

/**
 * The second signal: whether the phone is being carried around, from Google Play services
 * Activity Recognition (low power; the system batches and delivers transitions to a broadcast,
 * nothing of MaPa's keeps running). Stored on the phone as start/end times only; the activity
 * type and any place are never kept or uploaded.
 */
object MotionSignal {
    private const val TAG = "MaPaMotion"
    private const val KEEP_MS = 4L * 24 * 60 * 60 * 1000
    private val MOVING = listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE)

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"

    fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Registers for movement transitions. Safe to call repeatedly (same PendingIntent replaces). */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!hasPermission(app)) return
        val transitions = MOVING.flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build()
            }
        }
        ActivityRecognition.getClient(app)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(app))
            .addOnSuccessListener {
                val prefs = app.getSharedPreferences("mapa_motion", Context.MODE_PRIVATE)
                if (prefs.getLong("since", 0L) == 0L) prefs.edit().putLong("since", System.currentTimeMillis()).apply()
                Log.i(TAG, "movement signal on")
            }
            .addOnFailureListener { Log.w(TAG, "movement signal unavailable", it) }
    }

    /** When the signal started listening; null if it never did or the permission was taken away. */
    fun availableSince(ctx: Context): Long? =
        if (!hasPermission(ctx)) null
        else ctx.getSharedPreferences("mapa_motion", Context.MODE_PRIVATE).getLong("since", 0L).takeIf { it > 0 }

    /** Movement spans that end after [fromMs] (open spans included). */
    fun spans(ctx: Context, fromMs: Long): List<MoveSpan> = log(ctx).takeIf { it.exists() }?.readLines().orEmpty()
        .mapNotNull { line -> line.split(',').let { p -> p.getOrNull(0)?.toLongOrNull()?.let { MoveSpan(it, p.getOrNull(1)?.toLongOrNull()) } } }
        .filter { (it.endMs ?: Long.MAX_VALUE) >= fromMs }

    fun forget(ctx: Context) {
        log(ctx).delete()
        ctx.getSharedPreferences("mapa_motion", Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(pending(ctx)) }
    }

    /** ENTER of a moving type opens a span; EXIT closes the open one. Old spans are dropped. */
    @Synchronized
    internal fun record(ctx: Context, enters: List<Long>, exits: List<Long>) {
        val now = System.currentTimeMillis()
        val spans = spans(ctx, now - KEEP_MS).toMutableList()
        (enters.map { it to true } + exits.map { it to false }).sortedBy { it.first }.forEach { (at, enter) ->
            val open = spans.indexOfLast { it.endMs == null }
            if (enter) { if (open < 0) spans += MoveSpan(at, null) }
            else if (open >= 0) spans[open] = spans[open].copy(endMs = at)
        }
        log(ctx).apply { parentFile?.mkdirs() }.writeText(spans.joinToString("\n") { "${it.startMs},${it.endMs ?: ""}" })
    }

    private fun log(ctx: Context) = File(ctx.filesDir, "rhythm/movement.txt")

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, MotionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
        // Event times are elapsed-realtime nanos; convert to wall clock.
        val offset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        fun wall(nanos: Long) = offset + nanos / 1_000_000
        MotionSignal.record(
            context.applicationContext,
            enters = events.filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }.map { wall(it.elapsedRealTimeNanos) },
            exits = events.filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT }.map { wall(it.elapsedRealTimeNanos) },
        )
    }
}

package app.mapa.family

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.mapa.core.family.ParentCards
import app.mapa.core.language.Lang
import app.mapa.core.wire.AlertKind
import app.mapa.core.wire.EventWire
import app.mapa.core.wire.ParentWire
import app.mapa.core.wire.StatusWire
import app.mapa.shared.Db
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import java.util.concurrent.TimeUnit

/**
 * Alerts without a server (Firebase Spark plan): every 15 minutes the family phone checks each
 * parent it follows, shows new alert events from the parent's phone, and raises "updates stopped"
 * itself when the parent's phone has been silent for [STALE_AFTER_MIN]. Android may run this late
 * under Doze, so alerts can arrive 15-30 minutes after the fact. Server push (backend/functions)
 * replaces this when the project moves to Blaze; [Alerts.show] de-duplicates the two.
 */
class FamilyCheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        val ctx = applicationContext
        val now = System.currentTimeMillis()
        val seen = ctx.getSharedPreferences("mapa_family_check", Context.MODE_PRIVATE)
        val pids = runCatching { await(Db.links(uid).get(Source.SERVER)).documents.map { it.id } }.getOrElse { return Result.retry() }
        for (pid in pids) runCatching {
            val profile = await(Db.parent(pid).get(Source.SERVER)).data?.let(ParentWire::decode) ?: return@runCatching
            val lang = Lang.of(profile.lang)
            val since = seen.getLong(pid, now - 60 * 60_000L)

            await(Db.events(pid).whereGreaterThan("at", since).get(Source.SERVER)).documents.forEach { d ->
                val e = d.data?.let(EventWire::decode) ?: return@forEach
                val kind = e.alert ?: return@forEach
                Alerts.show(ctx, d.id, AlertWatcher.title(profile.name, kind, lang), e.text, pid, kind)
            }
            seen.edit().putLong(pid, now).apply()

            if (profile.paused) return@runCatching
            val status = await(Db.status(pid).get(Source.SERVER)).data?.let(StatusWire::decode) ?: return@runCatching
            if (now - status.computedAtMs < STALE_AFTER_MIN * 60_000L) return@runCatching
            val card = ParentCards.build(FamilyRepo.person(profile), status, null, now, lang, profile.showPaused)
            // One alert per silence: the id changes only when the phone sends something new.
            Alerts.show(ctx, "stale_${pid}_${status.computedAtMs}", AlertWatcher.title(profile.name, AlertKind.STALE, lang), card.detail, pid, AlertKind.STALE)
        }
        return Result.success()
    }

    private fun <T> await(t: com.google.android.gms.tasks.Task<T>): T = Tasks.await(t, 20, TimeUnit.SECONDS)

    companion object {
        const val STALE_AFTER_MIN = 120L
        private const val WORK = "family-check"

        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<FamilyCheckWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK)
        }
    }
}

package app.mapa.parent.rhythm

import android.content.Context
import android.util.Log
import app.mapa.core.BinCounter
import app.mapa.core.LocalBins
import app.mapa.core.demo.SyntheticParent
import app.mapa.core.engine.EngineHost
import app.mapa.core.engine.FactsBuilder
import app.mapa.core.engine.FileJournalStore
import app.mapa.core.engine.FileSnapshotStore
import app.mapa.core.language.NoteWriter
import app.mapa.core.wire.NoteFactsWire
import app.mapa.core.language.Phrases
import app.mapa.core.wire.AlertKind
import app.mapa.core.wire.DayDoc
import app.mapa.core.wire.DayWire
import app.mapa.core.wire.EventDoc
import app.mapa.core.wire.EventWire
import app.mapa.core.wire.RoutineDoc
import app.mapa.core.wire.StatusExtras
import app.mapa.core.wire.StatusWire
import app.mapa.core.wire.TrendWire
import app.mapa.parent.BuildConfig
import app.mapa.parent.Prefs
import app.mapa.parent.cloud.ParentCloud
import app.mapa.parent.collect.Device
import app.mapa.core.MovementBins
import app.mapa.core.PowerTimeline
import app.mapa.parent.collect.PowerLog
import app.mapa.parent.collect.MotionSignal
import app.mapa.parent.collect.UsageProbe
import app.mapa.routine.Alert
import app.mapa.routine.Bins
import app.mapa.routine.RoutineState
import app.mapa.routine.TickResult
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * One tick of the parent's phone, every 15 minutes:
 *   1. pull life tags and new-routine answers the family entered,
 *   2. read Usage Access history since the last tick and bin it,
 *   3. run the on-device rhythm model over each closed bin (it retrains itself overnight),
 *   4. write the AI note with the on-device language model, guard-checked, template fallback,
 *   5. sync the derived status, today's bins, timeline events and alerts.
 * Nothing raw leaves the phone: see core/wire/StatusWire.kt.
 */
object ParentRuntime {
    private const val TAG = "MaPaRuntime"
    private const val NOTE_EVERY_MS = 2 * 60 * 60 * 1000L
    /** Android keeps usage history for a few days; older gaps are filled as unobserved by the engine. */
    private const val BACKFILL_BINS = 3 * Bins.PER_DAY
    /** At setup, read up to a week of past use from Android, so the model knows this parent on day one. */
    private const val INSTALL_BACKFILL_BINS = 7 * Bins.PER_DAY
    /** Timeline entries older than this, found while catching up after a long gap, are not uploaded. */
    private const val EVENT_WINDOW_BINS = 6 * 4
    private const val MAX_EVENTS_PER_TICK = 6

    @Volatile
    private var host: EngineHost? = null

    fun host(ctx: Context): EngineHost = host ?: synchronized(this) {
        host ?: EngineHost(
            FileJournalStore(File(ctx.filesDir, "rhythm/journal.txt")),
            FileSnapshotStore(File(ctx.filesDir, "rhythm/engine.snapshot")),
            ModelStore.factory(ctx),
        ).also { host = it }
    }

    @Synchronized
    fun tick(ctx: Context, source: String): TickResult? {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (!prefs.consented) return null
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val nowT = LocalBins.binAt(now, zone)
        val h = host(app)
        if (!h.started) h.start(nowT - INSTALL_BACKFILL_BINS)
        // Consent may have been given offline: sign in (anonymously) as soon as the network allows.
        val pid = ParentCloud.parentId ?: runCatching { ParentCloud.signIn() }.getOrNull()
            ?.also { ParentCloud.saveProfile(it, prefs.profile(zone.id), prefs.consentAt) }

        if (pid != null) pullFamilyInputs(h, pid, zone)

        val from = maxOf(h.nextT!!, nowT - maxOf(BACKFILL_BINS, if (h.lastT == null) INSTALL_BACKFILL_BINS else 0))
        val power = Device.power(app)
        val nowSample = PowerLog.sample(app)
        val times = if (prefs.paused || from >= nowT) emptyList()
        else UsageProbe.interactionTimes(app, LocalBins.startMs(from, zone), LocalBins.startMs(nowT, zone))
        times?.maxOrNull()?.let { if (it > prefs.lastInteractionAt) prefs.lastInteractionAt = it }
        // Second signal: was the phone carried around in each bin (times only, never where).
        val moved = if (prefs.paused || from >= nowT) null else MovementBins.moved(
            MotionSignal.spans(app, LocalBins.startMs(from, zone)), from, nowT, zone, MotionSignal.availableSince(app), now,
        )
        val obs = BinCounter.observations(
            times.orEmpty(), from, nowT, zone,
            observed = times != null && !prefs.paused, charging = power.charging, battery = power.battery, moved = moved,
            power = PowerTimeline.forBins(PowerLog.read(app), from, nowT, zone, nowSample),
        )
        moved?.let { m -> Log.i(TAG, "movement: ${m.count { it == true }} moved, ${m.count { it == false }} still, ${m.count { it == null }} unknown") }
        val results = h.feed(obs.map { it to prefs.paused })
        val last = results.lastOrNull() ?: return h.last
        Log.i(TAG, "tick[$source]: fed ${results.size} bins, state ${last.decision.state}, decider ${last.decider}")

        val events = timeline(results, prefs, nowT, zone, power.charging)
        writeNoteIfDue(app, h, prefs, last, now, zone, power)
        val status = StatusWire.encode(last, extras(app, h, prefs, last, now, zone))
        val dayDoc = h.today()?.let { (d, bins) ->
            DayDoc(
                LocalDate.ofEpochDay(d.toLong()).toString(), bins.toList(), FactsBuilder.usualBand(h, d),
                (16 until Bins.PER_DAY).firstOrNull { bins[it] == 1 }?.let { it * Bins.MINUTES },
            )
        }
        prefs.lastStatusJson = JsonMaps.toJson(status)
        dayDoc?.let { prefs.lastDayJson = JsonMaps.toJson(DayWire.encode(it)) }

        if (pid != null) {
            ParentCloud.uploadStatus(pid, status)
            if (!prefs.paused) dayDoc?.let { ParentCloud.uploadDay(pid, it.date, DayWire.encode(it)) }
            events.forEach { ParentCloud.addEvent(pid, EventWire.encode(it)) }
            h.lastTrend?.takeIf { it.day != prefs.uploadedTrendDay }?.let {
                ParentCloud.uploadTrend(pid, LocalDate.ofEpochDay(it.day.toLong()).toString(), TrendWire.encode(it))
                prefs.uploadedTrendDay = it.day
            }
            h.engine?.pendingQuestion?.takeIf { it.sinceDay != prefs.askedRoutineSinceDay }?.let { q ->
                ParentCloud.askRoutine(pid, RoutineDoc(q.shiftMinutes, q.weeks, q.sinceDay, now, null, null, null))
                prefs.askedRoutineSinceDay = q.sinceDay
            }
            val today = now / 86_400_000L
            if (prefs.prunedDay != today) runCatching { ParentCloud.prune(pid, now) }.onSuccess { prefs.prunedDay = today }
        }
        prefs.lastSyncAt = now
        return last
    }

    /** State changes for "Why did this change?", with the unusual and low-battery alerts attached. */
    private fun timeline(results: List<TickResult>, prefs: Prefs, nowT: Int, zone: ZoneId, charging: Boolean): List<EventDoc> {
        val ph = Phrases(prefs.lang)
        val person = prefs.person
        var prev = prefs.lastState.takeIf { it.isNotEmpty() }?.let { RoutineState.valueOf(it) }
        val changes = ArrayList<EventDoc>()
        for (r in results) {
            val s = r.decision.state
            if (s != prev) {
                changes += EventDoc(LocalBins.startMs(r.t + 1, zone), r.t, prev, s, r.decision.reason, ph.detail(r.decision.reason, person, r.t + 1), null)
                prev = s
            }
        }
        val last = results.last()
        prefs.lastState = last.decision.state.name
        val out = changes.filter { it.t >= nowT - EVENT_WINDOW_BINS }.takeLast(MAX_EVENTS_PER_TICK).toMutableList()

        // One unusual alert per silence, even if it was raised while catching up on missed bins,
        // but only while the silence is still going on.
        val alerts = results.flatMap { it.alerts }
        if (alerts.any { it is Alert.UnusualInactivity } && last.decision.state == RoutineState.UNUSUAL_INACTIVITY) {
            val i = out.indexOfLast { it.to == RoutineState.UNUSUAL_INACTIVITY }
            if (i >= 0) out[i] = out[i].copy(alert = AlertKind.UNUSUAL)
            else out += EventDoc(LocalBins.startMs(last.t + 1, zone), last.t, last.decision.state, last.decision.state, last.decision.reason, ph.detail(last.decision.reason, person, last.t + 1), AlertKind.UNUSUAL)
        }
        alerts.filterIsInstance<Alert.LowBattery>().lastOrNull()?.takeIf { it.t >= nowT - 2 && !charging }?.let {
            out += EventDoc(LocalBins.startMs(it.t + 1, zone), it.t, last.decision.state, last.decision.state, last.decision.reason, ph.lowBattery(person, it.battery), AlertKind.LOW_BATTERY)
        }
        return out
    }

    private fun writeNoteIfDue(ctx: Context, h: EngineHost, prefs: Prefs, last: TickResult, now: Long, zone: ZoneId, power: Device.Power) {
        val state = last.decision.state
        if (prefs.paused || state == RoutineState.MONITORING_UNAVAILABLE) return
        val newDay = LocalBins.binAt(prefs.noteAt, zone).let { Bins.day(it) } != Bins.day(last.t + 1)
        if (state.name == prefs.noteState && now - prefs.noteAt < NOTE_EVERY_MS && !newDay) return
        val facts = FactsBuilder.build(
            h, prefs.person, prefs.lang, now, zone, state, prefs.lastInteractionAt.takeIf { it > 0 }, power.battery, power.charging,
        ) ?: return
        // The parent phone writes only the template note (no language model here); the family
        // phone writes the AI note from the same facts (Option C, decided 2026-10-21).
        val note = NoteWriter(emptyList()).write(facts)
        prefs.noteFactsJson = JsonMaps.toJson(NoteFactsWire.encode(facts))
        prefs.note = note.text
        prefs.noteSource = note.source
        prefs.noteAt = now
        prefs.noteState = state.name
    }

    private fun extras(ctx: Context, h: EngineHost, prefs: Prefs, last: TickResult, now: Long, zone: ZoneId): StatusExtras {
        val engine = h.engine!!
        val factory = ModelStore.factory(ctx)
        val day = Bins.day(last.t + 1)
        return StatusExtras(
            computedAtMs = now,
            zone = zone.id,
            lastInteractionAtMs = prefs.lastInteractionAt.takeIf { it > 0 },
            phase = engine.scheduler.phase(day),
            // With the Hybrid: the days of this parent's own rhythm it really reads (backfilled days count).
            dayNumber = last.hybrid?.historyDays ?: engine.scheduler.dayNumber(day),
            model = factory.modelId,
            modelDecides = factory.modelDecides,
            usualFirstMinute = engine.profile.usualFirstActivity(day, Bins.EPOCH_DOW),
            lastTrainedT = h.lastTrainedT,
            note = prefs.note.ifEmpty { null },
            noteSource = prefs.noteSource.ifEmpty { null },
            noteLang = prefs.lang.code,
            noteAtMs = prefs.noteAt.takeIf { it > 0 },
            appVersion = BuildConfig.VERSION_NAME,
            noteFacts = JsonMaps.fromJson(prefs.noteFactsJson),
            runtime = factory.stats.runtime.takeIf { it != "none" },
            params = factory.stats.params.takeIf { it > 0 },
            modelBytes = factory.stats.modelBytes.takeIf { it > 0 },
            stepMicros = factory.stats.meanMicros.takeIf { it > 0 },
        )
    }

    private fun pullFamilyInputs(h: EngineHost, pid: String, zone: ZoneId) {
        ParentCloud.fetchTags(pid)?.forEach { h.addTag(it.id, it.toLifeTag(zone)) }
        val q = h.engine?.pendingQuestion ?: return
        val r = ParentCloud.fetchRoutine(pid) ?: return
        if (r.answer != null && r.appliedAtMs == null && r.sinceDay == q.sinceDay && h.answerNewRoutine(r.answer == "yes")) {
            ParentCloud.markRoutineApplied(pid, System.currentTimeMillis())
        }
    }

    /** Publishes "Sharing paused" immediately when the parent taps Pause. */
    fun publishPause(ctx: Context) {
        val prefs = Prefs(ctx)
        val pid = ParentCloud.parentId ?: return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        ParentCloud.setPaused(pid, prefs.paused)
        if (prefs.paused) {
            val status = StatusWire.pausedNow(LocalBins.binAt(now, zone) - 1, now, zone.id, BuildConfig.VERSION_NAME)
            ParentCloud.uploadStatus(pid, status)
            prefs.lastStatusJson = JsonMaps.toJson(status)
        }
    }

    /** Forgets everything on this phone: the model, its journal and its snapshot. */
    @Synchronized
    fun forget(ctx: Context) {
        host(ctx).reset()
        MotionSignal.forget(ctx) // the movement log lives under rhythm/ too; this also stops updates
        File(ctx.filesDir, "rhythm").deleteRecursively()
    }

    /** Debug builds only: replaces this phone's history with 21 sample days, to demo a trained model. */
    @Synchronized
    fun loadSampleDays(ctx: Context, days: Int = 21) {
        check(BuildConfig.DEBUG) { "sample days are for debug builds only" }
        val zone = ZoneId.systemDefault()
        val nowT = LocalBins.binAt(System.currentTimeMillis(), zone)
        val today = Bins.day(nowT)
        val h = host(ctx)
        h.reset()
        h.start((today - days) * Bins.PER_DAY)
        val sample = SyntheticParent().days(today - days, days)
        h.feed(sample.map { it to false })
        Prefs(ctx).lastState = ""
        // The family's History shows days as the phone uploaded them: upload the last six sample days too.
        val pid = ParentCloud.parentId ?: return
        sample.groupBy { Bins.day(it.t) }.filterKeys { it in (today - 6) until today }.forEach { (d, list) ->
            val bins = IntArray(Bins.PER_DAY) { -1 }
            list.forEach { o -> bins[Bins.binOfDay(o.t)] = if (o.interactions < 0) -1 else if (o.interactions > 0) 1 else 0 }
            val doc = DayDoc(
                LocalDate.ofEpochDay(d.toLong()).toString(), bins.toList(), FactsBuilder.usualBand(h, d),
                (16 until Bins.PER_DAY).firstOrNull { bins[it] == 1 }?.let { it * Bins.MINUTES },
            )
            runCatching { ParentCloud.uploadDay(pid, doc.date, DayWire.encode(doc)) }
        }
    }
}

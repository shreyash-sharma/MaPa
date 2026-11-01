package app.mapa.core.engine

import app.mapa.routine.BinObservation
import app.mapa.routine.Bins
import app.mapa.routine.LifeTag
import app.mapa.routine.RoutineEngine
import app.mapa.routine.TickResult
import app.mapa.routine.TrendReport
import java.io.File

/** Where the engine snapshot lives. On the phone: a file in app-private storage. */
interface SnapshotStore {
    fun read(): String?
    fun write(text: String)
    fun clear()
}

class FileSnapshotStore(private val file: File) : SnapshotStore {
    override fun read(): String? = if (file.exists()) file.readText() else null

    /** Write-then-rename so a process killed mid-write never leaves half a snapshot. */
    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    override fun clear() {
        file.delete()
    }
}

class MemorySnapshotStore : SnapshotStore {
    var text: String? = null
    override fun read() = text
    override fun write(text: String) {
        this.text = text
    }
    override fun clear() {
        text = null
    }
}

/**
 * Owns the phone's [RoutineEngine] and makes it survive process death.
 *
 * Two records are kept in app-private storage:
 *  - a small append-only journal of every input (bins, life tags, new-routine answers),
 *  - the engine's own snapshot after each batch, tagged with the model id and journal length.
 *
 * A fresh process restores the snapshot when it matches the shipped model and the journal;
 * otherwise (first run after a model upgrade, or a crash between the two writes) it replays
 * the journal, so a newer model re-learns the parent's whole history from raw bins.
 */
class EngineHost(
    private val journal: JournalStore,
    private val snapshots: SnapshotStore,
    private val factory: EngineFactory,
) {
    var engine: RoutineEngine? = null
        private set
    var installT: Int? = null
        private set
    var lastT: Int? = null
        private set
    /** Result of the newest bin fed in this process; null right after a restore. */
    var last: TickResult? = null
        private set
    var lastTrend: TrendReport? = null
        private set
    /** Bin of the last training run, so the parent and family can see "Rhythm updated Sunday". */
    var lastTrainedT: Int? = null
        private set
    /** True when this process rebuilt the engine by replaying the journal rather than restoring. */
    var replayed: Boolean = false
        private set
    private val tagIds = HashSet<String>()
    private var journalCount = 0
    private var todayDay: Int? = null
    private val todayBins = IntArray(Bins.PER_DAY) { -1 }

    /** The current day's bins as uploaded to the family: -1 not observed, 0 quiet, 1 used. */
    @Synchronized
    fun today(): Pair<Int, IntArray>? = todayDay?.let { it to todayBins.copyOf() }

    val started: Boolean get() = engine != null

    init {
        load()
    }

    /** First bin the collector should feed next, or null before [start]. */
    val nextT: Int? get() = if (engine == null) null else (lastT ?: (installT!! - 1)) + 1

    @Synchronized
    fun start(installT: Int) {
        if (engine != null) return
        record(listOf(JournalEntry.Start(installT, factory.modelId)))
    }

    /** Feeds closed bins in order; bins at or before the last fed bin are ignored. Returns their results. */
    @Synchronized
    fun feed(bins: List<Pair<BinObservation, Boolean>>): List<TickResult> {
        val start = installT ?: return emptyList()
        val fresh = bins.sortedBy { it.first.t }
            .filter { (o, _) -> o.t >= start && lastT.let { it == null || o.t > it } }
            .distinctBy { it.first.t }
        return record(fresh.map { (o, p) -> JournalEntry.Bin(o, p) })
    }

    /** Adds a life tag once per id (tags arrive from the parent and from the family via sync). */
    @Synchronized
    fun addTag(id: String, tag: LifeTag): Boolean {
        if (engine == null || id in tagIds) return false
        record(listOf(JournalEntry.Tag(id, tag)))
        return true
    }

    /** Applies the family's answer to the pending new-routine question. False if nothing was pending. */
    @Synchronized
    fun answerNewRoutine(yes: Boolean): Boolean {
        if (engine?.pendingQuestion == null) return false
        record(listOf(JournalEntry.Answer(yes)))
        return true
    }

    /** Forgets everything: used when the parent deletes their data or revokes consent. */
    @Synchronized
    fun reset() {
        journal.clear()
        snapshots.clear()
        engine = null
        installT = null
        lastT = null
        last = null
        lastTrend = null
        lastTrainedT = null
        tagIds.clear()
        journalCount = 0
        todayDay = null
        todayBins.fill(-1)
    }

    private fun record(entries: List<JournalEntry>): List<TickResult> {
        if (entries.isEmpty()) return emptyList()
        journal.append(entries)
        val results = entries.mapNotNull { apply(it) }
        journalCount += entries.size
        saveSnapshot()
        return results
    }

    private fun apply(e: JournalEntry): TickResult? {
        when (e) {
            is JournalEntry.Start -> if (engine == null) {
                installT = e.installT
                engine = factory.create(e.installT)
            }
            is JournalEntry.Bin -> {
                val eng = engine ?: return null
                if (lastT != null && e.obs.t <= lastT!!) return null
                val r = eng.onBin(e.obs, e.paused)
                trackToday(e.obs, e.paused)
                lastT = e.obs.t
                last = r
                if (r.trained != null) lastTrainedT = r.t
                r.trend?.let { lastTrend = it }
                return r
            }
            is JournalEntry.Tag -> {
                val eng = engine ?: return null
                if (tagIds.add(e.id)) eng.addLifeTag(e.tag)
            }
            is JournalEntry.Answer -> engine?.answerNewRoutine(e.yes)
        }
        return null
    }

    private fun trackToday(obs: BinObservation, paused: Boolean) {
        val day = Bins.day(obs.t)
        if (day != todayDay) {
            todayDay = day
            todayBins.fill(-1)
        }
        todayBins[Bins.binOfDay(obs.t)] = when {
            paused || obs.interactions < 0 -> -1
            obs.interactions > 0 -> 1
            else -> 0
        }
    }

    private fun load() {
        val entries = journal.read()
        val restored = snapshots.read()?.let { tryRestore(it, entries) } ?: false
        if (!restored) {
            replayed = entries.isNotEmpty()
            for (e in entries) apply(e)
            journalCount = entries.size
            last = null
            if (engine != null) saveSnapshot()
        }
    }

    private fun tryRestore(text: String, entries: List<JournalEntry>): Boolean {
        val nl = text.indexOf('\n')
        if (nl < 0) return false
        val meta = HostMeta.parse(text.substring(0, nl)) ?: return false
        if (meta.modelId != factory.modelId || meta.journalCount != entries.size) return false
        val eng = runCatching { factory.restore(text.substring(nl + 1)) }.getOrNull() ?: return false
        engine = eng
        installT = meta.installT
        lastT = meta.lastT
        lastTrainedT = meta.lastTrainedT
        lastTrend = meta.trend
        journalCount = meta.journalCount
        meta.today?.let { (day, bins) ->
            todayDay = day
            bins.copyInto(todayBins)
        }
        entries.filterIsInstance<JournalEntry.Tag>().forEach { tagIds += it.id }
        return true
    }

    private fun saveSnapshot() {
        val eng = engine ?: return
        val meta = HostMeta(factory.modelId, journalCount, installT!!, lastT, lastTrainedT, lastTrend, todayDay?.let { it to todayBins })
        snapshots.write(meta.encode() + "\n" + eng.snapshot())
    }
}

/** First line of the snapshot file: what the engine snapshot was taken under, plus host-side extras. */
internal data class HostMeta(
    val modelId: String,
    val journalCount: Int,
    val installT: Int,
    val lastT: Int?,
    val lastTrainedT: Int?,
    val trend: TrendReport?,
    val today: Pair<Int, IntArray>?,
) {
    fun encode(): String = listOf(
        MAGIC, modelId, journalCount, installT, lastT ?: "-", lastTrainedT ?: "-",
        trend?.let { "${it.day},${it.firstUseShiftMinutes ?: "-"},${it.lastUseShiftMinutes ?: "-"},${it.activityChange ?: "-"},${it.notable}" } ?: "-",
        today?.let { (d, b) -> "$d," + b.joinToString("") { v -> if (v < 0) "x" else v.toString() } } ?: "-",
    ).joinToString(" ")

    companion object {
        private const val MAGIC = "mapa-host-1"

        fun parse(line: String): HostMeta? = runCatching {
            val p = line.split(' ')
            require(p[0] == MAGIC)
            fun optInt(s: String) = if (s == "-") null else s.toInt()
            val trend = if (p[6] == "-") null else p[6].split(',').let {
                TrendReport(it[0].toInt(), optInt(it[1]), optInt(it[2]), if (it[3] == "-") null else it[3].toDouble(), it[4].toBoolean())
            }
            val today = p.getOrNull(7)?.takeIf { it != "-" }?.split(',')?.let { (d, b) ->
                d.toInt() to IntArray(Bins.PER_DAY) { i -> if (b[i] == 'x') -1 else b[i] - '0' }
            }
            HostMeta(p[1], p[2].toInt(), p[3].toInt(), optInt(p[4]), optInt(p[5]), trend, today)
        }.getOrNull()
    }
}
